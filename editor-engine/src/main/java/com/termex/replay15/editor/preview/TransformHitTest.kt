package com.termex.replay15.editor.preview

import kotlin.math.cos
import kotlin.math.sin

/** Rotation-aware hit testing with a minimum accessible target around the visual bounds. */
internal object TransformHitTest {
    fun contains(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        centerX: Float,
        centerY: Float,
        rotationDegrees: Float,
        touchX: Float,
        touchY: Float,
        minimumSizePx: Float,
    ): Boolean {
        require(right >= left && bottom >= top && minimumSizePx >= 0f)
        val halfWidth = maxOf((right - left) / 2f, minimumSizePx / 2f)
        val halfHeight = maxOf((bottom - top) / 2f, minimumSizePx / 2f)
        val radians = Math.toRadians((-rotationDegrees).toDouble())
        val dx = touchX - centerX
        val dy = touchY - centerY
        val localX = dx * cos(radians).toFloat() - dy * sin(radians).toFloat()
        val localY = dx * sin(radians).toFloat() + dy * cos(radians).toFloat()
        return localX in -halfWidth..halfWidth && localY in -halfHeight..halfHeight
    }
}
