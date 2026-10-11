package com.termex.replay15.editor.preview.engine

import com.termex.replay15.editor.assets.TransitionInstance
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.transform.LayerStateEvaluator
import kotlin.math.roundToLong

/** Indexed once per project snapshot. No media is opened while resolving demand. */
class ActiveClipResolver(val project: Project, private val timeMapper: ClipTimeMapper = ProjectClipTimeMapper) {
    data class ClipKey(val trackId: String, val clipId: String)
    /**
     * A placed clip. [leadInUs]/[leadOutUs] extend its visible span around a transition: the
     * incoming clip starts before its in point and the outgoing clip keeps playing after its out
     * point, reading real media from the handles (see [TransitionHandles]) instead of freezing.
     */
    data class Video(val key: ClipKey, val startUs: Long, val clip: VideoClip, private val mapper: ClipTimeMapper = ProjectClipTimeMapper,
                     val leadInUs: Long = 0L, val leadOutUs: Long = 0L) {
        val endUs = startUs + clip.durationUs
        /** First project instant on screen; earlier than [startUs] while a transition brings it in. */
        val visibleStartUs: Long get() = startUs - leadInUs
        /** End of the visible span; later than [endUs] while a transition takes it out. */
        val visibleEndUs: Long get() = endUs + leadOutUs
        fun sourceTimeUs(projectTimeUs: Long): Long {
            if (leadInUs > 0L && projectTimeUs < startUs) {
                return TransitionHandles.sourceAt(clip, (projectTimeUs - startUs).coerceAtLeast(-leadInUs))
            }
            if (leadOutUs > 0L && projectTimeUs >= endUs) {
                return TransitionHandles.sourceAt(clip, (projectTimeUs - startUs).coerceAtMost(clip.durationUs + leadOutUs - 1L))
            }
            val bounded = projectTimeUs.coerceIn(startUs, (endUs - 1L).coerceAtLeast(startUs))
            // A reversed clip reaches outUs first, so the bound must span both endpoints.
            return mapper.projectToSource(clip, startUs, bounded)
                .coerceIn(minOf(clip.inUs, clip.outUs), maxOf(clip.inUs, clip.outUs))
        }
        /** Inverse of [sourceTimeUs]; handle frames map outside [startUs, endUs) so pacing presents them on time. */
        fun projectTimeUs(sourceTimeUs: Long): Long {
            if (!clip.image && TransitionHandles.supportsHandles(clip)) {
                if (leadInUs > 0L && sourceTimeUs < clip.inUs) {
                    return startUs - ((clip.inUs - sourceTimeUs) / clip.speed.toDouble()).roundToLong()
                }
                if (leadOutUs > 0L && sourceTimeUs > clip.outUs) {
                    return endUs + ((sourceTimeUs - clip.outUs) / clip.speed.toDouble()).roundToLong()
                }
            }
            return mapper.sourceToProject(clip, startUs, sourceTimeUs)
        }
        fun contains(timeUs: Long) = timeUs >= visibleStartUs && timeUs < visibleEndUs
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
    /** Resolved once per snapshot; preview video, audio and export share [TransitionTiming]. */
    val windows: List<TransitionWindow> = TransitionTiming.windows(project)
    private val main = run {
        var start = 0L
        project.videos.mapIndexed { index, clip ->
            val leadIn = windows.firstOrNull { it.rightIndex == index }?.rightExtensionUs ?: 0L
            val leadOut = windows.firstOrNull { it.leftIndex == index }?.leftExtensionUs ?: 0L
            Video(ClipKey(MAIN_TRACK, clip.id), start, clip, timeMapper, leadIn, leadOut).also { start += clip.durationUs }
        }
    }
    private val layers = project.videoTracks.map { track ->
        track.clips.map { Video(ClipKey(track.id, it.clip.id), it.startUs, it.clip, timeMapper) }
    }
    private val all = main + layers.flatten()
    private val visualTracks = all.map { it.key.trackId }.distinct().filter(project::visualEnabled).toSet()
    private val audioResolver = ActiveAudioSourceResolver(project, timeMapper)

    fun resolve(projectTimeUs: Long): Snapshot {
        // The UI can point at durationUs; the last visible instant is durationUs - 1.
        val time = projectTimeUs.coerceIn(0, (durationUs - 1).coerceAtLeast(0))
        val active = all.filter { !it.clip.isNullObject && it.contains(time) }
        val audio = audioResolver.resolve(time, 0L)
        return Snapshot(time, active.filter { it.key.trackId in visualTracks }, audio,
            ActiveTextResolver.activeTexts(project, time),
            project.stickers.filter { project.visualEnabled(STICKER_TRACK) && LayerStateEvaluator.isActive(it, time) },
            project.adjustmentClips.filter { it.enabled && time >= it.startUs && time < it.endUs },
            if (MAIN_TRACK !in visualTracks) emptyList() else windows.filter { it.contains(time) }
                .map { window -> Transition(window.transition, window.progress(time), window.startUs, window.endUs) })
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
        // A clip entering through a transition is needed from its visible start, before its cut.
        return all.asSequence().filter {
            !it.clip.image && !it.clip.isNullObject && it.key.trackId in visualTracks &&
                it.visibleStartUs > time && it.visibleStartUs <= end
        }.sortedBy { it.visibleStartUs }.distinctBy { it.key.trackId }.toList()
    }

}
