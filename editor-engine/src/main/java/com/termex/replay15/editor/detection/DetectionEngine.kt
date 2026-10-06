package com.termex.replay15.editor.detection

import android.graphics.Bitmap
import com.termex.replay15.editor.domain.TrackingPoint

/** A frame owned by the caller until the engine invokes [release]. */
data class DetectionFrame(
    val bitmap: Bitmap,
    val rotationDegrees: Int = 0,
    val release: () -> Unit = {},
    /** Caller-supplied clip-local timestamp; detection results must use this timeline. */
    val timeUs: Long = 0L,
)

/**
 * One engine-neutral subject, already normalized to 0..1 of the frame as it should be seen.
 *
 * Normalized coordinates are the contract: they stay correct when the preview surface is
 * resized, when a proxy is swapped in, and when the export runs at another resolution.
 * Never store absolute pixels here.
 */
data class DetectionObservation(
    val timeUs: Long,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val confidence: Float,
) {
    init {
        require(timeUs >= 0L)
        require(listOf(centerX, centerY, width, height, confidence).all { it.isFinite() })
        require(centerX in 0f..1f && centerY in 0f..1f)
        require(width in .001f..1f && height in .001f..1f)
        require(confidence in 0f..1f)
    }

    /** Bridges this observation into the tracker that masks already consume. */
    fun toTrackingPoint(): TrackingPoint =
        TrackingPoint(timeUs, centerX, centerY, width, height, confidence)
}

/** Which subject a [DetectionEngine] reports on. */
enum class DetectionTarget(val label: String) {
    FACE("Rosto"),
    POSE("Corpo"),
}

interface DetectionEngine : AutoCloseable {
    /** Stable engine identity, used for cache keys and diagnostics. */
    val id: String

    val target: DetectionTarget

    fun process(frame: DetectionFrame, callback: (List<DetectionObservation>) -> Unit)

    override fun close()
}

/** Clamps an ML Kit pixel box into a normalized observation, or null when it is degenerate. */
internal fun normalizedObservation(
    timeUs: Long,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    imageWidth: Int,
    imageHeight: Int,
    confidence: Float,
): DetectionObservation? {
    if (imageWidth <= 0 || imageHeight <= 0) return null
    val x0 = left.coerceIn(0f, imageWidth.toFloat()) / imageWidth
    val x1 = right.coerceIn(0f, imageWidth.toFloat()) / imageWidth
    val y0 = top.coerceIn(0f, imageHeight.toFloat()) / imageHeight
    val y1 = bottom.coerceIn(0f, imageHeight.toFloat()) / imageHeight
    val width = x1 - x0
    val height = y1 - y0
    if (width < .001f || height < .001f) return null
    return runCatching {
        DetectionObservation(
            timeUs = timeUs,
            centerX = (x0 + x1) * .5f,
            centerY = (y0 + y1) * .5f,
            width = width.coerceIn(.001f, 1f),
            height = height.coerceIn(.001f, 1f),
            confidence = confidence.coerceIn(0f, 1f),
        )
    }.getOrNull()
}
