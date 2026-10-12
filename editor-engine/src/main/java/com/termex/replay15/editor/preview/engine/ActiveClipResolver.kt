package com.termex.replay15.editor.preview.engine

import com.termex.replay15.editor.assets.TransitionInstance
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.transform.LayerStateEvaluator

/** Indexed once per project snapshot. No media is opened while resolving demand. */
class ActiveClipResolver(val project: Project, private val timeMapper: ClipTimeMapper = ProjectClipTimeMapper) {
    data class ClipKey(val trackId: String, val clipId: String)
    data class Video(val key: ClipKey, val startUs: Long, val clip: VideoClip, private val mapper: ClipTimeMapper = ProjectClipTimeMapper,
                     val transitionEndUs: Long = startUs + clip.durationUs) {
        val endUs = startUs + clip.durationUs
        fun sourceTimeUs(projectTimeUs: Long): Long {
            val bounded = projectTimeUs.coerceIn(startUs, (endUs - 1L).coerceAtLeast(startUs))
            // A reversed clip reaches outUs first, so the bound must span both endpoints.
            return mapper.projectToSource(clip, startUs, bounded)
                .coerceIn(minOf(clip.inUs, clip.outUs), maxOf(clip.inUs, clip.outUs))
        }
        fun projectTimeUs(sourceTimeUs: Long): Long = mapper.sourceToProject(clip, startUs, sourceTimeUs)
        fun contains(timeUs: Long) = timeUs >= startUs && timeUs < transitionEndUs
    }
    data class Transition(val instance: TransitionInstance, val progress: Float, val startUs: Long = 0, val endUs: Long = 0)
    data class Snapshot(
        val projectTimeUs: Long,
        /** Bottom to top, including both inputs of an overlap. */
        val videos: List<Video>,
        val audio: List<ResolvedAudioSource>,
        val texts: List<TextClip>,
        val stickers: List<StickerClip>,
        val adjustments: List<AdjustmentClip>,
        val transitions: List<Transition>,
    )

    val durationUs = project.durationUs
    private val main = project.videos.mapIndexed { index, clip ->
        val start = project.startOf(index)
        val next = project.videos.getOrNull(index + 1)
        val outgoing = next?.let { project.transitionBetween(clip.id, it.id) }
        // The outgoing clip stays on screen exactly as long as the transition window below,
        // never past the end of the clip that receives the transition.
        val hold = if (outgoing == null || next == null) 0L else minOf(outgoing.durationUs, next.durationUs)
        Video(ClipKey(MAIN_TRACK, clip.id), start, clip, timeMapper, start + clip.durationUs + hold)
    }
    private val layers = project.videoTracks.map { track ->
        track.clips.map { Video(ClipKey(track.id, it.clip.id), it.startUs, it.clip, timeMapper) }
    }
    private val all = main + layers.flatten()
    private val visualTracks = all.map { it.key.trackId }.distinct().filter(project::visualEnabled).toSet()
    private val audioResolver = ActiveAudioSourceResolver(project, timeMapper)
    private val transitions = project.transitions.mapNotNull { transition ->
        val left = main.firstOrNull { it.clip.id == transition.leftClipId }
        val right = main.firstOrNull { it.clip.id == transition.rightClipId }
        if (left == null || right == null) null else Triple(transition, right.startUs, minOf(right.startUs + transition.durationUs, right.endUs))
    }

    fun resolve(projectTimeUs: Long): Snapshot {
        // The UI can point at durationUs; the last visible instant is durationUs - 1.
        val time = projectTimeUs.coerceIn(0, (durationUs - 1).coerceAtLeast(0))
        val active = all.filter { !it.clip.isNullObject && it.contains(time) }
        val audio = audioResolver.resolve(time, 0L)
        return Snapshot(time, active.filter { it.key.trackId in visualTracks }, audio,
            ActiveTextResolver.activeTexts(project, time),
            project.stickers.filter { project.visualEnabled(STICKER_TRACK) && LayerStateEvaluator.isActive(it, time) },
            project.adjustmentClips.filter { it.enabled && time >= it.startUs && time < it.endUs },
            if (MAIN_TRACK !in visualTracks) emptyList() else transitions.filter { time >= it.second && time < it.third }
                .map { (transition, start, end) -> Transition(transition,
                    transition.easing.apply(((time - start).toFloat() / (end - start).coerceAtLeast(1L)).coerceIn(0f, 1f), transition.bezier), start, end) })
    }

    fun audioAt(projectTimeUs: Long, generation: Long): List<ResolvedAudioSource> =
        audioResolver.resolve(projectTimeUs, generation)

    fun upcomingAudio(projectTimeUs: Long, horizonUs: Long, generation: Long): List<ResolvedAudioSource> =
        audioResolver.upcoming(projectTimeUs, horizonUs, generation)

    /** At most one imminent source per track; visibility is evaluated before warming. */
    fun upcoming(projectTimeUs: Long, horizonUs: Long): List<Video> {
        require(horizonUs >= 0)
        val time = projectTimeUs.coerceIn(0, durationUs)
        val end = time + horizonUs.coerceAtMost(durationUs - time)
        return all.asSequence().filter {
            !it.clip.image && !it.clip.isNullObject && it.key.trackId in visualTracks &&
                it.startUs > time && it.startUs <= end
        }.sortedBy { it.startUs }.distinctBy { it.key.trackId }.toList()
    }

    /** Photos entering within [horizonUs]; they need no decoder but must be uploaded ahead of time. */
    fun upcomingImageUris(projectTimeUs: Long, horizonUs: Long): List<String> {
        require(horizonUs >= 0)
        val time = projectTimeUs.coerceIn(0, durationUs)
        val end = time + horizonUs.coerceAtMost(durationUs - time)
        return all.asSequence().filter {
            it.clip.image && !it.clip.isNullObject && it.key.trackId in visualTracks &&
                it.startUs > time && it.startUs <= end
        }.map { it.clip.effectivePreviewUri }.distinct().toList()
    }

}
