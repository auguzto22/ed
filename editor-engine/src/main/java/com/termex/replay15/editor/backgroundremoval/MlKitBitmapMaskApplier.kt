package com.termex.replay15.editor.backgroundremoval

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.termex.replay15.editor.domain.BackgroundRemovalEffect
import com.termex.replay15.editor.domain.BackgroundMode
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** Applies one cached still-image mask for stickers and export overlays. */
class MlKitBitmapMaskApplier(context: Context) : AutoCloseable {
    private val segmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .enableRawSizeMask()
            .build()
    )

    fun apply(source: Bitmap, effect: BackgroundRemovalEffect): Bitmap? = runCatching {
        if (!effect.enabled || effect.mode != BackgroundMode.REMOVE) return@runCatching source
        val raw = Tasks.await(segmenter.process(InputImage.fromBitmap(source, 0)), 10L, TimeUnit.SECONDS)
        val mask = raw.buffer.duplicate().order(ByteOrder.nativeOrder()).apply { rewind() }
        val maskValues = FloatArray(raw.width * raw.height)
        for (index in maskValues.indices) maskValues[index] = mask.float.coerceIn(0f, 1f)
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        MatteRefiner.apply(pixels, source.width, source.height, maskValues, raw.width, raw.height, effect)
        Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        }
    }.getOrNull()

    override fun close() = segmenter.close()
}
