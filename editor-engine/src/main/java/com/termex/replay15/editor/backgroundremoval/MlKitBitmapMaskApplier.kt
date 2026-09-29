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
import kotlin.math.roundToInt

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
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val mx = (x * raw.width / source.width).coerceIn(0, raw.width - 1)
                val my = (y * raw.height / source.height).coerceIn(0, raw.height - 1)
                val index = my * raw.width + mx
                val confidence = maskValues[index]
                val alpha = (smoothstep(effect.threshold - effect.feather, effect.threshold + effect.feather, confidence) *
                    ((pixels[y * source.width + x] ushr 24) / 255f) * 255f).roundToInt().coerceIn(0, 255)
                pixels[y * source.width + x] = (pixels[y * source.width + x] and 0x00FFFFFF) or (alpha shl 24)
            }
        }
        Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        }
    }.getOrNull()

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0).coerceAtLeast(.001f)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    override fun close() = segmenter.close()
}
