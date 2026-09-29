package com.termex.replay15.editor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.termex.replay15.editor.domain.MaskPathPoint
import com.termex.replay15.editor.domain.MaskState
import kotlin.math.hypot

/** Small normalized polygon editor: drag points, double-tap to add and long-press to remove. */
class MaskPathEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x334F5B68; strokeWidth = density }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x554D8DFF; style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8CB4FF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * density
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC66D.toInt(); style = Paint.Style.FILL }
    private val path = Path()
    private var values = MaskState.DEFAULT_CUSTOM_PATH
    private var selected = -1
    var onChange: ((List<MaskPathPoint>) -> Unit)? = null

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true

        override fun onDoubleTap(event: MotionEvent): Boolean {
            if (values.size >= MaskState.MAX_MASK_PATH_POINTS) return true
            val point = normalized(event.x, event.y)
            values = values + point
            selected = values.lastIndex
            changed()
            return true
        }

        override fun onLongPress(event: MotionEvent) {
            val index = nearest(event.x, event.y)
            if (index >= 0 && values.size > 3) {
                values = values.filterIndexed { position, _ -> position != index }
                selected = -1
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                changed()
            }
        }
    })

    init {
        setBackgroundColor(0xFF15191F.toInt())
        contentDescription = "Editor do caminho da mascara"
    }

    fun setPoints(points: List<MaskPathPoint>) {
        values = points.takeIf { it.size >= 3 } ?: MaskState.DEFAULT_CUSTOM_PATH
        selected = -1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (step in 1..3) {
            val x = width * step / 4f
            val y = height * step / 4f
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
        }
        path.reset()
        values.forEachIndexed { index, point ->
            val x = point.x * width
            val y = point.y * height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, linePaint)
        values.forEachIndexed { index, point ->
            canvas.drawCircle(point.x * width, point.y * height, 7f * density,
                if (index == selected) selectedPaint else pointPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                selected = nearest(event.x, event.y)
                parent?.requestDisallowInterceptTouchEvent(selected >= 0)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (selected >= 0) {
                val point = normalized(event.x, event.y)
                values = values.mapIndexed { index, old -> if (index == selected) point else old }
                changed()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) selected = -1
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun normalized(x: Float, y: Float) = MaskPathPoint(
        (x / width.coerceAtLeast(1)).coerceIn(0f, 1f),
        (y / height.coerceAtLeast(1)).coerceIn(0f, 1f),
    )

    private fun nearest(x: Float, y: Float): Int {
        val threshold = 28f * density
        return values.indices.minByOrNull { index ->
            hypot(values[index].x * width - x, values[index].y * height - y)
        }?.takeIf { index -> hypot(values[index].x * width - x, values[index].y * height - y) <= threshold } ?: -1
    }

    private fun changed() {
        invalidate()
        onChange?.invoke(values.toList())
    }
}
