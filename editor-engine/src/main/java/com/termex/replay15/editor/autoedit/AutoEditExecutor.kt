package com.termex.replay15.editor.autoedit

import com.termex.replay15.editor.assets.EffectInstance
import com.termex.replay15.editor.domain.*
import kotlin.math.abs

/** Converts a validated plan into the editor's normal, persisted and fully editable primitives. */
object AutoEditExecutor {
    fun execute(project: Project, clipIndex: Int, rawPlan: AutoEditPlan): Project {
        val source = project.videos.getOrNull(clipIndex) ?: error("Clipe nao encontrado")
        require(source.id == rawPlan.clipId) { "O clipe mudou durante a analise" }
        val plan = AutoEditPlanner.validate(rawPlan)
        require(abs(source.durationUs - plan.sourceDurationUs) <= 2_000L) { "A duracao do clipe mudou durante a analise" }

        val keep = keepRanges(source.durationUs, plan.cuts)
        val removedUs = plan.cuts.sumOf { it.endUs - it.startUs }
        val newDuration = source.durationUs - removedUs
        val clipStart = project.startOf(clipIndex)
        val clipEnd = clipStart + source.durationUs
        val mapLocal: (Long) -> Long = { value ->
            val t = value.coerceIn(0, source.durationUs)
            t - plan.cuts.sumOf { cut ->
                when { t <= cut.startUs -> 0L; t >= cut.endUs -> cut.endUs - cut.startUs; else -> t - cut.startUs }
            }
        }
        val mapAbsolute: (Long) -> Long = { value ->
            when {
                value <= clipStart -> value
                value >= clipEnd -> value - removedUs
                else -> clipStart + mapLocal(value - clipStart)
            }
        }

        val autoKeys = autoKeyframes(source, plan)
        val pieces = keep.mapIndexed { pieceIndex, range ->
            val sourceIn = source.timeMap.sourceAt(range.first)
            val sourceOut = source.timeMap.sourceAt(range.last + 1).coerceAtMost(source.outUs)
            val keys = boundedKeys(source, sourceIn, sourceOut, autoKeys)
            val effectInstances = source.effects + plan.effects.mapNotNull { decision ->
                val eventSource = source.timeMap.sourceAt(decision.startUs)
                if (eventSource !in sourceIn until sourceOut || source.effects.size >= 12) null else EffectInstance(
                    id = newId(), assetId = decision.assetId, intensity = decision.intensity,
                    values = decision.values, startTimeUs = eventSource,
                    endTimeUs = source.timeMap.sourceAt(decision.endUs).coerceAtMost(sourceOut),
                )
            }.take((12 - source.effects.size).coerceAtLeast(0))
            source.copy(
                id = if (pieceIndex == 0) source.id else newId(),
                inUs = sourceIn,
                outUs = sourceOut,
                volume = plan.audio?.let { (source.volume * it.normalizeVolume).coerceIn(0f, 2f) } ?: source.volume,
                audioFadeInUs = when { pieceIndex > 0 -> 25_000L; plan.audio != null -> maxOf(source.audioFadeInUs, plan.audio.fadeInUs); else -> source.audioFadeInUs },
                audioFadeOutUs = when { pieceIndex < keep.lastIndex -> 25_000L; plan.audio != null -> maxOf(source.audioFadeOutUs, plan.audio.fadeOutUs); else -> source.audioFadeOutUs },
                transitionIn = if (pieceIndex > 0) ClipTransition.NONE else source.transitionIn,
                transitionOut = if (pieceIndex < keep.lastIndex) ClipTransition.NONE else source.transitionOut,
                keyframes = keys,
                effects = effectInstances,
            )
        }

        val newVideos = project.videos.toMutableList().apply {
            removeAt(clipIndex)
            addAll(clipIndex, pieces)
        }
        val captionCandidates = if (plan.captions.isEmpty()) emptySet() else project.texts.filter { text ->
            text.startUs < clipEnd && text.endUs > clipStart && text.y >= .6f && text.endUs - text.startUs <= 6 * SECOND
        }.toSet()
        val keptTexts = project.texts.filterNot { it in captionCandidates }.mapNotNull { text -> remapText(text, clipStart, mapAbsolute) }
        val captions = plan.captions.mapNotNull { caption ->
            val start = clipStart + mapLocal(caption.startUs)
            val end = clipStart + mapLocal(caption.endUs)
            if (end - start < 80_000L || start >= clipStart + newDuration) null else TextClip(
                text = caption.text,
                startUs = start,
                endUs = minOf(end, clipStart + newDuration),
                y = .78f,
                size = when (plan.options.style) { AutoEditStyle.GAMING, AutoEditStyle.SHORTS -> .068f; else -> .06f },
                bold = plan.options.style != AutoEditStyle.CINEMATIC,
                fontId = when (plan.options.style) { AutoEditStyle.GAMING, AutoEditStyle.SHORTS -> TextFont.MONTSERRAT.id; else -> TextFont.OUTFIT.id },
                backgroundColor = if (plan.options.style == AutoEditStyle.CLEAN || plan.options.style == AutoEditStyle.PODCAST) 0x99000000.toInt() else 0,
                outlineWidth = if (plan.options.style in setOf(AutoEditStyle.GAMING, AutoEditStyle.DYNAMIC, AutoEditStyle.SHORTS)) .009f else .004f,
                shadow = true,
                animation = when (plan.options.style) { AutoEditStyle.DYNAMIC, AutoEditStyle.GAMING, AutoEditStyle.SHORTS -> TextAnimation.POP; else -> TextAnimation.FADE },
            )
        }

        val next = project.copy(
            videos = newVideos,
            texts = (keptTexts + captions).sortedBy { it.startUs }.take(MAX_TEXTS),
            audio = project.audio.map { audio -> audio.copy(startUs = mapAbsolute(audio.startUs)) },
            stickers = project.stickers.mapNotNull { sticker ->
                val start = mapAbsolute(sticker.startUs); val end = mapAbsolute(sticker.endUs)
                if (end <= start) null else sticker.copy(startUs = start, endUs = end)
            },
            markers = project.markers.map { it.copy(timeUs = mapAbsolute(it.timeUs)) },
            videoTracks = project.videoTracks.map { track ->
                var previousEnd = 0L
                val mapped = track.clips.sortedBy { it.startUs }.mapNotNull { item ->
                    val start = maxOf(previousEnd, mapAbsolute(item.startUs))
                    if (start > MAX_PROJECT_US - item.clip.durationUs) null else item.copy(startUs = start).also { previousEnd = it.endUs }
                }
                track.copy(clips = mapped)
            },
            adjustmentClips = project.adjustmentClips.mapNotNull { adjustment ->
                val start = mapAbsolute(adjustment.startUs); val end = mapAbsolute(adjustment.endUs)
                if (end <= start) null else adjustment.copy(startUs = start, endUs = end)
            },
            aspect = if (plan.options.autoReframe && plan.reframes.isNotEmpty()) 9f / 16f else project.aspect,
            canvasFill = if (plan.options.autoReframe && plan.reframes.isNotEmpty()) CanvasFill.FILL else project.canvasFill,
        )
        return next
    }

    private fun keepRanges(durationUs: Long, cuts: List<CutDecision>): List<LongRange> {
        val result = mutableListOf<LongRange>()
        var cursor = 0L
        cuts.forEach { cut ->
            if (cut.startUs > cursor) result += cursor until cut.startUs
            cursor = cut.endUs
        }
        if (durationUs > cursor) result += cursor until durationUs
        return result.ifEmpty { listOf(0L until durationUs) }
    }

    private fun autoKeyframes(source: VideoClip, plan: AutoEditPlan): List<TransformKeyframe> {
        val result = source.keyframes.associateByTo(sortedMapOf()) { it.sourceUs }
        fun place(localUs: Long, zoomMultiplier: Float = 1f, x: Float? = null, y: Float? = null) {
            val sourceUs = source.timeMap.sourceAt(localUs.coerceIn(0, source.durationUs))
            val base = source.transformAt(sourceUs)
            if (sourceUs in result || result.size < 200) {
                result[sourceUs] = base.copy(sourceUs = sourceUs, zoom = (base.zoom * zoomMultiplier).coerceIn(.25f, 4f),
                    x = x ?: base.x, y = y ?: base.y, easing = Easing.SMOOTH)
            }
        }
        plan.zooms.forEach { zoom ->
            place(zoom.timeUs - 70_000L)
            place(zoom.timeUs, zoom.scale)
            place(zoom.timeUs + zoom.durationUs)
        }
        plan.reframes.forEach { reframe ->
            place(reframe.timeUs - 180_000L)
            place(reframe.timeUs + 180_000L, x = reframe.x, y = reframe.y)
        }
        return result.values.toList()
    }

    private fun boundedKeys(source: VideoClip, sourceIn: Long, sourceOut: Long, all: List<TransformKeyframe>): List<TransformKeyframe> {
        if (all.isEmpty()) return emptyList()
        val start = source.transformAt(sourceIn).copy(sourceUs = sourceIn)
        val end = source.transformAt(sourceOut).copy(sourceUs = sourceOut)
        return (listOf(start) + all.filter { it.sourceUs in (sourceIn + 1) until sourceOut } + end)
            .distinctBy { it.sourceUs }.sortedBy { it.sourceUs }.take(200)
    }

    private fun remapText(text: TextClip, clipStart: Long, map: (Long) -> Long): TextClip? {
        if (text.endUs <= clipStart) return text
        val start = map(text.startUs); val end = map(text.endUs)
        return if (end <= start) null else text.copy(startUs = start, endUs = end)
    }
}
