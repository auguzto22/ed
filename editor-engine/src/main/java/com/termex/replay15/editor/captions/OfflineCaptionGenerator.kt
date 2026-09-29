package com.termex.replay15.editor.captions

import android.content.Context
import com.termex.replay15.editor.domain.Project
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.FileInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Legacy rollback provider. The editor's automatic-caption action now uses GeminiCaptionGenerator;
 * this implementation remains available for offline experiments and rollback without being the
 * default path.
 */
@Deprecated("Use GeminiCaptionGenerator for automatic captions")
class OfflineCaptionGenerator(
    private val context: Context,
    private val rerankingWeights: CaptionRerankingWeights = CaptionRerankingWeights(
        acousticWeight = 1f, contextWeight = .05f, vocabularyWeight = .1f, entityWeight = .1f
    ),
) {
    private val ptBrLexicon by lazy { PtBrLexiconRepository(context.applicationContext) }
    private val mixedVocabulary by lazy { BrazilianMixedVocabulary.load(context) }
    private val ptModel = "vosk-model-small-pt-0.3"
    private val enModel = "vosk-model-small-en-us-0.15"
    private fun modelName(language: String) = if (language == "en-US") enModel else ptModel
    private fun modelDir(name: String) = File(context.filesDir, name)

    fun hasModel(): Boolean = hasVoskModelFiles(modelDir(ptModel))

    fun generate(project: Project, clipIndex: Int, progress: (String) -> Unit, check: () -> Unit): Pair<Project, Int> {
        CaptionDebugLog.configure(context)
        val perf = CaptionPerf()
        val clip = project.videos[clipIndex]
        require(!clip.image) { "Selecione um vídeo com áudio" }
        require(project.captionLanguage in setOf("pt-BR", "en-US", "auto")) { "Idioma de legendas indisponível" }
        val requested = project.captionLanguage
        ensureModel(modelName(requested), progress, check)
        if (requested == "auto") ensureModel(enModel, progress, check)
        check()
        progress("Preparando áudio...")
        val audio = perf.measure("extractAudio") { CaptionAudioExtractor(context).extract(clip.uri, clip.inUs, clip.outUs, check) }
        try {
            progress("Detectando fala... 5%")
            val vad = perf.measure("vad") { perf.vadCalls++; VadTimeline.fromPcm(audio.rawAudio, sensitive = true) }
            val speech = perf.measure("speechDetection") { vad.speechRegions() }
            require(speech.isNotEmpty()) { "Nenhuma região com fala foi encontrada" }
            val language = if (requested == "auto") {
                progress("Identificando idioma da fala...")
                detectLanguage(audio.processedAudio, speech.first(), audio.durationMs, check)
            } else requested
            val selectedModel = modelName(language)
            progress("Carregando modelo de ${if (language == "en-US") "inglês" else "português brasileiro"}...")
            val gapDetector = MissingWordDetector()
            val dirtyConfig = DirtyRegionConfig()
            val dirtyDetector = CaptionDirtyRegionDetector(dirtyConfig)
            val review = mutableListOf<SpeechRegion>()
            val qualities = mutableMapOf<SpeechRegion, CaptionSegmentQuality>()
            val finalVerifier = CaptionMissingWordFinalVerifier()
            val memory = CaptionCorrectionMemory(File(context.filesDir, "caption_corrections/${project.id}.json"))
            val entities = DynamicEntityContext.fromProject(project, mixedVocabulary)
            val userContextTerms = project.captionVocabulary + memory.confirmedTerms()
            val recognitionVocabulary = mixedVocabulary.recognitionTerms(
                userContextTerms + entities.terms
            )
            val rescorer = if (language == "pt-BR") PtBrContextRescorer(ptBrLexicon,
                userContextTerms, memory, mixedVocabulary, entities) else null
            val words = Model(modelDir(selectedModel).absolutePath).use { model ->
                val cache = CaptionRecognitionCache()
                val fingerprint = "${audio.rawAudio.absolutePath}:${audio.rawAudio.length()}:${audio.rawAudio.lastModified()}"
                fun recognize(start: Long, end: Long, variant: AudioVariant,
                    initialPass: Boolean = false, reportProgress: Boolean = false,
                    contextBefore: String? = null, contextFollowing: String? = null): List<CaptionWord> {
                    val key = CaptionRecognitionCache.Key(fingerprint, start, end, selectedModel, variant,
                        language, recognitionVocabulary.sorted().hashCode(), CaptionMode.ULTRA_PRECISE,
                        listOf(contextBefore, contextFollowing).hashCode())
                    cache.get(key)?.let { perf.cacheHits++; return it.words }
                    perf.cacheMisses++; perf.asrCalls++
                    if (initialPass) {
                        if (start == 0L && end == audio.durationMs) perf.fullTranscriptions++
                    } else {
                        perf.localRetranscriptions++
                        if (start == 0L && end == audio.durationMs) perf.fullTranscriptions++
                    }
                    return perf.measure(if (initialPass) "initialChunk" else "localRetranscription") {
                        transcribeWindow(model, if (variant == AudioVariant.RAW) audio.rawAudio else audio.processedAudio,
                            start, end, language, check, if (reportProgress) progress else null,
                            userContextTerms, entities, contextBefore, contextFollowing)
                    }.also { cache.put(key, TranscriptionHypothesis(it, SpeechRegion(start, end, 1f),
                        language, variant)) }
                }
                progress("Transcrevendo...")
                val windows = planInitialCaptionWindows(speech)
                val initial = perf.measure("initialTranscription") {
                    val totalMs = windows.sumOf { it.endMs - it.startMs }.coerceAtLeast(1)
                    var completedMs = 0L
                    var words = emptyList<CaptionWord>()
                    var previousChunkText: String? = null
                    for (window in windows) {
                        check()
                        val chunk = recognize(window.startMs, window.endMs, AudioVariant.PROCESSED,
                            initialPass = true, reportProgress = windows.size == 1,
                            contextBefore = previousChunkText)
                        words = mergeTimedWords(words, chunk)
                        previousChunkText = chunk.joinToString(" ") { it.text }.takeIf(String::isNotBlank)
                        completedMs += window.endMs - window.startMs
                        progress("Transcrevendo: ${10 + completedMs * 60 / totalMs}%")
                    }
                    words
                }
                progress("Verificando legendas... 80%")
                val gaps = mutableListOf<SpeechGap>()
                val dirty = perf.measure("missingWordDetection") {
                    speech.flatMap { region ->
                        check()
                        val scoped = initial.filter { it.endMs > region.startMs && it.startMs < region.endMs }
                        val found = gapDetector.findUnexplainedSpeech(region, scoped, vad)
                        gaps += found
                        val detected = dirtyDetector.detect(region, scoped, found,
                            gapDetector.quality(region, scoped, vad, found)).toMutableList()
                        if (CaptionIncoherenceDetector().needsRetry(scoped, region)) {
                            detected += CaptionDirtyRegion(region.startMs, region.endMs, setOf(DirtyReason.INCOHERENT))
                        }
                        detected
                    }
                }
                val mergedDirty = mergeNearbyDirtyRegions(dirty, dirtyConfig.mergeDistanceMs)
                perf.dirtyRegions = mergedDirty.size
                CaptionDebugLog.d("CaptionPerf", "dirtyRegions = ${mergedDirty.size}")
                val recovery = MissingWordRecoveryEngine(transcribe = { _, _ -> emptyList() })
                var merged = initial
                for ((index, region) in mergedDirty.withIndex()) {
                    check()
                    progress("Corrigindo trecho ${index + 1}/${mergedDirty.size}...")
                    perf.measure("recoveryRegion[$index]") {
                        val localGaps = gaps.filter { it.startMs < region.endMs && it.endMs > region.startMs }
                        val start = (region.startMs - dirtyConfig.contextMs).coerceAtLeast(0)
                        val end = (region.endMs + dirtyConfig.contextMs).coerceAtMost(audio.durationMs)
                        val contextBefore = merged.filter { it.endMs <= region.startMs }.takeLast(8)
                            .joinToString(" ") { it.text }.takeIf(String::isNotBlank)
                        val contextFollowing = merged.filter { it.startMs >= region.endMs }.take(8)
                            .joinToString(" ") { it.text }.takeIf(String::isNotBlank)
                        // Two different signals and spans provide independent evidence, within a fixed retry budget.
                        var state = region
                        val processed = if (state.attemptCount < dirtyConfig.maxRecoveryAttempts) {
                            state = state.copy(attemptCount = state.attemptCount + 1)
                            recognize(start, end, AudioVariant.PROCESSED,
                                contextBefore = contextBefore, contextFollowing = contextFollowing)
                        } else emptyList()
                        val rawStart = (start - dirtyConfig.contextMs).coerceAtLeast(0)
                        val rawEnd = (end + dirtyConfig.contextMs).coerceAtMost(audio.durationMs)
                        val raw = if (state.attemptCount < dirtyConfig.maxRecoveryAttempts) {
                            state = state.copy(attemptCount = state.attemptCount + 1)
                            perf.retries++
                            recognize(rawStart, rawEnd, AudioVariant.RAW,
                                contextBefore = contextBefore, contextFollowing = contextFollowing)
                        } else emptyList()
                        CaptionDebugLog.d("CaptionPerf", "recoveryRegion[$index] attempts=${state.attemptCount}")
                        merged = confirmLocalWords(merged, region, processed, raw, rescorer = rescorer)
                        for (gap in localGaps) {
                            merged = mergeTimedWords(merged, recovery.recoverFromHypotheses(gap, vad, processed, raw))
                        }
                    }
                    progress("Corrigindo trechos: ${index + 1}/${mergedDirty.size} (${90 + (index + 1) * 8 / mergedDirty.size}%)")
                }
                perf.measure("finalVerification") {
                    for (region in speech) {
                        check()
                        val scoped = merged.filter { it.endMs > region.startMs && it.startMs < region.endMs }
                        val quality = gapDetector.quality(region, scoped, vad)
                        qualities[region] = quality
                        if (!finalVerifier.accepted(quality)) review += region
                    }
                }
                merged.sortedBy { it.startMs }
            }
            check()
            progress("Sincronizando palavras... 98%")
            val segments = CaptionLineBreaker().breakWords(words).map { group ->
                val quality = group.mapNotNull { it.confidence }.takeIf { it.size == group.size }
                    ?.average()?.toFloat()
                val speechRegion = speech.firstOrNull { region -> group.any { it.startMs < region.endMs && it.endMs > region.startMs } }
                val confirmed = speechRegion != null && speechRegion !in review &&
                    group.all { (it.confidence ?: 0f) >= dirtyConfig.highConfidence && it.startMs < it.endMs } &&
                    group.zipWithNext().none { (before, after) -> before.endMs > after.startMs }
                CaptionSegment(if (confirmed) group.map { it.copy(verificationState = VerificationState.ACCEPTED) } else group,
                    language, null, false, quality,
                    if (confirmed) VerificationState.ACCEPTED else VerificationState.NEEDS_REVIEW,
                    speechRegion?.let(qualities::get))
            }
            require(segments.isNotEmpty()) { "Nenhuma fala foi reconhecida neste vídeo" }
            val source = CaptionProject(segments.map { segment ->
                segment.copy(words = segment.words.map { it.copy(
                    startMs = it.startMs + clip.inUs / 1_000,
                    endMs = it.endMs + clip.inUs / 1_000
                ) })
            }, review.map { it.copy(startMs = it.startMs + clip.inUs / 1_000,
                endMs = it.endMs + clip.inUs / 1_000) })
            progress("Finalizando legendas... 99%")
            val result = CaptionTimelineMapper().mapSource(project, clipIndex, source)
            return result to (result.texts.size - project.texts.size)
        } finally {
            perf.finish()
            audio.delete()
        }
    }

    private fun transcribeWindow(model: Model, pcm: File, startMs: Long, endMs: Long, language: String,
        check: () -> Unit, progress: ((String) -> Unit)? = null,
        vocabulary: Set<String> = emptySet(), entities: DynamicEntityContext = DynamicEntityContext.empty(),
        contextBefore: String? = null, contextFollowing: String? = null): List<CaptionWord> {
        if (endMs <= startMs) return emptyList()
        return Recognizer(model, 16_000f).use { recognizer ->
            recognizer.setWords(true)
            // Vosk exposes measured N-best alternatives. We rank them only
            // after decoding; no grammar is installed, so free PT-BR speech is
            // not constrained by the vocabulary.
            recognizer.setMaxAlternatives(4)
            val found = mutableListOf<CaptionWord>()
            val reranker = CaptionHypothesisReranker(
                weights = rerankingWeights,
                vocabulary = mixedVocabulary,
                entities = entities,
                additionalVocabulary = vocabulary,
            )
            var previousText: String? = contextBefore
            fun consume(json: String) {
                val candidates = parseVoskCandidates(json, endMs - startMs, language, startMs)
                if (candidates.isEmpty()) return
                val hypotheses = candidates.map { candidate ->
                    TranscriptionHypothesis(candidate.words, SpeechRegion(startMs, endMs, 1f), language,
                        AudioVariant.PROCESSED)
                }
                val ranked = reranker.rank(hypotheses, previousText, contextFollowing)
                CaptionDebugLog.d("ASR DEBUG", "Segment: ${startMs}ms – ${endMs}ms")
                ranked.forEachIndexed { index, score ->
                    CaptionDebugLog.d("ASR DEBUG", "Raw candidate ${index + 1}: ${score.hypothesis.words.joinToString(" ") { it.text }} " +
                        "acoustic=${score.acousticScore} context=${score.contextScore} vocabulary=${score.vocabularyScore} entity=${score.entityScore}")
                }
                val chosen = ranked.firstOrNull()?.hypothesis
                if (chosen != null) {
                    val selected = chosen.words.mapIndexed { index, word ->
                        val alternatives = ranked.drop(1).mapNotNull { score ->
                            score.hypothesis.words.getOrNull(index)?.let { alternative ->
                                WordCandidate(alternative.text, alternative.confidence ?: 0f)
                            }
                        }.distinctBy { it.text.lowercase() }
                        word.copy(alternatives = alternatives)
                    }
                    found += selected
                    previousText = selected.joinToString(" ") { it.text }
                    CaptionDebugLog.d("ASR DEBUG", "Final: ${previousText.orEmpty()}")
                }
            }
            val buffer = ByteArray(8_192)
            FileInputStream(pcm).buffered().use { input ->
                var offset = startMs * 32
                while (offset > 0) {
                    check()
                    val skipped = input.skip(offset)
                    if (skipped <= 0) break
                    offset -= skipped
                }
                if (offset > 0) return@use
                var remaining = (endMs - startMs) * 32
                var done = 0L
                while (remaining > 0) {
                    check()
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) break
                    if (recognizer.acceptWaveForm(buffer, count)) consume(recognizer.result)
                    remaining -= count; done += count
                    if (progress != null && done % 262_144 < count)
                        progress("Transcrevendo: ${(10 + done * 60 / ((endMs - startMs) * 32).coerceAtLeast(1)).coerceAtMost(70)}%")
                }
            }
            consume(recognizer.finalResult)
            found.sortedBy { it.startMs }.distinctBy { "${it.text.lowercase()}@${it.startMs}@${it.endMs}" }
        }
    }

    private data class VoskCandidate(val words: List<CaptionWord>, val decoderConfidence: Float?)

    private fun parseVoskCandidates(json: String, durationMs: Long, language: String,
        offsetMs: Long): List<VoskCandidate> {
        val root = JSONObject(json)
        val alternatives = root.optJSONArray("alternatives")
        val candidates = if (alternatives != null) {
            (0 until alternatives.length()).mapNotNull { index ->
                val item = alternatives.optJSONObject(index) ?: return@mapNotNull null
                val words = parseTimedWords(item.optJSONArray("result"), durationMs, language, offsetMs)
                if (words.isEmpty()) null else VoskCandidate(words,
                    item.optDouble("confidence", -1.0).takeIf { it in 0.0..1.0 }?.toFloat())
            }.ifEmpty {
                // Some Vosk builds omit per-word timing from an alternatives
                // item. Never synthesize timing from its text; use the measured
                // top-level result when that build also provides it.
                val words = parseTimedWords(root.optJSONArray("result"), durationMs, language, offsetMs)
                if (words.isEmpty()) emptyList() else listOf(VoskCandidate(words, null))
            }
        } else {
            val words = parseTimedWords(root.optJSONArray("result"), durationMs, language, offsetMs)
            if (words.isEmpty()) emptyList() else listOf(VoskCandidate(words, null))
        }
        return candidates
    }

    private fun parseTimedWords(items: org.json.JSONArray?, durationMs: Long, language: String,
        offsetMs: Long): List<CaptionWord> {
        if (items == null) return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val text = item.optString("word").trim()
            val start = (item.optDouble("start", -1.0) * 1_000).toLong()
            val end = (item.optDouble("end", -1.0) * 1_000).toLong().coerceAtMost(durationMs)
            val confidence = item.optDouble("conf", -1.0).takeIf { it in 0.0..1.0 }?.toFloat()
            if (text.isBlank() || start < 0 || end <= start) return@mapNotNull null
            CaptionWord(text = text, startMs = start + offsetMs, endMs = end + offsetMs,
                confidence = confidence,
                // Vosk's token posterior is acoustic/decoder evidence. It
                // is not copied into alignmentConfidence: Vosk does not
                // provide a separate forced-alignment confidence.
                acousticConfidence = confidence, alignmentConfidence = null, language = language,
                verificationState = when {
                    confidence == null -> VerificationState.NEEDS_REVIEW
                    confidence < .75f -> VerificationState.LOW_CONFIDENCE
                    else -> VerificationState.UNCHECKED
                })
        }
    }

    private fun detectLanguage(pcm: File, firstSpeech: SpeechRegion, durationMs: Long, check: () -> Unit): String {
        val start = firstSpeech.startMs.coerceAtLeast(0)
        val end = minOf(durationMs, maxOf(firstSpeech.endMs, start + 4_000), start + 8_000)
        if (end - start < 400) return "pt-BR"
        fun score(language: String): Float {
            val words = Model(modelDir(modelName(language)).absolutePath).use { model ->
                transcribeWindow(model, pcm, start, end, language, check)
            }
            if (words.isEmpty()) return 0f
            val confidence = words.mapNotNull { it.confidence }.average().takeUnless(Double::isNaN)?.toFloat() ?: 0f
            val coverage = words.sumOf { it.endMs - it.startMs }.toFloat() / (end - start)
            return confidence * .8f + coverage.coerceIn(0f, 1f) * .2f
        }
        val pt = score("pt-BR")
        val en = score("en-US")
        // The two models' confidence scales are not perfectly calibrated. Favor PT-BR on close calls.
        return if (en > pt + .12f) "en-US" else "pt-BR"
    }

    private fun ensureModel(name: String, progress: (String) -> Unit, check: () -> Unit) {
        val destination = modelDir(name)
        if (hasVoskModelFiles(destination)) return
        val archive = File(context.cacheDir, "$name.zip.part")
        val staging = File(context.filesDir, "$name.install")
        archive.delete()
        staging.deleteRecursively()
        try {
            progress("Baixando modelo de ${if (name == enModel) "inglês" else "português brasileiro"}...")
            val connection = (URL("https://alphacephei.com/vosk/models/$name.zip").openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
            }
            try {
                require(connection.responseCode == 200) { "Falha no download do modelo: HTTP ${connection.responseCode}" }
                val total = connection.contentLengthLong
                var downloaded = 0L
                connection.inputStream.use { input -> archive.outputStream().buffered().use { output ->
                    val buffer = ByteArray(32_768)
                    while (true) {
                        check()
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        require(downloaded <= 100_000_000) { "Arquivo do modelo excedeu o limite" }
                        output.write(buffer, 0, count)
                        if (total > 0 && downloaded % 524_288 < count)
                            progress("Baixando modelo: ${(downloaded * 100 / total).coerceAtMost(100)}%")
                    }
                } }
            } finally { connection.disconnect() }
            progress("Instalando modelo de ${if (name == enModel) "inglês" else "português brasileiro"}...")
            var extracted = 0L
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    check()
                    val entry = zip.nextEntry ?: break
                    val prefix = "$name/"
                    require(entry.name.startsWith(prefix)) { "Estrutura do modelo inválida" }
                    val relative = entry.name.removePrefix(prefix)
                    val target = File(staging, relative)
                    require(target.canonicalPath.startsWith(staging.canonicalPath + File.separator) || relative.isEmpty()) { "Caminho inválido no modelo" }
                    if (entry.isDirectory) target.mkdirs() else {
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(32_768)
                            while (true) {
                                check()
                                val count = zip.read(buffer)
                                if (count < 0) break
                                extracted += count
                                require(extracted <= 150_000_000) { "Modelo descompactado excedeu o limite" }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            require(hasVoskModelFiles(staging)) { "Modelo baixado está incompleto" }
            destination.deleteRecursively()
            require(staging.renameTo(destination)) { "Não foi possível instalar o modelo" }
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }
}

/** Vosk supports both its current directory layout and the older Portuguese model layout. */
internal fun hasVoskModelFiles(directory: File): Boolean {
    val current = listOf("am/final.mdl", "conf/model.conf", "graph/words.txt")
    val legacy = listOf("final.mdl", "mfcc.conf", "Gr.fst", "HCLr.fst", "disambig_tid.int")
    return current.all { File(directory, it).isFile } || legacy.all { File(directory, it).isFile }
}
