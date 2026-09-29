package com.termex.replay15.editor.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.net.Uri
import androidx.media3.effect.BitmapOverlay
import com.termex.replay15.editor.backgroundremoval.MlKitBitmapMaskApplier
import com.termex.replay15.editor.domain.*
import kotlin.math.min
import java.util.LinkedHashMap

/** Composes every timed text and image layer into one GPU overlay texture. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class TextCanvasOverlay(
    private val context: Context,
    private val initialTexts: List<TextClip>,
    private val initialStickers: List<StickerClip>,
    private val width: Int,
    private val height: Int,
) : BitmapOverlay() {
    private class Motion {
        var alpha = 1f
        var scale = 1f
        var offsetX = 0f
        var offsetY = 0f
    }
    private data class CachedTextLayout(val source: TextClip, val block: TextLayout.Block, val wordBucket: Long)

    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private var lastStateSignature = Long.MIN_VALUE
    private val activeTexts = ArrayList<TextClip>()
    private val activeStickers = ArrayList<StickerClip>()
    private val motion = Motion()
    private val stickerRect = RectF()
    private val textLayouts = object : LinkedHashMap<String, CachedTextLayout>(16, .75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, CachedTextLayout>?,
        ): Boolean = size > MAX_TEXT_LAYOUTS
    }
    private val stickerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val stickerBitmaps: Map<String, Bitmap> = initialStickers.mapNotNull { sticker ->
        runCatching { sticker.id to decode(context, sticker.uri) }.getOrNull()
    }.toMap()
    private val maskedStickerBitmaps = HashMap<String, Bitmap>()
    private var maskApplier: MlKitBitmapMaskApplier? = null

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val frame = bitmap ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            bitmap = it
            canvas = Canvas(it)
        }
        val texts = initialTexts
        val stickers = initialStickers
        activeTexts.clear()
        activeStickers.clear()
        var stateSignature = 1125899906842597L
        texts.forEach {
            if (presentationTimeUs in it.startUs until it.endUs) {
                activeTexts += it
                stateSignature = stateSignature * 31 + it.hashCode()
            }
        }
        stickers.forEach {
            if (presentationTimeUs in it.startUs until it.endUs && stickerBitmaps.containsKey(it.id)) {
                activeStickers += it
                stateSignature = stateSignature * 31 + it.hashCode()
            }
        }
        val animated = activeTexts.any { it.animation != TextAnimation.NONE || it.enterAnimation != TextAnimation.NONE ||
            it.duringAnimation != TextAnimation.NONE || it.exitAnimation != TextAnimation.NONE ||
            it.wordCues.isNotEmpty() || it.transformKeyframes.isNotEmpty() || it.mask?.keyframes?.isNotEmpty() == true } ||
            activeStickers.any {
                it.animation != TextAnimation.NONE || it.transformKeyframes.isNotEmpty() || it.mask?.keyframes?.isNotEmpty() == true
            }
        if (lastStateSignature != stateSignature || animated) {
            val target = requireNotNull(canvas)
            target.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            activeTexts.forEach { drawText(target, it, presentationTimeUs) }
            activeStickers.forEach { sticker ->
                val source = requireNotNull(stickerBitmaps[sticker.id])
                val bitmap = if (sticker.backgroundRemoval.enabled) {
                    maskedStickerBitmaps[sticker.id] ?: run {
                        val processed = (maskApplier ?: MlKitBitmapMaskApplier(context).also { maskApplier = it })
                            .apply(source, sticker.backgroundRemoval)
                        processed?.also { maskedStickerBitmaps[sticker.id] = it } ?: source
                    }
                } else source
                drawSticker(target, sticker, bitmap, presentationTimeUs)
            }
            lastStateSignature = stateSignature
        }
        return frame
    }

    private fun drawText(canvas: Canvas, text: TextClip, timeUs: Long) {
        val cached = textLayouts[text.id]
        val wordBucket = if (text.wordCues.isEmpty()) 0L else timeUs / 33_333L
        val layout = if (cached != null && sameTextLayout(cached.source, text) && cached.wordBucket == wordBucket) {
            cached.block
        } else {
            val source = text.copy(x = .5f, y = .5f, rotation = 0f, scale = 1f, animation = TextAnimation.NONE)
            TextLayout.create(source, width, height, context, timeUs).also { textLayouts[text.id] = CachedTextLayout(source, it, wordBucket) }
        }
        val transform = com.termex.replay15.editor.transform.LayerStateEvaluator.evaluate(text, timeUs, realtime = false).transform
        val evaluatedMotion = SubtitleAnimationEvaluator.evaluate(text, timeUs, width, height)
        canvas.save()
        val effectiveAlpha = (evaluatedMotion[0] * transform.opacity).coerceIn(0f, 1f)
        if (effectiveAlpha < 1f) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (effectiveAlpha * 255).toInt())
        canvas.translate(transform.x * width + evaluatedMotion[2], transform.y * height + evaluatedMotion[3])
        canvas.rotate(transform.rotation)
        canvas.scale(transform.scaleX * evaluatedMotion[1], transform.scaleY * evaluatedMotion[1])
        canvas.translate(-layout.width / 2f, -layout.height / 2f)
        CanvasLayerMask.draw(canvas, RectF(0f, 0f, layout.width.toFloat(), layout.height.toFloat()), text.maskAt(timeUs)) {
            layout.draw(canvas)
        }
        if (effectiveAlpha < 1f) canvas.restore()
        canvas.restore()
    }

    private fun drawSticker(canvas: Canvas, sticker: StickerClip, source: Bitmap, timeUs: Long) {
        val transform = com.termex.replay15.editor.transform.LayerStateEvaluator.evaluate(sticker, timeUs, realtime = false).transform
        updateMotion(sticker.animation, sticker.startUs, sticker.endUs, timeUs)
        val targetHeight = sticker.size * height * transform.scaleY
        val targetWidth = (sticker.size * height * source.width / source.height.toFloat()) * transform.scaleX
        stickerPaint.alpha = (255 * transform.opacity * motion.alpha).toInt().coerceIn(0, 255)
        canvas.save()
        canvas.translate(transform.x * width + motion.offsetX, transform.y * height + motion.offsetY)
        canvas.rotate(transform.rotation)
        canvas.scale(if (sticker.flip) -motion.scale else motion.scale, motion.scale)
        stickerRect.set(-targetWidth / 2, -targetHeight / 2, targetWidth / 2, targetHeight / 2)
        CanvasLayerMask.draw(canvas, stickerRect, sticker.maskAt(timeUs)) {
            canvas.drawBitmap(source, null, stickerRect, stickerPaint)
        }
        canvas.restore()
    }

    private fun updateMotion(animation: TextAnimation, startUs: Long, endUs: Long, timeUs: Long) {
        val progressIn = ((timeUs - startUs) / 350_000f).coerceIn(0f, 1f)
        val progressOut = ((endUs - timeUs) / 250_000f).coerceIn(0f, 1f)
        val progress = min(progressIn, progressOut)
        motion.alpha = if (animation == TextAnimation.NONE) 1f else progress
        motion.scale = if (animation == TextAnimation.POP) .72f + .28f * overshoot(progress) else 1f
        motion.offsetX = if (animation == TextAnimation.SLIDE_LEFT) (1f - progress) * width * .09f else 0f
        motion.offsetY = if (animation == TextAnimation.SLIDE_UP) (1f - progress) * height * .07f else 0f
    }

    private fun overshoot(value: Float): Float {
        val shifted = value - 1f
        return shifted * shifted * (2.2f * shifted + 2.2f) + 1f
    }

    private fun sameTextLayout(a: TextClip, b: TextClip): Boolean =
        a.text == b.text && a.size == b.size && a.color == b.color && a.bold == b.bold &&
            a.fontId == b.fontId && a.fontFamily == b.fontFamily && a.fontWeight == b.fontWeight &&
            a.fontStyle == b.fontStyle && a.fontVersion == b.fontVersion && a.italic == b.italic &&
            a.underline == b.underline && a.alignment == b.alignment && a.opacity == b.opacity &&
            a.letterSpacing == b.letterSpacing && a.lineSpacing == b.lineSpacing && a.boxWidth == b.boxWidth &&
            a.backgroundColor == b.backgroundColor && a.outlineColor == b.outlineColor &&
            a.outlineWidth == b.outlineWidth && a.shadow == b.shadow &&
            a.wordCues == b.wordCues && a.wordStyle == b.wordStyle

    override fun release() {
        try {
            super.release()
        } finally {
            bitmap?.recycle()
            bitmap = null
            canvas = null
            stickerBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
            maskedStickerBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
            maskedStickerBitmaps.clear()
            maskApplier?.close(); maskApplier = null
            textLayouts.clear()
            lastStateSignature = Long.MIN_VALUE
        }
    }

    private fun decode(context: Context, source: String): Bitmap = ImageDecoder.decodeBitmap(
        ImageDecoder.createSource(context.contentResolver, Uri.parse(source)),
    ) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longest = maxOf(info.size.width, info.size.height)
        if (longest > MAX_STICKER_PIXELS) {
            val scale = MAX_STICKER_PIXELS / longest.toFloat()
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        }
    }

    companion object {
        private const val MAX_STICKER_PIXELS = 2048
        private const val MAX_TEXT_LAYOUTS = 64
    }
}
