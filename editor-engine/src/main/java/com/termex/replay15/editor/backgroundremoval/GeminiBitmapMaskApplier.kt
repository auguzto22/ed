package com.termex.replay15.editor.backgroundremoval

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.termex.replay15.editor.ai.GeminiApiKeyConfig
import com.termex.replay15.editor.captions.GeminiHttpClient
import com.termex.replay15.editor.domain.BackgroundMode
import com.termex.replay15.editor.domain.BackgroundRemovalEffect
import com.termex.replay15.editor.domain.BackgroundRemovalProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val GEMINI_MODEL = "gemini-3.8-flash"

/**
 * Bitmap background remover supporting Gemini AI and local ML Kit fallback.
 * Applies foreground isolation for stickers, photos, and still-frame overlays.
 */
class GeminiBitmapMaskApplier(
    context: Context,
    private val apiKey: String = GeminiApiKeyConfig.getApiKey(),
) : AutoCloseable {

    private val mlKitApplier = MlKitBitmapMaskApplier(context)
    private val http = GeminiHttpClient(apiKey)

    fun apply(source: Bitmap, effect: BackgroundRemovalEffect): Bitmap? {
        if (!effect.enabled || effect.mode != BackgroundMode.REMOVE) return source

        if (effect.provider == BackgroundRemovalProvider.MLKIT || apiKey.isBlank()) {
            return mlKitApplier.apply(source, effect)
        }

        // Try Gemini segmentation first
        val geminiResult = runCatching { applyWithGemini(source, effect) }.getOrNull()
        if (geminiResult != null) return geminiResult

        // If provider is AUTO or Gemini failed, fallback to local ML Kit
        if (effect.provider == BackgroundRemovalProvider.AUTO) {
            return mlKitApplier.apply(source, effect)
        }

        // If explicitly set to GEMINI and failed, also fallback gracefully rather than returning null
        return mlKitApplier.apply(source, effect) ?: source
    }

    private fun applyWithGemini(source: Bitmap, effect: BackgroundRemovalEffect): Bitmap? {
        val maxDim = 512
        val scale = min(1f, maxDim.toFloat() / max(source.width, source.height))
        val targetW = (source.width * scale).roundToInt().coerceAtLeast(64)
        val targetH = (source.height * scale).roundToInt().coerceAtLeast(64)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(source, targetW, targetH, true)
        } else {
            source
        }

        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        if (scaled != source) scaled.recycle()
        val imageBase64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

        val prompt = """
            Identify and segment the primary foreground subject(s) in this image.
            Return ONLY a JSON object with:
            1. "box_2d": [ymin, xmin, ymax, xmax] (integers 0 to 1000).
            2. "polygons": an array of polygon contours, each being an array of [y, x] coordinate pairs (0 to 1000) tracing the subject's outer edge.
            Format: {"box_2d": [ymin, xmin, ymax, xmax], "polygons": [[[y, x], ...]]}
        """.trimIndent()

        val payload = JSONObject()
            .put("model", GEMINI_MODEL)
            .put("store", false)
            .put("input", prompt)
            .put("contents", JSONArray().put(JSONObject()
                .put("parts", JSONArray()
                    .put(JSONObject().put("text", prompt))
                    .put(JSONObject().put("inline_data", JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", imageBase64))))))
            .put("generationConfig", JSONObject().put("response_mime_type", "application/json"))

        val resp = http.withRetry({}) {
            try {
                http.postJson("/v1beta/models/$GEMINI_MODEL:generateContent", payload)
            } catch (_: Throwable) {
                http.createInteraction(payload) {}
            }
        }

        val candidates = resp.optJSONArray("candidates")
        val rawText = if (candidates != null && candidates.length() > 0) {
            candidates.getJSONObject(0).optJSONObject("content")
                ?.optJSONArray("parts")?.getJSONObject(0)?.optString("text")
        } else {
            resp.optString("output_text").takeIf(String::isNotBlank)
        } ?: resp.toString()

        val segResult = GeminiSegmentationParser.parseSegmentation(rawText, source.width, source.height, 0L)
            ?: return null

        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val mask = segResult.mask

        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val index = y * source.width + x
                val confidence = mask[index]
                val alpha = (smoothstep(effect.threshold - effect.feather, effect.threshold + effect.feather, confidence) *
                    ((pixels[index] ushr 24) / 255f) * 255f).roundToInt().coerceIn(0, 255)
                pixels[index] = (pixels[index] and 0x00FFFFFF) or (alpha shl 24)
            }
        }

        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        return output
    }

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0).coerceAtLeast(.001f)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    override fun close() {
        mlKitApplier.close()
    }
}
