package com.termex.replay15.editor.reference

import android.content.Context
import android.net.Uri
import android.util.Log
import com.termex.replay15.editor.domain.newId
import kotlin.math.abs

class ReferenceAnalysisCancellation {
    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }
    fun check() { if (cancelled || Thread.currentThread().isInterrupted) throw InterruptedException("Analise cancelada") }
}

enum class ReferenceAnalysisStage(val label: String) {
    PREPARING("Preparando"), CUTS("Detectando cortes"), MOTION("Analisando movimento"), EFFECTS("Analisando efeitos"),
    CAPTIONS("Analisando legendas"), AUDIO("Analisando audio"), STYLE("Criando estilo")
}

class ReferenceStyleAnalyzer(
    private val context: Context, private val config: FrameAnalysisConfig = FrameAnalysisConfig(),
) {
    fun analyze(uri: Uri, cancellation: ReferenceAnalysisCancellation, progress: (ReferenceAnalysisStage) -> Unit): ReferenceStyleProfile {
        progress(ReferenceAnalysisStage.PREPARING); cancellation.check()
        val media = ReferenceMediaProbe.probe(context, uri)
        val warnings = mutableListOf<String>(); val events = mutableListOf<ReferenceEvent>()
        val coarseFrames = mutableListOf<FrameFeatures>(); val coarseDeltas = mutableListOf<FrameDelta>()
        progress(ReferenceAnalysisStage.CUTS)
        runModule("visual-base", warnings) {
            var previous: FrameFeatures? = null
            ReferenceFrameSampler(context, config).coarse(media, cancellation) { frame ->
                previous?.let { coarseDeltas += GlobalMotionEstimator.delta(it, frame) }
                coarseFrames += frame.copy(luma = ByteArray(0)); previous = frame
            }
            events += ReferenceVisualDetectors.sceneEvents(coarseFrames, coarseDeltas)
            events += ReferenceVisualDetectors.flashes(coarseFrames)
        }
        progress(ReferenceAnalysisStage.MOTION); cancellation.check()
        runModule("motion", warnings) { events += ReferenceVisualDetectors.motion(coarseDeltas) }
        progress(ReferenceAnalysisStage.EFFECTS); cancellation.check()
        runModule("refinement", warnings) {
            val centers = (events.map { it.peakUs } + coarseDeltas.sortedByDescending { it.difference }.take(48).map { it.timeUs }).distinct().take(160)
            val frames = mutableListOf<FrameFeatures>(); val deltas = mutableListOf<FrameDelta>(); var previous: FrameFeatures? = null
            ReferenceFrameSampler(context, config).refine(media, centers, cancellation) { frame ->
                if (previous != null && frame.timeUs - previous!!.timeUs <= config.refinementStepUs * 2) deltas += GlobalMotionEstimator.delta(previous!!, frame)
                frames += frame.copy(luma = ByteArray(0)); previous = frame
            }
            val refined = ReferenceVisualDetectors.flashes(frames) + ReferenceVisualDetectors.motion(deltas) + ReferenceVisualDetectors.blurAndRgb(frames, deltas)
            mergeRefined(events, refined)
            events += detectSpeedAndUnknown(deltas, events)
        }
        progress(ReferenceAnalysisStage.CAPTIONS); cancellation.check()
        val captions = runModule("captions", warnings, CaptionStyleProfile()) { TextStyleAnalyzer.analyze(context, media, cancellation) }
        progress(ReferenceAnalysisStage.AUDIO); cancellation.check()
        val audio = runModule("audio", warnings, emptyList()) { ReferenceAudioAnalyzer.analyze(context, media, cancellation) }
        val beats = runModule("beats", warnings, emptyList()) { ReferenceAudioAnalyzer.beats(audio) }
        progress(ReferenceAnalysisStage.STYLE); cancellation.check()
        return ReferenceStyleProfileBuilder.build(media, events.distinctBy { it.type to it.peakUs }.sortedBy { it.startUs }, beats, captions, coarseFrames, warnings)
    }

    private fun mergeRefined(base: MutableList<ReferenceEvent>, refined: List<ReferenceEvent>) {
        refined.forEach { event ->
            val existing = base.indices.minByOrNull { abs(base[it].peakUs - event.peakUs) }
            if (existing != null && base[existing].type == event.type && abs(base[existing].peakUs - event.peakUs) < 220_000L) {
                if (event.confidence >= base[existing].confidence) base[existing] = event
            } else base += event
        }
    }

    private fun detectSpeedAndUnknown(deltas: List<FrameDelta>, known: List<ReferenceEvent>): List<ReferenceEvent> {
        if (deltas.size < 8) return emptyList()
        val result = mutableListOf<ReferenceEvent>()
        val movement = deltas.map { kotlin.math.hypot(it.translationX.toDouble(), it.translationY.toDouble()).toFloat() }
        for (i in 3 until deltas.size - 3) {
            val before = movement.subList(i - 3, i).average().toFloat(); val after = movement.subList(i, i + 3).average().toFloat()
            val ratio = after / before.coerceAtLeast(.002f)
            if ((ratio > 2.4f || ratio < .42f) && deltas[i].continuity > .68f && known.none { abs(it.peakUs - deltas[i].timeUs) < 300_000L }) {
                result += ReferenceEvent(ReferenceEventType.SPEED_RAMP, deltas[i - 1].timeUs, deltas[i].timeUs, deltas[i + 2].timeUs,
                    abs(ratio - 1f).coerceAtMost(3f) / 3f, .44f, mapOf("speedRatio" to ratio.coerceIn(.1f, 8f)), "progressive temporal motion change")
            }
            if (deltas[i].difference > .62f && known.none { abs(it.peakUs - deltas[i].timeUs) < 180_000L }) {
                result += ReferenceEvent(ReferenceEventType.UNKNOWN_EFFECT, deltas[i - 1].timeUs, deltas[i].timeUs, deltas[i + 1].timeUs,
                    deltas[i].difference, .36f, mapOf("horizontalDisplacement" to deltas[i].translationX, "verticalDisplacement" to deltas[i].translationY,
                        "luminanceChange" to deltas[i].luminanceDifference, "rgbOffset" to deltas[i].rgbSeparation), "unmatched short visual discontinuity")
            }
        }
        return result.take(80)
    }

    private inline fun <T> runModule(name: String, warnings: MutableList<String>, fallback: T, block: () -> T): T = try { block() } catch (cancelled: InterruptedException) { throw cancelled } catch (error: Exception) {
        Log.w("ReferenceStyle", "Module $name unavailable: ${error.javaClass.simpleName}")
        warnings += "$name indisponivel; os demais sinais foram preservados"
        fallback
    }
    private inline fun runModule(name: String, warnings: MutableList<String>, block: () -> Unit) = runModule(name, warnings, Unit, block)
}

object ReferenceStyleProfileBuilder {
    fun build(media: ReferenceMediaInfo, rawEvents: List<ReferenceEvent>, beats: List<BeatEvent>, captions: CaptionStyleProfile,
        frames: List<FrameFeatures>, warnings: List<String>): ReferenceStyleProfile {
        val events = removeOutliers(rawEvents)
        val cuts = events.filter { it.type in setOf(ReferenceEventType.HARD_CUT, ReferenceEventType.FADE, ReferenceEventType.CROSSFADE, ReferenceEventType.MOTION_TRANSITION) }
        val intervals = cuts.map { it.peakUs }.sorted().zipWithNext().map { (a, b) -> b - a }.filter { it >= 80_000L }
        val zooms = events.filter { it.type == ReferenceEventType.ZOOM }
        val shakes = events.filter { it.type == ReferenceEventType.SHAKE }
        val flashes = events.filter { it.type == ReferenceEventType.FLASH }
        val aligned = events.filter { it.type !in setOf(ReferenceEventType.TEXT, ReferenceEventType.UNKNOWN_EFFECT) }.mapNotNull { event ->
            beats.minOfOrNull { abs(it.timeUs - event.peakUs) }?.let { it <= 90_000L }
        }
        val combinations = correlate(events)
        val evidence = (cuts.size * 1.2f + zooms.size + shakes.size + flashes.size + beats.size * .12f + if (captions.present) 2f else 0f)
        val confidence = ((1f - kotlin.math.exp((-evidence / 9f).toDouble()).toFloat()) *
            events.map { it.confidence }.average().takeUnless(Double::isNaN)?.toFloat().orEmpty(.45f).coerceAtLeast(.35f)).coerceIn(.12f, .94f)
        val dominant = cuts.groupingBy { it.type }.eachCount().maxByOrNull { it.value }?.key
        return ReferenceStyleProfile(newId(), media.displayName.substringBeforeLast('.').take(90).ifBlank { "Estilo de referencia" }, media.fingerprint, media.uri,
            createdAtMs = System.currentTimeMillis(), sourceDurationUs = media.durationUs, cutCount = cuts.size,
            averageCutIntervalUs = intervals.average().takeUnless(Double::isNaN)?.toLong() ?: 0L, medianCutIntervalUs = intervals.medianLong(),
            cutIntervalDistributionUs = intervals.sorted().let { list -> if (list.size <= 20) list else List(20) { list[it * (list.lastIndex) / 19] } }, dominantCutType = dominant,
            events = events, beats = beats, zoomsPerMinute = zooms.size * 60_000_000f / media.durationUs,
            medianZoomScale = zooms.mapNotNull { it.values["scale"] }.median().takeIf { it > 0f } ?: 1f,
            medianShakeDurationUs = shakes.map { it.endUs - it.startUs }.medianLong(), medianFlashDurationUs = flashes.map { it.endUs - it.startUs }.medianLong(),
            speedRampUsage = events.count { it.type == ReferenceEventType.SPEED_RAMP } * 60_000_000f / media.durationUs,
            captionStyle = captions, beatAlignment = if (aligned.isEmpty()) 0f else aligned.count { it }.toFloat() / aligned.size,
            effectCombinations = combinations, colorSaturation = frames.map { it.saturation }.median(), colorContrast = frames.map { it.edgeEnergy }.median(),
            colorTemperature = frames.map { it.temperature }.median(), overallConfidence = confidence, warnings = warnings)
    }

    private fun removeOutliers(events: List<ReferenceEvent>): List<ReferenceEvent> = events.filter { it.confidence >= .25f }.groupBy { it.type }.flatMap { (_, group) ->
        if (group.size < 4) group else { val strengths = group.map { it.strength }; val median = strengths.median(); val mad = strengths.map { abs(it - median) }.median().coerceAtLeast(.08f); group.filter { abs(it.strength - median) <= mad * 4.5f } }
    }.sortedBy { it.startUs }

    private fun correlate(events: List<ReferenceEvent>): List<EffectCombination> {
        val impactTypes = setOf(ReferenceEventType.ZOOM, ReferenceEventType.SHAKE, ReferenceEventType.FLASH, ReferenceEventType.BLUR, ReferenceEventType.RGB_SPLIT)
        val groups = mutableListOf<List<ReferenceEvent>>(); val candidates = events.filter { it.type in impactTypes }.sortedBy { it.peakUs }
        candidates.forEach { event ->
            val group = candidates.filter { abs(it.peakUs - event.peakUs) <= 120_000L }.distinctBy { it.type }.sortedBy { it.type.name }
            if (group.size >= 2 && groups.none { it.map(ReferenceEvent::type) == group.map(ReferenceEvent::type) }) groups += group
        }
        return groups.map { group ->
            val types = group.map { it.type }; val anchors = candidates.filter { it.type == types.first() }
            val occurrences = anchors.count { anchor -> types.all { type -> candidates.any { it.type == type && abs(it.peakUs - anchor.peakUs) <= 120_000L } } }
            EffectCombination(types, occurrences, group.map { it.peakUs - group.first().peakUs }, (group.map { it.confidence }.average() * (occurrences / 3f).coerceAtMost(1f)).toFloat())
        }.filter { it.occurrences >= 2 }.sortedByDescending { it.occurrences }.take(8)
    }

    private fun Float?.orEmpty(default: Float) = this ?: default
}
