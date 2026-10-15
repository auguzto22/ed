package com.termex.replay15.editor.backgroundremoval

import org.json.JSONArray
import org.json.JSONObject

internal const val GEMINI_SEGMENTATION_MODEL = "gemini-3.8-flash"

/** One subject returned by Gemini. Coordinates are normalized to 0..1 of the full image. */
data class GeminiSubject(
    val label: String,
    /** [xmin, ymin, xmax, ymax] normalized 0..1. */
    val box: FloatArray,
    /** Outer contour as (x, y) pairs normalized 0..1. */
    val polygon: List<Pair<Float, Float>>,
)

/**
 * Builds the Interactions API request used for every Gemini background removal.
 *
 * Follows the documented segmentation contract: a JSON list where each entry has `box_2d`
 * ([ymin, xmin, ymax, xmax], 0..1000), `mask` (polygon of [x, y], 0..1000) and `label`.
 */
object GeminiSegmentationRequest {

    val PROMPT: String = """
        Remove the background of this image.
        Give the segmentation masks for EVERY foreground subject: people (including hair, hands and
        clothing), animals, products and any other object that is the subject of the photo.
        Do not include floor, walls, sky, scenery, tables or anything that is background.
        Trace each mask tightly along the real outer edge of the subject with many points.
        Write every point of "mask" as [x, y] in the full image, normalized to 0-1000.
        Output a JSON list of segmentation masks where each entry contains the 2D bounding box in
        the key "box_2d", the segmentation mask in key "mask", and the text label in the key "label".
        Use descriptive labels.
    """.trimIndent()

    fun build(imageBase64: String, mimeType: String = "image/jpeg"): JSONObject {
        val intArray = JSONObject().put("type", "array").put("items", JSONObject().put("type", "integer"))
        val pointArray = JSONObject().put("type", "array").put("items", intArray)
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("boxes", JSONObject()
                .put("type", "array")
                .put("items", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("box_2d", intArray)
                        .put("mask", pointArray)
                        .put("label", JSONObject().put("type", "string")))
                    .put("required", JSONArray().put("box_2d").put("mask").put("label")))))
            .put("required", JSONArray().put("boxes"))
        return JSONObject()
            .put("model", GEMINI_SEGMENTATION_MODEL)
            .put("store", false)
            .put("input", JSONArray()
                .put(JSONObject().put("type", "text").put("text", PROMPT))
                .put(JSONObject().put("type", "image").put("data", imageBase64).put("mime_type", mimeType)))
            .put("response_format", JSONObject()
                .put("type", "text")
                .put("mime_type", "application/json")
                .put("schema", schema))
            .put("generation_config", JSONObject().put("thinking_level", "minimal"))
    }

    /** Extracts the model text from an Interactions (or legacy generateContent) response. */
    fun outputText(response: JSONObject): String {
        response.optString("output_text").takeIf(String::isNotBlank)?.let { return it }
        response.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
            ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
            ?.takeIf(String::isNotBlank)?.let { return it }
        response.optJSONArray("outputs")?.let { outputs ->
            for (i in outputs.length() - 1 downTo 0) {
                outputs.optJSONObject(i)?.optString("text")?.takeIf(String::isNotBlank)?.let { return it }
            }
        }
        return ""
    }
}

/** Parser for Gemini segmentation responses. Pure Kotlin so it is unit-testable. */
object GeminiSegmentationParser {

    /** Returns only subjects with a real traced contour. A box alone is never a cutout. */
    fun parseSubjects(text: String): List<GeminiSubject> = runCatching {
        val clean = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val entries: JSONArray = when {
            clean.startsWith("[") -> JSONArray(clean)
            clean.startsWith("{") -> {
                val root = JSONObject(clean)
                root.optJSONArray("boxes") ?: root.optJSONArray("masks") ?: JSONArray().put(root)
            }
            else -> return emptyList()
        }
        (0 until entries.length()).mapNotNull { index -> entries.optJSONObject(index)?.let(::parseSubject) }
    }.getOrDefault(emptyList())

    private fun parseSubject(item: JSONObject): GeminiSubject? {
        val boxJson = item.optJSONArray("box_2d") ?: return null
        if (boxJson.length() < 4) return null
        val ymin = norm(boxJson.optDouble(0, 0.0))
        val xmin = norm(boxJson.optDouble(1, 0.0))
        val ymax = norm(boxJson.optDouble(2, 1000.0)).coerceAtLeast(ymin)
        val xmax = norm(boxJson.optDouble(3, 1000.0)).coerceAtLeast(xmin)
        if (xmax - xmin <= 0f || ymax - ymin <= 0f) return null

        val maskJson = item.optJSONArray("mask") ?: return null
        val raw = ArrayList<Pair<Float, Float>>(maskJson.length())
        for (i in 0 until maskJson.length()) {
            val pt = maskJson.optJSONArray(i) ?: continue
            if (pt.length() < 2) continue
            raw += norm(pt.optDouble(0, 0.0)) to norm(pt.optDouble(1, 0.0))
        }
        if (raw.size < 3) return null

        // The docs describe the polygon as "inside the box" normalized to 0..1000. Normally that
        // is the full image frame; if the points clearly do not fit the box, they are box-relative.
        val tolerance = 0.03f
        fun fits(points: List<Pair<Float, Float>>) = points.all { (x, y) ->
            x in (xmin - tolerance)..(xmax + tolerance) && y in (ymin - tolerance)..(ymax + tolerance)
        }
        // Gemini writes boxes as [y, x] and, despite the prompt, sometimes the contour too. Read
        // as [x, y] such a contour is mirrored across the diagonal: it failed the box check and
        // was then stretched as if box-relative, cutting a shape that had nothing to do with the
        // subject. The order that lands inside the box wins; [x, y] when both (or neither) do.
        val swapped = raw.map { (a, b) -> b to a }
        val polygon = when {
            fits(raw) -> raw
            fits(swapped) -> swapped
            else -> raw.map { (x, y) -> (xmin + x * (xmax - xmin)) to (ymin + y * (ymax - ymin)) }
        }
        return GeminiSubject(item.optString("label"), floatArrayOf(xmin, ymin, xmax, ymax), polygon)
    }

    /** Rasterizes the union of all subject contours into a 0/1 mask (used by the video path). */
    fun rasterize(subjects: List<GeminiSubject>, width: Int, height: Int): FloatArray {
        val mask = FloatArray(width * height)
        val xs = FloatArray(1024)
        for (subject in subjects) {
            val poly = subject.polygon
            for (y in 0 until height) {
                val py = (y + 0.5f) / height
                var count = 0
                var j = poly.size - 1
                for (i in poly.indices) {
                    val (xi, yi) = poly[i]
                    val (xj, yj) = poly[j]
                    if ((yi > py) != (yj > py) && count < xs.size) {
                        xs[count++] = xi + (py - yi) * (xj - xi) / (yj - yi)
                    }
                    j = i
                }
                if (count < 2) continue
                xs.sort(0, count)
                var k = 0
                while (k + 1 < count) {
                    val start = (xs[k] * width - 0.5f).let { kotlin.math.ceil(it).toInt() }.coerceIn(0, width)
                    val end = (xs[k + 1] * width - 0.5f).let { kotlin.math.floor(it).toInt() }.coerceIn(-1, width - 1)
                    for (x in start..end) mask[y * width + x] = 1f
                    k += 2
                }
            }
        }
        return mask
    }

    fun parseSegmentation(text: String, width: Int, height: Int, timestampUs: Long): SegmentationResult? {
        if (width <= 0 || height <= 0) return null
        val subjects = parseSubjects(text)
        if (subjects.isEmpty()) return null
        return SegmentationResult(width, height, timestampUs, rasterize(subjects, width, height), SegmentationOrigin.MEASURED)
    }

    private fun norm(value: Double): Float = (value.toFloat() / 1000f).coerceIn(0f, 1f)
}
