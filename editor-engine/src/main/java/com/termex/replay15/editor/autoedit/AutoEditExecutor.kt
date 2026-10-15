package com.termex.replay15.editor.autoedit

import com.termex.replay15.editor.assets.EffectInstance
import com.termex.replay15.editor.domain.*
import kotlin.math.abs

/** Converts a validated plan into the editor's normal, persisted and fully editable primitives. */
object AutoEditExecutor {
    /** One frame at 30 fps: the punch-in lands on the target frame, not a frame later. */
    private const val PUNCH_STEP_US = 33_000L

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

        val autoKeys = autoKeyframes(source,plan)
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
        val existingCaptions = project.texts.filter { ClipAnalyzer.isCaptionOf(it, clipStart, clipEnd) }
        // Captions made by the caption engine carry measured word timing, karaoke/word styles and
        // the user's look. Re-segmenting them into plain text threw all of that away, so they are
        // kept (and moved through the cuts like any other text). Only captions without word timing
        // (an imported SRT with long lines) are re-split, and the new lines copy their look.
        val resegment = plan.captions.isNotEmpty() && existingCaptions.none { it.wordCues.isNotEmpty() }
        val captionCandidates = if (resegment) existingCaptions.toSet() else emptySet()
        val keptTexts = project.texts.filterNot { it in captionCandidates }.mapNotNull { text -> remapText(text, clipStart, mapAbsolute) }
        val template = existingCaptions.firstOrNull()
        val captions = if (!resegment) emptyList() else plan.captions.mapNotNull { caption ->
            val start = clipStart + mapLocal(caption.startUs)
            val end = clipStart + mapLocal(caption.endUs)
            if (end - start < 80_000L || start >= clipStart + newDuration) null
            else template?.copy(id = newId(), text = caption.text, startUs = start, endUs = minOf(end, clipStart + newDuration),
                isCaption = true, wordCues = emptyList(), transformKeyframes = emptyList(), mask = null)
                ?: TextClip(
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
                isCaption = true,
            )
        }

        val next = project.copy(
            videos = newVideos,
            texts = (keptTexts + captions).sortedBy { it.startUs }.take(MAX_TEXTS),
            audio = project.audio.map { audio -> audio.copy(startUs = mapAbsolute(audio.startUs)) },
            stickers = project.stickers.mapNotNull { sticker ->
                val start = mapAbsolute(sticker.startUs); val end = mapAbsolute(sticker.endUs)
                if (end <= start) null else sticker.copy(startUs = start, endUs = end,
                    mask = sticker.mask?.let { mask -> mask.copy(keyframes = mask.keyframes.filter { it.timeUs <= end - start }) })
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
        fun place(localUs: Long, zoomMultiplier: Float = 1f, x: Float? = null, y: Float? = null,
            easing: Easing = Easing.SMOOTH, focusX: Float? = null, focusY: Float? = null) {
            val sourceUs = source.timeMap.sourceAt(localUs.coerceIn(0, source.durationUs))
            val base = source.transformAt(sourceUs)
            if (sourceUs in result || result.size < 200) {
                val zoom = (base.zoom * zoomMultiplier).coerceIn(.25f, 4f)
                // Zooming happens around the frame centre. Shifting by (0.5 - focus) * extra zoom keeps the
                // focus point (the face) where it was instead of pushing it out of the frame. The shift is
                // at most half of the extra zoom, so the frame edges never show.
                val extra = zoom - base.zoom
                val focusShiftX = focusX?.let { (.5f - it) * extra } ?: 0f
                val focusShiftY = focusY?.let { (.5f - it) * extra } ?: 0f
                result[sourceUs] = base.copy(sourceUs = sourceUs, zoom = zoom,
                    x = (x ?: (base.x + focusShiftX)).coerceIn(-.5f, .5f),
                    y = (y ?: (base.y + focusShiftY)).coerceIn(-.5f, .5f), easing = easing)
            }
        }
        plan.zooms.forEach { zoom ->
            if (zoom.hold) {
                // Punch-in: a cut to the closer framing, held, then a cut back.
                place(zoom.timeUs - PUNCH_STEP_US, easing = Easing.HOLD)
                place(zoom.timeUs, zoom.scale, easing = Easing.HOLD, focusX = zoom.focusX, focusY = zoom.focusY)
                place(zoom.timeUs + zoom.durationUs)
            } else {
                place(zoom.timeUs - 70_000L)
                place(zoom.timeUs, zoom.scale, focusX = zoom.focusX, focusY = zoom.focusY)
                place(zoom.timeUs + zoom.durationUs)
            }
        }
        plan.reframes.forEach { reframe ->
            place(reframe.timeUs - 180_000L)
            place(reframe.timeUs + 180_000L, x = reframe.x, y = reframe.y)
        }
        return result.values.toList()
    }

    private fun boundedKeys(source: VideoClip, sourceIn: Long, sourceOut: Long, all: List<TransformKeyframe>): List<TransformKeyframe> {
        if (all.isEmpty()) return emptyList()
        // The edges are read from the planned keys, not the original ones: a punch-in that spans a
        // removed pause must still be zoomed in on the piece after the cut.
        val planned = source.copy(keyframes = all)
        val start = planned.transformAt(sourceIn).copy(sourceUs = sourceIn)
        val end = planned.transformAt(sourceOut).copy(sourceUs = sourceOut)
        return (listOf(start) + all.filter { it.sourceUs in (sourceIn + 1) until sourceOut } + end)
            .distinctBy { it.sourceUs }.sortedBy { it.sourceUs }.take(200)
    }

    /**
     * Moves a text through the cuts. Word timing moves with it: cues left in place broke the
     * TextClip invariant (cues inside the text) whenever a cut fell inside a caption, which made
     * the whole Auto Edit fail, or showed the karaoke out of sync.
     */
    private fun remapText(text: TextClip, clipStart: Long, map: (Long) -> Long): TextClip? {
        if (text.endUs <= clipStart) return text
        val start = map(text.startUs); val end = map(text.endUs)
        if (end - start < MIN_TEXT_DURATION_US.coerceAtMost(text.durationUs)) return null
        val cues = text.wordCues.mapNotNull { cue ->
            val cueStart = map(cue.startUs).coerceIn(start, end); val cueEnd = map(cue.endUs).coerceIn(start, end)
            if (cueEnd > cueStart) SubtitleWordCue(cue.text, cueStart, cueEnd) else null
        }.sortedBy { it.startUs }
        // Mask keyframes must fit the (possibly shorter) text, or TextClip rejects it.
        val mask = text.mask?.let { mask -> mask.copy(keyframes = mask.keyframes.filter { it.timeUs <= end - start }) }
        return text.copy(startUs = start, endUs = end, wordCues = cues, mask = mask)
    }
}
