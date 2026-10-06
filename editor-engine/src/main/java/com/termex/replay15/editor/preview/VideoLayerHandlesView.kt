package com.termex.replay15.editor.preview

import com.termex.replay15.editor.core.PreviewPacingProbe
import android.content.Context
import android.graphics.*
import android.os.Build
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.termex.replay15.editor.core.isEditorDebuggable
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.render.RenderPlan
import kotlin.math.*

/** Direct manipulation overlay shared by the main clip and PIP/video layers. */
class VideoLayerHandlesView(context: Context) : View(context) {
    companion object { private const val TAG = "ReclyVideoGesture" }
    var project = Project(); set(value) { field = value; invalidate() }
    var mainClipIndex = -1; set(value) { field = value; invalidate() }
    var trackId: String? = null; set(value) { field = value; invalidate() }
    var clipId: String? = null; set(value) { field = value; invalidate() }
    var timeUs = 0L; set(value) { field = value; invalidate() }
    var onChange: (String, VideoClip) -> Unit = { _, _ -> }
    var onMainChange: (Int, VideoClip) -> Unit = { _, _ -> }
    /** Updates the render snapshot during a gesture without adding history entries. */
    var onPreviewChange: (String?, Int, VideoClip) -> Unit = { _, _, _ -> }
    var onCancelPreview: () -> Unit = {}
    var onMainSelected: (Int) -> Unit = {}
    var onTrackSelected: (String, String) -> Unit = { _, _ -> }

    private data class Target(val track: String?, val mainIndex: Int, val startUs: Long, val clip: VideoClip)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pressed: Target? = null
    private var initial: TransformKeyframe? = null
    private var draft: TransformKeyframe? = null
    private var downX = 0f
    private var downY = 0f
    private var pinchDistance = 0f
    private var pinchAngle = 0f
    private var snappedX: Float? = null
    private var snappedY: Float? = null
    private var gestureMoved = false
    private val debugLogs = context.isEditorDebuggable()

    private fun current(): Target? {
        val selectedTrack = trackId
        if (selectedTrack != null) {
            val item = project.videoTracks.firstOrNull { it.id == selectedTrack }?.activeAt(timeUs)
            if (item != null && item.clip.id == clipId && project.visualEnabled(selectedTrack)) {
                return Target(selectedTrack, -1, item.startUs, item.clip)
            }
        }
        val index = mainClipIndex.takeIf { it in project.videos.indices } ?: return null
        val start = project.startOf(index)
        val clip = project.videos[index]
        return clip.takeIf { timeUs in start until start + it.durationUs && project.visualEnabled(MAIN_TRACK) }
            ?.let { Target(null, index, start, it) }
    }

    private fun hitTest(target: Target, x: Float, y: Float): Boolean {
        val sourceUs = RenderPlan.sourceTime(target.clip, target.startUs, timeUs)
        val value = key(target).copy(sourceUs = sourceUs)
        val point = floatArrayOf(x, y)
        val rect = bounds(value)
        Matrix().apply { setRotate(-value.rotation, rect.centerX(), rect.centerY()); mapPoints(point) }

        val cornerRadiusPx = 24f * resources.displayMetrics.density
        val lx = point[0]; val ly = point[1]
        val cornerDist = listOf(
            hypot(lx - rect.right, ly - rect.bottom),
            hypot(lx - rect.left, ly - rect.bottom),
            hypot(lx - rect.right, ly - rect.top),
            hypot(lx - rect.left, ly - rect.top),
        ).minOrNull() ?: Float.MAX_VALUE

        return rect.contains(lx, ly) || cornerDist <= cornerRadiusPx
    }

    private fun resolveTargetAt(x: Float, y: Float): Target? {
        val active = current()
        if (active != null && hitTest(active, x, y)) {
            return active
        }
        for (track in project.videoTracks.asReversed()) {
            if (!project.visualEnabled(track.id) || project.trackState(track.id).locked) continue
            val item = track.activeAt(timeUs) ?: continue
            val candidate = Target(track.id, -1, item.startUs, item.clip)
            if (hitTest(candidate, x, y)) return candidate
        }
        if (project.visualEnabled(MAIN_TRACK) && !project.trackState(MAIN_TRACK).locked) {
            val mainIndex = project.indexAt(timeUs)
            if (mainIndex in project.videos.indices) {
                val start = project.startOf(mainIndex)
                val clip = project.videos[mainIndex]
                if (timeUs in start until start + clip.durationUs) {
                    val candidate = Target(null, mainIndex, start, clip)
                    if (hitTest(candidate, x, y)) return candidate
                }
            }
        }
        return null
    }

    private fun key(target: Target) = target.clip.transformAt(RenderPlan.sourceTime(target.clip, target.startUs, timeUs))
    private fun bounds(value: TransformKeyframe) = RectF(
        width * (.5f + value.x - value.zoom / 2), height * (.5f + value.y - value.zoom / 2),
        width * (.5f + value.x + value.zoom / 2), height * (.5f + value.y + value.zoom / 2),
    )

    override fun onDraw(canvas: Canvas) {
        val started = PreviewPacingProbe.begin("Recly.VideoLayerHandlesView.draw")
        try { drawMeasured(canvas) }
        finally { PreviewPacingProbe.end("Recly.VideoLayerHandlesView.draw", started) }
    }
    private fun drawMeasured(canvas: Canvas) {
        val target = current() ?: return
        val value = draft ?: key(target)
        val rect = bounds(value)
        val density = resources.displayMetrics.density

        // Motion Path Trajectory
        if (target.clip.keyframes.size >= 2) {
            val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xAA00E5FF.toInt()
                strokeWidth = 1.5f * density
                style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
            }
            val motionPath = Path()
            target.clip.keyframes.forEachIndexed { i, kf ->
                val px = width * (.5f + kf.x)
                val py = height * (.5f + kf.y)
                if (i == 0) motionPath.moveTo(px, py) else motionPath.lineTo(px, py)
            }
            canvas.drawPath(motionPath, pathPaint)
            val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF00E5FF.toInt()
                style = Paint.Style.FILL
            }
            target.clip.keyframes.forEach { kf ->
                val px = width * (.5f + kf.x)
                val py = height * (.5f + kf.y)
                canvas.drawCircle(px, py, 3f * density, markerPaint)
            }
        }

        val isNull = target.clip.isNullObject
        val strokeColor = if (isNull) 0xFFFFB300.toInt() else Color.WHITE
        paint.color = strokeColor; paint.strokeWidth = 1.5f * density; paint.style = Paint.Style.STROKE
        if (isNull) {
            paint.pathEffect = DashPathEffect(floatArrayOf(8f * density, 4f * density), 0f)
        } else {
            paint.pathEffect = null
        }
        canvas.save(); canvas.rotate(value.rotation, rect.centerX(), rect.centerY()); canvas.drawRect(rect, paint)

        // Reset pathEffect for corners and handles
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
        listOf(rect.left to rect.top, rect.right to rect.top, rect.left to rect.bottom, rect.right to rect.bottom).forEach {
            canvas.drawCircle(it.first, it.second, 4.5f * density, paint)
        }

        // Draw Anchor Point Pivot Crosshair
        val anchorPx = rect.centerX() + value.anchorX * (rect.width() / 2f)
        val anchorPy = rect.centerY() + value.anchorY * (rect.height() / 2f)
        paint.color = 0xFFFFAB00.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        canvas.drawCircle(anchorPx, anchorPy, 6f * density, paint)
        canvas.drawLine(anchorPx - 9f * density, anchorPy, anchorPx + 9f * density, anchorPy, paint)
        canvas.drawLine(anchorPx, anchorPy - 9f * density, anchorPx, anchorPy + 9f * density, paint)

        // Draw 3D or NULL badge at top-left
        if (isNull || target.clip.is3D) {
            val badgeText = if (isNull) "NULL 3D" else "3D"
            paint.textSize = 10f * density
            paint.typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            val badgeWidth = paint.measureText(badgeText)
            val badgeLeft = rect.left + 4f * density
            val badgeTop = rect.top + 4f * density
            paint.color = 0xD9141416.toInt()
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(badgeLeft, badgeTop, badgeLeft + badgeWidth + 8f * density, badgeTop + 14f * density, 4f * density, 4f * density, paint)
            paint.color = if (isNull) 0xFFFFB300.toInt() else 0xFF00E5FF.toInt()
            canvas.drawText(badgeText, badgeLeft + 4f * density, badgeTop + 11f * density, paint)
        }

        canvas.restore()

        paint.color = 0xCCFFFFFF.toInt(); paint.strokeWidth = density
        snappedX?.let { canvas.drawLine(width * (.5f + it), 0f, width * (.5f + it), height.toFloat(), paint) }
        snappedY?.let { canvas.drawLine(0f, height * (.5f + it), width.toFloat(), height * (.5f + it), paint) }
        if (pressed != null) drawHud(canvas, target, value)
    }

    private fun drawHud(canvas: Canvas, target: Target, value: TransformKeyframe) {
        val density = resources.displayMetrics.density
        val text = if (target.clip.is3D) {
            "3D  Z ${(value.z).roundToInt()}   X ${(value.x * 100).roundToInt()}   Y ${(value.y * 100).roundToInt()}   RX ${value.rotationX.roundToInt()}°  RY ${value.rotationY.roundToInt()}°  ${value.rotation.roundToInt()}°"
        } else {
            "${(value.zoom * 100).roundToInt()}%   X ${(value.x * 100).roundToInt()}   Y ${(value.y * 100).roundToInt()}   ${value.rotation.roundToInt()}°"
        }
        paint.textSize = 12f * density; paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val textWidth = paint.measureText(text)
        val left = (width - textWidth) / 2f - 10f * density
        val top = 10f * density
        paint.color = 0xD9141416.toInt(); paint.style = Paint.Style.FILL
        canvas.drawRoundRect(left, top, left + textWidth + 20f * density, top + 30f * density, 10f * density, 10f * density, paint)
        paint.color = Color.WHITE
        canvas.drawText(text, left + 10f * density, top + 20f * density, paint)
    }

    private enum class TouchMode { NONE, MOVE, CORNER_SCALE_ROTATE, PINCH }
    private var touchMode = TouchMode.NONE
    private var isDragging = false
    private var initialDistanceToCenter = 1f
    private var initialAngleToCenter = 0f

    private fun snap(value: Float, axis: Int): Float {
        val enterThreshold = .016f
        val releaseThreshold = .030f
        val currentSnap = if (axis == 0) snappedX else snappedY

        if (currentSnap != null) {
            if (abs(currentSnap - value) <= releaseThreshold) {
                return currentSnap
            } else {
                if (axis == 0) snappedX = null else snappedY = null
            }
        }

        val target = listOf(-.4f, 0f, .4f).minByOrNull { abs(it - value) }!!
        if (abs(target - value) <= enterThreshold) {
            val old = if (axis == 0) snappedX else snappedY
            if (target != old) {
                performHapticFeedback(
                    if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.TEXT_HANDLE_MOVE else HapticFeedbackConstants.KEYBOARD_TAP,
                )
            }
            if (axis == 0) snappedX = target else snappedY = target
            return target
        }
        return value
    }

    private fun changedClip(target: Target, value: TransformKeyframe): VideoClip {
        val clean = value.copy(
            zoom = value.zoom.takeIf { it.isFinite() }?.coerceIn(.25f, 4f) ?: 1f,
            x = value.x.takeIf { it.isFinite() }?.coerceIn(-.5f, .5f) ?: 0f,
            y = value.y.takeIf { it.isFinite() }?.coerceIn(-.5f, .5f) ?: 0f,
            rotation = value.rotation.takeIf { it.isFinite() }?.coerceIn(-180f, 180f) ?: 0f,
        )
        val clip = target.clip
        return if (clip.keyframes.isEmpty()) clip.copy(zoom = clean.zoom, offsetX = clean.x, offsetY = clean.y, fineRotation = clean.rotation)
        else clip.copy(keyframes = (clip.keyframes.filter { it.sourceUs != clean.sourceUs } + clean).sortedBy { it.sourceUs })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val target = resolveTargetAt(event.x, event.y) ?: return false
                if (project.trackState(target.track ?: MAIN_TRACK).locked) return false
                val sourceUs = RenderPlan.sourceTime(target.clip, target.startUs, timeUs)
                val value = key(target).copy(sourceUs = sourceUs)
                if (target.clip.keyframes.size >= 200 && target.clip.keyframes.none { it.sourceUs == sourceUs }) {
                    android.widget.Toast.makeText(context, "Limite de 200 keyframes. Selecione um ponto existente.", android.widget.Toast.LENGTH_LONG).show()
                    return false
                }
                val point = floatArrayOf(event.x, event.y)
                val rect = bounds(value)
                Matrix().apply { setRotate(-value.rotation, rect.centerX(), rect.centerY()); mapPoints(point) }

                // Check 48dp corner hit testing
                val cornerRadiusPx = 24f * density
                val lx = point[0]; val ly = point[1]
                val cornerDist = listOf(
                    hypot(lx - rect.right, ly - rect.bottom),
                    hypot(lx - rect.left, ly - rect.bottom),
                    hypot(lx - rect.right, ly - rect.top),
                    hypot(lx - rect.left, ly - rect.top),
                ).minOrNull() ?: Float.MAX_VALUE

                val insideRect = rect.contains(lx, ly)
                if (!insideRect && cornerDist > cornerRadiusPx) return false

                pressed = target; initial = value; draft = value
                downX = event.x; downY = event.y; pinchDistance = 0f; gestureMoved = false
                isDragging = false
                snappedX = null; snappedY = null

                if (cornerDist <= cornerRadiusPx) {
                    touchMode = TouchMode.CORNER_SCALE_ROTATE
                    initialDistanceToCenter = hypot(event.x - rect.centerX(), event.y - rect.centerY()).coerceAtLeast(1f)
                    initialAngleToCenter = atan2(event.y - rect.centerY(), event.x - rect.centerX())
                } else {
                    touchMode = TouchMode.MOVE
                }

                if (target.mainIndex >= 0) {
                    onMainSelected(target.mainIndex)
                } else if (target.track != null) {
                    onTrackSelected(target.track, target.clip.id)
                }
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (pressed != null && event.pointerCount == 2) {
                touchMode = TouchMode.PINCH
                initial = draft
                pinchDistance = hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0)).coerceAtLeast(1f)
                pinchAngle = atan2(event.getY(1) - event.getY(0), event.getX(1) - event.getX(0))
            }
            MotionEvent.ACTION_MOVE -> {
                val base = initial ?: return false
                val moveDist = hypot(event.x - downX, event.y - downY)
                if (!isDragging && moveDist > 6f * density) {
                    isDragging = true
                }

                val next = if (event.pointerCount >= 2 && touchMode == TouchMode.PINCH && pinchDistance > 0f) {
                    val distance = hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
                    val angle = atan2(event.getY(1) - event.getY(0), event.getX(1) - event.getX(0))
                    base.copy(
                        zoom = (base.zoom * distance / pinchDistance).coerceIn(.25f, 4f),
                        rotation = (base.rotation + (angle - pinchAngle) * 180f / PI.toFloat()).let { ((it + 180f) % 360f + 360f) % 360f - 180f },
                    )
                } else if (touchMode == TouchMode.CORNER_SCALE_ROTATE && isDragging) {
                    val rect = bounds(base)
                    val distNow = hypot(event.x - rect.centerX(), event.y - rect.centerY())
                    val angleNow = atan2(event.y - rect.centerY(), event.x - rect.centerX())
                    base.copy(
                        zoom = (base.zoom * distNow / initialDistanceToCenter).coerceIn(.25f, 4f),
                        rotation = (base.rotation + (angleNow - initialAngleToCenter) * 180f / PI.toFloat()).let { ((it + 180f) % 360f + 360f) % 360f - 180f },
                    )
                } else if (touchMode == TouchMode.MOVE && isDragging) {
                    base.copy(
                        x = snap((base.x + (event.x - downX) / width.coerceAtLeast(1)).coerceIn(-.5f, .5f), 0),
                        y = snap((base.y + (event.y - downY) / height.coerceAtLeast(1)).coerceIn(-.5f, .5f), 1),
                    )
                } else draft ?: base

                draft = next; gestureMoved = gestureMoved || (isDragging && next != base)
                if (isDragging) {
                    pressed?.let {
                        if (debugLogs) Log.d(TAG, "ACTION_MOVE clip=${it.clip.id} x=${next.x} y=${next.y} zoom=${next.zoom} rotation=${next.rotation}")
                        onPreviewChange(it.track, it.mainIndex, changedClip(it, next))
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val target = pressed; val value = draft
                val didDrag = isDragging && gestureMoved
                pressed = null; initial = null; draft = null; snappedX = null; snappedY = null
                touchMode = TouchMode.NONE; isDragging = false
                parent.requestDisallowInterceptTouchEvent(false)
                if (target != null && value != null && didDrag) {
                    val clip = changedClip(target, value)
                    if (clip.keyframes.size > 200) {
                        android.widget.Toast.makeText(context, "Limite de 200 keyframes.", android.widget.Toast.LENGTH_LONG).show()
                        onCancelPreview()
                    } else if (target.track != null) onChange(target.track, clip) else onMainChange(target.mainIndex, clip)
                } else onCancelPreview()
                invalidate(); performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = null; initial = null; draft = null; snappedX = null; snappedY = null
                touchMode = TouchMode.NONE; isDragging = false
                invalidate(); parent.requestDisallowInterceptTouchEvent(false); onCancelPreview()
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
