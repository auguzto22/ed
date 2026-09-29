package com.termex.replay15.editor.autoedit

import com.termex.replay15.editor.domain.SECOND
import kotlin.math.abs

object AutoEditPlanner {
    fun plan(analysis: ClipAnalysis, options: AutoEditOptions): AutoEditPlan {
        val cuts = if (options.cleanPauses) planCuts(analysis, options) else emptyList()
        val captions = if (options.captions) segmentCaptions(analysis.words, analysis.durationUs) else emptyList()
        val excluded = cuts.map { it.startUs..it.endUs }
        val peaks = meaningfulPeaks(analysis, options).filter { peak -> excluded.none { peak.timeUs in it } }
        val zooms = if (options.smartZoom) planZooms(peaks, options, analysis.durationUs) else emptyList()
        val reframes = if (options.autoReframe) planReframes(analysis.motion, options) else emptyList()
        val effects = planEffects(peaks, options, analysis.durationUs)
        val audio = if (options.audioEnhancement && analysis.hasAudio) planAudio(analysis, options) else null
        return validate(AutoEditPlan(
            clipId = analysis.clipId,
            sourceDurationUs = analysis.durationUs,
            options = options,
            cuts = cuts,
            captions = captions,
            zooms = zooms,
            reframes = reframes,
            effects = effects,
            audio = audio,
            warnings = analysis.warnings + buildList {
                if (options.captions && analysis.words.isEmpty()) add("Sem transcricao temporizada: nenhuma legenda nova foi inventada")
                if (options.autoReframe && reframes.isEmpty()) add("Sem foco visual confiavel: a proporcao original foi preservada")
            },
        ))
    }

    private fun planCuts(analysis: ClipAnalysis, options: AutoEditOptions): List<CutDecision> {
        if (analysis.audio.isEmpty()) return emptyList()
        val ordered = analysis.audio.map { it.rms }.sorted()
        val median = ordered[ordered.size / 2].coerceAtLeast(.0001f)
        val floor = ordered[(ordered.size * .2f).toInt().coerceIn(0, ordered.lastIndex)]
        val threshold = (floor * 1.7f).coerceAtMost(median * .32f).coerceAtLeast(.0004f)
        val minSilence = when (options.cleanup) {
            SpeechCleanup.NATURAL -> 1_150_000L
            SpeechCleanup.BALANCED -> 720_000L
            SpeechCleanup.FAST -> 430_000L
        }
        val keepPause = when (options.cleanup) {
            SpeechCleanup.NATURAL -> 520_000L
            SpeechCleanup.BALANCED -> 300_000L
            SpeechCleanup.FAST -> 170_000L
        }
        val dead = mutableListOf<Pair<Long, Long>>()
        var start: Long? = null
        analysis.audio.forEach { sample ->
            val quiet = sample.rms <= threshold && sample.peak <= threshold * 3.5f
            if (quiet && start == null) start = sample.startUs
            if (!quiet && start != null) { dead += start!! to sample.startUs; start = null }
        }
        start?.let { dead += it to analysis.durationUs }
        return dead.mapNotNull { (rawStart, rawEnd) ->
            if (rawEnd - rawStart < minSilence) return@mapNotNull null
            // Keep breaths and edit only the center of a measured quiet region. With word timing,
            // additionally stay outside every word boundary.
            var cutStart = rawStart + keepPause / 2
            var cutEnd = rawEnd - keepPause / 2
            analysis.words.lastOrNull { it.endUs <= cutStart }?.let { cutStart = maxOf(cutStart, it.endUs + 30_000) }
            analysis.words.firstOrNull { it.startUs >= cutEnd }?.let { cutEnd = minOf(cutEnd, it.startUs - 30_000) }
            val static = analysis.motion.filter { it.timeUs in rawStart..rawEnd }.map { it.amount }.average().let { it.isNaN() || it < .12 }
            if (cutEnd - cutStart < 120_000 || (!static && options.cleanup == SpeechCleanup.NATURAL)) null else
                CutDecision(cutStart, cutEnd, if (static) .9f else .72f, "silencio prolongado${if (static) " e imagem estavel" else ""}")
        }.mergeCuts()
    }

    private fun segmentCaptions(words: List<TimedWord>, durationUs: Long): List<CaptionDecision> {
        if (words.isEmpty()) return emptyList()
        val result = mutableListOf<CaptionDecision>()
        var group = mutableListOf<TimedWord>()
        fun flush() {
            if (group.isEmpty()) return
            val text = group.joinToString(" ") { it.text }.trim()
            val important = group.maxByOrNull { importance(it.text) }?.takeIf { importance(it.text) >= 2 }?.text
            result += CaptionDecision(group.first().startUs, minOf(durationUs, group.last().endUs + 80_000), text, important,
                group.map { it.confidence }.average().toFloat())
            group = mutableListOf()
        }
        words.forEach { word ->
            val gap = group.lastOrNull()?.let { word.startUs - it.endUs } ?: 0L
            val chars = group.sumOf { it.text.length + 1 }
            if (group.isNotEmpty() && (gap > 360_000 || chars + word.text.length > 42 || word.endUs - group.first().startUs > 2_500_000)) flush()
            group += word
            if (word.text.lastOrNull() in listOf('.', '!', '?', ':') && group.size >= 2) flush()
        }
        flush()
        return result
    }

    private fun importance(text: String): Int {
        val clean = text.lowercase().trim('.', ',', '!', '?', ':', ';')
        return when {
            clean in setOf("nunca", "agora", "olha", "absurdo", "primeiro", "melhor", "pior", "incrivel", "impossivel") -> 3
            clean.any(Char::isDigit) || clean.length >= 9 -> 2
            else -> 0
        }
    }

    private fun meaningfulPeaks(analysis: ClipAnalysis, options: AutoEditOptions): List<MotionSample> {
        if (analysis.motion.isEmpty()) return emptyList()
        val audioByTime = analysis.audio
        val scored = analysis.motion.map { motion ->
            val a = audioByTime.minByOrNull { abs((it.startUs + it.endUs) / 2 - motion.timeUs) }
            val audio = a?.let { (it.peak * 5f).coerceIn(0f, 1f) } ?: 0f
            motion to (motion.amount.coerceIn(0f, 1f) * .65f + audio * .35f)
        }
        val variationOffset = when (options.variation % 3) { 1 -> -.035f; 2 -> .035f; else -> 0f }
        val threshold = (when (options.intensity) { AutoEditIntensity.SUBTLE -> .72f; AutoEditIntensity.BALANCED -> .6f; AutoEditIntensity.STRONG -> .5f } + variationOffset)
        val spacing = when (options.style) { AutoEditStyle.GAMING, AutoEditStyle.SHORTS -> 1_450_000L; else -> 2_300_000L }
        val chosen = mutableListOf<MotionSample>()
        scored.sortedByDescending { it.second }.forEach { (sample, score) ->
            if (score >= threshold && chosen.none { abs(it.timeUs - sample.timeUs) < spacing }) chosen += sample.copy(amount = score)
        }
        return chosen.sortedBy { it.timeUs }.take((analysis.durationUs / (spacing * .8)).toInt().coerceIn(1, 8))
    }

    private fun planZooms(peaks: List<MotionSample>, options: AutoEditOptions, durationUs: Long): List<ZoomDecision> {
        val base = when (options.style) {
            AutoEditStyle.CLEAN, AutoEditStyle.CINEMATIC, AutoEditStyle.PODCAST -> .055f
            AutoEditStyle.DYNAMIC, AutoEditStyle.SHORTS -> .1f
            AutoEditStyle.GAMING -> .13f
        }
        return peaks.mapNotNull { peak ->
            val variationScale = when (options.variation % 3) { 1 -> 1.08f; 2 -> .94f; else -> 1f }
            val strength = (base * options.intensity.factor * variationScale * (.75f + peak.amount * .25f)).coerceIn(.03f, .25f)
            val eventDuration = minOf(260_000L, durationUs - peak.timeUs)
            if (eventDuration < 80_000L) null else ZoomDecision(peak.timeUs, eventDuration, 1f + strength,
                peak.amount.coerceIn(0f, 1f), "pico combinado de audio/movimento")
        }
    }

    private fun planReframes(samples: List<MotionSample>, options: AutoEditOptions): List<ReframeDecision> {
        if (samples.isEmpty()) return emptyList()
        val result = mutableListOf<ReframeDecision>()
        val deadZone = if (options.style == AutoEditStyle.GAMING) .18f else .12f
        var lastX = .5f; var lastY = .5f; var lastTime = Long.MIN_VALUE / 2
        samples.filter { it.amount >= .12f }.forEachIndexed { index, sample ->
            val moved = abs(sample.focusX - lastX) > deadZone || abs(sample.focusY - lastY) > deadZone
            if ((index == 0 || moved) && sample.timeUs - lastTime >= 900_000L) {
                // Offset moves opposite to crop focus. Gameplay uses a smaller range to protect HUD/context.
                val range = if (options.style == AutoEditStyle.GAMING) .16f else .28f
                result += ReframeDecision(sample.timeUs, ((.5f - sample.focusX) * range).coerceIn(-.5f, .5f),
                    ((.5f - sample.focusY) * range).coerceIn(-.5f, .5f), sample.amount.coerceIn(0f, 1f), "centro de acao visual")
                lastX = sample.focusX; lastY = sample.focusY; lastTime = sample.timeUs
            }
        }
        return result.take(24)
    }

    private fun planEffects(peaks: List<MotionSample>, options: AutoEditOptions, durationUs: Long): List<EffectDecision> {
        if (options.intensity == AutoEditIntensity.SUBTLE || options.style !in setOf(AutoEditStyle.GAMING, AutoEditStyle.DYNAMIC, AutoEditStyle.SHORTS)) return emptyList()
        val max = if (options.intensity == AutoEditIntensity.STRONG) 3 else 2
        return peaks.sortedByDescending { it.amount }.take(max).filter { it.amount >= .68f }.sortedBy { it.timeUs }.mapNotNull { peak ->
            val duration = if (options.style == AutoEditStyle.GAMING) 240_000L else 180_000L
            val start = (peak.timeUs - 40_000).coerceAtLeast(0)
            val end = minOf(durationUs, peak.timeUs + duration)
            if (end <= start) null else EffectDecision(start, end, "recly_shake",
                (.22f * options.intensity.factor).coerceAtMost(.48f), mapOf("distance" to 6f, "frequency" to 8f, "rotation" to .7f, "decay" to .5f),
                peak.amount.coerceIn(0f, 1f), "impacto visual forte")
        }
    }

    private fun planAudio(analysis: ClipAnalysis, options: AutoEditOptions): AudioDecision? {
        val active = analysis.audio.filter { it.rms > .003f }
        if (active.isEmpty()) return null
        val average = active.map { it.rms }.average().toFloat()
        val target = when { average < .035f -> 1.18f; average > .2f -> .88f; else -> 1f }
        val gain = (target * if (options.intensity == AutoEditIntensity.SUBTLE) .96f else 1f).coerceIn(.75f, 1.25f)
        return AudioDecision(gain, 80_000L, 120_000L, "nivel RMS seguro com fades curtos")
    }

    fun validate(plan: AutoEditPlan): AutoEditPlan {
        fun valid(start: Long, end: Long) = start >= 0 && end > start && end <= plan.sourceDurationUs
        require(plan.cuts.all { valid(it.startUs, it.endUs) }) { "Corte fora do clipe" }
        require(plan.cuts.zipWithNext().all { (a, b) -> a.endUs <= b.startUs }) { "Cortes sobrepostos" }
        require(plan.captions.all { valid(it.startUs, it.endUs) }) { "Legenda fora do clipe" }
        require(plan.zooms.all { it.timeUs + it.durationUs <= plan.sourceDurationUs }) { "Zoom fora do clipe" }
        require(plan.reframes.all { it.timeUs <= plan.sourceDurationUs }) { "Reframe fora do clipe" }
        require(plan.effects.all { valid(it.startUs, it.endUs) }) { "Efeito fora do clipe" }
        require(plan.cuts.sumOf { it.endUs - it.startUs } < plan.sourceDurationUs - 100_000L) { "Plano removeria o clipe inteiro" }
        return plan
    }

    private fun List<CutDecision>.mergeCuts(): List<CutDecision> {
        if (isEmpty()) return this
        val result = mutableListOf<CutDecision>()
        sortedBy { it.startUs }.forEach { cut ->
            val previous = result.lastOrNull()
            if (previous != null && cut.startUs <= previous.endUs + 60_000L) {
                result[result.lastIndex] = previous.copy(endUs = maxOf(previous.endUs, cut.endUs), confidence = maxOf(previous.confidence, cut.confidence))
            } else result += cut
        }
        return result
    }
}
