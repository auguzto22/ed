package com.termex.replay15.editor.backgroundremoval

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * High-quality segmentation for still images (photo clips).
 *
 * The selfie model used for video is built for live camera frames: it always runs at 256 px and
 * trades edge quality for speed, which on a photo leaves blocky hair and halos of background.
 * A photo only needs one mask, so here we can afford ML Kit Subject Segmentation, which returns a
 * full-resolution mask per subject. The selfie model still runs, but only as a "where is the
 * person" guide so chairs/plants touching the person are dropped (see [StillMaskCombiner]).
 *
 * If the subject model is not available yet (Play services downloads it on demand), the selfie
 * mask is returned marked [SegmentationResult.provisional] so callers can retry later.
 */
class StillImageSegmenter(@Suppress("UNUSED_PARAMETER") context: Context) : SegmentationEngine {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Recly-StillSegmentation").apply { isDaemon = true }
    }
    private val subjectSegmenter = SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder()
            .enableMultipleSubjects(
                SubjectSegmenterOptions.SubjectResultOptions.Builder()
                    .enableConfidenceMask()
                    .build()
            )
            .build()
    )
    private val personSegmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .enableRawSizeMask()
            .build()
    )
    @Volatile private var closed = false

    override fun process(frame: SegmentationFrame, timestampUs: Long, callback: (SegmentationResult?) -> Unit) {
        if (closed) {
            callback(null)
            return
        }
        val submitted = runCatching {
            executor.execute {
                val result = runCatching { segmentBlocking(frame.bitmap, timestampUs, frame.rotationDegrees) }
                    .onFailure { Log.w(TAG, "Falha ao segmentar a imagem", it) }
                    .getOrNull()
                callback(result)
            }
        }
        if (submitted.isFailure) callback(null)
    }

    /**
     * Runs both models and merges them. Blocks: never call from the main thread. Safe on a GL or
     * export thread (the export pass calls this once per photo clip).
     */
    fun segmentBlocking(bitmap: Bitmap, timestampUs: Long, rotationDegrees: Int = 0): SegmentationResult? {
        if (closed || bitmap.isRecycled) return null
        val image = InputImage.fromBitmap(bitmap, rotationDegrees)
        val rotated = rotationDegrees % 180 != 0
        val imageWidth = if (rotated) bitmap.height else bitmap.width
        val imageHeight = if (rotated) bitmap.width else bitmap.height

        val person = runCatching { Tasks.await(personSegmenter.process(image), TIMEOUT_S, TimeUnit.SECONDS) }
            .getOrNull()
            ?.let { mask ->
                val values = FloatArray(mask.width * mask.height)
                val buffer = mask.buffer.duplicate().order(ByteOrder.nativeOrder()).apply { rewind() }
                var index = 0
                while (buffer.remaining() >= Float.SIZE_BYTES && index < values.size) values[index++] = buffer.float.coerceIn(0f, 1f)
                if (index == values.size && mask.width > 0 && mask.height > 0) SegmentationResult(mask.width, mask.height, timestampUs, values) else null
            }

        val subjects = runCatching { Tasks.await(subjectSegmenter.process(image), TIMEOUT_S, TimeUnit.SECONDS) }
            // Usually the model is still being downloaded by Play services (requested at install
            // time via the manifest meta-data); the caller retries later.
            .onFailure { Log.w(TAG, "Subject segmentation indisponível; usando o modelo de selfie", it) }
            .getOrNull()
            ?.subjects
            ?.mapNotNull { subject ->
                val buffer = subject.confidenceMask ?: return@mapNotNull null
                val size = subject.width * subject.height
                if (size <= 0) return@mapNotNull null
                buffer.rewind()
                if (buffer.remaining() < size) return@mapNotNull null
                val values = FloatArray(size).also { buffer.get(it) }
                StillMaskCombiner.Subject(subject.startX, subject.startY, subject.width, subject.height, values)
            }

        val result = (if (subjects != null) {
            val combined = StillMaskCombiner.combine(
                imageWidth, imageHeight, subjects,
                person?.mask, person?.width ?: 0, person?.height ?: 0,
            )
            // When the subject model found nothing worth keeping, the person mask (if any) is the
            // final answer, not something to retry.
            if (combined != null) SegmentationResult(imageWidth, imageHeight, timestampUs, combined) else person
        } else {
            person?.copy(provisional = true)
        }) ?: return null
        return if (rotationDegrees % 360 == 0) refineEdges(result, bitmap) else result
    }

    /**
     * Snaps the matte onto the photo's own edges with a guided filter at the photo's resolution.
     * Removes the stair-stepping of upscaled masks and weakens halos of wall/background glued to
     * hair and shoulders. Cheap enough to run once per photo (linear in pixel count).
     */
    private fun refineEdges(result: SegmentationResult, bitmap: Bitmap): SegmentationResult {
        val width = bitmap.width
        val height = bitmap.height
        val matte = if (result.width == width && result.height == height) result.mask else
            FloatArray(width * height) { i ->
                StillMaskCombiner.sampleBilinear(
                    result.mask, result.width, result.height,
                    (i % width + .5f) / width, (i / width + .5f) / height,
                )
            }
        val pixels = IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }
        val radius = (maxOf(width, height) / EDGE_RADIUS_DIVISOR).coerceAtLeast(2)
        val refined = GuidedMatteFilter.filter(GuidedMatteFilter.luminance(pixels), matte, width, height, radius, EDGE_EPSILON)
        // Straighten the pixel-to-pixel wobble of the contour (serrated hems and sleeves).
        val sigma = (maxOf(width, height) / CONTOUR_SIGMA_DIVISOR).coerceIn(1f, 2f)
        return result.copy(width = width, height = height, mask = GuidedMatteFilter.gaussianBlur(refined, width, height, sigma))
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { subjectSegmenter.close() }
        runCatching { personSegmenter.close() }
        executor.shutdownNow()
    }

    private companion object {
        const val TAG = "StillSegmentation"
        const val TIMEOUT_S = 20L
        /** Guided-filter window radius = longest side / this (6 px on a 1536 px photo). */
        const val EDGE_RADIUS_DIVISOR = 256
        /**
         * Guided-filter regularisation. 1e-3 copied high-contrast fabric texture (stripes, prints)
         * into the matte next to the edge, which showed up as ripples along the contour.
         */
        const val EDGE_EPSILON = 1e-2f
        /** Contour smoothing sigma = longest side / this, clamped to 1..2 px (1.5 px on a 1536 px photo). */
        const val CONTOUR_SIGMA_DIVISOR = 1000f
    }
}
