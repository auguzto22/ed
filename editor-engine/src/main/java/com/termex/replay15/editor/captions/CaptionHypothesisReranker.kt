package com.termex.replay15.editor.captions

import kotlin.math.max

/**
 * Weights are injectable so a validation set can calibrate them. The safe
 * default is acoustic-only: no vocabulary or entity is allowed to override a
 * clear acoustic result until a measured calibration is supplied.
 */
data class CaptionRerankingWeights(
    val acousticWeight: Float = 1f,
    val languageWeight: Float = 0f,
    val contextWeight: Float = 0f,
    val vocabularyWeight: Float = 0f,
    val entityWeight: Float = 0f,
) {
    init {
        require(listOf(acousticWeight, languageWeight, contextWeight, vocabularyWeight, entityWeight)
            .all { it.isFinite() && it >= 0f })
        require(acousticWeight + languageWeight + contextWeight + vocabularyWeight + entityWeight > 0f)
    }
}

data class CaptionHypothesisScore(
    val hypothesis: TranscriptionHypothesis,
    val acousticScore: Float,
    val languageScore: Float,
    val contextScore: Float,
    val vocabularyScore: Float,
    val entityScore: Float,
    val finalScore: Float,
)

/** Scores measured alternatives; it never manufactures a word or performs a text replacement. */
class CaptionHypothesisReranker(
    private val weights: CaptionRerankingWeights = CaptionRerankingWeights(),
    private val vocabulary: BrazilianMixedVocabulary = BrazilianMixedVocabulary.empty(),
    private val entities: DynamicEntityContext = DynamicEntityContext.empty(),
    additionalVocabulary: Set<String> = emptySet(),
) {
    private val customVocabulary = additionalVocabulary.map(PtBrLexiconRepository::normalize).toSet()

    fun rank(
        candidates: List<TranscriptionHypothesis>,
        previousText: String? = null,
        followingText: String? = null,
    ): List<CaptionHypothesisScore> = candidates.map { candidate ->
        val words = candidate.words
        val acoustic = words.mapNotNull { it.acousticConfidence ?: it.confidence }
            .takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f
        val language = languageEvidence(words)
        val context = contextEvidence(words, previousText, followingText)
        val vocabularyScore = words.sumOf {
            val confidence = it.acousticConfidence ?: it.confidence
            val customBonus = if (confidence != null && confidence >= .55f &&
                PtBrLexiconRepository.normalize(it.text) in customVocabulary) .04 else 0.0
            vocabulary.vocabularyBonus(it.text, confidence).toDouble() + customBonus
        }
            .toFloat().coerceIn(0f, 1f)
        val entityScore = words.sumOf { entities.bonus(it.text, it.acousticConfidence ?: it.confidence).toDouble() }
            .toFloat().coerceIn(0f, 1f)
        val divisor = max(.0001f, weights.acousticWeight + weights.languageWeight + weights.contextWeight +
            weights.vocabularyWeight + weights.entityWeight)
        val finalScore = (weights.acousticWeight * acoustic + weights.languageWeight * language +
            weights.contextWeight * context + weights.vocabularyWeight * vocabularyScore +
            weights.entityWeight * entityScore) / divisor
        CaptionHypothesisScore(candidate, acoustic, language, context, vocabularyScore, entityScore, finalScore)
    }.sortedByDescending { it.finalScore }

    fun choose(
        candidates: List<TranscriptionHypothesis>,
        previousText: String? = null,
        followingText: String? = null,
    ): TranscriptionHypothesis? = rank(candidates, previousText, followingText).firstOrNull()?.hypothesis

    private fun languageEvidence(words: List<CaptionWord>): Float {
        if (words.isEmpty()) return 0f
        // A language model provider may replace this component. The local Vosk
        // result does not expose a separate language score, so this remains
        // neutral rather than pretending that token confidence is language confidence.
        return words.count { it.text.isNotBlank() }.toFloat() / words.size
    }

    private fun contextEvidence(words: List<CaptionWord>, before: String?, after: String?): Float {
        if (words.isEmpty()) return 0f
        val first = PtBrLexiconRepository.normalize(words.first().text)
        val last = PtBrLexiconRepository.normalize(words.last().text)
        val left = before.orEmpty().split(Regex("\\s+")).lastOrNull()?.let(PtBrLexiconRepository::normalize)
        val right = after.orEmpty().split(Regex("\\s+")).firstOrNull()?.let(PtBrLexiconRepository::normalize)
        return ((if (left != null && left == first) .5f else 0f) +
            (if (right != null && right == last) .5f else 0f)).coerceIn(0f, 1f)
    }
}
