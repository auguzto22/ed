package com.termex.replay15.editor.captions

data class CaptionEvaluationMetrics(
    val wer: Float,
    val slangWer: Float,
    val foreignWordWer: Float,
    val properNameWer: Float,
    val brandWer: Float,
    val hallucinationRate: Float,
    val missingWordRate: Float,
    val foreignWordAccuracy: Float,
    val properNameAccuracy: Float,
) {
    fun asMap(): Map<String, Float> = mapOf(
        "WER" to wer, "SlangWER" to slangWer, "ForeignWordWER" to foreignWordWer,
        "ProperNameWER" to properNameWer, "BrandWER" to brandWer,
        "HallucinationRate" to hallucinationRate, "MissingWordRate" to missingWordRate,
        "ForeignWordAccuracy" to foreignWordAccuracy, "ProperNameAccuracy" to properNameAccuracy,
    )
}

/**
 * Offline, reference-based evaluation. It is never used to rewrite captions;
 * automatic output is not accepted as ground truth.
 */
object CaptionEvaluationMetricsCalculator {
    fun calculate(
        reference: String,
        hypothesis: String,
        vocabulary: BrazilianMixedVocabulary = BrazilianMixedVocabulary.default(),
        entities: DynamicEntityContext = DynamicEntityContext.empty(),
    ): CaptionEvaluationMetrics {
        val expected = tokens(reference)
        val actual = tokens(hypothesis)
        val distance = editDistance(expected, actual)
        val types = expected.map { tokenType(it, vocabulary, entities) }
        fun categoryRate(category: TokenCategory): Float {
            val indices = types.withIndex().filter { it.value == category }.map { it.index }
            if (indices.isEmpty()) return 0f
            val errors = indices.count { index -> actual.getOrNull(index)?.equals(expected[index], true) != true }
            return errors.toFloat() / indices.size
        }
        val substitutions = actual.count { token -> expected.none { it.equals(token, true) } }
        return CaptionEvaluationMetrics(
            wer = if (expected.isEmpty()) if (actual.isEmpty()) 0f else 1f
                else distance.toFloat() / expected.size,
            slangWer = categoryRate(TokenCategory.SLANG),
            foreignWordWer = categoryRate(TokenCategory.FOREIGN),
            properNameWer = categoryRate(TokenCategory.PROPER),
            brandWer = categoryRate(TokenCategory.BRAND),
            hallucinationRate = rate(substitutions, actual.size),
            missingWordRate = rate((expected.size - actual.size).coerceAtLeast(0), expected.size),
            foreignWordAccuracy = accuracy(expected, actual, TokenCategory.FOREIGN, vocabulary, entities),
            properNameAccuracy = accuracy(expected, actual, TokenCategory.PROPER, vocabulary, entities),
        )
    }

    private enum class TokenCategory { OTHER, SLANG, FOREIGN, PROPER, BRAND }

    private fun tokenType(token: String, vocabulary: BrazilianMixedVocabulary, entities: DynamicEntityContext): TokenCategory {
        val entity = entities.entityType(token)
        if (entity != null) return if (entity == DynamicEntityType.BRAND || entity == DynamicEntityType.APP ||
            entity == DynamicEntityType.COMPANY || entity == DynamicEntityType.PRODUCT) TokenCategory.BRAND else TokenCategory.PROPER
        return when (vocabulary.entryFor(token)?.category) {
            MixedVocabularyCategory.PT_BR_SLANG -> TokenCategory.SLANG
            MixedVocabularyCategory.BRAND, MixedVocabularyCategory.SOCIAL_MEDIA -> TokenCategory.BRAND
            MixedVocabularyCategory.CONTENT, MixedVocabularyCategory.INTERNET, MixedVocabularyCategory.TECHNOLOGY,
            MixedVocabularyCategory.GAMING -> TokenCategory.FOREIGN
            else -> TokenCategory.OTHER
        }
    }

    private fun accuracy(
        expected: List<String>, actual: List<String>, category: TokenCategory,
        vocabulary: BrazilianMixedVocabulary, entities: DynamicEntityContext,
    ): Float {
        val positions = expected.withIndex().filter { tokenType(it.value, vocabulary, entities) == category }
        if (positions.isEmpty()) return 0f
        return positions.count { it.index < actual.size && actual[it.index].equals(it.value, true) }
            .toFloat() / positions.size
    }

    private fun tokens(value: String): List<String> = value.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    private fun rate(value: Int, total: Int): Float = if (total == 0) 0f else (value.toFloat() / total).coerceIn(0f, 1f)

    private fun editDistance(expected: List<String>, actual: List<String>): Int {
        val previous = IntArray(actual.size + 1) { it }
        var row = previous
        expected.forEachIndexed { i, expectedToken ->
            val next = IntArray(actual.size + 1)
            next[0] = i + 1
            actual.forEachIndexed { j, actualToken ->
                next[j + 1] = if (expectedToken.equals(actualToken, true)) row[j]
                else minOf(row[j] + 1, row[j + 1] + 1, next[j] + 1)
            }
            row = next
        }
        return row.last()
    }
}
