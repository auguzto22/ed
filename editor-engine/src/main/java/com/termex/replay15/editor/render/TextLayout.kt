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
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.text.StaticLayout
import com.termex.replay15.editor.domain.TextClip
import com.termex.replay15.editor.domain.TextAlignment
import com.termex.replay15.editor.domain.TextAnimation
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

    /** Texto exibido: caixa alta caractere a caractere, para os índices das palavras não mudarem. */
    private fun displayText(text: TextClip): String =
        if (text.wordStyle.uppercase) String(CharArray(text.text.length) { text.text[it].uppercaseChar() }) else text.text

    private fun wordStyledText(text: TextClip, timeUs: Long?): CharSequence {
        val shown = displayText(text)
        if (timeUs != null && (text.animation == TextAnimation.TYPEWRITER || text.enterAnimation == TextAnimation.TYPEWRITER)) {
            val progress = ((timeUs - text.startUs).toFloat() / minOf(text.durationUs, 1_500_000L).coerceAtLeast(1L)).coerceIn(0f, 1f)
            val charCount = (shown.length * progress).toInt().coerceIn(1, shown.length)
            return shown.substring(0, charCount)
        }
        if (timeUs == null || text.wordCues.isEmpty()) return shown
        val words = Regex("\\S+").findAll(shown).toList()
        if (words.isEmpty()) return shown
        val styled = SpannableString(shown)
        val style = text.wordStyle
        val hasOutline = text.outlineWidth > 0f
        words.forEachIndexed { index, match ->
            val cue = text.wordCues.getOrNull(index) ?: return@forEachIndexed
            val start = match.range.first
            val end = match.range.last + 1
            val active = timeUs in cue.startUs until cue.endUs
            val spoken = timeUs >= cue.endUs
            if (style.reveal && !active && !spoken) {
                // Ainda não falada: some, mas continua ocupando o lugar para a linha não pular.
                styled.setSpan(HiddenSpan, start, end, 0)
                return@forEachIndexed
            }
            val activeColor = style.activePalette.takeIf { it.isNotEmpty() }?.let { it[index % it.size] } ?: style.activeColor
            val color = when {
                active && activeColor != 0 -> activeColor
                spoken && style.spokenColor != 0 -> style.spokenColor
                !active && !spoken && style.futureColor != 0 -> style.futureColor
                style.normalColor != 0 -> style.normalColor
                else -> 0
            }
            val scale = if (!active) 1f else if (style.pop) popScale(style.activeScale, timeUs - cue.startUs) else style.activeScale
            if (active && style.highlightColor != 0) {
                // A caixa desenha a própria palavra, então cor, tamanho e negrito vão junto com ela.
                styled.setSpan(HighlightWordSpan(style.highlightColor, color, scale, style.boldCurrentWord, hasOutline), start, end, 0)
                return@forEachIndexed
            }
            if (color != 0) styled.setSpan(FillOnlyColorSpan(color), start, end, 0)
            if (scale != 1f) styled.setSpan(RelativeSizeSpan(scale), start, end, 0)
            if (active && style.boldCurrentWord) styled.setSpan(StyleSpan(Typeface.BOLD), start, end, 0)
        }
        return styled
    }

    /** Cresce de 1 até [target] em 180 ms com um leve overshoot ("pulo"); sem alvo, pula até 1,15. */
    private fun popScale(target: Float, elapsedUs: Long): Float {
        val goal = if (target == 1f) 1.15f else target
        val t = (elapsedUs / 180_000f).coerceIn(0f, 1f)
        val c = 2.2f
        val eased = 1f + (c + 1f) * (t - 1f) * (t - 1f) * (t - 1f) + c * (t - 1f) * (t - 1f)
        return (1f + (goal - 1f) * eased).coerceIn(.8f, 1.6f)
    }

    private class FillOnlyColorSpan(private val color: Int) : CharacterStyle() {
        override fun updateDrawState(textPaint: TextPaint) {
            if (textPaint.style == Paint.Style.FILL) textPaint.color = color
        }
    }

    /** Palavra ainda não falada no modo "revelar": invisível nas duas passadas (contorno e preenchimento). */
    private object HiddenSpan : CharacterStyle() {
        override fun updateDrawState(textPaint: TextPaint) {
            textPaint.color = Color.TRANSPARENT
            textPaint.clearShadowLayer()
        }
    }

    /**
     * Palavra falada dentro de uma caixa arredondada. A caixa entra na passada do contorno (fica
     * atrás dele); sem contorno, entra na passada de preenchimento. [textColor] 0 mantém a cor da camada.
     */
    private class HighlightWordSpan(
        private val boxColor: Int,
        private val textColor: Int,
        private val scale: Float,
        private val bold: Boolean,
        private val hasOutline: Boolean,
    ) : ReplacementSpan() {
        private fun styled(paint: Paint): TextPaint = TextPaint(paint).apply {
            textSize = paint.textSize * scale
            if (bold) typeface = Typeface.create(paint.typeface, Typeface.BOLD)
        }

        private fun padX(paint: Paint) = paint.textSize * .22f

        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            val p = styled(paint)
            if (fm != null) {
                val m = p.fontMetricsInt
                fm.ascent = m.ascent; fm.descent = m.descent; fm.top = m.top; fm.bottom = m.bottom
            }
            return (p.measureText(text, start, end) + padX(p) * 2).toInt()
        }

        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val p = styled(paint)
            val pad = padX(p)
            val width = p.measureText(text, start, end)
            val boxPass = if (hasOutline) paint.style == Paint.Style.STROKE else paint.style == Paint.Style.FILL
            if (boxPass) {
                val box = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = boxColor
                    alpha = (Color.alpha(boxColor) * (paint.alpha / 255f)).toInt()
                }
                val rect = RectF(x, y + p.ascent() - pad * .25f, x + width + pad * 2, y + p.descent() + pad * .25f)
                canvas.drawRoundRect(rect, p.textSize * .22f, p.textSize * .22f, box)
            }
            if (paint.style == Paint.Style.FILL && textColor != 0) p.color = (textColor and 0x00FFFFFF) or (paint.alpha shl 24)
            p.clearShadowLayer()
            canvas.drawText(text, start, end, x + pad, y.toFloat(), p)
        }
    }

    private fun withOpacity(color: Int, opacity: Float): Int =
        (color and 0x00FFFFFF) or ((Color.alpha(color) * opacity).toInt().coerceIn(0, 255) shl 24)
}
