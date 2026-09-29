package com.termex.replay15.editor.ui

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import com.termex.replay15.editor.domain.*
import kotlin.math.hypot

class EasingGraphView(context: Context) : View(context) {
    var easing = Easing.SMOOTH
    var bezier = CubicBezier()
    var onEdit: (CubicBezier) -> Unit = {}
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val pad get() = context.dp(24).toFloat()
    private var active = -1
    private fun x(t: Float) = pad + (width - pad * 2) * t
    private fun y(t: Float) = height - pad - (height - pad * 2) * (t + 2) / 5
    init { isFocusable = true; contentDescription = "Grafico de interpolacao. Bezier tem duas alcas arrastaveis, tambem editaveis nos campos abaixo." }
    override fun onDraw(canvas: Canvas) {
        paint.color = EditorStyle.BG; canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 18f, 18f, paint)
        paint.color = EditorStyle.CARD; paint.strokeWidth = 1f
        for (value in -2..3) canvas.drawLine(x(0f), y(value.toFloat()), x(1f), y(value.toFloat()), paint)
        for (index in 0..4) canvas.drawLine(x(index / 4f), pad, x(index / 4f), height - pad, paint)
        paint.color = EditorStyle.MUTED; canvas.drawLine(x(0f), y(0f), x(1f), y(1f), paint)
        path.reset()
        for (index in 0..200) {
            val t = index / 200f; val value = easing.apply(t, bezier)
            if (index == 0) path.moveTo(x(t), y(value)) else path.lineTo(x(t), y(value))
        }
        paint.color = EditorStyle.ACCENT; paint.style = Paint.Style.STROKE; paint.strokeWidth = context.dp(2).toFloat()
        canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
        if (easing == Easing.BEZIER) {
            paint.color = Color.WHITE
            canvas.drawLine(x(0f), y(0f), x(bezier.x1), y(bezier.y1), paint)
            canvas.drawLine(x(1f), y(1f), x(bezier.x2), y(bezier.y2), paint)
            val p1x = x(bezier.x1); val p1y = y(bezier.y1)
            val p2x = x(bezier.x2); val p2y = y(bezier.y2)
            if (active == 0) {
                paint.color = 0x446366F1.toInt()
                canvas.drawCircle(p1x, p1y, context.dp(14).toFloat(), paint)
            }
            if (active == 1) {
                paint.color = 0x446366F1.toInt()
                canvas.drawCircle(p2x, p2y, context.dp(14).toFloat(), paint)
            }
            paint.color = if (active == 0) Color.WHITE else 0xFF6366F1.toInt()
            canvas.drawCircle(p1x, p1y, context.dp(7).toFloat(), paint)
            paint.color = if (active == 1) Color.WHITE else 0xFF6366F1.toInt()
            canvas.drawCircle(p2x, p2y, context.dp(7).toFloat(), paint)
        }
        paint.textSize = context.dp(11).toFloat(); paint.color = EditorStyle.MUTED
        canvas.drawText("TEMPO", x(.4f), height - 3f, paint)
        canvas.drawText("VALOR", pad, pad - 5f, paint)
    }
    private var downX = 0f
    private var downY = 0f
    private var isDragging = false
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (easing != Easing.BEZIER || width <= 2 * pad || height <= 2 * pad) return false
        val touchRadius = context.dp(24f).toFloat()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; isDragging = false
                val d0 = hypot(event.x - x(bezier.x1), event.y - y(bezier.y1))
                val d1 = hypot(event.x - x(bezier.x2), event.y - y(bezier.y2))
                active = when {
                    d0 <= touchRadius && (d0 <= d1 || d1 > touchRadius) -> 0
                    d1 <= touchRadius -> 1
                    else -> -1
                }
                if (active >= 0) {
                    parent.requestDisallowInterceptTouchEvent(true)
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    invalidate()
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> if (active >= 0) {
                if (!isDragging && hypot(event.x - downX, event.y - downY) > context.dp(6f)) {
                    isDragging = true
                }
                if (isDragging) {
                    val xx = ((event.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
                    val yy = (((height - pad - event.y) / (height - 2 * pad)) * 5 - 2).coerceIn(-2f, 3f)
                    bezier = if (active == 0) bezier.copy(x1 = xx, y1 = yy) else bezier.copy(x2 = xx, y2 = yy)
                    onEdit(bezier); invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = -1
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
