package com.termex.replay15.editor.reference

import com.termex.replay15.editor.autoedit.*
import com.termex.replay15.editor.domain.SECOND
import kotlin.math.abs
import kotlin.math.roundToInt

data class ReferenceSpeedChange(val timeUs: Long, val durationUs: Long, val peakSpeed: Float)
data class ReferenceStyleEditPlan(
    val profileId: String, val base: AutoEditPlan, val speedChanges: List<ReferenceSpeedChange>,
    val unsupportedEffects: List<ReferenceEventType>, val warnings: List<String>,
)

object ReferenceEffectMapper {
    private val assets = mapOf(
        ReferenceEventType.SHAKE to "recly_shake", ReferenceEventType.FLASH to "recly_flash",
        ReferenceEventType.RGB_SPLIT to "recly_rgb", ReferenceEventType.BLUR to "recly_gaussian",
    )
    fun asset(type: ReferenceEventType) = assets[type]
    fun unsupported(profile: ReferenceStyleProfile) = profile.events.map { it.type }.distinct().filter {
        it in setOf(ReferenceEventType.UNKNOWN_EFFECT) || (it in setOf(ReferenceEventType.SHAKE, ReferenceEventType.FLASH, ReferenceEventType.RGB_SPLIT, ReferenceEventType.BLUR) && asset(it) == null)
    }
}

object ReferenceStylePlanner {
    fun plan(profile: ReferenceStyleProfile, analysis: ClipAnalysis, intensity: ReferenceIntensity = ReferenceIntensity.BALANCED): ReferenceStyleEditPlan {
        val factor = when (intensity) { ReferenceIntensity.SUBTLE -> .7f; ReferenceIntensity.BALANCED -> 1f; ReferenceIntensity.STRONG -> 1.25f }
        val options = AutoEditOptions(style = AutoEditStyle.CLEAN, intensity = when (intensity) {
            ReferenceIntensity.SUBTLE -> AutoEditIntensity.SUBTLE; ReferenceIntensity.BALANCED -> AutoEditIntensity.BALANCED; ReferenceIntensity.STRONG -> AutoEditIntensity.STRONG
        }, cleanup = SpeechCleanup.NATURAL, captions = profile.captionStyle.present, cleanPauses = profile.cutCount > 0,
            smartZoom = false, autoReframe = profile.events.any { it.type == ReferenceEventType.ZOOM }, audioEnhancement = profile.beats.isNotEmpty())
        val foundation = AutoEditPlanner.plan(analysis, options)
        val targets = contentEvents(analysis)
        val referenceRate = profile.events.groupingBy { it.type }.eachCount().mapValues { (_, count) -> count * 60_000_000f / profile.sourceDurationUs }
        val minSpacing = maxOf(650_000L, (profile.medianCutIntervalUs.takeIf { it > 0 } ?: 1_600_000L) / 2)
        fun selected(type: ReferenceEventType): List<TargetCandidate> {
            val desired = ((referenceRate[type] ?: 0f) * analysis.durationUs / 60_000_000f * factor).roundToInt().coerceIn(0, 8)
            val result = mutableListOf<TargetCandidate>()
            targets.sortedWith(compareByDescending<TargetCandidate> { it.score }.thenBy { it.timeUs }).forEach { target ->
                if (result.size < desired && result.none { abs(it.timeUs - target.timeUs) < minSpacing }) result += target
            }
            return result.sortedBy { it.timeUs }
        }
        val zoomStrength = ((profile.medianZoomScale.takeIf { it > 1f } ?: 1.08f) - 1f) * factor
        val zooms = selected(ReferenceEventType.ZOOM).mapNotNull { target ->
            val duration = minOf(profile.events.filter { it.type == ReferenceEventType.ZOOM }.map { it.endUs - it.startUs }.medianLong().takeIf { it >= 80_000L } ?: 240_000L,
                analysis.durationUs - target.timeUs).coerceAtMost(900_000L)
            if (duration < 80_000L) null else ZoomDecision(target.timeUs, duration, 1f + zoomStrength.coerceIn(.03f, .25f), target.score, target.reason)
        }
        val effectDecisions = mutableListOf<EffectDecision>()
        listOf(ReferenceEventType.SHAKE, ReferenceEventType.FLASH, ReferenceEventType.RGB_SPLIT, ReferenceEventType.BLUR).forEach { type ->
            val samples = profile.events.filter { it.type == type }; val asset = ReferenceEffectMapper.asset(type) ?: return@forEach
            val duration = samples.map { it.endUs - it.startUs }.medianLong().coerceIn(70_000L, 650_000L)
            val strength = samples.map { it.strength }.median().takeIf { it > 0 } ?: .4f
            selected(type).forEach { target ->
                val start = (target.timeUs - duration / 5).coerceAtLeast(0); val end = minOf(analysis.durationUs, start + duration)
                if (end > start) effectDecisions += EffectDecision(start, end, asset, (strength * factor).coerceIn(.08f, .9f), values(type, samples, strength, duration), target.score, target.reason)
            }
        }
        // Preserve repeated combinations by co-locating missing members on the strongest content events.
        profile.effectCombinations.filter { it.confidence >= .45f }.take(2).forEach { combination ->
            val anchor = targets.maxByOrNull { it.score } ?: return@forEach
            combination.types.filter { it != ReferenceEventType.ZOOM }.forEach { type ->
                val asset = ReferenceEffectMapper.asset(type) ?: return@forEach
                if (effectDecisions.none { it.assetId == asset && abs(it.startUs - anchor.timeUs) < 180_000L }) {
                    val end = minOf(analysis.durationUs, anchor.timeUs + 180_000L)
                    if (end > anchor.timeUs) effectDecisions += EffectDecision(anchor.timeUs, end, asset, .28f * factor, values(type, emptyList(), .35f, 180_000L), anchor.score, "combinacao de impacto observada")
                }
            }
        }
        val boundedEffects = effectDecisions.sortedWith(compareByDescending<EffectDecision> { it.confidence }.thenBy { it.startUs }).take(12).sortedBy { it.startUs }
        val speed = selected(ReferenceEventType.SPEED_RAMP).take(2).map { target -> ReferenceSpeedChange(target.timeUs, 500_000L, (1f + .45f * factor).coerceAtMost(2f)) }
        val warnings = buildList {
            addAll(foundation.warnings)
            if (profile.overallConfidence < .4f) add("A referencia contem pouca evidencia; a aplicacao foi conservadora")
            if (ReferenceEffectMapper.unsupported(profile).isNotEmpty()) add("Elementos desconhecidos foram preservados no perfil, mas nao aplicados")
        }
        val plan = foundation.copy(zooms = zooms, effects = boundedEffects, warnings = warnings)
        return ReferenceStyleEditPlan(profile.id, AutoEditPlanner.validate(plan), speed, ReferenceEffectMapper.unsupported(profile), warnings)
    }

    private data class TargetCandidate(val timeUs: Long, val score: Float, val reason: String)
    private fun contentEvents(analysis: ClipAnalysis): List<TargetCandidate> {
        val audio = analysis.audio.mapIndexedNotNull { index, sample ->
            val local = analysis.audio.subList((index - 8).coerceAtLeast(0), (index + 9).coerceAtMost(analysis.audio.size))
            val median = local.map { it.peak }.median(); val previous = analysis.audio.getOrNull(index - 1)?.peak ?: sample.peak
            val score = ((sample.peak - median).coerceAtLeast(0f) * 2.2f + (sample.peak - previous).coerceAtLeast(0f)).coerceIn(0f, 1f)
            if (score >= .18f) TargetCandidate((sample.startUs + sample.endUs) / 2, score, "pico de audio do novo clipe") else null
        }
        val motion = analysis.motion.mapNotNull { sample -> if (sample.amount >= .28f) TargetCandidate(sample.timeUs, sample.amount.coerceIn(0f, 1f), "impacto visual do novo clipe") else null }
        return (audio + motion).sortedBy { it.timeUs }.fold(mutableListOf<TargetCandidate>()) { result, candidate ->
            val near = result.indexOfLast { abs(it.timeUs - candidate.timeUs) <= 120_000L }
            if (near >= 0) { val old = result[near]; result[near] = TargetCandidate((old.timeUs + candidate.timeUs) / 2, (old.score * .45f + candidate.score * .55f).coerceAtMost(1f), "pico combinado de audio e movimento") }
            else result += candidate
            result
        }
    }

    private fun values(type: ReferenceEventType, samples: List<ReferenceEvent>, strength: Float, duration: Long): Map<String, Float> = when (type) {
        ReferenceEventType.SHAKE -> mapOf("distance" to (strength * 18f).coerceIn(2f, 24f), "frequency" to (samples.mapNotNull { it.values["frequency"] }.median().takeIf { it > 0 } ?: 8f).coerceIn(.1f, 20f), "rotation" to (strength * 2f).coerceIn(0f, 5f), "decay" to (samples.mapNotNull { it.values["decay"] }.median().takeIf { it > 0 } ?: .55f))
        ReferenceEventType.FLASH -> mapOf("amount" to (strength * .8f).coerceIn(.08f, .9f), "frequency" to (SECOND.toFloat() / duration.coerceAtLeast(1)).coerceIn(.2f, 12f))
        ReferenceEventType.RGB_SPLIT -> mapOf("distance" to (samples.mapNotNull { it.values["amount"] }.median().takeIf { it > 0 } ?: strength * 18f).coerceIn(1f, 40f), "angle" to 0f, "horizontal_offset" to 0f, "vertical_offset" to 0f)
        ReferenceEventType.BLUR -> mapOf("radius" to (strength * 18f).coerceIn(1f, 22f), "horizontal" to 1f, "vertical" to 1f)
        else -> emptyMap()
    }
}
