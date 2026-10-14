package com.termex.replay15.editor.autoedit

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Prompt and strict parsing for the AI B-roll pass. The model only proposes which spoken word
 * deserves a picture and how to search for it; timing is snapped back to the measured word, so a
 * drifting timestamp from the model can never move an image away from the speech.
 */
object BrollSuggestionParser {
    private const val SNAP_WINDOW_US = 1_500_000L
    private const val LEAD_US = 80_000L
    private val queryPattern = Regex("^[a-z0-9 ,'-]{2,60}$")

    /** Transcript as numbered lines with start times, the only thing sent to the model. */
    fun transcriptLines(words: List<TimedWord>): List<String> {
        val lines = mutableListOf<String>()
        var current = mutableListOf<TimedWord>()
        fun flush() {
            if (current.isEmpty()) return
            lines += "[%.2f] %s".format(Locale.US, current.first().startUs / 1e6, current.joinToString(" ") { it.text.trim() })
            current = mutableListOf()
        }
        words.forEach { word ->
            val gap = current.lastOrNull()?.let { word.startUs - it.endUs } ?: 0L
            if (current.size >= 12 || gap > 600_000L) flush()
            current += word
        }
        flush()
        return lines
    }

    fun prompt(words: List<TimedWord>, durationUs: Long, maxImages: Int, languageCode: String): String = """
        You edit short vertical podcast clips. Each line below is speech from the clip, prefixed with its start time in seconds.
        Spoken language: $languageCode. Clip length: ${"%.2f".format(Locale.US, durationUs / 1e6)} seconds.

        Pick at most $maxImages moments where the speaker mentions something concrete and easy to photograph
        (money, a job, a car, a house, a faucet, food, a phone, a city...). Each moment shows a full-screen stock photo for 1 to 2 seconds.
        Skip abstract ideas, feelings, names of people, brands, and anything sensitive, violent or sexual.
        Keep at least 3 seconds between moments and nothing in the first 3 seconds.

        Answer only with JSON in this exact shape:
        {"broll":[{"start":12.3,"end":13.8,"keyword":"<the exact spoken word>","query":"<2 to 4 English words for a stock photo search>"}]}

        Transcript:
    """.trimIndent() + "\n" + transcriptLines(words).joinToString("\n")

    /**
     * Parses the model answer. Entries with an unknown word, an invalid query or a time outside the
     * clip are dropped; the rest are snapped to the start of the matching spoken word.
     */
    fun parse(text: String, words: List<TimedWord>, durationUs: Long): List<BrollSuggestion> {
        val json = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val array: JSONArray = when {
            json.startsWith("[") -> JSONArray(json)
            json.startsWith("{") -> JSONObject(json).optJSONArray("broll") ?: JSONArray()
            else -> return emptyList()
        }
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val keyword = item.optString("keyword").trim().take(60)
            val query = item.optString("query").trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
            val startSec = item.optDouble("start", Double.NaN)
            val endSec = item.optDouble("end", Double.NaN)
            if (keyword.isBlank() || !queryPattern.matches(query) || !startSec.isFinite()) return@mapNotNull null
            val proposedStart = (startSec * 1e6).toLong()
            if (proposedStart !in 0 until durationUs) return@mapNotNull null
            val spoken = snap(keyword, proposedStart, words) ?: return@mapNotNull null
            val proposedLength = if (endSec.isFinite() && endSec > startSec) ((endSec - startSec) * 1e6).toLong() else BrollPlanner.DEFAULT_DURATION_US
            val length = proposedLength.coerceIn(BrollPlanner.MIN_DURATION_US, BrollPlanner.MAX_DURATION_US)
            val start = (spoken.startUs - LEAD_US).coerceAtLeast(0L)
            val end = minOf(start + length, durationUs)
            if (end <= start) null else BrollSuggestion(start, end, PodcastCaptions.displayWord(spoken.text).ifBlank { keyword }, query, .85f)
        }
    }

    /** The spoken word the model meant: same word (accents/case ignored) closest to its time. */
    private fun snap(keyword: String, aroundUs: Long, words: List<TimedWord>): TimedWord? {
        // A multi-word keyword ("fecha o ralo") is matched by its most meaningful (longest) word.
        val wanted = BrollPlanner.normalize(keyword).split(' ').maxByOrNull { it.length }.orEmpty()
        if (wanted.length < 2) return null
        return words.asSequence()
            .filter { kotlin.math.abs(it.startUs - aroundUs) <= SNAP_WINDOW_US }
            .filter { word ->
                val spoken = BrollPlanner.normalize(word.text)
                // Same word, or the same stem for plurals/conjugations (trabalho / trabalhar).
                spoken == wanted || (wanted.length >= 5 && spoken.length >= 5 && spoken.take(5) == wanted.take(5))
            }
            .minByOrNull { kotlin.math.abs(it.startUs - aroundUs) }
    }
}
