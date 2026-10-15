package com.termex.replay15.editor.backgroundremoval

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.termex.replay15.editor.ai.GeminiApiKeyConfig
import com.termex.replay15.editor.captions.GeminiHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Gemini AI Segmentation Engine for foreground subject isolation and background removal.
 * Uses Gemini API to detect subjects, contours, and bounding boxes, converting them
 * to standard [SegmentationResult] float masks.
 *
 * Automatically falls back to [fallbackEngine] (e.g. local ML Kit) if offline or on error.
 */
class GeminiBackgroundSegmenter(
    private val context: Context,
    private val apiKey: String = GeminiApiKeyConfig.getApiKey(),
    private val fallbackEngine: SegmentationEngine? = MlKitHumanSegmenter(context, streamMode = true),
) : SegmentationEngine {

    private val http = GeminiHttpClient(apiKey)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val closed = AtomicBoolean(false)

    override fun process(
        frame: SegmentationFrame,
        timestampUs: Long,
        onResult: (SegmentationResult?) -> Unit,
    ) {
        // Exactly one answer per frame, whichever path (Gemini, fallback, failure) produces it.
        val answered = AtomicBoolean(false)
        val callback: (SegmentationResult?) -> Unit = { if (answered.compareAndSet(false, true)) onResult(it) }
        // The frame belongs to the caller (SegmentationScheduler releases it after the callback),
        // as with every other engine. Releasing it here as well freed the capture buffer a second
        // time, after the scheduler had already handed it to the next capture, so the GL thread
        // could overwrite a bitmap that ML Kit was still reading (garbled or wrong masks).
        if (closed.get()) {
            callback(null)
            return
        }

        if (apiKey.isBlank()) {
            if (fallbackEngine != null) fallbackEngine.process(frame, timestampUs, callback) else callback(null)
            return
        }

        scope.launch {
            try {
                val inputBitmap = frame.bitmap
                val maxDim = 384
                val scale = min(1f, maxDim.toFloat() / max(inputBitmap.width, inputBitmap.height))
                val targetW = (inputBitmap.width * scale).roundToInt().coerceAtLeast(64)
                val targetH = (inputBitmap.height * scale).roundToInt().coerceAtLeast(64)
                val scaled = if (scale < 1f) {
                    Bitmap.createScaledBitmap(inputBitmap, targetW, targetH, true)
                } else {
                    inputBitmap
                }

                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                if (scaled != inputBitmap) scaled.recycle()
                val imageBase64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

                val payload = GeminiSegmentationRequest.build(imageBase64)

                var rawResponse: String? = null
                try {
                    val resp = http.withRetry({ if (closed.get()) throw RuntimeException("Cancelled") }) {
                        http.createInteraction(payload) { if (closed.get()) throw RuntimeException("Cancelled") }
                    }
                    rawResponse = GeminiSegmentationRequest.outputText(resp)
                } catch (failure: Throwable) {
                    // A persistent Gemini failure used to be invisible: the ML Kit fallback
                    // mask was delivered with no indication that the preferred path never ran.
                    android.util.Log.w("GeminiSegmenter", "Gemini segmentation failed; falling back", failure)
                }

                val result = rawResponse?.let {
                    GeminiSegmentationParser.parseSegmentation(it, targetW, targetH, timestampUs)
                }

                if (result != null) {
                    callback(result)
                } else if (fallbackEngine != null && !closed.get()) {
                    fallbackEngine.process(frame, timestampUs, callback)
                } else {
                    callback(null)
                }
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) {
                    // Closed while waiting for Gemini: still answer once so the scheduler unblocks.
                    callback(null)
                } else if (fallbackEngine != null && !closed.get()) {
                    fallbackEngine.process(frame, timestampUs, callback)
                } else {
                    callback(null)
                }
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            scope.cancel()
            fallbackEngine?.close()
        }
    }
}
