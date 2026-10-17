package com.termex.replay15.editor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.termex.replay15.editor.domain.MAX_CLIP_SPEED
import com.termex.replay15.editor.domain.MIN_CLIP_SPEED
import com.termex.replay15.editor.domain.SpeedPoint
import com.termex.replay15.editor.domain.VideoClip
import com.termex.replay15.editor.domain.formatSpeed
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToLong

/**
 * Editor visual da curva de velocidade.
 *
 * O traço é a curva **amostrada pelo motor** (`ClipTimeMap.sampleCurve`, em Rust), não uma
 * interpolação própria da tela: o que se vê é exatamente o que a prévia e a exportação usam,
 * com easing e Bézier. O eixo horizontal é o tempo da mídia (cortes e splits preservam a curva);
 * a parte fora do trecho cortado fica apagada. O eixo vertical é logarítmico (0,1× a 16×), então
 * 0,5× e 2× ficam à mesma distância de 1×.
 *
 * Gestos: arrastar um ponto muda tempo e velocidade (com ímã em 1×); toque duplo na curva cria um
 * ponto ali com a velocidade atual do motor. A linha vertical é a agulha da timeline.
 */
class SpeedCurveView(context: Context) : View(context) {
    /** O clipe em edição (com a curva atual); a curva desenhada vem do mapa de tempo dele. */
    var clip: VideoClip? = null
        set(value) { field = value; invalidate() }
    var points: List<SpeedPoint> = emptyList()
    var selected = 0
    /** Posição da agulha no tempo da mídia, ou `null` fora do clipe. */
    var playheadSourceUs: Long? = null
        set(value) { field = value; invalidate() }
    /** Chamado a cada mudança com o dedo ([finished] = dedo levantado). */
    var onEdit: (points: List<SpeedPoint>, selected: Int, finished: Boolean) -> Unit = { _, _, _ -> }
    /** Toque duplo: pedido de novo ponto num tempo da mídia. */
    var onAddAt: (sourceUs: Long) -> Unit = {}

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val dash = DashPathEffect(floatArrayOf(context.dp(4).toFloat(), context.dp(4).toFloat()), 0f)
    private val pad get() = context.dp(30).toFloat()
    private val sourceUs: Long get() = clip?.sourceUs ?: 1L
    private var dragging = false
    private var moved = false
    private var downX = 0f
    private var downY = 0f

    private fun x(time: Long) = pad + (width - 2 * pad) * (time.toDouble() / sourceUs).toFloat()
    private fun y(speed: Float) = height - pad - (height - 2 * pad) * (ln(speed / MIN_CLIP_SPEED) / ln(MAX_CLIP_SPEED / MIN_CLIP_SPEED))
    private fun timeAt(px: Float) = (((px - pad) / (width - 2 * pad)).coerceIn(0f, 1f) * sourceUs.toDouble()).roundToLong()
    private fun speedAt(py: Float): Float {
        val raw = (MIN_CLIP_SPEED * exp(((height - pad - py) / (height - 2 * pad)).coerceIn(0f, 1f) * ln(MAX_CLIP_SPEED / MIN_CLIP_SPEED)))
            .coerceIn(MIN_CLIP_SPEED, MAX_CLIP_SPEED)
        // Ímã em 1×: perto da linha de tempo real a velocidade gruda nela.
        if (abs(y(raw) - y(1f)) < context.dp(8)) return 1f
        return (raw * 100f).toInt() / 100f
    }

    init {
        isFocusable = true
        contentDescription = "Curva de velocidade. Arraste os pontos ou use os controles abaixo."
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(event: MotionEvent): Boolean {
            onAddAt(timeAt(event.x))
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            return true
        }
    })

    override fun onDraw(canvas: Canvas) {
        val c = clip ?: return
        paint.style = Paint.Style.FILL
        paint.color = EditorStyle.BG
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), context.dp(16).toFloat(), context.dp(16).toFloat(), paint)
        // Fora do trecho cortado: apagado.
        paint.color = EditorStyle.alpha(EditorStyle.TEXT, 0x10)
        canvas.drawRect(pad, pad, x(c.inUs), height - pad, paint)
        canvas.drawRect(x(c.outUs), pad, width - pad, height - pad, paint)
        // Grade de velocidades.
        paint.textSize = context.dp(10).toFloat()
        listOf(.1f, .25f, .5f, 1f, 2f, 4f, 8f, 16f).forEach { speed ->
            val strong = speed == 1f
            paint.color = if (strong) EditorStyle.alpha(EditorStyle.TEXT, 0x88) else EditorStyle.CARD_PRESSED
            paint.strokeWidth = context.dp(if (strong) 1.5f else 1f).toFloat()
            canvas.drawLine(pad, y(speed), width - pad, y(speed), paint)
            paint.color = if (strong) EditorStyle.TEXT else EditorStyle.MUTED
            canvas.drawText(formatSpeed(speed), 4f, y(speed) + context.dp(3), paint)
        }
        // Curva amostrada pelo motor.
        val samples = runCatching { c.timeMap.sampleCurve(0, c.sourceUs, 160) }.getOrNull()
        if (samples != null) {
            path.reset()
            samples.forEachIndexed { index, speed ->
                val px = pad + (width - 2 * pad) * index / (samples.size - 1f)
                if (index == 0) path.moveTo(px, y(speed)) else path.lineTo(px, y(speed))
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = context.dp(2.5f).toFloat()
            paint.color = EditorStyle.ACCENT
            canvas.drawPath(path, paint)
        }
        // Agulha da timeline.
        playheadSourceUs?.let { at ->
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = context.dp(1.5f).toFloat()
            paint.color = EditorStyle.ACTION
            paint.pathEffect = dash
            canvas.drawLine(x(at), pad * .5f, x(at), height - pad * .5f, paint)
            paint.pathEffect = null
        }
        // Pontos.
        paint.style = Paint.Style.FILL
        points.forEachIndexed { index, point ->
            val px = x(point.sourceUs)
            val py = y(point.speed)
            if (index == selected) {
                paint.color = EditorStyle.alpha(EditorStyle.ACTION, 0x44)
                canvas.drawCircle(px, py, context.dp(15).toFloat(), paint)
                paint.color = EditorStyle.ACTION
                canvas.drawCircle(px, py, context.dp(8).toFloat(), paint)
                paint.color = EditorStyle.TEXT
                paint.textSize = context.dp(11).toFloat()
                canvas.drawText(formatSpeed(point.speed), (px + context.dp(10)).coerceAtMost(width - pad * 2), (py - context.dp(12)).coerceAtLeast(pad), paint)
            } else {
                paint.color = EditorStyle.ACCENT
                canvas.drawCircle(px, py, context.dp(6).toFloat(), paint)
            }
        }
        paint.color = EditorStyle.MUTED
        paint.textSize = context.dp(10).toFloat()
        canvas.drawText("0s", pad, height - context.dp(6).toFloat(), paint)
        canvas.drawText(String.format(java.util.Locale.ROOT, "%.1fs", sourceUs / 1_000_000.0), width - pad * 2, height - context.dp(6).toFloat(), paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (clip == null || width <= pad * 2 || height <= pad * 2) return false
        gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; moved = false
                val radius = context.dp(26f).toFloat()
                val best = points.indices.map { it to hypot(event.x - x(points[it].sourceUs), event.y - y(points[it].speed)) }
                    .filter { it.second <= radius }.minByOrNull { it.second }
                dragging = best != null
                if (best != null) {
                    selected = best.first
                    parent.requestDisallowInterceptTouchEvent(true)
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onEdit(points, selected, false)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging && selected in points.indices) {
                if (!moved && hypot(event.x - downX, event.y - downY) > context.dp(5f)) moved = true
                if (moved) {
                    val min = points.getOrNull(selected - 1)?.sourceUs?.plus(1) ?: 0L
                    val max = points.getOrNull(selected + 1)?.sourceUs?.minus(1) ?: sourceUs
                    val time = timeAt(event.x).coerceIn(min, max)
                    val speed = speedAt(event.y)
                    val old = points[selected]
                    if (speed == 1f && old.speed != 1f) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    points = points.mapIndexed { index, point -> if (index == selected) point.copy(sourceUs = time, speed = speed) else point }
                    onEdit(points, selected, false)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) onEdit(points, selected, true)
                dragging = false; moved = false
                parent.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
