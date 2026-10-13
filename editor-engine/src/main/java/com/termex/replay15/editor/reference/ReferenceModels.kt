package com.termex.replay15.editor.reference

import com.termex.replay15.editor.domain.TextAnimation
import com.termex.replay15.editor.domain.TextFont
import org.json.JSONArray
import org.json.JSONObject

// 2: vertical-video sampling fix, punch-in zooms, layout layer (titles, cards, overlays, B-roll).
const val REFERENCE_ANALYSIS_VERSION = 2

enum class ReferenceEventType { HARD_CUT, FADE, CROSSFADE, MOTION_TRANSITION, ZOOM, SHAKE, FLASH, BLUR, RGB_SPLIT, SPEED_RAMP, TEXT, UNKNOWN_EFFECT }
enum class ReferenceIntensity { SUBTLE, BALANCED, STRONG }

data class ReferenceMediaInfo(
    val uri: String, val displayName: String, val durationUs: Long, val width: Int, val height: Int,
    val rotation: Int, val frameRate: Float, val hasAudio: Boolean, val sizeBytes: Long, val modifiedMs: Long,
    val fingerprint: String,
)

data class ReferenceEvent(
    val type: ReferenceEventType, val startUs: Long, val peakUs: Long = startUs, val endUs: Long = startUs + 1,
    val strength: Float, val confidence: Float, val values: Map<String, Float> = emptyMap(),
    val structuralDescription: String = "",
) {
    init {
        require(startUs >= 0 && peakUs in startUs..endUs && endUs > startUs)
        require(strength.isFinite() && confidence in 0f..1f && values.values.all(Float::isFinite))
    }
}

data class BeatEvent(val timeUs: Long, val strength: Float, val confidence: Float) {
    init { require(timeUs >= 0 && strength >= 0f && confidence in 0f..1f) }
}

data class CaptionStyleProfile(
    val present: Boolean = false, val x: Float = .5f, val y: Float = .78f, val relativeHeight: Float = .06f,
    val fillColor: Int = -1, val outlineColor: Int = 0xFF000000.toInt(), val hasBackground: Boolean = false,
    val bold: Boolean = true, val italic: Boolean = false, val family: String = "sans-serif",
    val closestFont: TextFont = TextFont.MONTSERRAT, val fontMatchConfidence: Float = 0f,
    val animation: TextAnimation = TextAnimation.NONE, val averageWords: Float = 0f, val medianWords: Int = 0,
    val confidence: Float = 0f, val uppercase: Boolean = false,
)

data class EffectCombination(
    val types: List<ReferenceEventType>, val occurrences: Int, val medianOffsetsUs: List<Long>, val confidence: Float,
)

data class ReferenceStyleProfile(
    val id: String, val name: String, val sourceVideoFingerprint: String, val sourceUri: String,
    val analysisVersion: Int = REFERENCE_ANALYSIS_VERSION, val createdAtMs: Long,
    val sourceDurationUs: Long, val cutCount: Int, val averageCutIntervalUs: Long, val medianCutIntervalUs: Long,
    val cutIntervalDistributionUs: List<Long>, val dominantCutType: ReferenceEventType?,
    val events: List<ReferenceEvent>, val beats: List<BeatEvent>, val zoomsPerMinute: Float,
    val medianZoomScale: Float, val medianShakeDurationUs: Long, val medianFlashDurationUs: Long,
    val speedRampUsage: Float, val captionStyle: CaptionStyleProfile,
    val beatAlignment: Float, val effectCombinations: List<EffectCombination>,
    val colorSaturation: Float = 0f, val colorContrast: Float = 0f, val colorTemperature: Float = 0f,
    val overallConfidence: Float, val warnings: List<String> = emptyList(),
    /** Graphics on top of the footage and cutaways; see [ReferenceLayoutElement]. */
    val layout: List<ReferenceLayoutElement> = emptyList(),
    /** True when the semantic (Gemini) pass contributed to this profile. */
    val semanticAnalysis: Boolean = false,
    val styleNotes: String = "",
) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")) && name.isNotBlank() && name.length <= 100)
        require(analysisVersion > 0 && sourceDurationUs > 0 && overallConfidence in 0f..1f)
        require(events.size <= 4000 && beats.size <= 12000 && layout.size <= 60 && styleNotes.length <= 400)
    }

    fun summary(): List<String> = buildList {
        if (cutCount == 0 && events.none { it.type != ReferenceEventType.TEXT } && layout.isEmpty() && !captionStyle.present) add("Edicao minima detectada")
        if (cutCount > 0) add(if (medianCutIntervalUs < 2_000_000) "Cortes rapidos" else "Cortes espacados")
        if (events.any { it.type == ReferenceEventType.ZOOM }) add("Punch zoom")
        if (events.any { it.type == ReferenceEventType.SHAKE }) add("Impact shake")
        if (events.any { it.type == ReferenceEventType.FLASH }) add("Flash")
        if (events.any { it.type == ReferenceEventType.RGB_SPLIT }) add("Separacao RGB")
        if (captionStyle.present) add("Legendas ${captionStyle.family}")
        if (beatAlignment >= .55f) add("Efeitos sincronizados ao beat")
        layout.groupingBy { it.kind }.eachCount().forEach { (kind, count) ->
            add(if (count == 1) kind.label else "${kind.label} ($count)")
        }
    }

    /** One readable line per layout element, for the profile sheet. */
    fun layoutLines(): List<String> = layout.map { element ->
        val where = when {
            element.kind == ReferenceLayoutKind.BROLL -> "tela cheia"
            element.box.y < .33f -> "topo"
            element.box.y > .66f -> "embaixo"
            else -> "meio"
        }
        val text = element.label.takeIf(String::isNotBlank)?.let { " \"${it.take(40)}\"" }.orEmpty()
        "%s · %s · %.1f–%.1fs%s".format(element.kind.label, where, element.startUs / 1e6, element.endUs / 1e6, text)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("sourceVideoFingerprint", sourceVideoFingerprint); put("sourceUri", sourceUri)
        put("analysisVersion", analysisVersion); put("createdAtMs", createdAtMs); put("sourceDurationUs", sourceDurationUs)
        put("cutCount", cutCount); put("averageCutIntervalUs", averageCutIntervalUs); put("medianCutIntervalUs", medianCutIntervalUs)
        put("cutIntervalDistributionUs", JSONArray(cutIntervalDistributionUs)); put("dominantCutType", dominantCutType?.name)
        put("events", JSONArray(events.map { eventJson(it) })); put("beats", JSONArray(beats.map { beatJson(it) }))
        put("zoomsPerMinute", zoomsPerMinute); put("medianZoomScale", medianZoomScale)
        put("medianShakeDurationUs", medianShakeDurationUs); put("medianFlashDurationUs", medianFlashDurationUs)
        put("speedRampUsage", speedRampUsage); put("captionStyle", captionJson(captionStyle)); put("beatAlignment", beatAlignment)
        put("effectCombinations", JSONArray(effectCombinations.map { combinationJson(it) }))
        put("colorSaturation", colorSaturation); put("colorContrast", colorContrast); put("colorTemperature", colorTemperature)
        put("overallConfidence", overallConfidence); put("warnings", JSONArray(warnings))
        put("layout", JSONArray(layout.map { it.toJson() })); put("semanticAnalysis", semanticAnalysis); put("styleNotes", styleNotes)
    }

    companion object {
        fun fromJson(json: JSONObject): ReferenceStyleProfile = ReferenceStyleProfile(
            id = json.getString("id"), name = json.getString("name"), sourceVideoFingerprint = json.getString("sourceVideoFingerprint"),
            sourceUri = json.optString("sourceUri"), analysisVersion = json.getInt("analysisVersion"), createdAtMs = json.getLong("createdAtMs"),
            sourceDurationUs = json.getLong("sourceDurationUs"), cutCount = json.getInt("cutCount"),
            averageCutIntervalUs = json.getLong("averageCutIntervalUs"), medianCutIntervalUs = json.getLong("medianCutIntervalUs"),
            cutIntervalDistributionUs = json.getJSONArray("cutIntervalDistributionUs").longs(),
            dominantCutType = json.optString("dominantCutType").takeIf(String::isNotBlank)?.let(ReferenceEventType::valueOf),
            events = json.getJSONArray("events").objects(::eventFromJson), beats = json.getJSONArray("beats").objects(::beatFromJson),
            zoomsPerMinute = json.getDouble("zoomsPerMinute").toFloat(), medianZoomScale = json.getDouble("medianZoomScale").toFloat(),
            medianShakeDurationUs = json.getLong("medianShakeDurationUs"), medianFlashDurationUs = json.getLong("medianFlashDurationUs"),
            speedRampUsage = json.getDouble("speedRampUsage").toFloat(), captionStyle = captionFromJson(json.getJSONObject("captionStyle")),
            beatAlignment = json.getDouble("beatAlignment").toFloat(),
            effectCombinations = json.getJSONArray("effectCombinations").objects(::combinationFromJson),
            colorSaturation = json.optDouble("colorSaturation", 0.0).toFloat(), colorContrast = json.optDouble("colorContrast", 0.0).toFloat(),
            colorTemperature = json.optDouble("colorTemperature", 0.0).toFloat(), overallConfidence = json.getDouble("overallConfidence").toFloat(),
            warnings = json.getJSONArray("warnings").strings(),
            layout = layoutFromJson(json.optJSONArray("layout")), semanticAnalysis = json.optBoolean("semanticAnalysis", false),
            styleNotes = json.optString("styleNotes").take(400),
        )
    }
}

private fun eventJson(e: ReferenceEvent) = JSONObject().apply { put("type", e.type.name); put("startUs", e.startUs); put("peakUs", e.peakUs); put("endUs", e.endUs); put("strength", e.strength); put("confidence", e.confidence); put("values", JSONObject(e.values)); put("structuralDescription", e.structuralDescription) }
private fun eventFromJson(j: JSONObject) = ReferenceEvent(ReferenceEventType.valueOf(j.getString("type")), j.getLong("startUs"), j.getLong("peakUs"), j.getLong("endUs"), j.getDouble("strength").toFloat(), j.getDouble("confidence").toFloat(), j.getJSONObject("values").let { o -> o.keys().asSequence().associateWith { o.getDouble(it).toFloat() } }, j.optString("structuralDescription"))
private fun beatJson(b: BeatEvent) = JSONObject().apply { put("timeUs", b.timeUs); put("strength", b.strength); put("confidence", b.confidence) }
private fun beatFromJson(j: JSONObject) = BeatEvent(j.getLong("timeUs"), j.getDouble("strength").toFloat(), j.getDouble("confidence").toFloat())
private fun captionJson(c: CaptionStyleProfile) = JSONObject().apply { put("present", c.present); put("x", c.x); put("y", c.y); put("relativeHeight", c.relativeHeight); put("fillColor", c.fillColor); put("outlineColor", c.outlineColor); put("hasBackground", c.hasBackground); put("bold", c.bold); put("italic", c.italic); put("family", c.family); put("closestFont", c.closestFont.name); put("fontMatchConfidence", c.fontMatchConfidence); put("animation", c.animation.name); put("averageWords", c.averageWords); put("medianWords", c.medianWords); put("confidence", c.confidence); put("uppercase", c.uppercase) }
private fun captionFromJson(j: JSONObject) = CaptionStyleProfile(j.getBoolean("present"), j.getDouble("x").toFloat(), j.getDouble("y").toFloat(), j.getDouble("relativeHeight").toFloat(), j.getInt("fillColor"), j.getInt("outlineColor"), j.getBoolean("hasBackground"), j.getBoolean("bold"), j.getBoolean("italic"), j.getString("family"), TextFont.valueOf(j.getString("closestFont")), j.getDouble("fontMatchConfidence").toFloat(), TextAnimation.valueOf(j.getString("animation")), j.getDouble("averageWords").toFloat(), j.getInt("medianWords"), j.getDouble("confidence").toFloat(), j.optBoolean("uppercase", false))
private fun combinationJson(c: EffectCombination) = JSONObject().apply { put("types", JSONArray(c.types.map(Enum<*>::name))); put("occurrences", c.occurrences); put("medianOffsetsUs", JSONArray(c.medianOffsetsUs)); put("confidence", c.confidence) }
private fun combinationFromJson(j: JSONObject) = EffectCombination(j.getJSONArray("types").strings().map(ReferenceEventType::valueOf), j.getInt("occurrences"), j.getJSONArray("medianOffsetsUs").longs(), j.getDouble("confidence").toFloat())
private fun JSONArray.strings() = List(length()) { getString(it) }
private fun JSONArray.longs() = List(length()) { getLong(it) }
private fun <T> JSONArray.objects(read: (JSONObject) -> T) = List(length()) { read(getJSONObject(it)) }
