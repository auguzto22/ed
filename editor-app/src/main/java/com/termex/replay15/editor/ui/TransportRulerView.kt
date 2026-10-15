package com.termex.replay15.editor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View

/**
 * Régua de segundos que corre por trás da barra do play (referência: vídeo do Mauro, estilo Edits).
 *
 * A agulha é o centro da barra: o segundo atual fica sempre no meio e as marcas andam para a
 * esquerda enquanto toca, acumulando "1 s, 3 s, 5 s..." como uma mini timeline. Um traço por
 * segundo, rótulo nos segundos ímpares, e as bordas somem num degradê para os ícones ficarem limpos.
 * Só desenha: quem move é [positionUs], atualizado a cada quadro em updatePosition.
 */
class TransportRulerView(context: Context) : View(context) {

    var positionUs: Long = 0L
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var durationUs: Long = 0L
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40FFFFFF
        strokeWidth = 1f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x8CFFFFFF.toInt()
        textSize = 13f * density
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        fontFeatureSettings = "tnum"
    }
    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private var fadeWidth = -1

    init {
        // Camada própria para o degradê das bordas (DST_IN) apagar só a régua.
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Cerca de 7 segundos visíveis na largura da tela, como na referência.
        val pxPerSecond = (w / 7f).coerceAtLeast(48f * density)
        val center = w / 2f
        val nowSec = positionUs / 1_000_000.0
        val halfSpan = center / pxPerSecond
        val first = kotlin.math.floor(nowSec - halfSpan).toInt().coerceAtLeast(0)
        val totalSec = if (durationUs > 0L) kotlin.math.ceil(durationUs / 1_000_000.0).toInt() else Int.MAX_VALUE
        val last = minOf(kotlin.math.ceil(nowSec + halfSpan).toInt() + 1, totalSec)

        val tickTop = h * .18f
        val tickBottom = h * .82f
        val baseline = h * .44f
        for (s in first..last) {
            val x = center + ((s - nowSec) * pxPerSecond).toFloat()
            if (s % 2 == 1) {
                // Rótulo ímpar à esquerda do traço, alinhado com a linha de cima do contador.
                val text = "$s s"
                canvas.drawText(text, x - labelPaint.measureText(text) - 4f * density, baseline, labelPaint)
            }
            canvas.drawLine(x, tickTop, x, tickBottom, tickPaint)
        }

        // Degradê nas bordas: a régua aparece no meio e some perto do play e do desfazer/refazer.
        if (fadeWidth != width) {
            fadeWidth = width
            fadePaint.shader = LinearGradient(0f, 0f, w, 0f,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.BLACK, Color.BLACK, Color.TRANSPARENT, Color.TRANSPARENT),
                floatArrayOf(0f, .08f, .3f, .7f, .92f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w, h, fadePaint)
    }
}
