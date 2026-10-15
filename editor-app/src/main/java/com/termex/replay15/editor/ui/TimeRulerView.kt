package com.termex.replay15.editor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor

/**
 * Régua de tempo que desliza por trás do contador da barra de reprodução.
 *
 * A agulha é o centro da view: a posição atual fica sempre no meio e os segundos passam
 * da direita para a esquerda enquanto o vídeo toca. Segundos ímpares têm rótulo ("1 s",
 * "3 s"...) com um traço curto embaixo; segundos pares têm só uma linha fina e apagada.
 * Tudo some suavemente nas bordas e na faixa ocupada pelo contador, para o tempo nunca
 * ficar ilegível.
 *
 * Identidade Blitz: agulha vermelha no centro (continua na agulha da timeline logo abaixo)
 * e, enquanto o vídeo toca, o ponto de gravação vermelho do logo pulsando ao lado do tempo.
 */
class TimeRulerView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    /** Distância, em pixels, que representa um segundo de vídeo. */
    private val pxPerSecond = dp(56f)
    private val edgeFade = dp(28f)
    private val centerFade = dp(12f)

    /** Metade da largura que o contador ocupa no meio; a régua some embaixo dele. */
    var clearHalfWidthPx = 0f
        set(value) { if (field != value) { field = value; invalidate() } }

    /** Enquanto toca, o ponto de gravação aparece e pulsa. */
    var playing = false
        set(value) { if (field != value) { field = value; invalidate() } }

    private var positionUs = 0L
    private var durationUs = 0L

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = EditorStyle.MUTED
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        fontFeatureSettings = "tnum"
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt()
        strokeWidth = dp(1f)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x2EFFFFFF
        strokeWidth = dp(1f)
    }

    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = EditorStyle.ACTION
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = EditorStyle.ACTION }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setBackgroundColor(Color.TRANSPARENT)
    }

    /** Chamado a cada quadro pela atividade; só redesenha quando algo mudou. */
    fun update(positionUs: Long, durationUs: Long) {
        if (positionUs == this.positionUs && durationUs == this.durationUs) return
        this.positionUs = positionUs
        this.durationUs = durationUs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (durationUs <= 0L || width == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val posSec = positionUs / 1_000_000f
        val halfSpan = cx / pxPerSecond
        val first = floor(posSec - halfSpan).toInt().coerceAtLeast(0)
        val last = ceil(posSec + halfSpan).toInt().coerceAtMost((durationUs / 1_000_000L).toInt())

        val labelBaseline = h * .44f
        val tickTop = h * .60f
        val tickBottom = h * .78f
        val lineTop = h * .16f
        val lineBottom = h * .84f

        for (second in first..last) {
            val x = cx + (second - posSec) * pxPerSecond
            val alpha = visibility(x, w, cx)
            if (alpha <= 0f) continue
            if (second % 2 == 1) {
                labelPaint.alpha = (255 * alpha).toInt()
                canvas.drawText(label(second), x, labelBaseline, labelPaint)
                tickPaint.alpha = (128 * alpha).toInt()
                canvas.drawLine(x, tickTop, x, tickBottom, tickPaint)
            } else {
                linePaint.alpha = (46 * alpha).toInt()
                canvas.drawLine(x, lineTop, x, lineBottom, linePaint)
            }
        }

        // Agulha vermelha: sai de baixo do contador e encosta na timeline.
        canvas.drawLine(cx, h * .88f, cx, h, needlePaint)

        // Ponto de gravação, no canto de cima à direita do tempo, como no logo.
        if (playing && clearHalfWidthPx > 0f) {
            val pulse = .55f + .45f * cos(SystemClock.uptimeMillis() / 1000.0 * 2 * PI).toFloat()
            dotPaint.alpha = (255 * pulse).toInt()
            canvas.drawCircle(cx + clearHalfWidthPx - dp(2f), h * .22f, dp(3f), dotPaint)
            postInvalidateOnAnimation()
        }
    }

    /** 1 no meio livre da régua, 0 nas bordas e embaixo do contador, com transição suave. */
    private fun visibility(x: Float, w: Float, cx: Float): Float {
        val edge = minOf(x, w - x) / edgeFade
        val distance = abs(x - cx) - clearHalfWidthPx
        val center = if (clearHalfWidthPx <= 0f) 1f else distance / centerFade
        return minOf(edge, center, 1f).coerceAtLeast(0f)
    }

    private fun label(second: Int): String =
        if (second < 60) "$second s"
        else String.format(java.util.Locale.ROOT, "%d:%02d", second / 60, second % 60)
}
