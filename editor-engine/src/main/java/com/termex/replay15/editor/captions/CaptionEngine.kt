package com.termex.replay15.editor.captions

import android.content.Context
import com.termex.replay15.editor.domain.Project

/** Owns temporary audio and connects decoding, region selection, recognition and timeline mapping. */
@Deprecated("Legacy offline caption engine; use GeminiCaptionGenerator")
class CaptionEngine(
    private val context: Context,
    private val providerFactory: (CaptionAudioExtractor.AudioCopy) -> CaptionTranscriber,
    private val languageDetector: CaptionLanguageDetector,
    private val diarization: CaptionDiarizationEngine? = null
) {
    suspend fun generateForClip(project: Project, clipIndex: Int, mode: CaptionMode,
        progress: CaptionProgress, cancel: CaptionCancellation): Pair<Project, CaptionProject> {
        val clip = project.videos[clipIndex]
        require(!clip.image) { "Imagem não contém fala" }
        progress.update("Analisando áudio", 0, 1); cancel.check()
        val audio = CaptionAudioExtractor(context).extract(clip.uri, clip.inUs, clip.outUs) { cancel.check() }
        try {
            val detector = SpeechRegionDetector {
                progress.update("Detectando fala", 0, 1); cancel.check()
                val regular = PcmSpeechRegionDetector(audio.rawAudio).detect()
                if (regular.isNotEmpty()) regular else PcmSpeechRegionDetector(audio.rawAudio, sensitive = true).detect()
            }
            val mixedVocabulary = BrazilianMixedVocabulary.load(context)
            val captionContext = ProjectCaptionContext(
                vocabulary = CaptionVocabulary(project.captionVocabulary),
                entities = DynamicEntityContext.fromProject(project, mixedVocabulary),
                mixedVocabulary = mixedVocabulary,
            )
            val controller = CaptionGenerationController(detector, languageDetector, providerFactory(audio), diarization,
                captionContext)
            val local = controller.generate(audio.durationMs, mode, progress, cancel)
            cancel.check()
            val source = local.copy(segments = local.segments.map { segment ->
                segment.copy(words = segment.words.map { it.copy(startMs = it.startMs + clip.inUs / 1_000,
                    endMs = it.endMs + clip.inUs / 1_000) })
            }, reviewRegions = local.reviewRegions.map { it.copy(startMs = it.startMs + clip.inUs / 1_000,
                endMs = it.endMs + clip.inUs / 1_000) })
            val mapped = CaptionTimelineMapper().mapSource(project, clipIndex, source)
            return mapped to source
        } finally { audio.delete() }
    }
}
