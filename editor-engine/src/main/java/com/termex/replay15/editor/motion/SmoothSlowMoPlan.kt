package com.termex.replay15.editor.motion

import com.termex.replay15.editor.domain.MAX_CLIP_SPEED
import com.termex.replay15.editor.domain.MIN_CLIP_SPEED
import com.termex.replay15.editor.domain.VideoClip
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.abs

/**
 * How much extra frame rate to generate for a slowed clip.
 *
 * Interpolating a 30fps clip to 90fps quadruples its size and encoding time, so the user
 * chooses the ceiling rather than paying for it by default.
 */
enum class SmoothSlowMoProfile(
    val label: String,
    val maxOutputFps: Int,
    /** Speeds above this are not slowed enough to look bad, so no work is done at all. */
    val minSpeedToInterpolate: Float,
) {
    OFF("Desligado", 0, 0f),
    FLUID("Fluido 60", 60, .95f),
    SMOOTH("Suave 90", 90, .95f);

    val enabled: Boolean get() = this != OFF
}

/** Everything the background job needs, resolved once so the worker never touches the project. */
data class SmoothSlowMoRequest(
    val clipId: String,
    val inputUri: String,
    val inUs: Long,
    val outUs: Long,
    val sourceFps: Float,
    val outputFps: Int,
    val slowestSpeed: Float,
) {
    init {
        require(inUs >= 0L && outUs > inUs)
        require(sourceFps > 0f && outputFps > 0)
        require(slowestSpeed in MIN_CLIP_SPEED..MAX_CLIP_SPEED)
    }

    /** Derived media is keyed by everything that changes its content. */
    val cacheKey: String
        get() = "ssm_${clipId.hashCode()}_${inUs}_${outUs}_${outputFps}_${sourceFps.toInt()}"
}

/**
 * Decides whether a clip needs interpolated frames and at what frame rate.
 *
 * Only clips that are actually slowed qualify. A clip left at 1x is already smooth, and
 * interpolating it would only cost storage and battery.
 */
object SmoothSlowMoPlanner {

    /** The slowest speed anywhere in the clip, considering both the ramp and the base speed. */
    fun slowestSpeed(clip: VideoClip): Float {
        if (clip.speedCurve.isEmpty()) return clip.speed
        return clip.speedCurve.minOf { it.speed }
    }

    fun plan(clip: VideoClip, profile: SmoothSlowMoProfile): SmoothSlowMoRequest? {
        if (!profile.enabled) return null
        if (clip.image) return null
        if (clip.sourceUs <= MIN_INTERPOLATED_US) return null

        val slowest = slowestSpeed(clip)
        if (slowest >= profile.minSpeedToInterpolate) return null

        // The decoder has to be able to emit the new rate, otherwise the extra frames are
        // invented by the encoder's own duplication and nothing is gained.
        val outputFps = ceil(clip.fps / slowest).roundToInt().coerceIn(1, profile.maxOutputFps)
        if (outputFps <= clip.fps) return null

        return SmoothSlowMoRequest(
            clipId = clip.id,
            inputUri = clip.uri,
            inUs = clip.inUs,
            outUs = clip.outUs,
            sourceFps = clip.fps,
            outputFps = outputFps,
            slowestSpeed = slowest,
        )
    }

    /**
     * Clips that a project would derive media for. Used by the editor to show the user what
     * is about to be rendered before starting any background work.
     */
    fun plannedFor(clips: List<VideoClip>, profile: SmoothSlowMoProfile): List<SmoothSlowMoRequest> =
        clips.mapNotNull { plan(it, profile) }

    /**
     * How many output frames each source frame is expanded into, including itself.
     *
     * Rounded rather than truncated: `(1f / 0.9f).toInt()` is 1, which silently turned the
     * whole feature into frame duplication for any speed above 0.5x. A frame the estimator
     * could not measure, or a shot with nothing moving, stays at 1.
     */
    fun interpolatedFrameCount(
        velocity: Pair<Float, Float>?,
        slowestSpeed: Float,
        outputFps: Int,
        minMotionPx: Float = 1.5f,
    ): Int {
        if (velocity == null) return 1
        if (abs(velocity.first) < minMotionPx && abs(velocity.second) < minMotionPx) return 1
        return (1f / slowestSpeed).roundToInt().coerceIn(1, outputFps)
    }

    private const val MIN_INTERPOLATED_US = 500_000L
}
