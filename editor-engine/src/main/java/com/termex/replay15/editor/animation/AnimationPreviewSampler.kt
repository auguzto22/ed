package com.termex.replay15.editor.animation

/** Pose of a preset at one instant, in the same units as [AnimationPoint]. */
data class AnimationPose(val zoom: Float, val x: Float, val y: Float, val rotation: Float, val opacity: Float)

/**
 * Samples a preset's points the same way the transform evaluator interpolates keyframes
 * (each segment uses the easing of its starting point). Pure Kotlin so the library previews
 * stay faithful to what is applied and can be unit-tested.
 */
object AnimationPreviewSampler {
    fun sample(points: List<AnimationPoint>, progress: Float): AnimationPose {
        require(points.size >= 2)
        val time = progress.coerceIn(0f, 1f)
        val index = points.indexOfLast { it.time <= time }.coerceIn(0, points.size - 2)
        val a = points[index]
        val b = points[index + 1]
        val span = (b.time - a.time).coerceAtLeast(1e-6f)
        val t = a.easing.apply(((time - a.time) / span).coerceIn(0f, 1f), a.bezier)
        fun mix(from: Float, to: Float) = from + (to - from) * t
        return AnimationPose(
            zoom = mix(a.zoom, b.zoom),
            x = mix(a.x, b.x),
            y = mix(a.y, b.y),
            rotation = mix(a.rotation, b.rotation),
            opacity = mix(a.opacity, b.opacity).coerceIn(0f, 1f),
        )
    }

    /**
     * Maps wall-clock time to animation progress for a looping preview: one-shot presets play
     * once and hold the final pose for [holdMs]; loops repeat continuously.
     */
    fun progressAt(elapsedMs: Long, durationMs: Long, loop: Boolean, holdMs: Long = 700L): Float {
        val duration = durationMs.coerceAtLeast(1L)
        if (loop) return (elapsedMs.mod(duration)).toFloat() / duration
        val cycle = duration + holdMs.coerceAtLeast(0L)
        return ((elapsedMs.mod(cycle)).toFloat() / duration).coerceAtMost(1f)
    }
}
