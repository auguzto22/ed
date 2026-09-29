package com.termex.replay15.editor.transform

import kotlin.math.abs

/**
 * Central evaluation engine for interpolating [TransformState] between [TransformKeyframe]s.
 */
object TransformEvaluator {

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /**
     * Interpolates between two angles in degrees using the shortest angular path.
     * Prevents unexpected 360-degree rotations when crossing the 0/360 boundary.
     */
    fun lerpAngle(start: Float, end: Float, t: Float): Float {
        var delta = (end - start) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        val result = start + delta * t
        return ((result + 180f) % 360f + 360f) % 360f - 180f
    }

    fun interpolateTransform(a: TransformState, b: TransformState, t: Float): TransformState {
        return TransformState(
            x = lerp(a.x, b.x, t),
            y = lerp(a.y, b.y, t),
            scaleX = lerp(a.scaleX, b.scaleX, t),
            scaleY = lerp(a.scaleY, b.scaleY, t),
            rotation = lerpAngle(a.rotation, b.rotation, t),
            opacity = lerp(a.opacity, b.opacity, t).coerceIn(0f, 1f),
        )
    }

    fun evaluate(
        base: TransformState,
        keyframes: List<TransformKeyframe>,
        timeUs: Long,
        timeOffsetUs: Long = 0L,
    ): TransformState {
        if (keyframes.isEmpty()) {
            return base
        }

        val sorted = if (keyframes.zipWithNext().all { (a, b) -> a.timeUs <= b.timeUs }) {
            keyframes
        } else {
            keyframes.sortedBy { it.timeUs }
        }

        val first = sorted.first()
        val last = sorted.last()

        if (timeUs <= first.timeUs - timeOffsetUs) {
            return first.transform
        }
        if (timeUs >= last.timeUs - timeOffsetUs) {
            return last.transform
        }

        // Find surrounding interval [a, b]
        val nextIndex = sorted.indexOfFirst { it.timeUs - timeOffsetUs >= timeUs }
        if (nextIndex <= 0) {
            return first.transform
        }

        val a = sorted[nextIndex - 1]
        val b = sorted[nextIndex]

        val span = (b.timeUs - a.timeUs).toDouble()
        if (span <= 0.0) return b.transform
        val progress = ((timeUs - (a.timeUs - timeOffsetUs)).toDouble() / span).toFloat().coerceIn(0f, 1f)

        val t = a.easing.apply(progress, a.bezier)
        return interpolateTransform(a.transform, b.transform, t)
    }
}
