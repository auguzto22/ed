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
) {
    init {
        require(width > 0 && height > 0 && mask.size == width * height)
    }
}

interface SegmentationEngine : AutoCloseable {
    fun process(
        frame: SegmentationFrame,
        timestampUs: Long,
        callback: (SegmentationResult?) -> Unit,
    )

    override fun close()
}
