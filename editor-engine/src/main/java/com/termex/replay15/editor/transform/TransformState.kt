package com.termex.replay15.editor.transform

/**
 * Generic normalized 2D transformation state.
 *
 * Coordinates are normalized:
 * x: 0.0 (left), 0.5 (center), 1.0 (right)
 * y: 0.0 (top), 0.5 (center), 1.0 (bottom)
 * scaleX / scaleY: 1.0 (original size)
 * rotation: degrees (-180..180)
 * opacity: 0.0..1.0
 */
data class TransformState(
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotation: Float = 0f,
    val opacity: Float = 1f,
) {
    init {
        require(x.isFinite() && y.isFinite()) { "Transform coordinates must be finite" }
        require(scaleX.isFinite() && scaleY.isFinite()) { "Transform scale must be finite" }
        require(rotation.isFinite() && opacity.isFinite()) { "Transform rotation and opacity must be finite" }
    }

    fun bounded(): TransformState = copy(
        x = x.coerceIn(0f, 1f),
        y = y.coerceIn(0f, 1f),
        scaleX = scaleX.coerceIn(0.05f, 20f),
        scaleY = scaleY.coerceIn(0.05f, 20f),
        rotation = ((rotation + 180f) % 360f + 360f) % 360f - 180f,
        opacity = opacity.coerceIn(0f, 1f),
    )
}
