package com.termex.replay15.editor.backgroundremoval

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.Base64
import com.termex.replay15.editor.ai.GeminiApiKeyConfig
import com.termex.replay15.editor.captions.GeminiHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val GEMINI_SEGMENTATION_MODEL = "gemini-3.8-flash"

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

                val prompt = """
                    Segment the main foreground subject(s) (person, character, animal, or object) from the background.
                    Return ONLY valid JSON with:
                    1. "box_2d": [ymin, xmin, ymax, xmax] coordinates normalized from 0 to 1000.
                    2. "polygons": an array of polygon contours, each being an array of [y, x] coordinate pairs normalized from 0 to 1000 tracing the subject boundary.
                    Format:
                    {"box_2d": [100, 200, 900, 800], "polygons": [[[100, 200], [120, 250], [900, 800]]]}
                """.trimIndent()

                val payload = JSONObject()
                    .put("model", GEMINI_SEGMENTATION_MODEL)
                    .put("store", false)
                    .put("input", prompt)
                    .put("contents", JSONArray().put(JSONObject()
                        .put("parts", JSONArray()
                            .put(JSONObject().put("text", prompt))
                            .put(JSONObject().put("inline_data", JSONObject()
                                .put("mime_type", "image/jpeg")
                                .put("data", imageBase64))))))
                    .put("generationConfig", JSONObject().put("response_mime_type", "application/json"))

                var rawResponse: String? = null
                try {
                    val resp = http.withRetry({ if (closed.get()) throw RuntimeException("Cancelled") }) {
                        try {
                            http.postJson("/v1beta/models/$GEMINI_SEGMENTATION_MODEL:generateContent", payload)
                        } catch (_: Throwable) {
                            http.createInteraction(payload) { if (closed.get()) throw RuntimeException("Cancelled") }
                        }
                    }
                    rawResponse = extractText(resp)
                } catch (_: Throwable) {
                    // Handled below via fallback
                }

                val result = rawResponse?.let {
                    GeminiSegmentationParser.parseSegmentation(it, targetW, targetH, timestampUs)
                }

                if (result != null) {
                    callback(result)
                } else if (fallbackEngine != null && !closed.get()) {
                    fallbackEngine.process(frame, timestampUs, callback)
                    return@launch
                } else {
                    callback(null)
                }
            } catch (_: Throwable) {
                if (fallbackEngine != null && !closed.get()) {
                    fallbackEngine.process(frame, timestampUs, callback)
                    return@launch
                } else {
                    callback(null)
                }
            } finally {
                frame.release()
            }
        }
    }

    private fun extractText(response: JSONObject): String {
        val candidates = response.optJSONArray("candidates")
        if (candidates != null && candidates.length() > 0) {
            val candidate = candidates.getJSONObject(0)
            val content = candidate.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            if (parts != null && parts.length() > 0) {
                val text = parts.getJSONObject(0).optString("text")
                if (text.isNotBlank()) return text
            }
        }
        val direct = response.optString("output_text")
        if (direct.isNotBlank()) return direct
        return response.toString()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            scope.cancel()
            fallbackEngine?.close()
        }
    }
}

/**
 * Parser for Gemini foreground segmentation responses.
 * Can parse polygons, bounding boxes, or base64 masks into [SegmentationResult].
 */
object GeminiSegmentationParser {

    fun parseSegmentation(
        jsonString: String,
        targetWidth: Int,
        targetHeight: Int,
        timestampUs: Long,
    ): SegmentationResult? {
        return runCatching {
            val cleanJson = jsonString
                .removePrefix("```").removePrefix("json").removeSuffix("```").trim()
            val startIdx = cleanJson.indexOf('{')
            val endIdx = cleanJson.lastIndexOf('}')
            if (startIdx < 0 || endIdx <= startIdx) return null
            val obj = JSONObject(cleanJson.substring(startIdx, endIdx + 1))

            val polygons = obj.optJSONArray("polygons")
            val box = obj.optJSONArray("box_2d") ?: obj.optJSONArray("box")

            val parsedPolys = mutableListOf<List<Pair<Float, Float>>>()
            if (polygons != null && polygons.length() > 0) {
                for (p in 0 until polygons.length()) {
                    val poly = polygons.optJSONArray(p) ?: continue
                    if (poly.length() < 3) continue
                    val points = mutableListOf<Pair<Float, Float>>()
                    for (v in 0 until poly.length()) {
                        val pt = poly.optJSONArray(v) ?: continue
                        val yNorm = pt.optDouble(0, 0.0).toFloat().coerceIn(0f, 1000f)
                        val xNorm = pt.optDouble(1, 0.0).toFloat().coerceIn(0f, 1000f)
                        val px = (xNorm / 1000f) * targetWidth
                        val py = (yNorm / 1000f) * targetHeight
                        points.add(px to py)
                    }
                    if (points.size >= 3) {
                        parsedPolys.add(points)
                    }
                }
            }

            var parsedBox: FloatArray? = null
            if (box != null && box.length() >= 4) {
                val ymin = ((box.optDouble(0, 0.0).toFloat() / 1000f) * targetHeight).coerceIn(0f, targetHeight.toFloat())
                val xmin = ((box.optDouble(1, 0.0).toFloat() / 1000f) * targetWidth).coerceIn(0f, targetWidth.toFloat())
                val ymax = ((box.optDouble(2, 1000.0).toFloat() / 1000f) * targetHeight).coerceIn(ymin, targetHeight.toFloat())
                val xmax = ((box.optDouble(3, 1000.0).toFloat() / 1000f) * targetWidth).coerceIn(xmin, targetWidth.toFloat())
                parsedBox = floatArrayOf(xmin, ymin, xmax, ymax)
            }

            if (parsedPolys.isEmpty() && parsedBox == null) {
                return null
            }

            val totalPixels = targetWidth * targetHeight
            val floatMask = FloatArray(totalPixels)

            if (parsedPolys.isNotEmpty()) {
                for (y in 0 until targetHeight) {
                    val py = y + 0.5f
                    val rowOffset = y * targetWidth
                    for (x in 0 until targetWidth) {
                        val px = x + 0.5f
                        var inside = false
                        for (poly in parsedPolys) {
                            if (pointInPolygon(px, py, poly)) {
                                inside = true
                                break
                            }
                        }
                        floatMask[rowOffset + x] = if (inside) 1.0f else 0.0f
                    }
                }
            } else if (parsedBox != null) {
                val xmin = parsedBox[0]
                val ymin = parsedBox[1]
                val xmax = parsedBox[2]
                val ymax = parsedBox[3]
                val cx = (xmin + xmax) / 2f
                val cy = (ymin + ymax) / 2f
                val rx = (xmax - xmin) / 2f
                val ry = (ymax - ymin) / 2f
                if (rx > 0 && ry > 0) {
                    for (y in 0 until targetHeight) {
                        val py = y + 0.5f
                        val dy = (py - cy) / ry
                        val rowOffset = y * targetWidth
                        for (x in 0 until targetWidth) {
                            val px = x + 0.5f
                            val dx = (px - cx) / rx
                            val distSq = dx * dx + dy * dy
                            floatMask[rowOffset + x] = if (distSq <= 1.0f) 1.0f else 0.0f
                        }
                    }
                }
            }

            SegmentationResult(
                width = targetWidth,
                height = targetHeight,
                timestampUs = timestampUs,
                mask = floatMask,
            )
        }.getOrNull()
    }

    private fun pointInPolygon(px: Float, py: Float, poly: List<Pair<Float, Float>>): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val xi = poly[i].first
            val yi = poly[i].second
            val xj = poly[j].first
            val yj = poly[j].second
            val intersect = ((yi > py) != (yj > py)) && (px < (xj - xi) * (py - yi) / (yj - yi) + xi)
            if (intersect) inside = !inside
            j = i
        }
        return inside
    }
}
