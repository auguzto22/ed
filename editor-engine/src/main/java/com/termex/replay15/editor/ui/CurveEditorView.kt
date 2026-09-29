package com.termex.replay15.editor.ui

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class CurveEditorView(context: Context, initial: List<Float>) : View(context) {
    var values: List<Float> = initial; private set
    var onChange: (List<Float>) -> Unit = {}
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var active = -1
    private val pad get() = context.dp(18).toFloat()
    init { minimumHeight = context.dp(180); contentDescription = "Curva de luminosidade. Arraste um dos cinco pontos para alterar o contraste."; isFocusable = true }
    fun reset(points: List<Float> = listOf(0f, .25f, .5f, .75f, 1f)) { values = points; onChange(points); invalidate() }
    override fun onDraw(canvas: Canvas) {
        paint.color = 0xFF0D0C13.toInt(); canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), pad, pad, paint)
        val w = width - 2 * pad; val h = height - 2 * pad
        paint.color = 0xFF352D45.toInt(); paint.strokeWidth = 1f
        for (i in 0..4) { val t = i / 4f; canvas.drawLine(pad + w * t, pad, pad + w * t, pad + h, paint); canvas.drawLine(pad, pad + h * t, pad + w, pad + h * t, paint) }
        paint.color = 0xFF554D64.toInt(); canvas.drawLine(pad, pad + h, pad + w, pad, paint)
        val path = Path()
        values.forEachIndexed { index, y -> if (index == 0) path.moveTo(pad, pad + h * (1 - y)) else path.lineTo(pad + w * index / 4, pad + h * (1 - y)) }
        paint.color = EditorStyle.ACCENT; paint.style = Paint.Style.STROKE; paint.strokeWidth = context.dp(2).toFloat(); canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
        values.forEachIndexed { index, y ->
            val isSelected = index == active
            paint.color = if (isSelected) Color.WHITE else EditorStyle.ACCENT
            val px = pad + w * index / 4
            val py = pad + h * (1 - y)
            canvas.drawCircle(px, py, context.dp(if (isSelected) 6.5f else 5f).toFloat(), paint)
            if (isSelected) {
                paint.color = 0x446366F1.toInt()
                canvas.drawCircle(px, py, context.dp(12f).toFloat(), paint)
            }
        }
    }
    private var downX = 0f
    private var downY = 0f
    private var isDragging = false
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val w = width - 2 * pad
        val h = height - 2 * pad
        val touchRadius = context.dp(24f).toFloat()

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; isDragging = false
                // Find nearest point within 48dp capture zone
                val best = values.indices.map { i ->
                    val px = pad + w * i / 4
                    val py = pad + h * (1 - values[i])
                    i to kotlin.math.hypot(e.x - px, e.y - py)
                }.filter { it.second <= touchRadius }.minByOrNull { it.second }

                if (best != null) {
                    active = best.first
                    parent.requestDisallowInterceptTouchEvent(true)
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    invalidate()
                    return true
                } else {
                    active = -1
                    return false
                }
            }
            MotionEvent.ACTION_MOVE -> if (active in values.indices) {
                if (!isDragging && kotlin.math.hypot(e.x - downX, e.y - downY) > context.dp(6f)) {
                    isDragging = true
                }
                if (isDragging) {
                    val newY = (1 - (e.y - pad) / h).coerceIn(0f, 1f)
                    values = values.mapIndexed { i, old -> if (i == active) newY else old }
                    onChange(values)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = -1
                isDragging = false
                parent.requestDisallowInterceptTouchEvent(false)
                if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
                invalidate()
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
