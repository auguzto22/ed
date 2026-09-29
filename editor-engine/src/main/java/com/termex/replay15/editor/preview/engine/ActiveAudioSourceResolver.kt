package com.termex.replay15.editor.preview.engine

import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.preview.engine.audio.AudioTimelineMapper

data class ResolvedAudioSource(
    val id: String,
    val key: ActiveClipResolver.ClipKey,
    val uri: String,
    val projectStartUs: Long,
    val projectEndUs: Long,
    val sourceStartUs: Long,
    val sourceTimeUs: Long,
    val volume: Float,
    val speed: Float,
    val preservePitch: Boolean,
    val generation: Long,
    val embedded: Boolean,
) {
    /** Mixer-facing name retained for callers; the value is the evaluated volume/fade envelope. */
    val gain: Float get() = volume
}

/** Resolves both embedded video audio and independent audio clips in project-time domain. */
class ActiveAudioSourceResolver(
    private val project: Project,
    private val mapper: ClipTimeMapper = ProjectClipTimeMapper,
) {
    private data class Embedded(val video: ActiveClipResolver.Video)
    private val embedded = buildList {
        project.videos.forEachIndexed { index, clip ->
            if (!clip.image && !clip.isNullObject) add(Embedded(ActiveClipResolver.Video(
                ActiveClipResolver.ClipKey(MAIN_TRACK, clip.id), project.startOf(index), clip, mapper)))
        }
        project.videoTracks.forEach { track -> track.clips.forEach { placed ->
            if (!placed.clip.image && !placed.clip.isNullObject) add(Embedded(ActiveClipResolver.Video(
                ActiveClipResolver.ClipKey(track.id, placed.clip.id), placed.startUs, placed.clip, mapper)))
        } }
    }

    fun resolve(projectTimeUs: Long, generation: Long): List<ResolvedAudioSource> {
        val time = projectTimeUs.coerceAtLeast(0L)
        val videoAudio = embedded.asSequence().filter { it.video.contains(time) && project.audioEnabled(it.video.key.trackId) }
            .map { item ->
                val video = item.video
                val clip = video.clip
                val sourceTime = mapper.projectToSource(clip, video.startUs, time)
                val entering = project.transitions.firstOrNull { it.rightClipId == clip.id }?.durationUs ?: 0L
                val leaving = project.transitions.firstOrNull { it.leftClipId == clip.id }?.durationUs ?: 0L
                ResolvedAudioSource(
                    id = "video:${clip.id}:audio", key = video.key, uri = clip.uri,
                    projectStartUs = video.startUs, projectEndUs = video.endUs,
                    sourceStartUs = clip.inUs, sourceTimeUs = sourceTime,
                    volume = AudioTimelineMapper.calculateGain(clip.volume, time - video.startUs, clip.durationUs,
                        maxOf(clip.audioFadeInUs, entering), maxOf(clip.audioFadeOutUs, leaving)),
                    speed = clip.timeMap.speedAtInput(sourceTime - clip.inUs),
                    preservePitch = clip.preservePitch, generation = generation, embedded = true,
                )
            }
        val external = project.audio.asSequence().filter {
            project.audioEnabled("audio:${it.id}") && time >= it.startUs && time < it.startUs + it.durationUs
        }.map { clip ->
            ResolvedAudioSource(
                id = "audio:${clip.id}", key = ActiveClipResolver.ClipKey("audio:${clip.id}", clip.id), uri = clip.uri,
                projectStartUs = clip.startUs, projectEndUs = clip.startUs + clip.durationUs,
                sourceStartUs = clip.inUs, sourceTimeUs = clip.inUs + time - clip.startUs,
                volume = AudioTimelineMapper.calculateGain(clip, time - clip.startUs), speed = 1f, preservePitch = true,
                generation = generation, embedded = false,
            )
        }
        return (videoAudio + external).toList()
    }

    fun upcoming(projectTimeUs: Long, horizonUs: Long, generation: Long): List<ResolvedAudioSource> {
        val end = projectTimeUs + horizonUs.coerceAtLeast(0L)
        val starts = buildList {
            embedded.map { it.video.startUs }.filter { it > projectTimeUs && it <= end }.forEach { add(it) }
            project.audio.map { it.startUs }.filter { it > projectTimeUs && it <= end }.forEach { add(it) }
        }
        return starts.flatMap { resolve(it, generation) }.distinctBy { it.id }
    }
}
