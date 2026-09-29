package com.termex.replay15.editor.captions

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A hypothesis can suggest a word; independent timing agreement and audio activity must confirm it. */
class TranscriptDifferenceDetector {
    fun candidates(gap: SpeechGap, alternatives: List<List<CaptionWord>>): List<CaptionWord> = alternatives
        .flatten().filter { it.startMs < gap.endMs && it.endMs > gap.startMs && it.text.isNotBlank() }
        .filter { candidate ->
            gap.previousWord?.let { candidate.text.equals(it.text, true) && temporalOverlap(candidate, it) > .25f } != true &&
            gap.nextWord?.let { candidate.text.equals(it.text, true) && temporalOverlap(candidate, it) > .25f } != true
        }
}

fun temporalOverlap(a: CaptionWord, b: CaptionWord): Float {
    val overlap = (min(a.endMs, b.endMs) - max(a.startMs, b.startMs)).coerceAtLeast(0)
    return overlap.toFloat() / min(a.endMs - a.startMs, b.endMs - b.startMs).coerceAtLeast(1)
}

class MissingWordRecoveryEngine(
    private val transcribe: suspend (SpeechRegion, AudioVariant) -> List<CaptionWord>,
    private val config: MissingWordConfig = MissingWordConfig()
) {
    /** Scores shared local hypotheses so nearby gaps need only two recognizer calls total. */
    fun recoverFromHypotheses(gap: SpeechGap, vad: VadTimeline,
        processed: List<CaptionWord>, raw: List<CaptionWord>): List<CaptionWord> =
        chooseCandidates(gap, vad, listOf(processed, raw), setOf(1))

    suspend fun recover(gap: SpeechGap, region: SpeechRegion, durationMs: Long, vad: VadTimeline,
        secondary: List<CaptionWord>, cancel: () -> Unit = {}): List<CaptionWord> {
        val windows = listOf(
            SpeechRegion(max(0, gap.startMs - config.contextShortMs), min(durationMs, gap.endMs + config.contextShortMs), region.confidence) to AudioVariant.PROCESSED,
            SpeechRegion(max(0, gap.startMs - config.contextLongMs), min(durationMs, gap.endMs + config.contextLongMs), region.confidence) to AudioVariant.RAW
        ).filter { it.first.endMs > it.first.startMs }.distinct().take(config.maxRetries)
        val attempts = mutableListOf<List<CaptionWord>>()
        for ((index, request) in windows.withIndex()) {
            cancel()
            CaptionDebugLog.d("CaptionRecovery", "Retry #${index + 1} window=${request.first.startMs}..${request.first.endMs} source=${request.second}")
            attempts += transcribe(request.first, request.second).also { words ->
                CaptionDebugLog.d("CaptionRecovery", "Hypothesis=${words.filter { it.startMs < gap.endMs && it.endMs > gap.startMs }.joinToString(" ") { it.text }}")
            }
        }
        return chooseCandidates(gap, vad, listOf(secondary) + attempts,
            windows.indices.filter { windows[it].second == AudioVariant.RAW }.map { it + 1 }.toSet())
    }

    private fun chooseCandidates(gap: SpeechGap, vad: VadTimeline, sources: List<List<CaptionWord>>,
        rawSources: Set<Int>): List<CaptionWord> {
        val differences = TranscriptDifferenceDetector()
        val candidates = sources.flatMapIndexed { source, words ->
            differences.candidates(gap, listOf(words)).map { it to source }
        }
        val groups = mutableListOf<MutableList<Pair<CaptionWord, Int>>>()
        for (candidate in candidates) {
            val group = groups.firstOrNull { members -> members.any {
                it.first.text.equals(candidate.first.text, true) && temporalOverlap(it.first, candidate.first) >= .45f &&
                    abs((it.first.startMs + it.first.endMs) - (candidate.first.startMs + candidate.first.endMs)) <= 300
            } }
            if (group == null) groups.add(mutableListOf(candidate)) else group.add(candidate)
        }
        return groups.filter { group -> group.map { it.second }.distinct().size >= 2 &&
            group.any { it.second in rawSources } }.mapNotNull { group ->
            val start = group.map { it.first.startMs }.sorted()[group.size / 2]
            val end = group.map { it.first.endMs }.sorted()[group.size / 2]
            val candidate = group.maxBy { it.first.confidence ?: 0f }.first
            val acoustic = vad.averageSpeechProbability(start, end)
            val distinctTexts = groups.any { other -> other !== group && other.isNotEmpty() &&
                other.any { temporalOverlap(it.first, candidate) > .45f } }
            CaptionDebugLog.d("CaptionAlignment", "candidate=${candidate.text} acousticActivity=$acoustic agreement=${group.size}")
            if (start >= end || start < gap.startMs - config.wordBoundaryToleranceMs ||
                end > gap.endMs + config.wordBoundaryToleranceMs || acoustic < config.speechGapThreshold || distinctTexts) null
            else candidate.copy(startMs = start, endMs = end, verificationState = VerificationState.RECOVERED)
                .also { CaptionDebugLog.d("CaptionRecovery", "RECOVERED word=${it.text}") }
        }.sortedBy { it.startMs }
    }
}

/** Deduplicates the same acoustic interval at overlapping chunk boundaries. */
fun mergeTimedWords(primary: List<CaptionWord>, additions: List<CaptionWord>): List<CaptionWord> {
    val result = primary.sortedBy { it.startMs }.toMutableList()
    for (word in additions.sortedBy { it.startMs }) {
        val duplicateIndex = result.indexOfFirst { it.text.equals(word.text, true) && temporalOverlap(it, word) >= .55f }
        if (duplicateIndex >= 0) {
            val existing = result[duplicateIndex]
            // Keep the evidence-rich copy and retain the most precise timing.
            val existingScore = evidenceScore(existing)
            val newScore = evidenceScore(word)
            if (newScore > existingScore || (newScore == existingScore &&
                    word.endMs - word.startMs < existing.endMs - existing.startMs)) result[duplicateIndex] = word
            continue
        }
        val conflictIndex = result.indexOfFirst { !it.text.equals(word.text, true) && temporalOverlap(it, word) > .25f }
        if (conflictIndex < 0) result += word
        else if (evidenceScore(word) > evidenceScore(result[conflictIndex]) + .08f) {
            // A competing overlap is replaced only with a materially stronger
            // measured hypothesis. A vocabulary entry alone cannot do this.
            result[conflictIndex] = word
        }
    }
    return result.sortedWith(compareBy<CaptionWord> { it.startMs }.thenBy { it.endMs })
}

private fun evidenceScore(word: CaptionWord): Float =
    listOfNotNull(word.confidence, word.acousticConfidence, word.alignmentConfidence)
        .takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f

/** Confirms a low confidence word only when both local signals agree at the same time. */
fun confirmLocalWords(primary: List<CaptionWord>, region: CaptionDirtyRegion,
    processed: List<CaptionWord>, raw: List<CaptionWord>, minimumConfidence: Float = .75f,
    rescorer: PtBrContextRescorer? = null): List<CaptionWord> =
    primary.mapIndexed { index, word ->
        if (word.endMs <= region.startMs || word.startMs >= region.endMs ||
            (word.confidence ?: 0f) >= minimumConfidence) return@mapIndexed word
        val a = processed.firstOrNull { it.text.equals(word.text, true) &&
            temporalOverlap(it, word) >= .45f && (it.confidence ?: 0f) >= minimumConfidence }
        val b = raw.firstOrNull { it.text.equals(word.text, true) &&
            temporalOverlap(it, word) >= .45f && (it.confidence ?: 0f) >= minimumConfidence }
        if (a != null && b != null && temporalOverlap(a, b) >= .45f) {
            word.copy(confidence = minOf(a.confidence!!, b.confidence!!),
                verificationState = VerificationState.RECOVERED)
        } else rescorer?.choose(word, primary.getOrNull(index - 1)?.text,
            primary.getOrNull(index + 1)?.text, processed, raw) ?: word
    }
