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
        // Podcast look: the user asked for the reference captions, so the clip's captions are
        // replaced by short highlighted lines built from the same measured word timing.
        val podcast = plan.podcastCaptions
        val resegment = !podcast && plan.captions.isNotEmpty() && existingCaptions.none { it.wordCues.isNotEmpty() }
        val captionCandidates = if (resegment || podcast) existingCaptions.toSet() else emptySet()
        val keptTexts = project.texts.filterNot { it in captionCandidates }.mapNotNull { text -> remapText(text, clipStart, mapAbsolute) }
        val template = existingCaptions.firstOrNull()
        val timelineEnd = clipStart + newDuration
        val captions = if (podcast) plan.captions.mapNotNull { caption -> podcastLayer(caption, clipStart, timelineEnd, mapLocal) }
        else if (!resegment) emptyList() else plan.captions.mapNotNull { caption ->
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

        val mappedTracks = project.videoTracks.map { track ->
            var previousEnd = 0L
            val mapped = track.clips.sortedBy { it.startUs }.mapNotNull { item ->
                val start = maxOf(previousEnd, mapAbsolute(item.startUs))
                if (start > MAX_PROJECT_US - item.clip.durationUs) null else item.copy(startUs = start).also { previousEnd = it.endUs }
            }
            track.copy(clips = mapped)
        }
        // B-roll goes on its own new layer at the top, so it covers the speaker but stays under
        // the captions (texts are drawn after every video layer) and can be moved or deleted.
        val brollTrack = if (mappedTracks.size >= MAX_VIDEO_TRACKS) null else brollTrack(plan, clipStart, timelineEnd, mapLocal)

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
            videoTracks = mappedTracks + listOfNotNull(brollTrack),
            trackStates = if (brollTrack == null) project.trackStates else project.trackStates + (brollTrack.id to TrackState(BROLL_TRACK_NAME)),
            adjustmentClips = project.adjustmentClips.mapNotNull { adjustment ->
                val start = mapAbsolute(adjustment.startUs); val end = mapAbsolute(adjustment.endUs)
                if (end <= start) null else adjustment.copy(startUs = start, endUs = end)
            },
            aspect = if (plan.options.autoReframe && plan.reframes.isNotEmpty()) 9f / 16f else project.aspect,
            canvasFill = if (plan.options.autoReframe && plan.reframes.isNotEmpty()) CanvasFill.FILL else project.canvasFill,
        )
        return next
    }

    const val MAX_VIDEO_TRACKS = 7
    const val BROLL_TRACK_NAME = "B-roll"
    /** Shorter than this after the cuts, an image reads as a glitch and is left out. */
    const val MIN_BROLL_US = 600_000L
    /** Slow push-in over each image, like the reference. */
    const val BROLL_PUSH_IN = 1.08f

    /**
     * Captions transcribed during the analysis, moved by [shiftUs] (the clip may have moved on the
     * timeline while the user kept editing). Layers that would start before zero are dropped.
     */
    fun shiftedCaptions(captions: List<TextClip>, shiftUs: Long): List<TextClip> {
        if (shiftUs == 0L) return captions
        return captions.mapNotNull { text ->
            if (text.startUs + shiftUs < 0L || text.endUs + shiftUs > MAX_PROJECT_US) return@mapNotNull null
            text.copy(startUs = text.startUs + shiftUs, endUs = text.endUs + shiftUs,
                wordCues = text.wordCues.map { it.copy(startUs = it.startUs + shiftUs, endUs = it.endUs + shiftUs) })
        }
    }

    /** Adds transcribed captions to [project] within the layer limit, before the plan is applied. */
    fun withCaptions(project: Project, captions: List<TextClip>): Project {
        if (captions.isEmpty()) return project
        val known = project.texts.mapTo(HashSet()) { it.id }
        val room = (MAX_TEXTS - project.texts.size).coerceAtLeast(0)
        val added = captions.filter { it.id !in known }.take(room)
        return if (added.isEmpty()) project else project.copy(texts = (project.texts + added).sortedBy { it.startUs })
    }

    /** Width / height of the canvas once [plan] is applied; B-roll images are cropped to it. */
    fun canvasAspectAfter(project: Project, plan: AutoEditPlan): Float {
        if (plan.options.autoReframe && plan.reframes.isNotEmpty()) return 9f / 16f
        val (width, height) = project.dimensions()
        return width.toFloat() / height
    }

    private fun podcastLayer(caption: CaptionDecision, clipStart: Long, timelineEnd: Long, mapLocal: (Long) -> Long): TextClip? {
        val start = clipStart + mapLocal(caption.startUs)
        val end = minOf(clipStart + mapLocal(caption.endUs), timelineEnd)
        if (end - start < 80_000L) return null
        val cues = caption.words.mapNotNull { word ->
            val cueStart = (clipStart + mapLocal(word.startUs)).coerceIn(start, end)
            val cueEnd = (clipStart + mapLocal(word.endUs)).coerceIn(start, end)
            if (cueEnd > cueStart) SubtitleWordCue(word.text.take(120), cueStart, cueEnd) else null
        }
        return PodcastCaptions.layer(caption.text.take(500), start, end, cues)
    }

    private fun brollTrack(plan: AutoEditPlan, clipStart: Long, timelineEnd: Long, mapLocal: (Long) -> Long): VideoTrack? {
        val clips = mutableListOf<TimedVideoClip>()
        var previousEnd = 0L
        plan.placedBrolls.sortedBy { it.startUs }.forEach { decision ->
            val media = decision.media ?: return@forEach
            val start = maxOf(clipStart + mapLocal(decision.startUs), previousEnd)
            val end = minOf(clipStart + mapLocal(decision.endUs), timelineEnd)
            val duration = end - start
            if (duration < MIN_BROLL_US) return@forEach
            val image = VideoClip(
                uri = media.uri, name = "B-roll: ${decision.keyword}".take(180),
                sourceUs = duration, width = media.width, height = media.height,
                image = true, inUs = 0L, outUs = duration, volume = 0f,
                keyframes = listOf(
                    TransformKeyframe(sourceUs = 0L, zoom = 1f, easing = Easing.LINEAR),
                    TransformKeyframe(sourceUs = duration, zoom = BROLL_PUSH_IN, easing = Easing.LINEAR),
                ),
            )
            clips += TimedVideoClip(start, image)
            previousEnd = start + image.durationUs
        }
        return if (clips.isEmpty()) null else VideoTrack(clips = clips.take(200))
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
