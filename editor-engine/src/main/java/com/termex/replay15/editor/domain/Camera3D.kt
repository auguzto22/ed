package com.termex.replay15.editor.domain

import kotlin.math.tan

/**
 * 3D Camera for perspective, view matrix generation, parallax, and depth in Recly.
 *
 * The default framing distance is fixed at FOV 60 degrees:
 * d = 1 / tan(60 / 2)
 * For FOV = 60°, d ≈ 1.73205.
 *
 * At Z = 0 with default camera, the perspective projection aligns exactly with
 * the 2D canvas coordinates, ensuring 100% visual backward compatibility.
 */
data class Camera3D(
    val positionX: Float = 0f,
    val positionY: Float = 0f,
    val positionZ: Float = 0f,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,
    val fieldOfView: Float = 60f,
    val focusDistance: Float = 0f,
    val depthOfFieldAmount: Float = 0f,
    val nearPlane: Float = 0.05f,
    val farPlane: Float = 1000f,
    val keyframes: List<CameraKeyframe> = emptyList(),
) {
    init {
        require(positionX.isFinite() && positionY.isFinite() && positionZ.isFinite())
        require(rotationX.isFinite() && rotationY.isFinite() && rotationZ.isFinite())
        require(fieldOfView in 10f..160f)
        require(nearPlane.isFinite() && farPlane.isFinite() && nearPlane > 0f && farPlane > nearPlane)
        require(depthOfFieldAmount in 0f..1f)
        require(keyframes.size <= 200)
        require(keyframes.zipWithNext().all { (a, b) -> a.timeUs < b.timeUs })
    }

    /**
     * Calculates the canonical distance from camera eye to the Z = 0 focus plane.
     */
    fun canonicalDistance(fovDeg: Float = fieldOfView): Float {
        val halfFovRad = Math.toRadians(fovDeg.toDouble() / 2.0)
        return (1.0 / tan(halfFovRad)).toFloat()
    }

    /**
     * Builds the 4x4 View Matrix (world space to camera view space).
     */
    fun toViewMatrix(out: FloatArray = FloatArray(16)): FloatArray {
        Matrix4.setIdentity(out)
        // Camera rotation in reverse order for view space:
        Matrix4.rotateX(out, -rotationX)
        Matrix4.rotateY(out, -rotationY)
        Matrix4.rotateZ(out, -rotationZ)
        val eyeX = positionX * 2f
        val eyeY = -positionY * 2f
        val eyeZ = (canonicalDistance(60f) + positionZ).coerceIn(0.25f, 50f)
        Matrix4.translate(out, -eyeX, -eyeY, -eyeZ)
        return out
    }

    /**
     * Builds the 4x4 Perspective Projection Matrix.
     */
    fun toProjectionMatrix(aspect: Float, out: FloatArray = FloatArray(16)): FloatArray {
        Matrix4.perspective(out, fieldOfView.coerceIn(15f, 120f), aspect, nearPlane, farPlane)
        return out
    }

    /**
     * Evaluates camera properties at the given project timestamp [timeUs].
     */
    fun cameraAt(timeUs: Long): Camera3D {
        if (keyframes.isEmpty()) return this
        val upper = keyframes.upperBoundTime(timeUs, CameraKeyframe::timeUs)
        if (upper == 0) return fromKeyframe(keyframes.first())
        if (upper == keyframes.size) return fromKeyframe(keyframes.last())
        val a = keyframes[upper - 1]
        val b = keyframes[upper]
        val t = a.easing.apply((timeUs - a.timeUs).toFloat() / (b.timeUs - a.timeUs).coerceAtLeast(1L), a.bezier)
        fun mix(x: Float, y: Float) = x + (y - x) * t
        fun angle(x: Float, y: Float): Float {
            var delta = (y % 360f - x % 360f) % 360f
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            return x + delta * t
        }
        return copy(
            positionX = mix(a.positionX, b.positionX),
            positionY = mix(a.positionY, b.positionY),
            positionZ = mix(a.positionZ, b.positionZ),
            rotationX = angle(a.rotationX, b.rotationX),
            rotationY = angle(a.rotationY, b.rotationY),
            rotationZ = angle(a.rotationZ, b.rotationZ),
            fieldOfView = mix(a.fieldOfView, b.fieldOfView).coerceIn(10f, 160f),
            focusDistance = mix(a.focusDistance, b.focusDistance),
            depthOfFieldAmount = mix(a.depthOfFieldAmount, b.depthOfFieldAmount).coerceIn(0f, 1f),
        )
    }

    /** Project-global upsert; slider drafts never compete with the stored track. */
    fun upsert(timeUs: Long, state: Camera3D): Camera3D {
        val nearby = keyframes.minByOrNull { kotlin.math.abs(it.timeUs - timeUs) }
            ?.takeIf { kotlin.math.abs(it.timeUs - timeUs) <= 20_000L }
        val key = CameraKeyframe(timeUs, state.positionX, state.positionY, state.positionZ,
            state.rotationX, state.rotationY, state.rotationZ, state.fieldOfView,
            state.focusDistance, state.depthOfFieldAmount,
            nearby?.easing ?: Easing.SMOOTH, nearby?.bezier ?: CubicBezier())
        return copy(keyframes = (keyframes.filterNot { kotlin.math.abs(it.timeUs - timeUs) <= 20_000L } + key).sortedBy { it.timeUs })
    }

    /** Shared final-scene projection for preview and export. Buffers belong to the GL caller. */
    class SceneMatrix {
        private val view = FloatArray(16)
        private val projection = FloatArray(16)
        private val model = FloatArray(16)
        private val vp = FloatArray(16)
        private val out = FloatArray(16)

        fun evaluate(camera: Camera3D, aspect: Float): FloatArray {
            if (camera.isDefault || !aspect.isFinite() || aspect <= 0f) {
                Matrix4.setIdentity(out)
                return out
            }
            camera.toViewMatrix(view)
            camera.toProjectionMatrix(aspect, projection)
            Matrix4.setIdentity(model)
            Matrix4.scale(model, aspect, 1f, 1f)
            Matrix4.multiply(vp, projection, view)
            Matrix4.multiply(out, vp, model)
            if (out.any { !it.isFinite() }) Matrix4.setIdentity(out)
            return out
        }
    }

    private fun fromKeyframe(k: CameraKeyframe): Camera3D = copy(
        positionX = k.positionX,
        positionY = k.positionY,
        positionZ = k.positionZ,
        rotationX = k.rotationX,
        rotationY = k.rotationY,
        rotationZ = k.rotationZ,
        fieldOfView = k.fieldOfView,
        focusDistance = k.focusDistance,
        depthOfFieldAmount = k.depthOfFieldAmount,
    )

    val isDefault: Boolean
        get() = positionX == 0f && positionY == 0f && positionZ == 0f &&
            rotationX == 0f && rotationY == 0f && rotationZ == 0f &&
            fieldOfView == 60f && keyframes.isEmpty()
}

/**
 * Keyframe for animating Camera3D properties over the project timeline.
 */
data class CameraKeyframe(
    val timeUs: Long,
    val positionX: Float = 0f,
    val positionY: Float = 0f,
    val positionZ: Float = 0f,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,
    val fieldOfView: Float = 60f,
    val focusDistance: Float = 0f,
    val depthOfFieldAmount: Float = 0f,
    val easing: Easing = Easing.SMOOTH,
    val bezier: CubicBezier = CubicBezier(),
) {
    init {
        require(timeUs >= 0L)
        require(positionX.isFinite() && positionY.isFinite() && positionZ.isFinite())
        require(rotationX.isFinite() && rotationY.isFinite() && rotationZ.isFinite())
        require(fieldOfView in 10f..160f)
        require(depthOfFieldAmount in 0f..1f)
    }
}
