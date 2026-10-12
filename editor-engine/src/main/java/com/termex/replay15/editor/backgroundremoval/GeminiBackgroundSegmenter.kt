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
        callback: (SegmentationResult?) -> Unit,
    ) {
        if (closed.get()) {
            frame.release()
            callback(null)
            return
        }

        if (apiKey.isBlank()) {
            if (fallbackEngine != null) {
                fallbackEngine.process(frame, timestampUs, callback)
            } else {
                frame.release()
                callback(null)
            }
            return
        }

        scope.launch {
            // When the fallback takes over it owns the frame until its own callback; releasing it
            // here let the next capture overwrite the bitmap while ML Kit was still reading it.
            var delegated = false
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
                    delegated = true
                    fallbackEngine.process(frame, timestampUs, callback)
                    return@launch
                } else {
                    callback(null)
                }
            } catch (_: Throwable) {
                if (fallbackEngine != null && !closed.get()) {
                    delegated = true
                    fallbackEngine.process(frame, timestampUs, callback)
                    return@launch
                } else {
                    callback(null)
                }
            } finally {
                if (!delegated) frame.release()
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
