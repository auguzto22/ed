package com.termex.replay15.editor.scene3d

import com.termex.replay15.editor.domain.Camera3D
import com.termex.replay15.editor.domain.Matrix4
import com.termex.replay15.editor.domain.ModelPose
import com.termex.replay15.editor.domain.Scene3DClip
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The same projection the renderer uses (Filament lens with a 24 mm sensor, look-at camera),
 * followed by the project's own 3D camera on the composed frame, so on-screen handles sit
 * exactly on the rendered model. Works in scene space (the "girar a luz" stage turn moves
 * camera and objects together, so it cancels out and is left out here).
 *
 * Screen coordinates are normalized device coordinates of the output frame: x and y in -1..1,
 * y up.
 */
class SceneProjection(clip: Scene3DClip, localUs: Long, private val aspect: Float, projectCamera: Camera3D = Camera3D()) {
    private val viewProjection = FloatArray(16)
    private val inverseViewProjection: FloatArray
    /** 3x3 homography of the project camera on the frame plane (null = identity). */
    private val frame: DoubleArray?
    private val frameInverse: DoubleArray?
    val eye: FloatArray

    init {
        require(aspect.isFinite() && aspect > 0f)
        val basis = clip.worldCamera(localUs)
        eye = basis.eye
        val view = FloatArray(16)
        Matrix4.lookAt(view, basis.eye[0], basis.eye[1], basis.eye[2], basis.target[0], basis.target[1], basis.target[2],
            basis.up[0], basis.up[1], basis.up[2])
        val projection = FloatArray(16)
        Matrix4.perspective(projection, verticalFov(clip.focalLengthAt(localUs)), aspect, NEAR, FAR)
        Matrix4.multiply(viewProjection, projection, view)
        inverseViewProjection = invert(viewProjection) ?: error("Câmera inválida")
        if (projectCamera.copy(keyframes = emptyList()).isDefault) {
            frame = null; frameInverse = null
        } else {
            val m = Camera3D.SceneMatrix().evaluate(projectCamera.copy(keyframes = emptyList()), aspect)
            val h = doubleArrayOf(
                m[0].toDouble(), m[4].toDouble(), m[12].toDouble(),
                m[1].toDouble(), m[5].toDouble(), m[13].toDouble(),
                m[3].toDouble(), m[7].toDouble(), m[15].toDouble(),
            )
            frame = h; frameInverse = invert3(h)
        }
    }

    /** Output-frame NDC of a scene-space point, or null when it is behind the camera. */
    fun project(x: Float, y: Float, z: Float): FloatArray? {
        val p = Matrix4.transformPoint(viewProjection, x, y, z, 1f)
        if (p[3] <= 1e-5f) return null
        val nx = p[0] / p[3]; val ny = p[1] / p[3]
        val h = frame ?: return floatArrayOf(nx, ny)
        val w = h[6] * nx + h[7] * ny + h[8]
        if (w <= 1e-9) return null
        return floatArrayOf(((h[0] * nx + h[1] * ny + h[2]) / w).toFloat(), ((h[3] * nx + h[4] * ny + h[5]) / w).toFloat())
    }

    fun project(point: FloatArray) = project(point[0], point[1], point[2])

    /** Scene-space ray (origin, unit direction) under an output-frame NDC point. */
    fun ray(ndcX: Float, ndcY: Float): Pair<FloatArray, FloatArray>? {
        var x = ndcX; var y = ndcY
        frameInverse?.let { h ->
            val w = h[6] * x + h[7] * y + h[8]
            if (abs(w) < 1e-12) return null
            val px = ((h[0] * x + h[1] * y + h[2]) / w).toFloat(); val py = ((h[3] * x + h[4] * y + h[5]) / w).toFloat()
            x = px; y = py
        }
        val near = Matrix4.transformPoint(inverseViewProjection, x, y, -1f, 1f)
        val far = Matrix4.transformPoint(inverseViewProjection, x, y, 1f, 1f)
        if (abs(near[3]) < 1e-12f || abs(far[3]) < 1e-12f) return null
        val a = floatArrayOf(near[0] / near[3], near[1] / near[3], near[2] / near[3])
        val b = floatArrayOf(far[0] / far[3], far[1] / far[3], far[2] / far[3])
        return a to Vec.normalize(Vec.sub(b, a))
    }

    companion object {
        const val NEAR = .05f
        const val FAR = 400f

        /** Vertical field of view of Filament's setLensProjection (24 mm sensor height). */
        fun verticalFov(focalLengthMm: Float): Float = Math.toDegrees(2.0 * atan(12.0 / focalLengthMm)).toFloat()

        fun invert(m: FloatArray): FloatArray? {
            val a = Array(4) { row -> DoubleArray(8) { column -> if (column < 4) m[column * 4 + row].toDouble() else if (column - 4 == row) 1.0 else 0.0 } }
            for (column in 0 until 4) {
                var pivot = column
                for (row in column + 1 until 4) if (abs(a[row][column]) > abs(a[pivot][column])) pivot = row
                if (abs(a[pivot][column]) < 1e-12) return null
                val swap = a[pivot]; a[pivot] = a[column]; a[column] = swap
                val divisor = a[column][column]
                for (k in 0 until 8) a[column][k] /= divisor
                for (row in 0 until 4) if (row != column) {
                    val factor = a[row][column]
                    if (factor != 0.0) for (k in 0 until 8) a[row][k] -= factor * a[column][k]
                }
            }
            return FloatArray(16) { index -> a[index % 4][4 + index / 4].toFloat() }
        }

        private fun invert3(h: DoubleArray): DoubleArray? {
            val det = h[0] * (h[4] * h[8] - h[5] * h[7]) - h[1] * (h[3] * h[8] - h[5] * h[6]) + h[2] * (h[3] * h[7] - h[4] * h[6])
            if (abs(det) < 1e-12) return null
            return doubleArrayOf(
                (h[4] * h[8] - h[5] * h[7]) / det, (h[2] * h[7] - h[1] * h[8]) / det, (h[1] * h[5] - h[2] * h[4]) / det,
                (h[5] * h[6] - h[3] * h[8]) / det, (h[0] * h[8] - h[2] * h[6]) / det, (h[2] * h[3] - h[0] * h[5]) / det,
                (h[3] * h[7] - h[4] * h[6]) / det, (h[1] * h[6] - h[0] * h[7]) / det, (h[0] * h[4] - h[1] * h[3]) / det,
            )
        }
    }
}

/** Small vector helpers shared by the gizmo math. */
object Vec {
    fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    fun add(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])
    fun scale(a: FloatArray, s: Float) = floatArrayOf(a[0] * s, a[1] * s, a[2] * s)
    fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    fun length(a: FloatArray) = sqrt(dot(a, a))
    fun normalize(a: FloatArray): FloatArray { val l = length(a).coerceAtLeast(1e-12f); return floatArrayOf(a[0] / l, a[1] / l, a[2] / l) }
}

/**
 * Pure math behind the on-screen 3D handles: dragging along an axis, in the view plane, around
 * an axis, and uniform scale; plus Euler conversions that match [Matrix4.rotateEuler]
 * (R = Rz * Ry * Rx) and stay continuous with the previous angles.
 */
object GizmoMath {
    /** Parameter along the line (origin, unit axis) closest to the ray, or null when parallel. */
    fun closestOnAxis(origin: FloatArray, axis: FloatArray, rayOrigin: FloatArray, rayDir: FloatArray): Float? {
        val w = Vec.sub(origin, rayOrigin)
        val b = Vec.dot(axis, rayDir)
        val denominator = 1f - b * b
        if (denominator < 1e-6f) return null
        val d = Vec.dot(axis, w); val e = Vec.dot(rayDir, w)
        return (b * e - d) / denominator
    }

    /** Scene-space distance moved along [axis] between two screen points. */
    fun axisDrag(projection: SceneProjection, origin: FloatArray, axis: FloatArray, from: FloatArray, to: FloatArray): Float? {
        val a = projection.ray(from[0], from[1]) ?: return null
        val b = projection.ray(to[0], to[1]) ?: return null
        val unit = Vec.normalize(axis)
        val s0 = closestOnAxis(origin, unit, a.first, a.second) ?: return null
        val s1 = closestOnAxis(origin, unit, b.first, b.second) ?: return null
        return (s1 - s0).takeIf { it.isFinite() }
    }

    /** Intersection of a ray with the plane through [point] with [normal], or null. */
    fun rayPlane(rayOrigin: FloatArray, rayDir: FloatArray, point: FloatArray, normal: FloatArray): FloatArray? {
        val denominator = Vec.dot(normal, rayDir)
        if (abs(denominator) < 1e-6f) return null
        val t = Vec.dot(normal, Vec.sub(point, rayOrigin)) / denominator
        if (t <= 0f || !t.isFinite()) return null
        return Vec.add(rayOrigin, Vec.scale(rayDir, t))
    }

    /** Scene-space movement in the plane facing the camera through [origin]. */
    fun planeDrag(projection: SceneProjection, origin: FloatArray, from: FloatArray, to: FloatArray): FloatArray? {
        val normal = Vec.normalize(Vec.sub(projection.eye, origin))
        val a = projection.ray(from[0], from[1]) ?: return null
        val b = projection.ray(to[0], to[1]) ?: return null
        val p0 = rayPlane(a.first, a.second, origin, normal) ?: return null
        val p1 = rayPlane(b.first, b.second, origin, normal) ?: return null
        return Vec.sub(p1, p0)
    }

    /**
     * Degrees turned around [axis] through [origin] between two screen points (right-handed, so
     * positive is counter-clockwise looking down the axis). Falls back to the screen-space angle
     * around the projected origin when the ring is seen edge-on.
     */
    fun rotationDrag(projection: SceneProjection, origin: FloatArray, axis: FloatArray, from: FloatArray, to: FloatArray): Float? {
        val unit = Vec.normalize(axis)
        val viewDir = Vec.normalize(Vec.sub(origin, projection.eye))
        if (abs(Vec.dot(unit, viewDir)) > .15f) {
            val a = projection.ray(from[0], from[1]) ?: return null
            val b = projection.ray(to[0], to[1]) ?: return null
            val p0 = rayPlane(a.first, a.second, origin, unit)
            val p1 = rayPlane(b.first, b.second, origin, unit)
            if (p0 != null && p1 != null) {
                val v0 = Vec.sub(p0, origin); val v1 = Vec.sub(p1, origin)
                if (Vec.length(v0) > 1e-5f && Vec.length(v1) > 1e-5f) {
                    val angle = atan2(Vec.dot(unit, Vec.cross(v0, v1)).toDouble(), Vec.dot(v0, v1).toDouble())
                    return Math.toDegrees(angle).toFloat()
                }
            }
        }
        val centre = projection.project(origin) ?: return null
        val a0 = atan2((from[1] - centre[1]).toDouble(), (from[0] - centre[0]).toDouble())
        val a1 = atan2((to[1] - centre[1]).toDouble(), (to[0] - centre[0]).toDouble())
        var delta = Math.toDegrees(a1 - a0)
        while (delta > 180.0) delta -= 360.0
        while (delta < -180.0) delta += 360.0
        // Screen angles are counter-clockwise; looking along the view direction flips the sign.
        val sign = if (Vec.dot(unit, viewDir) > 0f) -1f else 1f
        return delta.toFloat() * sign
    }

    /** Uniform scale factor from the screen distances to the projected origin. */
    fun scaleDrag(projection: SceneProjection, origin: FloatArray, from: FloatArray, to: FloatArray, aspect: Float): Float? {
        val centre = projection.project(origin) ?: return null
        fun distance(p: FloatArray) = sqrt(((p[0] - centre[0]) * aspect).let { it * it } + (p[1] - centre[1]).let { it * it })
        val d0 = distance(from)
        if (d0 < 1e-3f) return null
        return (distance(to) / d0).takeIf { it.isFinite() && it > 0f }
    }

    /** 3x3 rotation (row-major) of a pose's Euler angles, R = Rz * Ry * Rx. */
    fun rotation(rx: Float, ry: Float, rz: Float): FloatArray {
        val m = FloatArray(16).also { Matrix4.setIdentity(it); Matrix4.rotateEuler(it, rx, ry, rz) }
        return floatArrayOf(m[0], m[4], m[8], m[1], m[5], m[9], m[2], m[6], m[10])
    }

    fun multiply3(a: FloatArray, b: FloatArray) = FloatArray(9) { index ->
        val row = index / 3; val column = index % 3
        a[row * 3] * b[column] + a[row * 3 + 1] * b[3 + column] + a[row * 3 + 2] * b[6 + column]
    }

    fun transpose3(a: FloatArray) = floatArrayOf(a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8])

    /** Rotation of [degrees] around a unit [axis] (Rodrigues), row-major. */
    fun axisAngle(axis: FloatArray, degrees: Float): FloatArray {
        val u = Vec.normalize(axis)
        val r = Math.toRadians(degrees.toDouble()); val c = cos(r).toFloat(); val s = sin(r).toFloat(); val t = 1f - c
        return floatArrayOf(
            t * u[0] * u[0] + c, t * u[0] * u[1] - s * u[2], t * u[0] * u[2] + s * u[1],
            t * u[0] * u[1] + s * u[2], t * u[1] * u[1] + c, t * u[1] * u[2] - s * u[0],
            t * u[0] * u[2] - s * u[1], t * u[1] * u[2] + s * u[0], t * u[2] * u[2] + c,
        )
    }

    /**
     * Euler angles (x, y, z degrees) of a row-major rotation for R = Rz * Ry * Rx, choosing of the
     * two equivalent solutions (and of every 360° multiple) the one nearest to [previous].
     */
    fun euler(r: FloatArray, previous: FloatArray = floatArrayOf(0f, 0f, 0f)): FloatArray {
        val sy = (-r[6]).coerceIn(-1f, 1f)
        val candidates = ArrayList<FloatArray>(2)
        if (abs(sy) < .99999f) {
            val y1 = asin(sy.toDouble())
            val y2 = Math.PI - y1
            for (y in listOf(y1, y2)) {
                val cy = cos(y)
                val x = atan2(r[7] / cy, r[8] / cy)
                val z = atan2(r[3] / cy, r[0] / cy)
                candidates += floatArrayOf(Math.toDegrees(x).toFloat(), Math.toDegrees(y).toFloat(), Math.toDegrees(z).toFloat())
            }
        } else {
            // Gimbal lock: only x - z (or x + z) is defined; keep z where it was.
            val z = Math.toRadians(previous[2].toDouble())
            val y = if (sy > 0f) Math.PI / 2 else -Math.PI / 2
            val x = if (sy > 0f) z + atan2(r[1].toDouble(), r[2].toDouble()) else -z + atan2(-r[1].toDouble(), -r[2].toDouble())
            candidates += floatArrayOf(Math.toDegrees(x).toFloat(), Math.toDegrees(y).toFloat(), Math.toDegrees(z).toFloat())
        }
        fun near(value: Float, reference: Float): Float {
            var v = value
            while (v - reference > 180f) v -= 360f
            while (v - reference < -180f) v += 360f
            return v
        }
        return candidates.map { c -> floatArrayOf(near(c[0], previous[0]), near(c[1], previous[1]), near(c[2], previous[2])) }
            .minBy { c -> abs(c[0] - previous[0]) + abs(c[1] - previous[1]) + abs(c[2] - previous[2]) }
    }

    /** Linear 3x3 (row-major) part of a column-major 4x4 matrix. */
    fun linear(m: FloatArray) = floatArrayOf(m[0], m[4], m[8], m[1], m[5], m[9], m[2], m[6], m[10])

    fun apply3(m: FloatArray, v: FloatArray) = floatArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[3] * v[0] + m[4] * v[1] + m[5] * v[2], m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
    )

    fun invert3(m: FloatArray): FloatArray? {
        val det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
        if (abs(det) < 1e-12f) return null
        return floatArrayOf(
            (m[4] * m[8] - m[5] * m[7]) / det, (m[2] * m[7] - m[1] * m[8]) / det, (m[1] * m[5] - m[2] * m[4]) / det,
            (m[5] * m[6] - m[3] * m[8]) / det, (m[0] * m[8] - m[2] * m[6]) / det, (m[2] * m[3] - m[0] * m[5]) / det,
            (m[3] * m[7] - m[4] * m[6]) / det, (m[1] * m[6] - m[0] * m[7]) / det, (m[0] * m[4] - m[1] * m[3]) / det,
        )
    }

    /** Moves [pose] by a scene-space [delta], expressed in the space of its parent [parentMatrix]. */
    fun translate(pose: ModelPose, parentMatrix: FloatArray, delta: FloatArray): ModelPose {
        val inverse = invert3(linear(parentMatrix)) ?: return pose
        val local = apply3(inverse, delta)
        return pose.copy(
            positionX = (pose.positionX + local[0]).coerceIn(-50f, 50f),
            positionY = (pose.positionY + local[1]).coerceIn(-50f, 50f),
            positionZ = (pose.positionZ + local[2]).coerceIn(-50f, 50f),
        )
    }

    /**
     * Turns [pose] by [degrees] around a scene-space [axis]. The parent's rotation is taken out
     * (scale is assumed uniform, as every pose here is), so the turn happens around the axis
     * the user sees.
     */
    fun rotate(pose: ModelPose, parentMatrix: FloatArray, axis: FloatArray, degrees: Float): ModelPose {
        val parent = orthonormal(linear(parentMatrix))
        val localAxis = apply3(transpose3(parent), axis)
        val turned = multiply3(axisAngle(localAxis, degrees), rotation(pose.rotationX, pose.rotationY, pose.rotationZ))
        val angles = euler(turned, floatArrayOf(pose.rotationX, pose.rotationY, pose.rotationZ))
        return pose.copy(rotationX = angles[0], rotationY = angles[1], rotationZ = angles[2])
    }

    /** Rotation part of a matrix with uniform scale (columns normalized). */
    fun orthonormal(m: FloatArray): FloatArray {
        val columns = (0 until 3).map { c -> Vec.normalize(floatArrayOf(m[c], m[3 + c], m[6 + c])) }
        return floatArrayOf(columns[0][0], columns[1][0], columns[2][0], columns[0][1], columns[1][1], columns[2][1],
            columns[0][2], columns[1][2], columns[2][2])
    }
}
