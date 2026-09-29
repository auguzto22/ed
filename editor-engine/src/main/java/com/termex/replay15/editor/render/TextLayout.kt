package com.termex.replay15.editor.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.StaticLayout
import com.termex.replay15.editor.domain.TextClip
import com.termex.replay15.editor.domain.TextAlignment
import kotlin.math.ceil

object TextLayout {
    class Block internal constructor(
        private val layout: StaticLayout,
        private val text: TextClip,
        private val padding: Float,
        private val corner: Float,
    ) {
        val width: Int = layout.width + (padding * 2).toInt()
        val height: Int = layout.height + (padding * 2).toInt()

        fun draw(canvas: Canvas) {
            if (Color.alpha(text.backgroundColor) > 0) {
                val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withOpacity(text.backgroundColor, text.opacity) }
                canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), corner, corner, background)
            }
            canvas.save()
            canvas.translate(padding, padding)
            val paint = layout.paint
            if (text.outlineWidth > 0f) {
                paint.style = Paint.Style.STROKE
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeWidth = text.outlineWidth * layout.height.coerceAtLeast(1)
                paint.color = withOpacity(text.outlineColor, text.opacity)
                paint.clearShadowLayer()
                layout.draw(canvas)
            }
            paint.style = Paint.Style.FILL
            paint.color = withOpacity(text.color, text.opacity)
            if (text.shadow) paint.setShadowLayer(layout.height * .055f, layout.height * .025f, layout.height * .035f, withOpacity(Color.BLACK, text.opacity * .8f))
            else paint.clearShadowLayer()
            layout.draw(canvas)
            paint.clearShadowLayer()
            canvas.restore()
        }
    }

    fun create(text: TextClip, width: Int, height: Int, context: android.content.Context? = null, timeUs: Long? = null): Block {
        val style = when {
            text.bold && text.italic -> Typeface.BOLD_ITALIC
            text.bold -> Typeface.BOLD
            text.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = text.size * height
            color = withOpacity(text.color, text.opacity)
            typeface = if (context == null) Typeface.create("sans-serif", style)
            else if (text.fontWeight != 400 || text.italic) EditorFonts.get(context, text.fontId, text.fontWeight, text.italic)
            else EditorFonts.get(context, text.fontId, style)
            isUnderlineText = text.underline
            letterSpacing = text.letterSpacing
        }
        val padding = (height * .012f).coerceAtLeast(4f)
        val maximum = (width * .92f - padding * 2).toInt().coerceAtLeast(1)
        val rendered = wordStyledText(text, timeUs)
        val desired = ceil(Layout.getDesiredWidth(rendered, paint)).toInt().coerceAtLeast(1)
        val textWidth = if (text.boxWidth == 0f) minOf(desired, maximum) else (width * text.boxWidth - padding * 2).toInt().coerceIn(1, maximum)
        val alignment = when (text.alignment) {
            TextAlignment.LEFT -> Layout.Alignment.ALIGN_NORMAL
            TextAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlignment.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        }
        val layout = StaticLayout.Builder.obtain(rendered, 0, rendered.length, paint, textWidth)
            .setAlignment(alignment).setIncludePad(false).setLineSpacing(0f, text.lineSpacing).build()
        return Block(layout, text, padding, height * .018f)
    }

    private fun wordStyledText(text: TextClip, timeUs: Long?): CharSequence {
        if (timeUs == null || text.wordCues.isEmpty()) return text.text
        val words = Regex("\\S+").findAll(text.text).toList()
        if (words.isEmpty()) return text.text
        val styled = SpannableString(text.text)
        val style = text.wordStyle
        words.forEachIndexed { index, match ->
            val cue = text.wordCues.getOrNull(index) ?: return@forEachIndexed
            val active = timeUs in cue.startUs until cue.endUs
            val spoken = timeUs >= cue.endUs
            val color = when {
                active && style.activeColor != 0 -> style.activeColor
                spoken && style.spokenColor != 0 -> style.spokenColor
                !active && !spoken && style.futureColor != 0 -> style.futureColor
                style.normalColor != 0 -> style.normalColor
                else -> 0
            }
            if (color != 0) styled.setSpan(FillOnlyColorSpan(color), match.range.first, match.range.last + 1, 0)
            if (active && style.activeScale != 1f) {
                styled.setSpan(RelativeSizeSpan(style.activeScale), match.range.first, match.range.last + 1, 0)
            }
            if (active && style.boldCurrentWord) {
                styled.setSpan(StyleSpan(Typeface.BOLD), match.range.first, match.range.last + 1, 0)
            }
        }
        return styled
    }

    private class FillOnlyColorSpan(private val color: Int) : CharacterStyle() {
        override fun updateDrawState(textPaint: TextPaint) {
            if (textPaint.style == Paint.Style.FILL) textPaint.color = color
        }
    }

    private fun withOpacity(color: Int, opacity: Float): Int =
        (color and 0x00FFFFFF) or ((Color.alpha(color) * opacity).toInt().coerceIn(0, 255) shl 24)
}
