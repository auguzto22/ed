package com.termex.replay15.editor.captions

import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.TextClip
import kotlinx.coroutines.CancellationException

data class CaptionCorrectionResult(
    val project: Project,
    val correctedCount: Int,
    val keptOriginalCount: Int,
)

/** Corrects existing caption layers in bounded batches without changing their timing or style. */
class CaptionCorrectionService(
    private val corrector: CaptionTextCorrector,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    init { require(batchSize in 1..100) }

    suspend fun correct(
        project: Project,
        progress: (completed: Int, total: Int) -> Unit = { _, _ -> },
        checkCancelled: () -> Unit = {},
    ): CaptionCorrectionResult {
        val captions = project.texts.filter { it.isCaption }.sortedWith(compareBy<TextClip> { it.startUs }.thenBy { it.id })
        if (captions.isEmpty()) throw CorrectionFailed("Este projeto ainda não tem legendas para corrigir.")

        val inputs = captions.mapIndexed { index, clip ->
            CaptionCorrectionContext(
                id = clip.id,
                text = clip.text,
                previousText = captions.getOrNull(index - 1)?.text,
                nextText = captions.getOrNull(index + 1)?.text,
            )
        }
        val clipsById = captions.associateBy { it.id }
        val correctedById = LinkedHashMap<String, String>(captions.size)
        val batches = inputs.chunked(batchSize)
        batches.forEachIndexed { index, batch ->
            checkCancelled()
            val response = try {
                corrector.correctCaptions(
                    segments = batch,
                    languageCode = project.captionLanguage,
                    terms = project.captionVocabulary,
                    projectContext = project.name,
                    checkCancelled = checkCancelled,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: CaptionGenerationException) {
                throw error
            } catch (error: Throwable) {
                throw CorrectionFailed(cause = error)
            }

            val expectedIds = batch.map { it.id }.toSet()
            val responseIds = response.map { it.id }
            if (responseIds.size != batch.size || responseIds.toSet() != expectedIds || responseIds.size != responseIds.toSet().size) {
                throw CorrectionFailed("A resposta da correção veio incompleta. Nenhuma legenda foi alterada.")
            }
            val originals = batch.associateBy { it.id }
            response.forEach { item ->
                val corrected = item.correctedText.trim()
                if (corrected.isBlank() || corrected.length > MAX_CORRECTED_TEXT_LENGTH) {
                    throw CorrectionFailed("A resposta da correção contém texto vazio ou longo demais.")
                }
                val original = requireNotNull(originals[item.id])
                val clip = requireNotNull(clipsById[item.id])
                correctedById[item.id] = if (isConservative(original.text, corrected, clip, project.captionVocabulary)) {
                    corrected
                } else {
                    original.text
                }
            }
            checkCancelled()
            progress(index + 1, batches.size)
        }

        checkCancelled()
        var correctedCount = 0
        val updated = project.copy(texts = project.texts.map { clip ->
            val corrected = correctedById[clip.id] ?: return@map clip
            if (corrected == clip.text) return@map clip
            val correctedWords = corrected.replace('\n', ' ').split(Regex("\\s+")).filter(String::isNotBlank)
            val cues = if (clip.wordCues.size == correctedWords.size) {
                clip.wordCues.mapIndexed { index, cue -> cue.copy(text = correctedWords[index]) }
            } else if (clip.wordCues.isEmpty()) {
                emptyList()
            } else {
                // Keep the source untouched when a correction would invalidate its word timing map.
                return@map clip
            }
            correctedCount++
            clip.copy(text = corrected, wordCues = cues)
        })
        return CaptionCorrectionResult(
            project = updated,
            correctedCount = correctedCount,
            keptOriginalCount = captions.size - correctedCount,
        )
    }

    private fun isConservative(
        original: String,
        corrected: String,
        clip: TextClip,
        vocabulary: Set<String>,
    ): Boolean {
        val originalWords = normalizedWords(original)
        val correctedWords = normalizedWords(corrected)
        if (originalWords.isEmpty() || correctedWords.isEmpty()) return false
        if (clip.wordCues.isNotEmpty() && correctedWords.size != clip.wordCues.size) return false
        val countDifference = kotlin.math.abs(originalWords.size - correctedWords.size)
        if (countDifference > maxOf(2, originalWords.size / 2)) return false

        val originalNegations = originalWords.filter(::isNegation).toSet()
        val correctedNegations = correctedWords.filter(::isNegation).toSet()
        if (originalNegations != correctedNegations) return false

        val remaining = correctedWords.toMutableList()
        val shared = originalWords.count { word ->
            val index = remaining.indexOf(word)
            if (index < 0) false else { remaining.removeAt(index); true }
        }
        val overlap = shared.toFloat() / maxOf(originalWords.size, correctedWords.size)
        if (overlap >= MIN_TOKEN_OVERLAP) return true

        val originalCompact = originalWords.joinToString("")
        val correctedCompact = correctedWords.joinToString("")
        if (vocabulary.any { normalizedWords(it).joinToString("") == correctedCompact }) return true
        return originalWords.size == 1 && correctedWords.size == 1 &&
            bigramDice(originalCompact, correctedCompact) >= MIN_NAME_SIMILARITY
    }

    private fun normalizedWords(text: String): List<String> = text.lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter(String::isNotBlank)

    private fun isNegation(word: String): Boolean = word in NEGATIONS

    private fun bigramDice(left: String, right: String): Float {
        if (left == right) return 1f
        if (left.length < 2 || right.length < 2) return 0f
        val leftBigrams = left.windowed(2).groupingBy { it }.eachCount().toMutableMap()
        var shared = 0
        right.windowed(2).forEach { pair ->
            val count = leftBigrams[pair] ?: 0
            if (count > 0) {
                shared++
                leftBigrams[pair] = count - 1
            }
        }
        return 2f * shared / (left.length - 1 + right.length - 1)
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 40
        const val MAX_CORRECTED_TEXT_LENGTH = 500
        private const val MIN_TOKEN_OVERLAP = .4f
        private const val MIN_NAME_SIMILARITY = .55f
        private val NEGATIONS = setOf("não", "nunca", "jamais", "sem", "nem", "not", "never", "no", "without")
    }
}
