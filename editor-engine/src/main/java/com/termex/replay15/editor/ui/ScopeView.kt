package com.termex.replay15.editor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.termex.replay15.editor.scopes.ScopeAnalyzer
import com.termex.replay15.editor.scopes.ScopeFrame

/** A single frame holds a few percent of the pixels, so the parade needs a visual gain. */
private const val PARADE_GAIN = 8f

/** Which measurement the scope panel is showing. */
enum class ScopeMode(val label: String, val description: String) {
    WAVEFORM("Waveform", "Brilho por coluna: mostra onde a imagem estoura ou some no preto."),
    PARADE("Parade RGB", "Cada canal separado: revela uma cor dominante ou um canal cortado."),
    VECTORSCOPE("Vectorscope", "Matiz e saturação: se o traces cai no centro, a imagem está lavada."),
    HISTOGRAM("Histograma", "Distribuição dos tons: mostra contraste, preto queimado e branco estourado."),
}

class ScopeView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    var mode: ScopeMode = ScopeMode.WAVEFORM
        set(value) { field = value; invalidate() }
    var frame: ScopeFrame? = null
        set(value) { field = value; invalidate() }

    private val pad get() = context.dp(10).toFloat()

    init {
        minimumHeight = context.dp(150)
        gridPaint.color = 0xFF2A2436.toInt()
        gridPaint.strokeWidth = 1f
        labelPaint.color = 0xFF8B8499.toInt()
        labelPaint.textSize = context.dp(9).toFloat()
        contentDescription = "Escopo de cor e brilho do quadro atual"
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), pad, pad, paint.apply { color = 0xFF0D0C13.toInt() })
        val w = width - 2 * pad
        val h = height - 2 * pad
        if (w <= 0f || h <= 0f) return

        drawGrid(canvas, w, h)
        val data = frame
        if (data == null) {
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("Sem quadro para medir", pad + w / 2, pad + h / 2, labelPaint)
            labelPaint.textAlign = Paint.Align.LEFT
            return
        }
        when (mode) {
            ScopeMode.WAVEFORM -> drawWaveform(canvas, data, w, h)
            ScopeMode.PARADE -> drawParade(canvas, data, w, h)
            ScopeMode.VECTORSCOPE -> drawVectorscope(canvas, data, w, h)
            ScopeMode.HISTOGRAM -> drawHistogram(canvas, data, w, h)
        }
    }

    private fun drawGrid(canvas: Canvas, w: Float, h: Float) {
        for (i in 0..4) {
            val t = i / 4f
            canvas.drawLine(pad + w * t, pad, pad + w * t, pad + h, gridPaint)
            canvas.drawLine(pad, pad + h * t, pad + w, pad + h * t, gridPaint)
        }
    }

    private fun drawWaveform(canvas: Canvas, data: ScopeFrame, w: Float, h: Float) {
        // A 128-column waveform is denser than the pixels available, so the envelope is drawn
        // as a filled band: a line would alias and hide the very clipping it is meant to reveal.
        paint.color = 0xCC8A7CFF.toInt()
        val columnWidth = w / data.waveformColumns
        for (column in 0 until data.waveformColumns) {
            val x = pad + column * columnWidth
            val y = pad + h * (1f - data.waveform[column])
            canvas.drawRect(x, y, x + columnWidth.coerceAtLeast(1f), pad + h, paint)
        }
    }

    private fun drawParade(canvas: Canvas, data: ScopeFrame, w: Float, h: Float) {
        val third = w / 3f
        val channels = listOf(
            Triple(data.paradeRed, 0xFFFF5C5C.toInt(), "R"),
            Triple(data.paradeGreen, 0xFF5CFF8A.toInt(), "G"),
            Triple(data.paradeBlue, 0xFF5CA8FF.toInt(), "B"),
        )
        channels.forEachIndexed { index, (values, color, label) ->
            paint.color = color
            val originX = pad + third * index
            val columnWidth = third / values.size
            for (level in values.indices) {
                // Parades are read horizontally: the level runs left to right and the bar grows
                // up from the bottom of the lane, so a channel that dies out is obvious.
                val value = values[level]
                if (value <= 0f) continue
                val barHeight = (value * PARADE_GAIN).coerceIn(0f, 1f) * h
                canvas.drawRect(originX + level * columnWidth, pad + h - barHeight,
                    originX + (level + 1) * columnWidth, pad + h, paint)
            }
            canvas.drawText(label, originX + 2f, pad - context.dp(2), labelPaint)
        }
    }

    private fun drawVectorscope(canvas: Canvas, data: ScopeFrame, w: Float, h: Float) {
        val size = minOf(w, h)
        val cx = pad + w / 2f
        val cy = pad + h / 2f
        val radius = size / 2f - pad / 2f
        paint.color = 0x33FFFFFF
        canvas.drawCircle(cx, cy, radius, paint)
        // The six primary/secondary targets, so the trace can be read against a known hue.
        val targets = listOf(
            "R" to 0f, "Yl" to 60f, "G" to 120f, "Cy" to 180f, "B" to 240f, "Mg" to 300f,
        )
        targets.forEach { (label, degrees) ->
            val radians = Math.toRadians((degrees - 90).toDouble())
            val x = cx + (Math.cos(radians) * radius).toFloat()
            val y = cy + (Math.sin(radians) * radius).toFloat()
            canvas.drawCircle(x, y, context.dp(1.5f).toFloat(), gridPaint.apply { color = 0xFF6C6480.toInt() })
        }
        paint.color = 0xFFE8E4F5.toInt()
        val bins = ScopeAnalyzer.VECTOR_BINS
        for (binY in 0 until bins) for (binX in 0 until bins) {
            val count = data.vectorscope[(binY * bins + binX) * 2]
            if (count <= 0.0005f) continue
            val angle = ((binX + .5f) / bins) * 360f - 180f - 90f
            val level = (binY + .5f) / bins
            val radians = Math.toRadians(angle.toDouble())
            val x = cx + (Math.cos(radians) * radius * level).toFloat()
            val y = cy + (Math.sin(radians) * radius * level).toFloat()
            paint.alpha = (60 + 195 * count.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, context.dp(1.6f).toFloat(), paint)
        }
        paint.alpha = 255
    }

    private fun drawHistogram(canvas: Canvas, data: ScopeFrame, w: Float, h: Float) {
        paint.color = 0xFFB4A8FF.toInt()
        val columnWidth = w / data.histogram.size
        val peak = data.histogram.maxOrNull()?.coerceAtLeast(1e-4f) ?: 1f
        data.histogram.forEachIndexed { index, value ->
            val barHeight = (value / peak * h).coerceAtMost(h)
            canvas.drawRect(pad + index * columnWidth, pad + h - barHeight,
                pad + (index + 1) * columnWidth, pad + h, paint)
        }
    }
}
