package com.termex.replay15.editor.captions

data class IncoherenceAssessment(
    val needsRetry: Boolean,
    val score: Float,
    val reasons: Set<String> = emptySet(),
)

/** Conservative deterministic checks; it never rewrites a transcript. */
class CaptionIncoherenceDetector(
    private val minimumAverageConfidence: Float = .45f,
    private val maximumRepeatedTokenRatio: Float = .55f,
) {
    fun assess(words: List<CaptionWord>, speech: SpeechRegion? = null): IncoherenceAssessment {
        if (words.isEmpty()) return IncoherenceAssessment(false, 0f)
        val reasons = mutableSetOf<String>()
        val confidence = words.mapNotNull { it.confidence }.takeIf { it.isNotEmpty() }
            ?.average()?.toFloat()
        if (confidence == null) reasons += "unknown_confidence"
        else if (confidence < minimumAverageConfidence) reasons += "low_confidence"
        val repeated = words.groupingBy { PtBrLexiconRepository.normalize(it.text) }.eachCount()
            .values.filter { it > 1 }.sum()
        val repeatedRatio = repeated.toFloat() / words.size
        if (words.size >= 4 && repeatedRatio > maximumRepeatedTokenRatio) reasons += "repeated_tokens"
        if (words.zipWithNext().any { (a, b) -> a.endMs > b.startMs || a.startMs >= a.endMs || b.startMs >= b.endMs })
            reasons += "invalid_timing"
        if (speech != null && words.none { it.startMs < speech.endMs && it.endMs > speech.startMs })
            reasons += "outside_speech"
        val score = (if (confidence == null) 0f else 1f - confidence) * .7f +
            (repeatedRatio.coerceIn(0f, 1f) * .3f)
        return IncoherenceAssessment(reasons.isNotEmpty(), score.coerceIn(0f, 1f), reasons)
    }

    fun needsRetry(words: List<CaptionWord>, speech: SpeechRegion? = null): Boolean = assess(words, speech).needsRetry
}
