package com.termex.replay15.editor.backgroundremoval

import android.content.Context
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** ML Kit human foreground segmentation. It never touches the preview or decoder threads. */
class MlKitHumanSegmenter(context: Context, streamMode: Boolean) : SegmentationEngine {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Recly-Segmentation").apply { isDaemon = true }
    }
    private val segmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(if (streamMode) SelfieSegmenterOptions.STREAM_MODE else SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .enableRawSizeMask()
            .setExecutor(executor)
            .build()
    )

    @Volatile private var closed = false

    override fun process(frame: SegmentationFrame, timestampUs: Long, callback: (SegmentationResult?) -> Unit) {
        if (closed) {
            callback(null)
            return
        }
        val image = runCatching { InputImage.fromBitmap(frame.bitmap, frame.rotationDegrees) }.getOrElse {
            callback(null)
            return
        }
        segmenter.process(image)
            .addOnSuccessListener(executor) { mask ->
                callback(mask.toResult(timestampUs))
            }
            .addOnFailureListener(executor) { callback(null) }
    }

    private fun SegmentationMask.toResult(timestampUs: Long): SegmentationResult? {
        val maskWidth = this.width
        val maskHeight = this.height
        if (maskWidth <= 0 || maskHeight <= 0) return null
        val source = buffer.duplicate().order(ByteOrder.nativeOrder()).apply { rewind() }
        val values = FloatArray(maskWidth * maskHeight)
        var index = 0
        while (source.remaining() >= Float.SIZE_BYTES && index < values.size) values[index++] = source.float.coerceIn(0f, 1f)
        return if (index == values.size) SegmentationResult(width, height, timestampUs, values) else null
    }

    override fun close() {
        if (closed) return
        closed = true
        segmenter.close()
        executor.shutdownNow()
    }
}
