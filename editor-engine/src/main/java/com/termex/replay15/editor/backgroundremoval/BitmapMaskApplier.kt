package com.termex.replay15.editor.backgroundremoval

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import com.termex.replay15.editor.ai.GeminiApiKeyConfig
import com.termex.replay15.editor.domain.BackgroundMode
import com.termex.replay15.editor.domain.BackgroundRemovalEffect
import com.termex.replay15.editor.domain.BackgroundRemovalProvider
import com.termex.replay15.editor.domain.SegmentationQuality
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Removes the background of still bitmaps (photo overlays / stickers) honouring the effect's
 * [BackgroundRemovalProvider], the same way [SegmentationEngineFactory] does for clips:
 *
 * - [BackgroundRemovalProvider.AUTO] and [BackgroundRemovalProvider.MLKIT] use the on-device
 *   [StillImageSegmenter] (per-pixel subject mask + guided edge refinement). Stickers used to be
 *   forced onto Gemini, whose polygon contours made the cut-outs look faceted; photo clips had
 *   already moved off Gemini for the same reason.
 * - [BackgroundRemovalProvider.GEMINI] is still available as an explicit choice. If the key is
 *   missing or Gemini cannot trace the subject, the local engine is used instead.
 *
 * Blocks: call from a render/export thread, never from the main thread.
 */
class BitmapMaskApplier(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext ?: context
    private var gemini: GeminiBitmapMaskApplier? = null
    private var local: StillImageSegmenter? = null
    private val lock = Any()
    /** Avoids re-running a failed segmentation every frame; retried after the cooldown. */
    private val failedAt = ConcurrentHashMap<String, Long>()
    /** How many times the subject model was still unavailable (downloading) for a source. */
    private val provisionalAttempts = ConcurrentHashMap<String, Int>()
    @Volatile private var closed = false

    fun apply(source: Bitmap, effect: BackgroundRemovalEffect): Bitmap? {
        if (!effect.enabled || effect.mode != BackgroundMode.REMOVE) return source
        if (closed || source.isRecycled) return null
        if (effect.provider == BackgroundRemovalProvider.GEMINI && GeminiApiKeyConfig.getApiKey().isNotBlank()) {
            geminiApplier()?.apply(source, effect)?.let { return it }
            Log.w(TAG, "Gemini não recortou a imagem; usando o motor local")
        }
        return applyLocal(source, effect)
    }

    private fun applyLocal(source: Bitmap, effect: BackgroundRemovalEffect): Bitmap? {
        val key = "${System.identityHashCode(source)}:${source.generationId}:${effect.quality}"
        failedAt[key]?.let { if (SystemClock.elapsedRealtime() - it < FAILURE_COOLDOWN_MS) return null }
        val segmenter = localSegmenter() ?: return null

        val input = segmentationInput(source, effect.quality)
        val result = runCatching { segmenter.segmentBlocking(input, timestampUs = 0L) }
            .onFailure { Log.w(TAG, "Falha ao segmentar a imagem localmente", it) }
            .getOrNull()
        input.recycle()

        if (result == null || result.width <= 0 || result.height <= 0) {
            failedAt[key] = SystemClock.elapsedRealtime()
            return null
        }
        if (result.provisional) {
            // The subject model is usually still being downloaded by Play services. The selfie
            // stand-in only knows people, so it would erase products/objects: keep the original
            // image and retry a few times before accepting the stand-in.
            val attempts = (provisionalAttempts[key] ?: 0) + 1
            provisionalAttempts[key] = attempts
            if (attempts < MAX_PROVISIONAL_ATTEMPTS) {
                failedAt[key] = SystemClock.elapsedRealtime()
                return null
            }
        }
        failedAt.remove(key)
        provisionalAttempts.remove(key)

        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h).also { source.getPixels(it, 0, w, 0, 0, w, h) }
        StillMatte.applyAlpha(pixels, w, h, result.mask, result.width, result.height, effect.threshold, effect.feather)
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Downscales to the quality's working size and flattens transparency onto white, so areas
     * that are already transparent read as background (same convention as the Gemini path).
     */
    private fun segmentationInput(source: Bitmap, quality: SegmentationQuality): Bitmap {
        val maxDim = when (quality) {
            SegmentationQuality.FAST -> 768
            SegmentationQuality.BALANCED -> 1024
            SegmentationQuality.HIGH -> 1536
        }
        val scale = min(1f, maxDim.toFloat() / max(source.width, source.height))
        val w = (source.width * scale).roundToInt().coerceAtLeast(1)
        val h = (source.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = if (w != source.width || h != source.height) Bitmap.createScaledBitmap(source, w, h, true) else source
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(output).apply {
            drawColor(Color.WHITE)
            drawBitmap(scaled, 0f, 0f, null)
        }
        if (scaled !== source) scaled.recycle()
        return output
    }

    private fun localSegmenter(): StillImageSegmenter? = synchronized(lock) {
        if (closed) null else local ?: StillImageSegmenter(appContext).also { local = it }
    }

    private fun geminiApplier(): GeminiBitmapMaskApplier? = synchronized(lock) {
        if (closed) null else gemini ?: GeminiBitmapMaskApplier(appContext).also { gemini = it }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { local?.close() }
            runCatching { gemini?.close() }
            local = null
            gemini = null
        }
        failedAt.clear()
        provisionalAttempts.clear()
    }

    private companion object {
        const val TAG = "BitmapMaskApplier"
        const val FAILURE_COOLDOWN_MS = 30_000L
        const val MAX_PROVISIONAL_ATTEMPTS = 3
    }
}

/** Pure-Kotlin matte application for still bitmaps, so it is unit-testable on the JVM. */
object StillMatte {
    /**
     * Writes the matte into the alpha channel of [pixels] (ARGB, row-major [width] x [height]).
     * [mask] is a confidence map of [maskWidth] x [maskHeight] covering the same image, already
     * edge-refined by the segmenter, so a bilinear lookup plus the threshold/feather ramp is
     * enough (no per-pixel bilateral pass over a full-resolution sticker).
     * The source alpha is preserved: already-transparent pixels stay transparent.
     */
    fun applyAlpha(
        pixels: IntArray,
        width: Int,
        height: Int,
        mask: FloatArray,
        maskWidth: Int,
        maskHeight: Int,
        threshold: Float,
        feather: Float,
    ) {
        require(pixels.size == width * height && mask.size == maskWidth * maskHeight)
        val soft = feather.coerceAtLeast(.001f)
        val edge0 = threshold - soft
        val edge1 = threshold + soft
        for (y in 0 until height) {
            val v = (y + .5f) / height
            for (x in 0 until width) {
                val index = y * width + x
                val color = pixels[index]
                val confidence = MatteRefiner.sampleBilinear(mask, maskWidth, maskHeight, (x + .5f) / width, v)
                val t = ((confidence - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
                val matte = t * t * (3f - 2f * t)
                val alpha = (matte * (color ushr 24)).roundToInt().coerceIn(0, 255)
                pixels[index] = (color and 0x00FFFFFF) or (alpha shl 24)
            }
        }
    }
}
