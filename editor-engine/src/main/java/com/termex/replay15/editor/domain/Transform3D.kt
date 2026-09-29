package com.termex.replay15.editor.domain

/**
 * 2.5D / 3D transformation state for layers in Recly.
 *
 * Coordinates:
 * - Origin (0, 0, 0) is at the center of the canvas.
 * - positionX: [-0.5, 0.5] (maps to NDC [-1, 1]).
 * - positionY: [-0.5, 0.5] (UI positive down; maps to NDC +Y up).
 * - positionZ: Z-depth. Z = 0 is the default composition plane.
 *              Positive Z is closer to the camera; negative Z is deeper.
 * - rotationX: Pitch (tilt forward/backward in degrees).
 * - rotationY: Yaw (card flip around vertical axis in degrees).
 * - rotationZ: Roll (in-plane 2D rotation in degrees, matching legacy rotation).
 * - scaleX, scaleY, scaleZ: Scaling along respective axes. Default (1, 1, 1).
 * - anchorX, anchorY, anchorZ: Pivot/Anchor point relative to layer center.
 *                              Default (0, 0, 0). (-0.5, 0) is left edge; (0.5, 0) is right edge.
 * - opacity: [0, 1].
 */
data class Transform3D(
    val positionX: Float = 0f,
    val positionY: Float = 0f,
    val positionZ: Float = 0f,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val scaleZ: Float = 1f,
    val anchorX: Float = 0f,
    val anchorY: Float = 0f,
    val anchorZ: Float = 0f,
    val opacity: Float = 1f,
) {
    init {
        require(scaleX.isFinite() && scaleY.isFinite() && scaleZ.isFinite())
        require(positionX.isFinite() && positionY.isFinite() && positionZ.isFinite())
        require(rotationX.isFinite() && rotationY.isFinite() && rotationZ.isFinite())
        require(anchorX.isFinite() && anchorY.isFinite() && anchorZ.isFinite())
        require(opacity in 0f..1f)
    }

    /**
     * Builds the 4x4 local Model Matrix in OpenGL column-major order:
     * M = T(pos) * Rz(rotZ) * Ry(rotY) * Rx(rotX) * S(scale) * T(-anchor)
     */
    fun toModelMatrix(out: FloatArray = FloatArray(16)): FloatArray {
        Matrix4.setIdentity(out)
        // 1. Translation to scene position (places anchor at position)
        Matrix4.translate(out, positionX * 2f, -positionY * 2f, positionZ)
        // 2. Rotation around pivot
        Matrix4.rotateEuler(out, rotationX, rotationY, rotationZ)
        // 3. Scaling
        Matrix4.scale(out, scaleX, scaleY, scaleZ)
        // 4. Translate by -anchor so that anchor point coincides with local origin
        if (anchorX != 0f || anchorY != 0f || anchorZ != 0f) {
            Matrix4.translate(out, -anchorX * 2f, anchorY * 2f, -anchorZ)
        }
        return out
    }

    companion object {
        val DEFAULT = Transform3D()

        /** Converts legacy 2D transform properties to Transform3D. */
        fun from2D(zoom: Float, x: Float, y: Float, rotation: Float, opacity: Float = 1f): Transform3D =
            Transform3D(
                positionX = x,
                positionY = y,
                positionZ = 0f,
                rotationX = 0f,
                rotationY = 0f,
                rotationZ = rotation,
                scaleX = zoom,
                scaleY = zoom,
                scaleZ = 1f,
                anchorX = 0f,
                anchorY = 0f,
                anchorZ = 0f,
                opacity = opacity,
            )
    }
}
