package com.termex.replay15.editor.reframe

import com.termex.replay15.editor.detection.DetectionObservation
import com.termex.replay15.editor.domain.Easing
import com.termex.replay15.editor.domain.TransformKeyframe
import com.termex.replay15.editor.domain.VideoClip
import com.termex.replay15.editor.domain.transformAt

/** Output shape the user wants the clip reframed into. */
enum class ReframeAspect(val label: String, val width: Int, val height: Int) {
    ORIGINAL("Original", 0, 0),
    LANDSCAPE_16_9("16:9", 16, 9),
    VERTICAL_9_16("9:16", 9, 16),
    SQUARE_1_1("1:1", 1, 1),
    PORTRAIT_4_5("4:5", 4, 5);

    val ratio: Float get() = width.toFloat() / height
}

/** One subject the camera should keep in frame, normalized to 0..1 of the source. */
data class ReframeSubject(
    val timeUs: Long,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val confidence: Float = 1f,
) {
    init {
        require(timeUs >= 0L)
        require(listOf(centerX, centerY, width, height, confidence).all { it.isFinite() })
        require(centerX in 0f..1f && centerY in 0f..1f)
        require(width in .001f..1f && height in .001f..1f)
        require(confidence in 0f..1f)
    }

    /** Reuses the Phase 7 detection output without converting through pixels. */
    companion object {
        fun from(observation: DetectionObservation): ReframeSubject =
            ReframeSubject(
                timeUs = observation.timeUs,
                centerX = observation.centerX,
                centerY = observation.centerY,
                width = observation.width,
                height = observation.height,
                confidence = observation.confidence,
            )
    }
}

/**
 * A point on the virtual camera path, normalized to 0..1 of the source frame.
 *
 * [scale] is a multiplier of the crop, so 1 means "no extra zoom".
 */
data class ReframePoint(
    val timeUs: Long,
    val centerX: Float,
    val centerY: Float,
    val scale: Float,
) {
    init {
        require(timeUs >= 0L)
        require(centerX in 0f..1f && centerY in 0f..1f)
        require(scale in 1f..4f)
    }
}

/**
 * Turns subject observations into a smooth virtual camera path.
 *
 * The engine never touches the decoder, the renderer or the project: it returns points, and
 * [toKeyframes] converts them into the transform keyframes the editor already persists.
 */
class AutoReframeEngine(
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val target: ReframeAspect = ReframeAspect.VERTICAL_9_16,
    /** Fraction of the output height the subject should occupy. */
    private val subjectShare: Float = .62f,
    private val maxZoom: Float = 2.5f,
    /** 0 freezes the camera, 1 follows every sample raw. Low values kill visible shake. */
    private val smoothing: Float = .35f,
    /** Below this spacing the camera is considered to be re-aiming, not following. */
    private val minIntervalUs: Long = 700_000L,
    private val deadZone: Float = .04f,
) {
    init {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(subjectShare in .2f.. .95f)
        require(maxZoom in 1f..4f)
        require(smoothing in 0f..1f)
        require(minIntervalUs >= 0L)
    }

    private val targetRatio: Float
        get() = if (target == ReframeAspect.ORIGINAL) sourceWidth.toFloat() / sourceHeight else target.ratio

    private val sourceRatio: Float get() = sourceWidth.toFloat() / sourceHeight

    /** Fraction of the source kept vertically / horizontally when filling the target shape. */
    private val cropHeight: Float get() = if (targetRatio <= sourceRatio) 1f else sourceRatio / targetRatio
    private val cropWidth: Float get() = if (targetRatio <= sourceRatio) targetRatio / sourceRatio else 1f

    fun plan(samples: List<ReframeSubject>, durationUs: Long): List<ReframePoint> {
        if (samples.isEmpty() || durationUs <= 0L) return emptyList()

        // Highest-confidence subject wins each moment; a frame with a face and a body tracks
        // the face, because that is the subject a viewer is asked to look at.
        val best = samples
            .filter { it.timeUs <= durationUs && it.confidence >= .2f }
            .groupBy { it.timeUs }
            .mapNotNull { (_, group) -> group.maxByOrNull { it.confidence } }
            .sortedBy { it.timeUs }

        if (best.isEmpty()) return emptyList()

        val points = mutableListOf<ReframePoint>()
        var smoothedX = best.first().centerX
        var smoothedY = best.first().centerY
        var smoothedScale = scaleFor(best.first().height)
        var lastEmittedUs = Long.MIN_VALUE / 2
        var started = false

        for (subject in best) {
            val targetScale = scaleFor(subject.height)
            // Exponential smoothing on all three axes together: the camera turns and zooms
            // as one movement, which is what stops the "shaky crop" look.
            smoothedX += (subject.centerX - smoothedX) * smoothing
            smoothedY += (subject.centerY - smoothedY) * smoothing
            smoothedScale += (targetScale - smoothedScale) * smoothing

            val isFirst = !started
            val farEnough = subject.timeUs - lastEmittedUs >= minIntervalUs
            val movedEnough = points.isEmpty() ||
                kotlin.math.abs(smoothedX - points.last().centerX) > deadZone ||
                kotlin.math.abs(smoothedY - points.last().centerY) > deadZone ||
                kotlin.math.abs(smoothedScale - points.last().scale) > .02f

            if (isFirst || (farEnough && movedEnough)) {
                points += ReframePoint(subject.timeUs, smoothedX, smoothedY, smoothedScale)
                lastEmittedUs = subject.timeUs
                started = true
            }
        }

        // A path with a single point cannot animate; hold it to the end of the clip.
        if (points.size == 1) points += points.first().copy(timeUs = durationUs)
        return points
    }

    private fun scaleFor(subjectHeight: Float): Float {
        // The subject's height inside the crop, then the zoom that makes it fill subjectShare.
        val heightInCrop = subjectHeight / cropHeight
        if (heightInCrop <= 0f) return 1f
        return (subjectShare / heightInCrop).coerceIn(1f, maxZoom)
    }

    /**
     * Converts the camera path into the clip's own transform keyframes, preserving whatever
     * the user had already animated on the layer.
     */
    fun toKeyframes(clip: VideoClip, points: List<ReframePoint>): List<TransformKeyframe> {
        if (points.isEmpty()) return emptyList()

        val result = clip.keyframes.associateByTo(sortedMapOf()) { it.sourceUs }
        val durationUs = clip.durationUs

        fun place(localUs: Long, scale: Float? = null, x: Float? = null, y: Float? = null) {
            val bounded = localUs.coerceIn(0L, durationUs)
            val sourceUs = clip.timeMap.sourceAt(bounded)
            val existing = result[sourceUs]
            if (existing == null && result.size >= MAX_KEYFRAMES) return
            // Lead-in points preserve an existing manual keyframe, while an actual camera
            // sample replaces only its zoom/position channels at that timestamp.
            if (existing != null && scale == null && x == null && y == null) return
            val base = existing ?: clip.transformAt(sourceUs)
            val nextScale = (scale ?: base.zoom).coerceIn(.25f, 4f)
            result[sourceUs] = base.copy(
                sourceUs = sourceUs,
                zoom = nextScale,
                // An offset of x in output space is a source offset of x / zoom, and the
                // subject is off-centre by (center - .5) in source space.
                x = (x ?: base.x).coerceIn(-.5f, .5f),
                y = (y ?: base.y).coerceIn(-.5f, .5f),
                easing = Easing.SMOOTH,
            )
        }

        val slotsForMotion = ((MAX_KEYFRAMES - result.size - 1) / 2).coerceAtLeast(0)
        val motionPoints = if (points.size <= slotsForMotion) points else {
            if (slotsForMotion == 0) emptyList()
            else if (slotsForMotion == 1) listOf(points.first())
            else List(slotsForMotion) { index ->
                points[(index.toLong() * points.lastIndex / (slotsForMotion - 1)).toInt()]
            }
        }

        for (point in motionPoints) {
            if (point.timeUs > 0L) place(point.timeUs - LEAD_IN_US)
            place(point.timeUs, point.scale, offsetFor(point.centerX, point.scale), offsetFor(point.centerY, point.scale))
        }
        motionPoints.lastOrNull()?.let { last ->
            place(durationUs, last.scale, offsetFor(last.centerX, last.scale), offsetFor(last.centerY, last.scale))
        }

        return result.values.take(MAX_KEYFRAMES)
    }

    private fun offsetFor(center: Float, scale: Float): Float =
        ((.5f - center) * scale * if (cropWidth < 1f) cropWidth else 1f).coerceIn(-.5f, .5f)

    private companion object {
        const val MAX_KEYFRAMES = 200
        const val LEAD_IN_US = 180_000L
    }
}
