package com.termex.replay15.editor.captions

import java.io.File

/** A provider-independent request. Custom vocabulary is intentionally not part of this request. */
data class TranscriptionRequest(
    val audioFile: File,
    val mimeType: String,
    val languageCode: String = "pt-BR",
    val checkCancelled: () -> Unit = {},
)

data class WordTimestamp(
    val word: String,
    val startUs: Long,
    val endUs: Long,
) {
    init {
        require(word.isNotBlank())
        require(startUs >= 0L && endUs > startUs)
    }
}

data class TranscriptionResult(
    val words: List<WordTimestamp>,
    val languageCode: String? = null,
) {
    init { require(words.zipWithNext().all { (a, b) -> a.startUs <= b.startUs }) }
}

/** Provider boundary used by the editor; the timeline does not know which provider was used. */
interface TranscriptionProvider {
    suspend fun transcribe(request: TranscriptionRequest): TranscriptionResult
}

data class CaptionGenerationOptions(
    val languageCode: String = "pt-BR",
    val maxWords: Int = 4,
    val maxLines: Int = 2,
    val contextualCorrection: Boolean = true,
    val recognizeNamesAndSlang: Boolean = true,
) {
    init {
        require(languageCode in setOf("pt-BR", "en-US", "auto"))
        require(maxWords in 1..12)
        require(maxLines in 1..2)
    }
}

data class TimedCaptionSegment(
    val id: String,
    val text: String,
    val startUs: Long,
    val endUs: Long,
    val wordCues: List<WordTimestamp>,
) {
    init {
        require(id.isNotBlank())
        require(text.isNotBlank())
        require(startUs >= 0L && endUs > startUs)
        require(wordCues.isNotEmpty())
        require(wordCues.first().startUs >= startUs && wordCues.last().endUs <= endUs)
    }

    fun withText(corrected: String): TimedCaptionSegment {
        val clean = corrected.trim()
        if (clean.isBlank()) return this
        val correctedWords = clean.replace('\n', ' ').split(Regex("\\s+")).filter(String::isNotBlank)
        val cues = if (correctedWords.size == wordCues.size) {
            wordCues.mapIndexed { index, cue -> cue.copy(word = correctedWords[index]) }
        } else wordCues
        return copy(text = clean, wordCues = cues)
    }
}

data class CaptionCorrectionContext(
    val id: String,
    val text: String,
    val previousText: String? = null,
    val nextText: String? = null,
)

interface CaptionContextCorrector {
    suspend fun correct(
        segments: List<TimedCaptionSegment>,
        terms: Set<String>,
        projectContext: String? = null,
        checkCancelled: () -> Unit = {},
    ): List<TimedCaptionSegment>
}

open class CaptionGenerationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class NoInternet(message: String = "Sem conexão com a internet.", cause: Throwable? = null) : CaptionGenerationException(message, cause)
class InvalidApiKey(message: String = "Não foi possível autenticar a API.", cause: Throwable? = null) : CaptionGenerationException(message, cause)
class QuotaExceeded(message: String = "Limite gratuito da transcrição atingido. Tente novamente mais tarde.", cause: Throwable? = null) : CaptionGenerationException(message, cause)
class UnsupportedAudio(message: String = "O áudio deste vídeo não é compatível.", cause: Throwable? = null) : CaptionGenerationException(message, cause)
class AudioExtractionFailed(message: String = "Não foi possível extrair o áudio do vídeo.", cause: Throwable? = null) : CaptionGenerationException(message, cause)
class UploadFailed(message: String = "Não foi possível enviar o áudio para transcrição.", cause: Throwable? = null) : CaptionGenerationException(message, cause)
class TranscriptionFailed(message: String = "Não foi possível transcrever o áudio.", cause: Throwable? = null) : CaptionGenerationException(message, cause)
class CorrectionFailed(message: String = "Não foi possível corrigir o texto da transcrição.", cause: Throwable? = null) : CaptionGenerationException(message, cause)
class CaptionBackendNotConfigured(message: String = "Backend seguro de legendas não configurado para esta versão.") : CaptionGenerationException(message)

class CaptionCancelled(message: String = "Geração de legendas cancelada.") : CaptionGenerationException(message)
