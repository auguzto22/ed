package com.termex.replay15.editor.captions

import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.SubtitleWordCue
import com.termex.replay15.editor.domain.TextClip
import kotlinx.coroutines.CancellationException

data class CaptionTranslationInput(
    val id: String,
    val text: String,
    val previousText: String? = null,
    val nextText: String? = null,
)

data class CaptionTranslationOutput(val id: String, val translatedText: String)

/** Provider boundary for caption translation. Times and timeline objects stay provider-independent. */
interface CaptionTranslator {
    suspend fun translate(
        segments: List<CaptionTranslationInput>,
        sourceLanguageCode: String,
        targetLanguageCode: String,
        checkCancelled: () -> Unit = {},
    ): List<CaptionTranslationOutput>
}

class CaptionTranslationFailed(message: String = "Não foi possível traduzir as legendas.", cause: Throwable? = null) :
    CaptionGenerationException(message, cause)

/** Translates in bounded batches, then reapplies each result to the original caption ID and timing. */
class CaptionTranslationService(
    private val translator: CaptionTranslator,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    init { require(batchSize in 1..100) }

    suspend fun translate(
        project: Project,
        targetLanguageCode: String,
        progress: (completed: Int, total: Int) -> Unit = { _, _ -> },
        checkCancelled: () -> Unit = {},
    ): Project {
        require(targetLanguageCode.isNotBlank())
        val captions = project.texts.filter { it.isCaption }.sortedWith(compareBy<TextClip> { it.startUs }.thenBy { it.id })
        if (captions.isEmpty()) throw CaptionTranslationFailed("Este projeto ainda não tem legendas para traduzir.")

        val inputs = captions.mapIndexed { index, caption ->
            CaptionTranslationInput(
                id = caption.id,
                text = caption.text,
                previousText = captions.getOrNull(index - 1)?.text,
                nextText = captions.getOrNull(index + 1)?.text,
            )
        }
        val translatedById = LinkedHashMap<String, String>(captions.size)
        val batches = inputs.chunked(batchSize)
        batches.forEachIndexed { index, batch ->
            checkCancelled()
            val response = try {
                translator.translate(batch, project.captionLanguage, targetLanguageCode, checkCancelled)
            } catch (error: CancellationException) {
                throw error
            } catch (error: CaptionGenerationException) {
                throw error
            } catch (error: Throwable) {
                throw CaptionTranslationFailed(cause = error)
            }
            val expectedIds = batch.map { it.id }.toSet()
            val responseIds = response.map { it.id }
            if (responseIds.size != batch.size || responseIds.toSet() != expectedIds || responseIds.size != responseIds.toSet().size) {
                throw CaptionTranslationFailed("A resposta da tradução veio incompleta. Nenhuma legenda foi alterada.")
            }
            response.forEach { item ->
                val text = item.translatedText.trim()
                if (text.isBlank() || text.length > MAX_TRANSLATED_TEXT_LENGTH) {
                    throw CaptionTranslationFailed("A resposta da tradução contém um texto vazio ou longo demais.")
                }
                translatedById[item.id] = text
            }
            checkCancelled()
            progress(index + 1, batches.size)
        }

        checkCancelled()
        return project.copy(texts = project.texts.map { clip ->
            val translated = translatedById[clip.id] ?: return@map clip
            clip.copy(
                text = translated,
                wordCues = translatedWordCues(clip, translated),
            )
        })
    }

    private fun translatedWordCues(clip: TextClip, text: String): List<SubtitleWordCue> {
        if (clip.wordCues.isEmpty()) return emptyList()
        val words = text.replace('\n', ' ').split(Regex("\\s+")).filter(String::isNotBlank)
        if (words.isEmpty() || words.size > MAX_WORD_CUES) return emptyList()
        val startUs = clip.wordCues.first().startUs
        val endUs = clip.wordCues.last().endUs
        val spanUs = endUs - startUs
        if (spanUs < words.size) return emptyList()
        return words.mapIndexed { index, word ->
            val wordStartUs = startUs + spanUs * index / words.size
            val wordEndUs = startUs + spanUs * (index + 1) / words.size
            SubtitleWordCue(word, wordStartUs, wordEndUs)
        }
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 40
        const val MAX_TRANSLATED_TEXT_LENGTH = 500
        private const val MAX_WORD_CUES = 300
    }
}
