package com.termex.replay15.editor.preview

import android.content.Context
import android.graphics.*
import android.os.Build
import android.view.*
import com.termex.replay15.editor.core.PreviewPacingProbe
import com.termex.replay15.editor.domain.StickerClip
import com.termex.replay15.editor.transform.RealtimeProjectState
import com.termex.replay15.editor.transform.LayerStateEvaluator
import com.termex.replay15.editor.transform.TransformKeyframe
import com.termex.replay15.editor.transform.TransformState
import kotlin.math.*

/**
 * Professional direct manipulation overlay for sticker and image layers on preview.
 *
 * Provides rotation-aware hit-testing, bounding box with corner handles and top rotation knob,
 * translation drag with magnetic snapping, two-finger pinch-scale and rotation,
 * frame-coalesced realtime updates via RealtimeProjectState, and clean single-commit undo.
 */
class StickerHandlesView(context: Context) : View(context) {

    var stickers: List<StickerClip> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var timeUs = 0L
        set(value) {
            field = value
            invalidate()
        }

    var selected: String? = null
        set(value) {
            field = value
            invalidate()
        }

    var onChange: (StickerClip) -> Unit = {}
    var onPreviewChange: (StickerClip) -> Unit = {}
    var onCancelPreview: () -> Unit = {}
    var onSelected: (String) -> Unit = {}
    var onEdit: (String) -> Unit = {}

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private enum class TouchMode {
        NONE,
        MOVE,
        CORNER_SCALE,
        ROTATION_HANDLE,
        PINCH,
    }

    private var touchMode = TouchMode.NONE
    private var pressedClip: StickerClip? = null
    private var initialTransform: TransformState? = null
    private var movingTransform: TransformState? = null

    private var downX = 0f
    private var downY = 0f
    private var isDragging = false
    private var lastTapTime = 0L
    private var lastTappedClipId: String? = null

    private var initialCornerDist = 1f
    private var initialRotationKnobAngle = 0f

    private var pinchAngle = 0f
    private var pinchInitialRotation = 0f
    private var multiTouch = false

    private var snappedX: Float? = null
    private var snappedY: Float? = null

    // Coalescing state to prevent flooding preview engine with redundant redraws
    private var pendingTransform: TransformState? = null
    private var pendingClip: StickerClip? = null
    private var frameScheduled = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val current = movingTransform ?: return false
            val factor = detector.scaleFactor
            if (!factor.isFinite() || factor <= 0f) return true
            val newScale = (current.scaleX * factor).coerceIn(0.05f, 10f)
            movingTransform = current.copy(scaleX = newScale, scaleY = newScale)
            val clip = pressedClip ?: return true
            scheduleTransform(clip, movingTransform!!)
            return true
        }
    })

    private fun scheduleTransform(clip: StickerClip, transform: TransformState) {
        pendingClip = clip
        pendingTransform = transform
        RealtimeProjectState.updateTransform(clip.id, transform)
        invalidate()

        if (!frameScheduled) {
            frameScheduled = true
            postOnAnimation {
                frameScheduled = false
                val targetClip = pendingClip ?: return@postOnAnimation
                val targetTransform = pendingTransform ?: return@postOnAnimation
                RealtimeProjectState.updateTransform(targetClip.id, targetTransform)
                onPreviewChange(targetClip)
            }
        }
    }

    private fun currentTransform(clip: StickerClip): TransformState {
        if (clip.id == pressedClip?.id && movingTransform != null) {
            return movingTransform!!
        }
        val rt = RealtimeProjectState.transform(clip.id)
        if (rt != null) return rt
        return LayerStateEvaluator.evaluate(clip, timeUs).transform
    }

    private fun bounds(sticker: StickerClip, transform: TransformState = currentTransform(sticker)): RectF {
        val side = sticker.size * minOf(width, height)
        val scaledW = side * transform.scaleX
        val scaledH = side * transform.scaleY
        val centerX = width * transform.x
        val centerY = height * transform.y
        val pad = 6f * resources.displayMetrics.density
        return RectF(
            centerX - scaledW / 2f - pad,
            centerY - scaledH / 2f - pad,
            centerX + scaledW / 2f + pad,
            centerY + scaledH / 2f + pad,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val started = PreviewPacingProbe.begin("Recly.StickerHandlesView.draw")
        try {
            drawMeasured(canvas)
        } finally {
            PreviewPacingProbe.end("Recly.StickerHandlesView.draw", started)
        }
    }

    private fun drawMeasured(canvas: Canvas) {
        val activeClip = pressedClip
            ?: stickers.find { it.id == selected && LayerStateEvaluator.isActive(it, timeUs) }
            ?: return

        val density = resources.displayMetrics.density
        val transform = currentTransform(activeClip)
        val rect = bounds(activeClip, transform)
        val centerX = rect.centerX()
        val centerY = rect.centerY()

        val stemLength = 26f * density
        val knobRadius = 6.5f * density
        val cornerRadius = 5.5f * density

        // Draw Snapping Guide Lines
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.color = 0xFF5EDADB.toInt()

        if (snappedX != null) {
            val sx = width * snappedX!!
            canvas.drawLine(sx, 0f, sx, height.toFloat(), paint)
        }
        if (snappedY != null) {
            val sy = height * snappedY!!
            canvas.drawLine(0f, sy, width.toFloat(), sy, paint)
        }

        // Draw Bounding Box and Handles rotated around center
        canvas.save()
        canvas.rotate(transform.rotation, centerX, centerY)

        // Bounding Rectangle
        paint.color = 0xFF5EDADB.toInt()
        paint.strokeWidth = 2f * density
        paint.style = Paint.Style.STROKE
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)

        // Rotation Stem & Knob
        paint.color = 0xFF5EDADB.toInt()
        paint.strokeWidth = 1.5f * density
        canvas.drawLine(centerX, rect.top, centerX, rect.top - stemLength, paint)

        // Rotation Knob
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(centerX, rect.top - stemLength, knobRadius, paint)
        paint.style = Paint.Style.STROKE
        paint.color = 0xFF5EDADB.toInt()
        paint.strokeWidth = 2f * density
        canvas.drawCircle(centerX, rect.top - stemLength, knobRadius, paint)

        // Corner Handles
        val corners = listOf(
            rect.left to rect.top,
            rect.right to rect.top,
            rect.left to rect.bottom,
            rect.right to rect.bottom,
        )
        for ((cx, cy) in corners) {
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawCircle(cx, cy, cornerRadius, paint)
            paint.style = Paint.Style.STROKE
            paint.color = 0xFF5EDADB.toInt()
            paint.strokeWidth = 2f * density
            canvas.drawCircle(cx, cy, cornerRadius, paint)
        }

        canvas.restore()

        // Floating HUD pill when dragging
        if (isDragging && pressedClip != null) {
            drawHud(canvas, transform, density)
        }
    }

    private fun drawHud(canvas: Canvas, transform: TransformState, density: Float) {
        val scalePercent = (transform.scaleX * 100).roundToInt()
        val xPercent = (transform.x * 100).roundToInt()
        val yPercent = (transform.y * 100).roundToInt()
        val rotDeg = transform.rotation.roundToInt()
        val text = "Imagem   $scalePercent%   X $xPercent   Y $yPercent   $rotDeg°"

        textPaint.textSize = 12f * density
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val textWidth = textPaint.measureText(text)
        val hudWidth = textWidth + 24f * density
        val hudHeight = 32f * density
        val left = (width - hudWidth) / 2f
        val top = 12f * density

        paint.style = Paint.Style.FILL
        paint.color = 0xD918181B.toInt()
        canvas.drawRoundRect(RectF(left, top, left + hudWidth, top + hudHeight), 16f * density, 16f * density, paint)

        textPaint.color = Color.WHITE
        canvas.drawText(text, left + 12f * density, top + 20f * density, textPaint)
    }

    private fun mapTouchToLocal(touchX: Float, touchY: Float, centerX: Float, centerY: Float, rotationDegrees: Float): FloatArray {
        val point = floatArrayOf(touchX, touchY)
        val matrix = Matrix()
        matrix.setRotate(-rotationDegrees, centerX, centerY)
        matrix.mapPoints(point)
        return point
    }

    private fun containsBody(sticker: StickerClip, transform: TransformState, touchX: Float, touchY: Float): Boolean {
        val rect = bounds(sticker, transform)
        val centerX = rect.centerX()
        val centerY = rect.centerY()
        val local = mapTouchToLocal(touchX, touchY, centerX, centerY, transform.rotation)
        val lx = local[0]
        val ly = local[1]

        val minTargetPx = 48f * resources.displayMetrics.density
        val halfW = maxOf(rect.width() / 2f, minTargetPx / 2f)
        val halfH = maxOf(rect.height() / 2f, minTargetPx / 2f)
        return abs(lx - centerX) <= halfW && abs(ly - centerY) <= halfH
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        val hitRadius = 24f * density

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val activeCandidates = stickers.filter { LayerStateEvaluator.isActive(it, timeUs) }
            val selectedCandidate = activeCandidates.firstOrNull { it.id == selected }

            var resolvedTarget: StickerClip? = null
            var resolvedMode = TouchMode.NONE

            if (selectedCandidate != null) {
                val t = currentTransform(selectedCandidate)
                val rect = bounds(selectedCandidate, t)
                val local = mapTouchToLocal(event.x, event.y, rect.centerX(), rect.centerY(), t.rotation)
                val lx = local[0]
                val ly = local[1]

                val stemLength = 26f * density
                val knobX = rect.centerX()
                val knobY = rect.top - stemLength
                val distToKnob = hypot(lx - knobX, ly - knobY)

                if (distToKnob <= hitRadius) {
                    resolvedTarget = selectedCandidate
                    resolvedMode = TouchMode.ROTATION_HANDLE
                    initialRotationKnobAngle = atan2(event.y - rect.centerY(), event.x - rect.centerX())
                } else {
                    val corners = listOf(
                        rect.left to rect.top,
                        rect.right to rect.top,
                        rect.left to rect.bottom,
                        rect.right to rect.bottom,
                    )
                    val minCornerDist = corners.minOfOrNull { (cx, cy) -> hypot(lx - cx, ly - cy) } ?: Float.MAX_VALUE
                    if (minCornerDist <= hitRadius) {
                        resolvedTarget = selectedCandidate
                        resolvedMode = TouchMode.CORNER_SCALE
                        initialCornerDist = hypot(event.x - rect.centerX(), event.y - rect.centerY()).coerceAtLeast(1f)
                    } else if (rect.contains(lx, ly) || containsBody(selectedCandidate, t, event.x, event.y)) {
                        resolvedTarget = selectedCandidate
                        resolvedMode = TouchMode.MOVE
                    }
                }
            }

            if (resolvedTarget == null) {
                val hitCandidate = activeCandidates.asReversed().firstOrNull {
                    containsBody(it, currentTransform(it), event.x, event.y)
                }
                if (hitCandidate != null) {
                    resolvedTarget = hitCandidate
                    resolvedMode = TouchMode.MOVE
                }
            }

            if (resolvedTarget != null) {
                pressedClip = resolvedTarget
                touchMode = resolvedMode
                val current = currentTransform(resolvedTarget)
                initialTransform = current
                movingTransform = current
                downX = event.x
                downY = event.y
                isDragging = false
                multiTouch = false
                snappedX = null
                snappedY = null

                if (selected != resolvedTarget.id) {
                    selected = resolvedTarget.id
                    onSelected(resolvedTarget.id)
                }
                parent.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            } else {
                if (selected != null) {
                    selected = null
                    onSelected("")
                    onCancelPreview()
                    invalidate()
                }
                return false
            }
        }

        if (pressedClip == null || initialTransform == null) return false

        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    multiTouch = true
                    touchMode = TouchMode.PINCH
                    pinchAngle = atan2(event.getY(1) - event.getY(0), event.getX(1) - event.getX(0))
                    pinchInitialRotation = movingTransform?.rotation ?: initialTransform!!.rotation
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val moveDist = hypot(event.x - downX, event.y - downY)
                if (!isDragging && moveDist > 5f * density) {
                    isDragging = true
                }

                val clip = pressedClip!!
                val rect = bounds(clip, movingTransform ?: initialTransform!!)
                val centerX = rect.centerX()
                val centerY = rect.centerY()

                when {
                    multiTouch && event.pointerCount >= 2 -> {
                        val angle = atan2(event.getY(1) - event.getY(0), event.getX(1) - event.getX(0))
                        val angleDiff = Math.toDegrees((angle - pinchAngle).toDouble()).toFloat()
                        val newRotation = ((pinchInitialRotation + angleDiff + 180f) % 360f + 360f) % 360f - 180f
                        movingTransform = (movingTransform ?: initialTransform!!).copy(rotation = newRotation)
                    }
                    touchMode == TouchMode.ROTATION_HANDLE -> {
                        val curAngle = atan2(event.y - centerY, event.x - centerX)
                        val angleDiff = Math.toDegrees((curAngle - initialRotationKnobAngle).toDouble()).toFloat()
                        val newRotation = ((initialTransform!!.rotation + angleDiff + 180f) % 360f + 360f) % 360f - 180f
                        movingTransform = (movingTransform ?: initialTransform!!).copy(rotation = newRotation)
                    }
                    touchMode == TouchMode.CORNER_SCALE -> {
                        val curDist = hypot(event.x - centerX, event.y - centerY)
                        val ratio = (curDist / initialCornerDist).coerceIn(0.05f, 20f)
                        val newScale = (initialTransform!!.scaleX * ratio).coerceIn(0.05f, 10f)
                        movingTransform = (movingTransform ?: initialTransform!!).copy(scaleX = newScale, scaleY = newScale)
                    }
                    touchMode == TouchMode.MOVE -> {
                        var rawX = (initialTransform!!.x + (event.x - downX) / width.toFloat()).coerceIn(0f, 1f)
                        var rawY = (initialTransform!!.y + (event.y - downY) / height.toFloat()).coerceIn(0f, 1f)

                        val snapThreshold = 0.015f
                        if (abs(rawX - 0.5f) < snapThreshold) {
                            if (snappedX == null) performHaptic()
                            rawX = 0.5f
                            snappedX = 0.5f
                        } else {
                            snappedX = null
                        }

                        if (abs(rawY - 0.5f) < snapThreshold) {
                            if (snappedY == null) performHaptic()
                            rawY = 0.5f
                            snappedY = 0.5f
                        } else {
                            snappedY = null
                        }

                        movingTransform = (movingTransform ?: initialTransform!!).copy(x = rawX, y = rawY)
                    }
                }

                if (isDragging && movingTransform != null) {
                    scheduleTransform(clip, movingTransform!!)
                }
            }
            MotionEvent.ACTION_UP -> {
                val clip = pressedClip!!
                val finalTransform = movingTransform ?: initialTransform!!

                RealtimeProjectState.clear(clip.id)
                pendingClip = null
                pendingTransform = null
                frameScheduled = false

                if (isDragging && finalTransform != initialTransform) {
                    val committedClip = applyTransformToClip(clip, finalTransform)
                    onChange(committedClip)
                } else {
                    onCancelPreview()
                    val isTap = hypot(event.x - downX, event.y - downY) < 6f * density
                    if (isTap) {
                        val now = event.eventTime
                        if (now - lastTapTime < 350L && lastTappedClipId == clip.id) {
                            onEdit(clip.id)
                            lastTapTime = 0L
                            lastTappedClipId = null
                        } else {
                            lastTapTime = now
                            lastTappedClipId = clip.id
                        }
                    }
                }

                pressedClip = null
                initialTransform = null
                movingTransform = null
                touchMode = TouchMode.NONE
                isDragging = false
                multiTouch = false
                snappedX = null
                snappedY = null
                invalidate()
                parent.requestDisallowInterceptTouchEvent(false)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedClip?.let { RealtimeProjectState.clear(it.id) }
                pendingClip = null
                pendingTransform = null
                frameScheduled = false
                pressedClip = null
                initialTransform = null
                movingTransform = null
                touchMode = TouchMode.NONE
                isDragging = false
                multiTouch = false
                snappedX = null
                snappedY = null
                invalidate()
                parent.requestDisallowInterceptTouchEvent(false)
                onCancelPreview()
            }
        }
        return true
    }

    private fun applyTransformToClip(clip: StickerClip, transform: TransformState): StickerClip {
        val clean = transform.bounded()
        return if (clip.transformKeyframes.isEmpty()) {
            clip.copy(
                x = clean.x,
                y = clean.y,
                scale = clean.scaleX,
                rotation = clean.rotation,
                opacity = clean.opacity,
            )
        } else {
            val targetTimeUs = timeUs.coerceIn(clip.startUs, clip.endUs)
            val toleranceUs = 35_000L
            val existing = clip.transformKeyframes.firstOrNull { abs(it.timeUs - targetTimeUs) <= toleranceUs }

            val updatedKey = if (existing != null) {
                existing.copy(transform = clean)
            } else {
                TransformKeyframe(targetTimeUs, clean)
            }

            val filtered = clip.transformKeyframes.filterNot {
                if (existing != null) it.timeUs == existing.timeUs else abs(it.timeUs - targetTimeUs) <= toleranceUs
            }
            val newKeyframes = (filtered + updatedKey).sortedBy { it.timeUs }
            clip.copy(transformKeyframes = newKeyframes)
        }
    }

    private fun performHaptic() {
        if (Build.VERSION.SDK_INT >= 27) {
            performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
        } else {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
