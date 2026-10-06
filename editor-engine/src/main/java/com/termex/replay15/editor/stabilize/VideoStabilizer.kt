package com.termex.replay15.editor.stabilize

import com.termex.replay15.editor.domain.Easing
import com.termex.replay15.editor.domain.TransformKeyframe
import com.termex.replay15.editor.domain.VideoClip
import com.termex.replay15.editor.domain.transformAt

/**
 * How aggressively shake is removed.
 *
 * A larger window averages over more time, so it kills more jitter but also eats into
 * deliberate fast camera moves, and it costs more of the frame to auto-crop.
 */
enum class StabilizationProfile(
    val label: String,
    val windowSamples: Int,
    val maxAutoZoom: Float,
) {
    MINIMAL("Minimo", 5, 1.05f),
    RECOMMENDED("Recomendado", 11, 1.15f),
    STRONG("Forte", 21, 1.30f),
}

/** The correction the video should receive at a given moment, normalized to 0..1. */
data class StabilizationCorrection(
    val timeUs: Long,
    val dx: Float,
    val dy: Float,
    val zoom: Float,
) {
    init {
        require(timeUs >= 0L)
        require(dx.isFinite() && dy.isFinite() && zoom.isFinite())
        require(zoom >= 1f)
    }
}

/**
 * Turns a raw camera-motion path into the inverse transform that cancels the shake.
 *
 * The correction is the difference between the raw path and its smoothed version, which is
 * what preserves an intentional pan: a slow deliberate move survives because both paths
 * agree, while high-frequency jitter is exactly what the smoothing removes.
 */
class StabilizationPath(private val profile: StabilizationProfile) {

    fun correct(motions: List<CameraMotion>): List<StabilizationCorrection> {
        if (motions.isEmpty()) return emptyList()
        val sorted = motions.sortedBy { it.timeUs }
        val smoothedX = smooth(sorted.map { it.dx })
        val smoothedY = smooth(sorted.map { it.dy })

        val half = profile.windowSamples / 2
        val corrections = sorted.mapIndexed { index, motion ->
            // Near the ends the smoothing window is truncated, so the average lags behind a
            // steady pan and would be mistaken for shake. Without full context there is no
            // reliable correction, so those samples are left untouched.
            val hasContext = index >= half && index <= sorted.size - half - 1
            StabilizationCorrection(
                timeUs = motion.timeUs,
                // Cancelling the camera means moving the layer the opposite way.
                dx = if (hasContext) (motion.dx - smoothedX[index]).coerceIn(-.5f, .5f) else 0f,
                dy = if (hasContext) (motion.dy - smoothedY[index]).coerceIn(-.5f, .5f) else 0f,
                zoom = 1f,
            )
        }

        // Auto-crop: the corrected frame can expose a border, so scale by the worst excursion
        // on either axis. Scaling by one uniform factor keeps the aspect untouched.
        val excursion = maxOf(
            corrections.maxOf { kotlin.math.abs(it.dx) },
            corrections.maxOf { kotlin.math.abs(it.dy) },
        )
        val zoom = if (excursion <= 0f) 1f else (1f / (1f - 2f * excursion)).coerceIn(1f, profile.maxAutoZoom)
        return corrections.map { it.copy(zoom = zoom) }
    }

    /** Box blur over a centered window, clamped at the ends. */
    private fun smooth(values: List<Float>): List<Float> {
        val half = profile.windowSamples / 2
        return values.indices.map { index ->
            val from = (index - half).coerceAtLeast(0)
            val to = (index + half).coerceAtMost(values.size - 1)
            var sum = 0f
            for (i in from..to) sum += values[i]
            sum / (to - from + 1)
        }
    }
}

/**
 * Orchestrates stabilization: analyze once, cache the result, apply as transform keyframes.
 *
 * Analysis is explicitly a pre-pass. Nothing here runs per playback frame, and the output is
 * the same [TransformKeyframe] the editor already persists and already feeds the renderer.
 */
class VideoStabilizer(private val profile: StabilizationProfile = StabilizationProfile.RECOMMENDED) {

    fun analyze(
        frames: Iterable<Pair<Long, LumaFrame>>,
        estimator: MotionEstimator = MotionEstimator(),
    ): List<CameraMotion> {
        val path = StabilizationPath(profile)
        val motions = ArrayList<CameraMotion>()
        var previous: LumaFrame? = null
        for ((timeUs, frame) in frames) {
            val before = previous
            previous = frame
            if (before != null) estimator.estimate(before, frame, timeUs)?.let(motions::add)
        }
        return path.correct(motions).map { CameraMotion(it.timeUs, it.dx, it.dy, 1f) }
    }

    /** Converts a correction path into the clip's transform keyframes. */
    fun toKeyframes(clip: VideoClip, corrections: List<StabilizationCorrection>): List<TransformKeyframe> {
        if (corrections.isEmpty()) return emptyList()

        val result = clip.keyframes.associateByTo(sortedMapOf()) { it.sourceUs }
        val durationUs = clip.durationUs

        fun place(localUs: Long, dx: Float, dy: Float, zoom: Float) {
            val bounded = localUs.coerceIn(0L, durationUs)
            val sourceUs = clip.timeMap.sourceAt(bounded)
            if (result.containsKey(sourceUs) || result.size >= MAX_KEYFRAMES) return
            val base = clip.transformAt(sourceUs)
            result[sourceUs] = base.copy(
                sourceUs = sourceUs,
                zoom = (base.zoom * zoom).coerceIn(.25f, 4f),
                x = (dx * zoom).coerceIn(-.5f, .5f),
                y = (dy * zoom).coerceIn(-.5f, .5f),
                easing = Easing.SMOOTH,
            )
        }

        corrections.forEach { place(it.timeUs, it.dx, it.dy, it.zoom) }
        return result.values.take(MAX_KEYFRAMES)
    }

    private companion object {
        const val MAX_KEYFRAMES = 200
    }
}
