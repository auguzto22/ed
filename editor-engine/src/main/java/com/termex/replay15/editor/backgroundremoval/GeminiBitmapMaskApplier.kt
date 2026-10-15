package com.termex.replay15.editor.backgroundremoval

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.termex.replay15.editor.ai.GeminiApiKeyConfig
import com.termex.replay15.editor.captions.GeminiHttpClient
import com.termex.replay15.editor.domain.BackgroundMode
import com.termex.replay15.editor.domain.BackgroundRemovalEffect
import com.termex.replay15.editor.domain.SegmentationQuality
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Removes the background of chosen images (photo overlays / stickers).
 *
 * With a Gemini key (debug builds), Gemini returns the traced contour of every foreground subject;
 * everything outside the union of those contours becomes fully transparent. Without a key (release
 * builds never carry one) or when Gemini cannot trace the subject, the on-device subject/selfie
 * models ([StillImageSegmenter]) cut the image instead. Before, release builds returned null here
 * and "Remover fundo" silently did nothing. Null still means "keep the original image".
 */
class GeminiBitmapMaskApplier(
    private val context: Context,
    private val apiKey: String = GeminiApiKeyConfig.getApiKey(),
) : AutoCloseable {

    /** Created on first use: only images that actually need a local cut-out pay for the models. */
    private var local: StillImageSegmenter? = null

    private val http = GeminiHttpClient(apiKey)
    /** Avoids hammering the API every frame after a failure; retried after the cooldown. */
    private val failedAt = ConcurrentHashMap<String, Long>()
    @Volatile private var closed = false

    fun apply(source: Bitmap, effect: BackgroundRemovalEffect): Bitmap? {
        if (!effect.enabled || effect.mode != BackgroundMode.REMOVE) return source
        if (closed || source.isRecycled) return null
        val key = "${System.identityHashCode(source)}:${source.generationId}:${effect.quality}"
        failedAt[key]?.let { if (SystemClock.elapsedRealtime() - it < FAILURE_COOLDOWN_MS) return null }

        val subjects = if (apiKey.isBlank()) emptyList() else runCatching { requestSubjects(source, effect.quality) }
            .onFailure { Log.w(TAG, "Gemini não conseguiu recortar a imagem; tentando no aparelho", it) }
            .getOrDefault(emptyList())
        val result = if (subjects.isNotEmpty()) cutout(source, subjects, effect) else localCutout(source, effect)
        if (result == null) failedAt[key] = SystemClock.elapsedRealtime() else failedAt.remove(key)
        return result
    }

    /** On-device fallback: subject + selfie models, the same ones photo clips use. */
    private fun localCutout(source: Bitmap, effect: BackgroundRemovalEffect): Bitmap? {
        val segmenter = synchronized(this) {
            if (closed) return null
            local ?: StillImageSegmenter(context).also { local = it }
        }
        val result = runCatching { segmenter.segmentBlocking(source, 0L) }
            .onFailure { Log.w(TAG, "Recorte no aparelho falhou", it) }
            .getOrNull() ?: return null
        if (closed || source.isRecycled) return null
        val w = source.width
        val h = source.height
        val pixels = IntArray(result.width * result.height) { index ->
            (result.mask[index].coerceIn(0f, 1f) * 255f).roundToInt() shl 24
        }
        val small = Bitmap.createBitmap(pixels, result.width, result.height, Bitmap.Config.ARGB_8888)
        val scaled = if (small.width == w && small.height == h) small
            else Bitmap.createScaledBitmap(small, w, h, true).also { small.recycle() }
        val alpha = scaled.extractAlpha().also { scaled.recycle() }
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val featherPx = effect.feather * min(w, h) * 0.05f
        Canvas(mask).drawBitmap(alpha, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            if (featherPx >= 0.5f) maskFilter = BlurMaskFilter(featherPx, BlurMaskFilter.Blur.NORMAL)
        })
        alpha.recycle()
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(output).apply {
            drawBitmap(source, 0f, 0f, null)
            drawBitmap(mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        }
        mask.recycle()
        return output
    }

    private fun requestSubjects(source: Bitmap, quality: SegmentationQuality): List<GeminiSubject> {
        val maxDim = when (quality) {
            SegmentationQuality.FAST -> 768
            SegmentationQuality.BALANCED -> 1024
            SegmentationQuality.HIGH -> 1536
        }
        val scale = min(1f, maxDim.toFloat() / max(source.width, source.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).roundToInt().coerceAtLeast(1),
                (source.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        } else source

        // JPEG has no alpha: flatten onto white so already-transparent areas read as background.
        val opaque = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        Canvas(opaque).apply {
            drawColor(android.graphics.Color.WHITE)
            drawBitmap(scaled, 0f, 0f, null)
        }
        if (scaled !== source) scaled.recycle()
        val bytes = ByteArrayOutputStream().use { out ->
            opaque.compress(Bitmap.CompressFormat.JPEG, 92, out)
            out.toByteArray()
        }
        opaque.recycle()

        val payload = GeminiSegmentationRequest.build(Base64.encodeToString(bytes, Base64.NO_WRAP))
        val response = http.withRetry({ if (closed) throw IllegalStateException("closed") }) {
            http.createInteraction(payload) { if (closed) throw IllegalStateException("closed") }
        }
        return GeminiSegmentationParser.parseSubjects(GeminiSegmentationRequest.outputText(response))
    }

    /** Keeps only the pixels inside the subjects' contours, at the source's full resolution. */
    private fun cutout(source: Bitmap, subjects: List<GeminiSubject>, effect: BackgroundRemovalEffect): Bitmap {
        val w = source.width
        val h = source.height
        val path = Path().apply { fillType = Path.FillType.WINDING }
        subjects.forEach { subject ->
            subject.polygon.forEachIndexed { index, (x, y) ->
                if (index == 0) path.moveTo(x * w, y * h) else path.lineTo(x * w, y * h)
            }
            path.close()
        }

        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val featherPx = effect.feather * min(w, h) * 0.05f
        Canvas(mask).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            if (featherPx >= 0.5f) maskFilter = BlurMaskFilter(featherPx, BlurMaskFilter.Blur.NORMAL)
        })

        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(output).apply {
            drawBitmap(source, 0f, 0f, null)
            // DST_IN multiplies the image alpha by the mask: outside the subjects alpha becomes 0.
            drawBitmap(mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        }
        mask.recycle()
        return output
    }

    override fun close() {
        closed = true
        failedAt.clear()
        synchronized(this) { local?.close(); local = null }
    }

    private companion object {
        const val TAG = "GeminiBgRemoval"
        const val FAILURE_COOLDOWN_MS = 30_000L
    }
}
