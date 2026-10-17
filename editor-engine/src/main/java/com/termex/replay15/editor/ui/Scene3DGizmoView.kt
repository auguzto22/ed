package com.termex.replay15.editor.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.scene3d.GizmoMath
import com.termex.replay15.editor.scene3d.SceneProjection
import com.termex.replay15.editor.scene3d.Vec
import kotlin.math.hypot
import kotlin.math.tan

/**
 * On-screen handles for a 3D layer, drawn over the preview frame (this view must cover exactly
 * the output frame, like the other handle views). Arrows move along X (red), Y (green) and Z
 * (blue), the centre square moves in the view plane; rings rotate; scale handles resize. Chips
 * at the bottom switch mode (Mover/Girar/Escala), axes (World XYZ/Local), Auto-Key and the
 * object being edited (the model or a null).
 */
class Scene3DGizmoView(context: Context) : View(context) {
    enum class Mode(val label: String) { MOVE("Mover"), ROTATE("Girar"), SCALE("Escala") }

    var project: Project = Project(); set(value) { field = value; target = target; invalidate() }
    var timeUs: Long = 0L; set(value) { if (field != value) { field = value; invalidate() } }
    /** Scene being edited; null hides the handles. Reopens on the object last edited in that scene. */
    var sceneId: String? = null
        set(value) {
            if (field == value) return
            field = value
            target = value?.let(Scene3DTools::targetOf) ?: MODEL_TARGET
            invalidate()
        }
    /** The model or a null of the scene; a null that no longer exists falls back to the model. */
    var target: String = MODEL_TARGET
        set(value) {
            val scene = sceneId?.let { id -> project.scenes3D.firstOrNull { it.id == id } }
            field = if (value == MODEL_TARGET || scene?.nullById(value) != null) value else MODEL_TARGET
            sceneId?.let { Scene3DTools.selectTarget(it, field) }
            invalidate()
        }
    var mode = Mode.MOVE; set(value) { field = value; invalidate() }
    var worldAxes = true; set(value) { field = value; invalidate() }

    var onPreview: (Project) -> Unit = {}
    var onCommit: (Project) -> Unit = {}
    var onCancel: () -> Unit = {}
    var onEdit: (sceneId: String) -> Unit = {}
    var onClose: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(12f); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) }

    private enum class Handle { X, Y, Z, CENTER }
    private class Chip(val rect: RectF, val action: () -> Unit)
    private val chips = ArrayList<Chip>()

    private class Drag(
        val handle: Handle, val startX: Float, val startY: Float, val clip: Scene3DClip, val local: Long,
        val projection: SceneProjection, val origin: FloatArray, val axis: FloatArray, val pose: ModelPose, val parent: FloatArray,
    )
    private var drag: Drag? = null

    private val axisColors = intArrayOf(0xFFFF4D4D.toInt(), 0xFF4CD964.toInt(), 0xFF4D8DFF.toInt())

    private fun clip(): Scene3DClip? = sceneId?.let { id -> project.scenes3D.firstOrNull { it.id == id } }?.takeIf { it.isActiveAt(timeUs) }

    private fun projection(clip: Scene3DClip, local: Long): SceneProjection? = runCatching {
        SceneProjection(clip, local, width.toFloat() / height.coerceAtLeast(1), project.camera.cameraAt(timeUs))
    }.getOrNull()

    /** Origin and the three axes of the edited object, scene space. */
    private fun frame(clip: Scene3DClip, local: Long): Pair<FloatArray, Array<FloatArray>> {
        val m = clip.objectMatrix(target, local)
        val origin = floatArrayOf(m[12], m[13], m[14])
        val axes = if (worldAxes) arrayOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f))
            else Array(3) { c -> Vec.normalize(floatArrayOf(m[c * 4], m[c * 4 + 1], m[c * 4 + 2])) }
        return origin to axes
    }

    /** World length of the handles so they look about 70 dp long. */
    private fun handleLength(clip: Scene3DClip, local: Long, projection: SceneProjection, origin: FloatArray): Float {
        val distance = Vec.length(Vec.sub(origin, projection.eye)).coerceAtLeast(.05f)
        val fov = Math.toRadians(SceneProjection.verticalFov(clip.focalLengthAt(local)).toDouble())
        val visibleHeight = 2f * distance * tan(fov / 2).toFloat()
        return visibleHeight * (dp(70f) / height.coerceAtLeast(1))
    }

    private fun toScreen(ndc: FloatArray) = floatArrayOf((ndc[0] + 1f) / 2f * width, (1f - ndc[1]) / 2f * height)
    private fun toNdc(x: Float, y: Float) = floatArrayOf(x / width * 2f - 1f, 1f - y / height * 2f)

    override fun onDraw(canvas: Canvas) {
        chips.clear()
        val clip = clip() ?: return
        val local = clip.localTime(timeUs)
        val projection = projection(clip, local) ?: return
        val (origin, axes) = frame(clip, local)
        val centre = projection.project(origin)?.let(::toScreen)
        if (centre != null) {
            val length = handleLength(clip, local, projection, origin)
            paint.strokeCap = Paint.Cap.ROUND
            for (a in 0 until 3) {
                val active = drag?.handle?.ordinal == a
                paint.color = axisColors[a]
                paint.strokeWidth = dp(if (active) 3.5f else 2.5f)
                when (mode) {
                    Mode.MOVE, Mode.SCALE -> {
                        val tip = projection.project(Vec.add(origin, Vec.scale(axes[a], length)))?.let(::toScreen) ?: continue
                        paint.style = Paint.Style.STROKE
                        canvas.drawLine(centre[0], centre[1], tip[0], tip[1], paint)
                        paint.style = Paint.Style.FILL
                        if (mode == Mode.MOVE) drawArrowHead(canvas, centre, tip) else canvas.drawRect(tip[0] - dp(5f), tip[1] - dp(5f), tip[0] + dp(5f), tip[1] + dp(5f), paint)
                    }
                    Mode.ROTATE -> {
                        val ring = ringPoints(projection, origin, axes[a], length) ?: continue
                        paint.style = Paint.Style.STROKE
                        val path = Path()
                        ring.forEachIndexed { i, p -> if (i == 0) path.moveTo(p[0], p[1]) else path.lineTo(p[0], p[1]) }
                        path.close()
                        canvas.drawPath(path, paint)
                    }
                }
            }
            paint.style = Paint.Style.FILL
            paint.color = if (drag?.handle == Handle.CENTER) Color.WHITE else 0xE6FFFFFF.toInt()
            canvas.drawCircle(centre[0], centre[1], dp(6f), paint)
            paint.color = 0xFF121213.toInt(); canvas.drawCircle(centre[0], centre[1], dp(2.5f), paint)
        }
        drawChips(canvas, clip)
    }

    private fun drawArrowHead(canvas: Canvas, from: FloatArray, to: FloatArray) {
        val dx = to[0] - from[0]; val dy = to[1] - from[1]
        val l = hypot(dx, dy).coerceAtLeast(1f)
        val ux = dx / l; val uy = dy / l
        val size = dp(9f)
        val path = Path().apply {
            moveTo(to[0] + ux * size * .6f, to[1] + uy * size * .6f)
            lineTo(to[0] - uy * size * .5f, to[1] + ux * size * .5f)
            lineTo(to[0] + uy * size * .5f, to[1] - ux * size * .5f)
            close()
        }
        canvas.drawPath(path, paint)
    }

    private fun ringPoints(projection: SceneProjection, origin: FloatArray, axis: FloatArray, radius: Float): List<FloatArray>? {
        val helper = if (kotlin.math.abs(axis[1]) < .9f) floatArrayOf(0f, 1f, 0f) else floatArrayOf(1f, 0f, 0f)
        val u = Vec.normalize(Vec.cross(axis, helper)); val v = Vec.cross(axis, u)
        val points = ArrayList<FloatArray>(64)
        for (i in 0 until 64) {
            val t = i / 64.0 * 2 * Math.PI
            val p = Vec.add(origin, Vec.add(Vec.scale(u, (kotlin.math.cos(t) * radius).toFloat()), Vec.scale(v, (kotlin.math.sin(t) * radius).toFloat())))
            points += projection.project(p)?.let(::toScreen) ?: return null
        }
        return points
    }

    private fun drawChips(canvas: Canvas, clip: Scene3DClip) {
        val targetName = if (target == MODEL_TARGET) "Modelo" else clip.nullById(target)?.name ?: "Modelo"
        val entries = listOf<Pair<String, () -> Unit>>(
            mode.label to { mode = Mode.entries[(mode.ordinal + 1) % Mode.entries.size] },
            (if (worldAxes) "World XYZ" else "Local") to { worldAxes = !worldAxes },
            (if (Scene3DTools.autoKey) "Auto-Key: On" else "Auto-Key: Off") to { Scene3DTools.autoKey = !Scene3DTools.autoKey; invalidate() },
            targetName to {
                val ids = listOf(MODEL_TARGET) + clip.nulls.map { it.id }
                target = ids[(ids.indexOf(target).coerceAtLeast(0) + 1) % ids.size]
            },
            "Editar" to { onEdit(clip.id) },
            "✕" to { onClose() },
        )
        var x = dp(8f)
        var y = height - dp(40f)
        entries.forEach { (label, action) ->
            val w = text.measureText(label) + dp(20f)
            if (x + w > width - dp(8f)) { x = dp(8f); y -= dp(38f) }
            val rect = RectF(x, y, x + w, y + dp(32f))
            paint.style = Paint.Style.FILL; paint.color = 0xD91C1C1E.toInt()
            canvas.drawRoundRect(rect, dp(10f), dp(10f), paint)
            text.color = Color.WHITE
            canvas.drawText(label, x + dp(10f), y + dp(21f), text)
            chips += Chip(rect, action)
            x += w + dp(6f)
        }
    }

    private fun hit(x: Float, y: Float, clip: Scene3DClip, local: Long, projection: SceneProjection): Pair<Handle, FloatArray>? {
        val (origin, axes) = frame(clip, local)
        val centre = projection.project(origin)?.let(::toScreen) ?: return null
        if (hypot(x - centre[0], y - centre[1]) <= dp(18f)) return Handle.CENTER to floatArrayOf(0f, 0f, 0f)
        val length = handleLength(clip, local, projection, origin)
        var best: Pair<Handle, FloatArray>? = null
        var bestDistance = dp(22f)
        for (a in 0 until 3) {
            val distance = when (mode) {
                Mode.MOVE, Mode.SCALE -> {
                    val tip = projection.project(Vec.add(origin, Vec.scale(axes[a], length)))?.let(::toScreen) ?: continue
                    segmentDistance(x, y, centre, tip)
                }
                Mode.ROTATE -> {
                    val ring = ringPoints(projection, origin, axes[a], length) ?: continue
                    ring.indices.minOf { i -> segmentDistance(x, y, ring[i], ring[(i + 1) % ring.size]) }
                }
            }
            if (distance < bestDistance) { bestDistance = distance; best = Handle.entries[a] to axes[a] }
        }
        return best
    }

    private fun segmentDistance(x: Float, y: Float, a: FloatArray, b: FloatArray): Float {
        val dx = b[0] - a[0]; val dy = b[1] - a[1]
        val l2 = dx * dx + dy * dy
        val t = if (l2 < 1e-6f) 0f else (((x - a[0]) * dx + (y - a[1]) * dy) / l2).coerceIn(0f, 1f)
        return hypot(x - (a[0] + t * dx), y - (a[1] + t * dy))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val clip = clip() ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                chips.firstOrNull { it.rect.contains(event.x, event.y) }?.let { chip ->
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    chip.action(); invalidate(); return true
                }
                val local = clip.localTime(timeUs)
                val projection = projection(clip, local) ?: return false
                val (handle, axis) = hit(event.x, event.y, clip, local, projection) ?: return false
                val (origin, _) = frame(clip, local)
                drag = Drag(handle, event.x, event.y, clip, local, projection, origin, axis, clip.poseAt(target, local),
                    clip.parentMatrix(clip.parentOf(target), local))
                parent?.requestDisallowInterceptTouchEvent(true)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val d = drag ?: return false
                applyDrag(d, event.x, event.y)?.let { onPreview(it) }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val d = drag ?: return false
                drag = null
                val result = applyDrag(d, event.x, event.y)
                if (result != null && hypot(event.x - d.startX, event.y - d.startY) > dp(2f)) onCommit(result) else onCancel()
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (drag != null) { drag = null; onCancel(); invalidate() }
                return true
            }
        }
        return false
    }

    /** The project with the drag applied, or null when the gesture cannot be mapped (e.g. behind the camera). */
    private fun applyDrag(d: Drag, x: Float, y: Float): Project? {
        val from = toNdc(d.startX, d.startY); val to = toNdc(x, y)
        val pose = when (d.handle) {
            Handle.CENTER -> when (mode) {
                Mode.SCALE -> GizmoMath.scaleDrag(d.projection, d.origin, from, to, width.toFloat() / height)?.let {
                    d.pose.copy(scale = (d.pose.scale * it).coerceIn(.01f, 100f))
                }
                else -> GizmoMath.planeDrag(d.projection, d.origin, from, to)?.let { GizmoMath.translate(d.pose, d.parent, it) }
            }
            else -> when (mode) {
                Mode.MOVE -> GizmoMath.axisDrag(d.projection, d.origin, d.axis, from, to)?.let {
                    GizmoMath.translate(d.pose, d.parent, Vec.scale(Vec.normalize(d.axis), it))
                }
                Mode.ROTATE -> GizmoMath.rotationDrag(d.projection, d.origin, d.axis, from, to)?.let { GizmoMath.rotate(d.pose, d.parent, d.axis, it) }
                Mode.SCALE -> GizmoMath.scaleDrag(d.projection, d.origin, from, to, width.toFloat() / height)?.let {
                    d.pose.copy(scale = (d.pose.scale * it).coerceIn(.01f, 100f))
                }
            }
        } ?: return null
        val changed = runCatching { Scene3DTools.applyPose(d.clip, d.local, d.pose, pose, target) }.getOrNull() ?: return null
        return project.copy(scenes3D = project.scenes3D.map { if (it.id == changed.id) changed else it })
    }
}
