package com.termex.replay15.editor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.termex.replay15.editor.tracking.TrackingRegion
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Interactive touch overlay that draws and manipulates the tracking bounding box
 * directly on top of the video preview.
 */
class TrackingOverlayView(context: Context) : View(context) {

    var region: TrackingRegion = TrackingRegion(centerX = 0.5f, centerY = 0.5f, width = 0.25f, height = 0.25f)
        set(value) {
            field = value
            invalidate()
        }

    var onRegionChanged: (TrackingRegion) -> Unit = {}

    private val density = resources.displayMetrics.density

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5FF.toInt() // Recly cyan accent
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xDD00E5FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5FF.toInt()
        style = Paint.Style.FILL
    }

    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x25000000
        style = Paint.Style.FILL
    }

    private enum class DragMode { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    private var activeMode = DragMode.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private fun getBoxRect(): RectF {
        val w = width.toFloat()
        val h = height.toFloat()
        val bw = region.width * w
        val bh = region.height * h
        val cx = region.centerX * w
        val cy = region.centerY * h
        return RectF(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val box = getBoxRect()

        // Subtle background scrim to highlight target
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        // Draw bounding box
        canvas.drawRect(box, boxPaint)

        // Draw corner brackets (L shapes)
        val cornerLen = min(24f * density, min(box.width(), box.height()) * 0.35f)

        // Top-left
        canvas.drawLine(box.left, box.top, box.left + cornerLen, box.top, cornerPaint)
        canvas.drawLine(box.left, box.top, box.left, box.top + cornerLen, cornerPaint)

        // Top-right
        canvas.drawLine(box.right, box.top, box.right - cornerLen, box.top, cornerPaint)
        canvas.drawLine(box.right, box.top, box.right, box.top + cornerLen, cornerPaint)

        // Bottom-left
        canvas.drawLine(box.left, box.bottom, box.left + cornerLen, box.bottom, cornerPaint)
        canvas.drawLine(box.left, box.bottom, box.left, box.bottom - cornerLen, cornerPaint)

        // Bottom-right
        canvas.drawLine(box.right, box.bottom, box.right - cornerLen, box.bottom, cornerPaint)
        canvas.drawLine(box.right, box.bottom, box.right, box.bottom - cornerLen, cornerPaint)

        // Draw center crosshair reticle
        val cx = box.centerX()
        val cy = box.centerY()
        val crossLen = 10f * density
        val gap = 3f * density
        canvas.drawLine(cx - crossLen, cy, cx - gap, cy, reticlePaint)
        canvas.drawLine(cx + gap, cy, cx + crossLen, cy, reticlePaint)
        canvas.drawLine(cx, cy - crossLen, cx, cy - gap, reticlePaint)
        canvas.drawLine(cx, cy + gap, cx, cy + crossLen, reticlePaint)

        // Draw corner handle nodes
        val handleRadius = 4.5f * density
        val corners = arrayOf(
            Pair(box.left, box.top),
            Pair(box.right, box.top),
            Pair(box.left, box.bottom),
            Pair(box.right, box.bottom)
        )
        for ((hx, hy) in corners) {
            canvas.drawCircle(hx, hy, handleRadius, handleFillPaint)
            canvas.drawCircle(hx, hy, handleRadius, handleStrokePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || width <= 0 || height <= 0) return false

        val x = event.x
        val y = event.y
        val touchTolerance = 32f * density

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = x
                lastTouchY = y
                val box = getBoxRect()

                activeMode = when {
                    abs(x - box.left) < touchTolerance && abs(y - box.top) < touchTolerance -> DragMode.TOP_LEFT
                    abs(x - box.right) < touchTolerance && abs(y - box.top) < touchTolerance -> DragMode.TOP_RIGHT
                    abs(x - box.left) < touchTolerance && abs(y - box.bottom) < touchTolerance -> DragMode.BOTTOM_LEFT
                    abs(x - box.right) < touchTolerance && abs(y - box.bottom) < touchTolerance -> DragMode.BOTTOM_RIGHT
                    box.contains(x, y) -> DragMode.MOVE
                    else -> {
                        // Tap outside recenters the tracking box at the tapped point
                        val newCx = (x / width).coerceIn(0.1f, 0.9f)
                        val newCy = (y / height).coerceIn(0.1f, 0.9f)
                        updateRegion(newCx, newCy, region.width, region.height)
                        DragMode.MOVE
                    }
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (x - lastTouchX) / width
                val dy = (y - lastTouchY) / height
                lastTouchX = x
                lastTouchY = y

                var cx = region.centerX
                var cy = region.centerY
                var rw = region.width
                var rh = region.height

                when (activeMode) {
                    DragMode.MOVE -> {
                        cx = (cx + dx).coerceIn(0.05f, 0.95f)
                        cy = (cy + dy).coerceIn(0.05f, 0.95f)
                    }
                    DragMode.TOP_LEFT -> {
                        rw = (rw - dx * 2f).coerceIn(0.05f, 0.9f)
                        rh = (rh - dy * 2f).coerceIn(0.05f, 0.9f)
                        cx = (cx + dx).coerceIn(0.05f, 0.95f)
                        cy = (cy + dy).coerceIn(0.05f, 0.95f)
                    }
                    DragMode.TOP_RIGHT -> {
                        rw = (rw + dx * 2f).coerceIn(0.05f, 0.9f)
                        rh = (rh - dy * 2f).coerceIn(0.05f, 0.9f)
                        cx = (cx + dx).coerceIn(0.05f, 0.95f)
                        cy = (cy + dy).coerceIn(0.05f, 0.95f)
                    }
                    DragMode.BOTTOM_LEFT -> {
                        rw = (rw - dx * 2f).coerceIn(0.05f, 0.9f)
                        rh = (rh + dy * 2f).coerceIn(0.05f, 0.9f)
                        cx = (cx + dx).coerceIn(0.05f, 0.95f)
                        cy = (cy + dy).coerceIn(0.05f, 0.95f)
                    }
                    DragMode.BOTTOM_RIGHT -> {
                        rw = (rw + dx * 2f).coerceIn(0.05f, 0.9f)
                        rh = (rh + dy * 2f).coerceIn(0.05f, 0.9f)
                        cx = (cx + dx).coerceIn(0.05f, 0.95f)
                        cy = (cy + dy).coerceIn(0.05f, 0.95f)
                    }
                    DragMode.NONE -> return false
                }

                updateRegion(cx, cy, rw, rh)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeMode = DragMode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return false
    }

    private fun updateRegion(cx: Float, cy: Float, w: Float, h: Float) {
        val safeW = w.coerceIn(0.02f, 0.95f)
        val safeH = h.coerceIn(0.02f, 0.95f)
        val safeCx = cx.coerceIn(safeW / 2f, 1f - safeW / 2f)
        val safeCy = cy.coerceIn(safeH / 2f, 1f - safeH / 2f)
        region = TrackingRegion(safeCx, safeCy, safeW, safeH)
        onRegionChanged(region)
    }
}
