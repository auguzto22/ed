package com.termex.replay15.editor.captions

import android.content.Context
import com.termex.replay15.editor.domain.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CaptionProgressEvent(val stage: String, val completed: Int, val total: Int)

data class AudioChunk(
    val startUs: Long,
    val endUs: Long,
) {
    val durationUs: Long get() = endUs - startUs
}

object LongAudioChunker {
    fun plan(durationUs: Long, maxChunkUs: Long = 20L * 60L * 1_000_000L, overlapUs: Long = 1_500_000L): List<AudioChunk> {
        require(durationUs > 0L && maxChunkUs > overlapUs)
        if (durationUs <= maxChunkUs) return listOf(AudioChunk(0L, durationUs))
        val result = mutableListOf<AudioChunk>()
        var start = 0L
        while (start < durationUs) {
            val end = minOf(durationUs, start + maxChunkUs)
            result += AudioChunk(start, end)
            if (end == durationUs) break
            start = end - overlapUs
        }
        return result
    }
}

/** End-to-end Gemini caption pipeline. It returns real TextClip layers through the existing mapper. */
class GeminiCaptionGenerator(
    private val context: Context,
    private val provider: TranscriptionProvider = CaptionProviderFactory.transcriptionProvider(),
    private val corrector: CaptionContextCorrector? = CaptionProviderFactory.contextCorrector(),
) {
    suspend fun generate(
        project: Project,
        clipIndex: Int,
        options: CaptionGenerationOptions,
        progress: (CaptionProgressEvent) -> Unit,
        checkCancelled: () -> Unit,
    ): Pair<Project, Int> = withContext(Dispatchers.IO) {
        val clip = project.videos.getOrNull(clipIndex) ?: error("Clipe de vídeo não encontrado")
        require(!clip.image) { "Selecione um vídeo com áudio" }
        val chunks = LongAudioChunker.plan(clip.durationUs)
        val chunkResults = mutableListOf<TimedWordChunk>()
        val extractor = GeminiAudioExtractor(context)
        chunks.forEachIndexed { index, chunk ->
            checkCancelled()
            progress(CaptionProgressEvent("Preparando áudio...", index, chunks.size))
            val audio = try {
                extractor.extract(
                    clip.uri,
                    clip.inUs + chunk.startUs,
                    clip.inUs + chunk.endUs,
                    checkCancelled,
                )
            } catch (error: CaptionGenerationException) { throw error }
            catch (error: Throwable) { throw AudioExtractionFailed(cause = error) }
            try {
                progress(CaptionProgressEvent("Enviando áudio...", index, chunks.size))
                val result = provider.transcribe(TranscriptionRequest(
                    audioFile = audio.file,
                    mimeType = audio.mimeType,
                    languageCode = options.languageCode,
                    checkCancelled = checkCancelled,
                ))
                chunkResults += TimedWordChunk(
                    chunkStartUs = chunk.startUs,
                    chunkEndUs = chunk.endUs,
                    words = result.words.map { word -> word.copy(
                        startUs = word.startUs + chunk.startUs,
                        endUs = word.endUs + chunk.startUs,
                    ) },
                )
            } finally { audio.delete() }
            progress(CaptionProgressEvent("Transcrevendo...", index + 1, chunks.size))
        }

        checkCancelled()
        val words = TranscriptDeduplicator().merge(chunkResults)
        if (words.isEmpty()) throw TranscriptionFailed("Nenhuma fala foi reconhecida neste vídeo.")
        val segmenter = CaptionSegmenter(CaptionSegmenterConfig(
            maxWords = options.maxWords,
            maxLines = options.maxLines,
        ))
        var segments = segmenter.segment(words)
        if (options.contextualCorrection && corrector != null) {
            progress(CaptionProgressEvent("Corrigindo palavras...", 0, 1))
            segments = runCatching {
                corrector.correct(
                    segments = segments,
                    terms = if (options.recognizeNamesAndSlang) project.captionVocabulary else emptySet(),
                    projectContext = project.name,
                    checkCancelled = checkCancelled,
                )
            }.getOrElse { error ->
                // Correction is explicitly best effort; a good timestamped transcript is still useful.
                CaptionDebugLog.d("GeminiCaption", "Context correction skipped: ${error.javaClass.simpleName}")
                segments
            }
            progress(CaptionProgressEvent("Corrigindo palavras...", 1, 1))
        }

        checkCancelled()
        progress(CaptionProgressEvent("Criando legendas...", 0, 1))
        val source = CaptionProject(
            segments = segments.map { segment ->
                CaptionSegment(
                    words = segment.wordCues.map { cue ->
                        CaptionWord(
                            text = cue.word,
                            startMs = (clip.inUs + cue.startUs) / 1_000L,
                            endMs = (clip.inUs + cue.endUs) / 1_000L,
                            confidence = 1f,
                            acousticConfidence = 1f,
                            alignmentConfidence = 1f,
                            language = options.languageCode.takeUnless { it == "auto" },
                            verificationState = VerificationState.ACCEPTED,
                        )
                    },
                    language = options.languageCode.takeUnless { it == "auto" },
                    speakerId = null,
                    overlappingSpeech = false,
                    qualityScore = 1f,
                    verificationState = VerificationState.ACCEPTED,
                    displayText = segment.text,
                )
            },
            reviewRegions = emptyList(),
        )
        val mapped = CaptionTimelineMapper().mapSource(project, clipIndex, source)
        progress(CaptionProgressEvent("Criando legendas...", 1, 1))
        mapped to (mapped.texts.size - project.texts.size)
    }
}
