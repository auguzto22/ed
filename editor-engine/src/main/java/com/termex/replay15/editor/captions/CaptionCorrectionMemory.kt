package com.termex.replay15.editor.captions

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LearnedCorrection(val recognized: String, val corrected: String,
    val contextBefore: String, val contextAfter: String, val occurrences: Int,
    val audioSegment: String? = null, val errorType: CaptionErrorType? = null,
    val modelVersion: String? = null, val startMs: Long? = null, val endMs: Long? = null)

enum class CaptionErrorType {
    FOREIGN_WORD, PTBR_SLANG, PROPER_NAME, BRAND, PHONETIC_CONFUSION,
    SEGMENT_BOUNDARY, LOW_CONFIDENCE, HALLUCINATION, MISSING_WORD, REPEATED_WORD
}

data class CaptionCorrectionRecord(
    val audioSegment: String? = null,
    val prediction: String,
    val correctText: String,
    val errorType: CaptionErrorType = CaptionErrorType.PHONETIC_CONFUSION,
    val modelVersion: String? = null,
    val startMs: Long? = null,
    val endMs: Long? = null,
)

/** Local project memory; a correction becomes a rescore hint only after repeated use. */
class CaptionCorrectionMemory(private val file: File) {
    private val entries = mutableListOf<LearnedCorrection>()

    init {
        if (file.isFile) runCatching {
            val array = JSONArray(file.readText())
            repeat(array.length().coerceAtMost(500)) { index ->
                val item = array.getJSONObject(index)
                entries += LearnedCorrection(item.getString("recognized"), item.getString("corrected"),
                    item.optString("before"), item.optString("after"), item.getInt("occurrences"),
                    item.optString("audioSegment").takeIf(String::isNotBlank),
                    item.optString("errorType").takeIf(String::isNotBlank)?.let { runCatching { CaptionErrorType.valueOf(it) }.getOrNull() },
                    item.optString("modelVersion").takeIf(String::isNotBlank),
                    item.optLong("startMs", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE },
                    item.optLong("endMs", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE })
            }
        }
    }

    @Synchronized fun record(original: String, edited: String) {
        record(CaptionCorrectionRecord(prediction = original, correctText = edited))
    }

    @Synchronized fun record(correction: CaptionCorrectionRecord) {
        val original = correction.prediction
        val edited = correction.correctText
        val a = original.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        val b = edited.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        if (a == b || a.isEmpty() || b.isEmpty()) return
        val prefix = a.zip(b).takeWhile { it.first.equals(it.second, true) }.size
        val suffix = a.drop(prefix).reversed().zip(b.drop(prefix).reversed())
            .takeWhile { it.first.equals(it.second, true) }.size
        val recognized = a.subList(prefix, a.size - suffix).joinToString(" ")
        val corrected = b.subList(prefix, b.size - suffix).joinToString(" ")
        if (recognized.isBlank() || corrected.isBlank() || recognized.length > 80 || corrected.length > 80) return
        val before = a.take(prefix).takeLast(2).joinToString(" ").lowercase()
        val after = a.takeLast(suffix).take(2).joinToString(" ").lowercase()
        val existing = entries.indexOfFirst { it.recognized.equals(recognized, true) &&
            it.corrected.equals(corrected, true) && it.contextBefore == before && it.contextAfter == after }
        if (existing >= 0) entries[existing] = entries[existing].copy(occurrences =
            (entries[existing].occurrences + 1).coerceAtMost(1000))
        else if (entries.size < 500) entries += LearnedCorrection(recognized, corrected, before, after, 1,
            correction.audioSegment, correction.errorType, correction.modelVersion, correction.startMs, correction.endMs)
        save()
    }

    /** Only repeated, human-provided corrections become future context terms. */
    @Synchronized fun confirmedTerms(): Set<String> = entries.asSequence()
        .filter { it.occurrences >= 2 }
        .flatMap { it.corrected.split(Regex("\\s+")).asSequence() }
        .filter { it.isNotBlank() }
        .toSet()

    @Synchronized fun corrections(): List<LearnedCorrection> = entries.toList()

    @Synchronized fun bonus(recognized: String, corrected: String, before: String?, after: String?): Float {
        val left = before.orEmpty().lowercase()
        val right = after.orEmpty().lowercase()
        val relevant = entries.filter { it.recognized.equals(recognized, true) &&
            it.corrected.equals(corrected, true) && it.occurrences >= 2 &&
            (it.contextBefore.isEmpty() || it.contextBefore.endsWith(left)) &&
            (it.contextAfter.isEmpty() || it.contextAfter.startsWith(right)) }
        return if (relevant.isEmpty()) 0f else .03f.coerceAtMost(relevant.maxOf { it.occurrences } * .015f)
    }

    private fun save() {
        file.parentFile?.mkdirs()
        val array = JSONArray()
        entries.forEach { item -> array.put(JSONObject().apply {
            put("recognized", item.recognized); put("corrected", item.corrected)
            put("before", item.contextBefore); put("after", item.contextAfter)
            put("occurrences", item.occurrences)
            item.audioSegment?.let { put("audioSegment", it) }
            item.errorType?.let { put("errorType", it.name) }
            item.modelVersion?.let { put("modelVersion", it) }
            item.startMs?.let { put("startMs", it) }
            item.endMs?.let { put("endMs", it) }
        }) }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(array.toString())
        if (!temporary.renameTo(file)) {
            file.writeText(array.toString())
            temporary.delete()
        }
    }
}
