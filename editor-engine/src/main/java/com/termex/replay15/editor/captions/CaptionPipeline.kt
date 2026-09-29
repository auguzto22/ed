package com.termex.replay15.editor.captions

import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.TextClip
import com.termex.replay15.editor.domain.SubtitleWordCue
import com.termex.replay15.editor.domain.MAX_TEXTS
import com.termex.replay15.editor.domain.ProjectClipTimeMapper
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class VerificationState { UNCHECKED, ACCEPTED, LOW_CONFIDENCE, RETRYING, CORRECTED, POSSIBLY_MISSING, RECOVERED, CONFLICT, ALIGNMENT_FAILED, NEEDS_REVIEW }
enum class CaptionMode { FAST, PRECISE, ULTRA_PRECISE }
data class WordCandidate(val text: String, val confidence: Float)
data class CaptionWord(
    val id: String = UUID.randomUUID().toString(), val text: String, val startMs: Long, val endMs: Long,
    val confidence: Float?, val acousticConfidence: Float?, val alignmentConfidence: Float?,
    val language: String? = null, val alternatives: List<WordCandidate> = emptyList(),
    val verificationState: VerificationState = VerificationState.UNCHECKED
)
data class SpeechRegion(val startMs: Long, val endMs: Long, val confidence: Float)
data class SpeakerSegment(val speakerId: String, val startMs: Long, val endMs: Long)
data class CaptionSegment(
    val words: List<CaptionWord>, val language: String?, val speakerId: String?,
    val overlappingSpeech: Boolean, val qualityScore: Float?, val verificationState: VerificationState,
    val acousticQuality: CaptionSegmentQuality? = null,
    /** Optional visual line breaks chosen after word grouping; timings remain in [words]. */
    val displayText: String? = null,
) {
    val startMs get() = words.first().startMs
    val endMs get() = words.last().endMs
    val text get() = displayText ?: words.joinToString(" ") { it.text }
}
data class CaptionProject(val segments: List<CaptionSegment>, val reviewRegions: List<SpeechRegion>,
    val reviewHypotheses: List<TranscriptionHypothesis> = emptyList())
data class TranscriptionHypothesis(
    val words: List<CaptionWord>, val region: SpeechRegion, val language: String?,
    val source: AudioVariant, val speakerId: String? = null, val overlappingSpeech: Boolean = false
)
enum class AudioVariant { RAW, PROCESSED }
data class CaptionRecognitionContext(
    val previousText: String? = null,
    val followingText: String? = null,
    val entities: DynamicEntityContext = DynamicEntityContext.empty(),
    /** Backend hint only; never a text-replacement map. */
    val pronunciationHints: Map<String, String> = emptyMap(),
)
data class RecognitionRequest(
    val region: SpeechRegion, val variant: AudioVariant, val language: String?,
    val vocabulary: Set<String> = emptySet(), val mode: CaptionMode = CaptionMode.ULTRA_PRECISE,
    val context: CaptionRecognitionContext = CaptionRecognitionContext(),
)
/** Providers must report measured word boundaries and real confidence, or leave them unknown. */
fun interface CaptionTranscriber { suspend fun transcribe(request: RecognitionRequest): TranscriptionHypothesis? }
/** Optional extension for backends such as Vosk that expose measured N-best results. */
interface CaptionNBestTranscriber : CaptionTranscriber {
    suspend fun transcribeNBest(request: RecognitionRequest): List<TranscriptionHypothesis>
    override suspend fun transcribe(request: RecognitionRequest): TranscriptionHypothesis? =
        transcribeNBest(request).firstOrNull()
}
fun interface SpeechRegionDetector { suspend fun detect(): List<SpeechRegion> }
fun interface CaptionLanguageDetector { suspend fun detect(region: SpeechRegion): String? }
fun interface CaptionDiarizationEngine { suspend fun detect(region: SpeechRegion): List<SpeakerSegment> }
fun interface CaptionProgress { fun update(stage: String, completed: Int, total: Int) }
fun interface CaptionCancellation { fun check() }

class CaptionVocabulary(initial: Set<String> = emptySet()) {
    private val confirmed = initial.toMutableSet()
    fun confirm(word: String) { if (word.isNotBlank()) confirmed += word.trim() }
    fun entries(): Set<String> = confirmed.toSet()
}
class ProjectCaptionContext(
    val vocabulary: CaptionVocabulary = CaptionVocabulary(),
    val entities: DynamicEntityContext = DynamicEntityContext.empty(),
    val mixedVocabulary: BrazilianMixedVocabulary = BrazilianMixedVocabulary.default(),
) {
    val recentSentences = ArrayDeque<String>()
    val languageHistory = ArrayDeque<String>()
    fun remember(segment: CaptionSegment) {
        recentSentences.addLast(segment.text)
        while (recentSentences.size > 30) recentSentences.removeFirst()
        segment.language?.let { languageHistory.addLast(it); while (languageHistory.size > 30) languageHistory.removeFirst() }
    }

    fun recognitionVocabulary(): Set<String> =
        mixedVocabulary.recognitionTerms(vocabulary.entries() + entities.terms)

    fun recognitionContext(): CaptionRecognitionContext = CaptionRecognitionContext(
        previousText = recentSentences.takeLast(2).joinToString(" ").takeIf(String::isNotBlank),
        entities = entities,
        pronunciationHints = mixedVocabulary.pronunciationHints,
    )
}

enum class CaptionProblem { LOW_CONFIDENCE, UNKNOWN_CONFIDENCE, INVALID_TIMING, NO_SPEECH, OUTSIDE_SPEECH, OVERLAP, LANGUAGE_CHANGE, DUPLICATE, INCOHERENT }
class CaptionErrorDetector {
    fun detect(h: TranscriptionHypothesis, speech: SpeechRegion): Set<CaptionProblem> = buildSet {
        if (h.words.isNotEmpty() && speech.confidence < .25f) add(CaptionProblem.NO_SPEECH)
        if (h.overlappingSpeech) add(CaptionProblem.OVERLAP)
        if (CaptionIncoherenceDetector().needsRetry(h.words, speech)) add(CaptionProblem.INCOHERENT)
        for ((index, word) in h.words.withIndex()) {
            if (word.confidence == null || word.acousticConfidence == null || word.alignmentConfidence == null) add(CaptionProblem.UNKNOWN_CONFIDENCE)
            if ((word.confidence ?: 0f) < .75f || (word.acousticConfidence ?: 0f) < .7f || (word.alignmentConfidence ?: 0f) < .7f) add(CaptionProblem.LOW_CONFIDENCE)
            if (word.startMs >= word.endMs || (index > 0 && h.words[index - 1].endMs > word.startMs)) add(CaptionProblem.INVALID_TIMING)
            if (word.startMs < speech.startMs - 100 || word.endMs > speech.endMs + 100) add(CaptionProblem.OUTSIDE_SPEECH)
            if (index > 0 && h.words[index - 1].language != null && word.language != null && h.words[index - 1].language != word.language && word.confidence == null) add(CaptionProblem.LANGUAGE_CHANGE)
        }
    }
}

/** Retry windows differ in span and signal; the recognizer never receives an identical request twice. */
class CaptionRetryEngine(private val transcriber: CaptionTranscriber) {
    private val shortContextMs = 3_000L
    private val longContextMs = 5_000L
    suspend fun retry(region: SpeechRegion, language: String?, vocabulary: Set<String>, mode: CaptionMode,
        durationMs: Long, cancel: CaptionCancellation,
        context: CaptionRecognitionContext = CaptionRecognitionContext()): List<TranscriptionHypothesis> {
        val midpoint = (region.startMs + region.endMs) / 2
        val windows = listOf(
            SpeechRegion(max(0, region.startMs - shortContextMs), min(durationMs, region.endMs + shortContextMs), region.confidence) to AudioVariant.PROCESSED,
            SpeechRegion(max(0, region.startMs - longContextMs), min(durationMs, region.endMs + longContextMs), region.confidence) to AudioVariant.RAW
        ).filter { it.first.endMs > it.first.startMs }.distinct()
        return windows.mapNotNull { (window, source) ->
            cancel.check()
            val request = RecognitionRequest(window, source, language, vocabulary, mode, context)
            val nBest = transcriber as? CaptionNBestTranscriber
            if (nBest != null) CaptionHypothesisReranker().choose(nBest.transcribeNBest(request),
                context.previousText, context.followingText)
            else transcriber.transcribe(request)
        }
    }
}

/** Agreement can corroborate a word only when the provider also supplies acoustic evidence. */
class CaptionConsensusEngine {
    fun choose(first: TranscriptionHypothesis, retries: List<TranscriptionHypothesis>): TranscriptionHypothesis? {
        val all = listOf(first) + retries
        return all.filter { hypothesis -> hypothesis.words.isNotEmpty() && hypothesis.words.all { word ->
            word.startMs < word.endMs && word.confidence != null && word.acousticConfidence != null && word.alignmentConfidence != null
        } }.maxByOrNull { hypothesis ->
            val confidence = hypothesis.words.map { minOf(it.confidence!!, it.acousticConfidence!!, it.alignmentConfidence!!) }.average()
            val agreement = hypothesis.words.count { word -> all.count { other -> other.words.any { candidate ->
                candidate.text.equals(word.text, true) && abs(candidate.startMs - word.startMs) < 300 && candidate.acousticConfidence != null
            } } >= 2 }.toDouble() / hypothesis.words.size
            confidence * .8 + agreement * .2
        }
    }
}
class CaptionFinalVerifier {
    fun valid(segment: CaptionSegment, speech: List<SpeechRegion>): Boolean {
        if (segment.words.isEmpty() || segment.overlappingSpeech || segment.verificationState != VerificationState.ACCEPTED ||
            (segment.acousticQuality?.unresolvedSpeechGaps ?: 0) > 0) return false
        if (segment.words.any { it.text.isBlank() || it.startMs < 0 || it.startMs >= it.endMs ||
                    it.confidence == null || it.acousticConfidence == null || it.alignmentConfidence == null ||
                    it.confidence !in 0f..1f || it.acousticConfidence !in 0f..1f || it.alignmentConfidence !in 0f..1f ||
                    it.verificationState != VerificationState.ACCEPTED }) return false
        if (segment.words.zipWithNext().any { (a, b) -> a.endMs > b.startMs }) return false
        return segment.words.all { word -> speech.any { it.confidence >= .25f && word.startMs >= it.startMs - 100 && word.endMs <= it.endMs + 100 } }
    }
}
class CaptionLineBreaker {
    fun breakWords(words: List<CaptionWord>, maxCharacters: Int = 42): List<List<CaptionWord>> {
        val result = mutableListOf<List<CaptionWord>>()
        var current = mutableListOf<CaptionWord>()
        for (word in words) {
            val gap = current.lastOrNull()?.let { word.startMs - it.endMs } ?: 0
            val length = current.sumOf { it.text.length + 1 } + word.text.length
            if (current.isNotEmpty() && (gap > 450 || length > maxCharacters || current.size >= 9)) {
                result += current; current = mutableListOf()
            }
            current += word
        }
        if (current.isNotEmpty()) result += current
        return result
    }
}
class CaptionGenerationController(
    private val detector: SpeechRegionDetector, private val languageDetector: CaptionLanguageDetector,
    private val transcriber: CaptionTranscriber, private val diarization: CaptionDiarizationEngine? = null,
    private val context: ProjectCaptionContext = ProjectCaptionContext()
) {
    suspend fun generate(durationMs: Long, mode: CaptionMode, progress: CaptionProgress, cancel: CaptionCancellation): CaptionProject {
        progress.update("Detectando fala", 0, 1); cancel.check()
        val speech = detector.detect().filter { it.endMs > it.startMs && it.confidence >= .25f }.sortedBy { it.startMs }
        // ASR sees continuous, padded blocks. CaptionLineBreaker is applied
        // only after recognition and verification, so a caption boundary can
        // never become an acoustic boundary.
        val asrWindows = planInitialCaptionWindows(speech).map {
            SpeechRegion(it.startMs, it.endMs, speech.filter { region ->
                region.startMs < it.endMs && region.endMs > it.startMs
            }.maxOfOrNull { region -> region.confidence } ?: 1f)
        }
        val errors = CaptionErrorDetector(); val retries = CaptionRetryEngine(transcriber)
        val consensus = CaptionConsensusEngine(); val verifier = CaptionFinalVerifier(); val breaker = CaptionLineBreaker()
        val segments = mutableListOf<CaptionSegment>(); val review = mutableListOf<SpeechRegion>()
        val reviewHypotheses = mutableListOf<TranscriptionHypothesis>()
        for ((index, region) in asrWindows.withIndex()) {
            cancel.check(); progress.update("Transcrevendo e verificando", index, asrWindows.size)
            val language = languageDetector.detect(region)
            val speakers = diarization?.detect(region).orEmpty()
            val request = RecognitionRequest(region, AudioVariant.PROCESSED, language,
                context.recognitionVocabulary(), mode, context.recognitionContext())
            val nBestTranscriber = transcriber as? CaptionNBestTranscriber
            val firstCandidates = if (nBestTranscriber != null) {
                nBestTranscriber.transcribeNBest(request)
            } else listOfNotNull(transcriber.transcribe(request))
            val first = CaptionHypothesisReranker(
                vocabulary = context.mixedVocabulary,
                entities = context.entities,
            ).choose(firstCandidates, request.context.previousText, request.context.followingText)
            if (first == null || first.words.isEmpty()) { review += region; continue }
            val problems = errors.detect(first, region)
            val attempts = if (mode != CaptionMode.FAST && problems.isNotEmpty()) retries.retry(
                region, language, context.recognitionVocabulary(), mode, durationMs, cancel,
                context.recognitionContext()
            ) else emptyList()
            val chosen = consensus.choose(first, attempts)
            if (chosen == null || errors.detect(chosen, region).isNotEmpty() || speakers.size > 1 || chosen.overlappingSpeech) {
                review += region
                val suspect = chosen ?: first
                reviewHypotheses += suspect.copy(words = suspect.words.map { word ->
                    val state = when {
                        word.alignmentConfidence == null || word.alignmentConfidence < .7f -> VerificationState.ALIGNMENT_FAILED
                        word.confidence == null || word.confidence < .75f || word.acousticConfidence == null || word.acousticConfidence < .7f -> VerificationState.LOW_CONFIDENCE
                        else -> VerificationState.NEEDS_REVIEW
                    }
                    word.copy(verificationState = state)
                })
                continue
            }
            val accepted = chosen.words.map { it.copy(verificationState = VerificationState.ACCEPTED) }
            for (group in breaker.breakWords(accepted)) {
                val score = group.map { minOf(it.confidence!!, it.acousticConfidence!!, it.alignmentConfidence!!) }.average().toFloat()
                val segment = CaptionSegment(group, chosen.language ?: language, chosen.speakerId ?: speakers.firstOrNull()?.speakerId, false, score, VerificationState.ACCEPTED)
                if (verifier.valid(segment, speech)) { segments += segment; context.remember(segment) } else review += region
            }
        }
        progress.update("Finalizando", asrWindows.size, asrWindows.size)
        // Overlap belongs to the ASR pass, not to the rendered result.
        val deduplicatedWords = mergeTimedWords(emptyList(), segments.flatMap { it.words })
        val defaultLanguage = segments.firstOrNull()?.language
        val finalSegments = breaker.breakWords(deduplicatedWords).map { group ->
            val score = group.map { minOf(it.confidence!!, it.acousticConfidence!!, it.alignmentConfidence!!) }
                .average().toFloat()
            CaptionSegment(group, group.firstOrNull()?.language ?: defaultLanguage, null, false, score,
                VerificationState.ACCEPTED)
        }
        return CaptionProject(finalSegments, review.distinct(), reviewHypotheses)
    }
}
class CaptionTimelineMapper {
    /** Maps word evidence in the original media through trims and speed changes. */
    fun mapSource(project: Project, clipIndex: Int, captions: CaptionProject): Project {
        val clip = project.videos[clipIndex]
        val clipStart = project.startOf(clipIndex)
        val remapped = captions.segments.mapNotNull { segment ->
            val words = segment.words.filter { it.startMs * 1_000 >= clip.inUs && it.endMs * 1_000 <= clip.outUs }.map { word ->
                word.copy(startMs = ProjectClipTimeMapper.sourceToProject(clip, clipStart, word.startMs * 1_000) / 1_000,
                    endMs = ProjectClipTimeMapper.sourceToProject(clip, clipStart, word.endMs * 1_000) / 1_000)
            }
            if (words.isEmpty()) null else segment.copy(words = words)
        }
        return map(project, captions.copy(segments = remapped))
    }
    fun map(project: Project, captions: CaptionProject, offsetMs: Long = 0): Project {
        require(project.texts.size + captions.segments.size <= MAX_TEXTS) { "Limite de legendas excedido" }
        val normalizer = PtBrTextNormalizer(project.captionVocabulary +
            DynamicEntityContext.fromProject(project).terms)
        val additions = captions.segments.map { segment ->
            val startUs = (segment.startMs + offsetMs) * 1_000
            val endUs = (segment.endMs + offsetMs) * 1_000
            require(endUs <= project.durationUs && startUs < endUs) { "Legenda fora da timeline" }
            TextClip(text = normalizer.normalize(segment.text),
                startUs = startUs, endUs = endUs, isCaption = true,
                wordCues = segment.words.map { word ->
                    SubtitleWordCue(word.text, (word.startMs + offsetMs) * 1_000, (word.endMs + offsetMs) * 1_000)
                }.filter { it.startUs >= startUs && it.endUs <= endUs })
        }
        return project.copy(texts = (project.texts + additions).sortedBy { it.startUs })
    }
}
