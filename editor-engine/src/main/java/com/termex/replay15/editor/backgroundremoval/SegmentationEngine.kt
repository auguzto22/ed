package com.termex.replay15.editor.backgroundremoval

import android.graphics.Bitmap

/** A frame owned by the caller until the scheduler invokes [release]. */
data class SegmentationFrame(
    val bitmap: Bitmap,
    val rotationDegrees: Int = 0,
    val release: () -> Unit = {},
)

/** Engine-neutral result. Runtime caches hold this data, never Android ML Kit objects. */
data class SegmentationResult(
    val width: Int,
    val height: Int,
    val timestampUs: Long,
    val mask: FloatArray,
    /**
     * How the mask was obtained. [MEASURED] means a segmentation model evaluated the frame;
     * [APPROXIMATED_FROM_BOX] means only a bounding box was available and the mask is a
     * geometric approximation of it. Callers that must not invent subject geometry (auto
     * reframe, tracking, style transfer) can refuse the approximated form explicitly.
     */
    val origin: SegmentationOrigin = SegmentationOrigin.MEASURED,
) {
    init {
        require(width > 0 && height > 0 && mask.size == width * height)
    }

    val isMeasured: Boolean get() = origin == SegmentationOrigin.MEASURED
}

enum class SegmentationOrigin {
    MEASURED,
    APPROXIMATED_FROM_BOX,
}

interface SegmentationEngine : AutoCloseable {
    fun process(
        frame: SegmentationFrame,
        timestampUs: Long,
        callback: (SegmentationResult?) -> Unit,
    )

    override fun close()
}
