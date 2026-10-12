package com.termex.replay15.editor.preview.engine

import com.termex.replay15.editor.audio.AudioEnhance
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
    /** Per-clip DSP (noise, voice, compression, normalize). Runs before the mix gain. */
    val enhance: AudioEnhance = AudioEnhance.NEUTRAL,
) {
    /** Mixer-facing name retained for callers; the value is the evaluated volume/fade envelope. */
    val gain: Float get() = volume
}

/** Resolves both embedded video audio and independent audio clips in project-time domain. */
class ActiveAudioSourceResolver(
    private val project: Project,
    private val mapper: ClipTimeMapper = ProjectClipTimeMapper,
) {
    /** [envelope] carries the clip's volume, its own fades and any transition cross-fades. */
    private data class Embedded(
        val video: ActiveClipResolver.Video,
        val envelope: ClipAudioEnvelope,
    )
    private val windows = TransitionTiming.windows(project)
    private val embedded = buildList {
        var start = 0L
        project.videos.forEachIndexed { index, clip ->
            val entering = windows.firstOrNull { it.rightIndex == index }
            val leaving = windows.firstOrNull { it.leftIndex == index }
            // Audio follows the picture through the handles, so both sides overlap and cross-fade.
            // Where the source has no media past the trim the picture holds a frame and the sound
            // stays silent, exactly like the exported file.
            if (!clip.image && !clip.isNullObject) add(Embedded(ActiveClipResolver.Video(
                ActiveClipResolver.ClipKey(MAIN_TRACK, clip.id), start, clip, mapper,
                TransitionHandles.usableHeadUs(clip, entering?.rightExtensionUs ?: 0L),
                TransitionHandles.usableTailUs(clip, leaving?.leftExtensionUs ?: 0L)),
                ClipAudioEnvelope(clip, start, entering, leaving)))
            start += clip.durationUs
        }
        project.videoTracks.forEach { track -> track.clips.forEach { placed ->
            if (!placed.clip.image && !placed.clip.isNullObject) add(Embedded(ActiveClipResolver.Video(
                ActiveClipResolver.ClipKey(track.id, placed.clip.id), placed.startUs, placed.clip, mapper),
                ClipAudioEnvelope(placed.clip, placed.startUs, null, null)))
        } }
    }
    private val enabledEmbedded = embedded.filter { project.audioEnabled(it.video.key.trackId) }
    private val embeddedStarts = enabledEmbedded.map { it.video.visibleStartUs }.toLongArray()
    private val embeddedEnds = enabledEmbedded.map { it.video.visibleEndUs }.toLongArray()
    private val enabledExternal = project.audio.filter { project.audioEnabled("audio:${it.id}") }
    private val externalStarts = enabledExternal.map { it.startUs }.toLongArray()
    private val externalEnds = enabledExternal.map { it.startUs + it.durationUs }.toLongArray()

    fun resolve(projectTimeUs: Long, generation: Long): List<ResolvedAudioSource> {
        val time = projectTimeUs.coerceAtLeast(0L)
        val videoIndices = if (NativePreviewBridge.available)
            NativePreviewBridge.nativeSelectActive(embeddedStarts, embeddedEnds, time).asSequence()
            else enabledEmbedded.indices.asSequence().filter { time >= embeddedStarts[it] && time < embeddedEnds[it] }
        val videoAudio = videoIndices.map(enabledEmbedded::get)
            .map { item ->
                val video = item.video
                val clip = video.clip
                val sourceTime = video.sourceTimeUs(time)
                ResolvedAudioSource(
                    id = "video:${clip.id}:audio", key = video.key, uri = clip.uri,
                    projectStartUs = video.visibleStartUs, projectEndUs = video.visibleEndUs,
                    sourceStartUs = video.sourceTimeUs(video.visibleStartUs), sourceTimeUs = sourceTime,
                    volume = item.envelope.gainAt(time),
                    speed = video.speedAtSource(sourceTime),
                    preservePitch = clip.preservePitch, generation = generation, embedded = true,
                    enhance = clip.enhance,
                )
            }
        val externalIndices = if (NativePreviewBridge.available)
            NativePreviewBridge.nativeSelectActive(externalStarts, externalEnds, time).asSequence()
            else enabledExternal.indices.asSequence().filter { time >= externalStarts[it] && time < externalEnds[it] }
        val external = externalIndices.map(enabledExternal::get).map { clip ->
            ResolvedAudioSource(
                id = "audio:${clip.id}", key = ActiveClipResolver.ClipKey("audio:${clip.id}", clip.id), uri = clip.uri,
                projectStartUs = clip.startUs, projectEndUs = clip.startUs + clip.durationUs,
                sourceStartUs = clip.inUs, sourceTimeUs = clip.inUs + time - clip.startUs,
                volume = AudioTimelineMapper.calculateGain(clip, time - clip.startUs), speed = 1f, preservePitch = true,
                generation = generation, embedded = false, enhance = clip.enhance,
            )
        }
        return (videoAudio + external).toList()
    }

    fun upcoming(projectTimeUs: Long, horizonUs: Long, generation: Long): List<ResolvedAudioSource> {
        val end = projectTimeUs + horizonUs.coerceAtLeast(0L)
        val starts = buildList {
            enabledEmbedded.map { it.video.visibleStartUs }.filter { it > projectTimeUs && it <= end }.forEach { add(it) }
            enabledExternal.map { it.startUs }.filter { it > projectTimeUs && it <= end }.forEach { add(it) }
        }
        return starts.flatMap { resolve(it, generation) }.distinctBy { it.id }
    }
}
