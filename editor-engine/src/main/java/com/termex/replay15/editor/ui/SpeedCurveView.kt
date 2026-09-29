package com.termex.replay15.editor.ui

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import com.termex.replay15.editor.domain.SpeedPoint
import com.termex.replay15.editor.domain.formatSpeed
import kotlin.math.*

class SpeedCurveView(context: Context, private val sourceUs: Long) : View(context) {
    var points: List<SpeedPoint> = emptyList()
    var selected = 0
    var onEdit: (List<SpeedPoint>, Int) -> Unit = { _, _ -> }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val pad get() = context.dp(32).toFloat()
    private var dragging = false
    private fun x(time: Long) = pad + (width - 2 * pad) * (time.toDouble() / sourceUs).toFloat()
    private fun y(speed: Float) = height - pad - (height - 2 * pad) * (ln(speed / .1f) / ln(160f))
    init { isFocusable = true; contentDescription = "Curva de velocidade. Arraste os pontos ou use os controles numericos abaixo." }
    override fun onDraw(canvas: Canvas) {
        paint.color = EditorStyle.BG; canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 18f, 18f, paint)
        paint.textSize = context.dp(11).toFloat()
        listOf(.1f, 1f, 4f, 16f).forEach { speed ->
            paint.color = if (speed == 1f) 0x99FFFFFF.toInt() else EditorStyle.CARD
            paint.strokeWidth = context.dp(if (speed == 1f) 1.5f else 1f).toFloat()
            canvas.drawLine(pad, y(speed), width - pad, y(speed), paint)
            paint.color = if (speed == 1f) Color.WHITE else EditorStyle.MUTED
            canvas.drawText(formatSpeed(speed), 2f, y(speed), paint)
        }
        path.reset()
        points.forEachIndexed { i, point ->
            if (i == 0) path.moveTo(x(point.sourceUs), y(point.speed)) else {
                val before = points[i - 1]
                for (step in 1..24) {
                    val t = step / 24f
                    path.lineTo(x(before.sourceUs + ((point.sourceUs - before.sourceUs) * t.toDouble()).roundToLong()),
                        y(before.speed + (point.speed - before.speed) * t))
                }
            }
        }
        paint.color = EditorStyle.ACCENT; paint.style = Paint.Style.STROKE; paint.strokeWidth = context.dp(2).toFloat()
        canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
        points.forEachIndexed { i, point ->
            val isPointSelected = i == selected
            val px = x(point.sourceUs)
            val py = y(point.speed)
            if (isPointSelected) {
                paint.color = 0x446366F1.toInt()
                canvas.drawCircle(px, py, context.dp(14).toFloat(), paint)
            }
            paint.color = if (isPointSelected) Color.WHITE else EditorStyle.ACCENT
            canvas.drawCircle(px, py, context.dp(if (isPointSelected) 9 else 6).toFloat(), paint)
        }
        paint.color = EditorStyle.MUTED
        canvas.drawText("0s", pad, height - 5f, paint)
        canvas.drawText("${sourceUs / 1_000_000}s", width - pad * 2, height - 5f, paint)
    }
    private var downX = 0f
    private var downY = 0f
    private var isDragging = false
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (points.isEmpty() || width <= pad * 2 || height <= pad * 2) return false
        val touchRadius = context.dp(24f).toFloat()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; isDragging = false
                val best = points.indices.map { i ->
                    i to hypot(event.x - x(points[i].sourceUs), event.y - y(points[i].speed))
                }.filter { it.second <= touchRadius }.minByOrNull { it.second }

                if (best != null) {
                    selected = best.first
                    dragging = true
                    parent.requestDisallowInterceptTouchEvent(true)
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    onEdit(points, selected)
                    invalidate()
                    return true
                } else {
                    dragging = false
                    return false
                }
            }
            MotionEvent.ACTION_MOVE -> if (dragging && selected in points.indices) {
                if (!isDragging && hypot(event.x - downX, event.y - downY) > context.dp(6f)) {
                    isDragging = true
                }
                if (isDragging) {
                    val min = points.getOrNull(selected - 1)?.sourceUs?.plus(1) ?: 0L
                    val max = points.getOrNull(selected + 1)?.sourceUs?.minus(1) ?: sourceUs
                    val time = (((event.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f) * sourceUs.toDouble()).roundToLong().coerceIn(min, max)
                    val speed = (.1 * exp(((height - pad - event.y) / (height - 2 * pad)).coerceIn(0f, 1f) * ln(160.0))).toFloat().coerceIn(.1f, 16f)
                    points = points.mapIndexed { index, old -> if (index == selected) SpeedPoint(time, speed) else old }
                    onEdit(points, selected)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                isDragging = false
                parent.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                invalidate()
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
