package com.termex.replay15.editor.motion

import com.termex.replay15.editor.assets.EffectInstance
import com.termex.replay15.editor.assets.EffectValueKeyframe
import com.termex.replay15.editor.domain.Easing
import com.termex.replay15.editor.domain.VideoClip
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Motion blur settings. [shutterAngleDegrees] is the real photographic control: 180 is a
 * normal shutter, 360 a full one, below that a faster shutter that freezes the motion.
 */
data class MotionBlurSettings(
    val amount: Float = .35f,
    val samples: Float = .4f,
    val shutterAngleDegrees: Float = 180f,
    /** Below this speed the layer is considered static and no blur is produced. */
    val minSpeedPxPerSecond: Float = 12f,
) {
    init {
        require(amount in 0f..2f)
        require(samples in 0f..1f)
        require(shutterAngleDegrees in 0f..360f)
        require(minSpeedPxPerSecond >= 0f)
    }
}

/**
 * Builds a motion blur effect from the movement the user already animated on the layer.
 *
 * The blur is derived from the clip's own transform keyframes rather than from a manual
 * angle, so it automatically follows position, zoom and speed ramps. The result is an
 * ordinary [EffectInstance] with parameter keyframes, which means preview, export, undo/redo
 * and project persistence all keep working without any new plumbing.
 */
object MotionBlurEngine {

    const val ASSET_ID = "recly_motion_blur_vector"

    // EffectInstance rejects more than 400 keyframes in total across all curves, and this
    // engine writes two curves, so 150 each stays comfortably inside the limit.
    private const val MAX_CURVE_POINTS = 150

    fun build(clip: VideoClip, settings: MotionBlurSettings, id: String): EffectInstance? {
        val keyframes = clip.keyframes.sortedBy { it.sourceUs }
        if (keyframes.size < 2) return null

        val fps = if (clip.fps > 0f) clip.fps else 30f
        val exposureSeconds = (settings.shutterAngleDegrees / 360f) / fps
        // A closed shutter exposes no light for any length of time, so there is nothing to
        // smear. Returning null keeps an inert effect out of the project entirely.
        if (exposureSeconds <= 0f || settings.amount <= 0f) return null

        val dxCurve = ArrayList<EffectValueKeyframe>()
        val dyCurve = ArrayList<EffectValueKeyframe>()

        for (index in 0 until keyframes.size - 1) {
            val from = keyframes[index]
            val to = keyframes[index + 1]
            val dtSeconds = (to.sourceUs - from.sourceUs) / 1_000_000f
            if (dtSeconds <= 0f) continue

            // x and y are normalized offsets; the renderer scales them by 2 into device space.
            val velocityX = (to.x - from.x) * 2f * clip.width / dtSeconds
            val velocityY = (to.y - from.y) * 2f * clip.height / dtSeconds
            if (hypot(velocityX, velocityY) < settings.minSpeedPxPerSecond) continue

            val travelX = min(abs(velocityX) * exposureSeconds * settings.amount, MAX_TRAVEL_PX)
            val travelY = min(abs(velocityY) * exposureSeconds * settings.amount, MAX_TRAVEL_PX)
            val signX = if (velocityX < 0f) -1f else 1f
            val signY = if (velocityY < 0f) -1f else 1f

            val at = from.sourceUs
            dxCurve += EffectValueKeyframe(at, travelX * signX, Easing.LINEAR)
            dyCurve += EffectValueKeyframe(at, travelY * signY, Easing.LINEAR)
            if (dxCurve.size >= MAX_CURVE_POINTS) break
        }

        if (dxCurve.isEmpty()) return null

        return EffectInstance(
            id = id,
            assetId = ASSET_ID,
            intensity = 1f,
            values = mapOf("amount" to settings.amount, "samples" to settings.samples),
            // The curves must be strictly increasing, which the walk above already guarantees
            // because it only ever emits from.keyframe.sourceUs in ascending order.
            keyframes = mapOf("dx" to dxCurve.distinctBy { it.sourceUs }, "dy" to dyCurve.distinctBy { it.sourceUs }),
        )
    }

    /** True when the clip is moving enough for the settings to produce any blur at all. */
    fun applies(clip: VideoClip, settings: MotionBlurSettings): Boolean = build(clip, settings, "probe") != null

    private const val MAX_TRAVEL_PX = 120f
}
