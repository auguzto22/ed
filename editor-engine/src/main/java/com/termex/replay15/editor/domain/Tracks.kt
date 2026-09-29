package com.termex.replay15.editor.domain

import com.termex.replay15.editor.assets.EffectInstance

enum class TrackType { VIDEO, AUDIO, TEXT, CAPTION, STICKER, EFFECT, OVERLAY }

data class TrackState(
    val name: String = "", val visible: Boolean = true, val locked: Boolean = false,
    val muted: Boolean = false, val solo: Boolean = false,
) { init { require(name.length <= 100) } }

data class TimedVideoClip(val startUs: Long, val clip: VideoClip) {
    init { require(startUs >= 0 && startUs <= MAX_PROJECT_US - clip.durationUs) }
    val endUs get() = startUs + clip.durationUs
}

/** A non-destructive effect layer evaluated once over the already composited video tracks. */
data class AdjustmentClip(
    val id: String = newId(), val startUs: Long, val endUs: Long,
    val name: String = "Camada de ajuste", val enabled: Boolean = true,
    val effects: List<EffectInstance> = emptyList(),
) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")) && name.length in 1..100)
        require(startUs >= 0 && endUs > startUs && endUs <= MAX_PROJECT_US)
        require(effects.size <= 12 && effects.map { it.id }.distinct().size == effects.size)
    }
}

/** Bottom to top order in Project.videoTracks. Clips within one track cannot overlap. */
data class VideoTrack(val id: String = newId(), val clips: List<TimedVideoClip> = emptyList()) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        require(clips.size <= 200 && clips.zipWithNext().all { (a, b) -> a.endUs <= b.startUs })
        require(clips.map { it.clip.id }.distinct().size == clips.size)
    }
    fun activeAt(timeUs: Long) = clips.firstOrNull { timeUs >= it.startUs && timeUs < it.endUs }
}

const val MAIN_TRACK = "main"
const val MAIN_TRACK_A = "main_a"
const val MAIN_TRACK_B = "main_b"
const val TEXT_TRACK = "texts"
const val STICKER_TRACK = "stickers"
const val MAX_PROJECT_US = 24 * 60 * 60 * SECOND
const val PROJECT_SCHEMA = 24

fun Project.trackState(id: String) = trackStates[id] ?: TrackState()
fun Project.visualEnabled(id: String): Boolean {
    val stateId = if (id == MAIN_TRACK_A || id == MAIN_TRACK_B) MAIN_TRACK else id
    val ids = listOf(MAIN_TRACK) + listOfNotNull(TEXT_TRACK.takeIf { texts.isNotEmpty() }, STICKER_TRACK.takeIf { stickers.isNotEmpty() }) + videoTracks.filter { it.clips.isNotEmpty() }.map { it.id }
    return trackState(stateId).visible && (ids.none { trackState(it).solo } || trackState(stateId).solo)
}
fun Project.audioEnabled(id: String): Boolean {
    val stateId = if (id == MAIN_TRACK_A || id == MAIN_TRACK_B) MAIN_TRACK else id
    val ids = listOf(MAIN_TRACK) + videoTracks.map { it.id } + audio.map { "audio:${it.id}" }
    return !trackState(stateId).muted && (ids.none { trackState(it).solo } || trackState(stateId).solo)
}
val Project.allVideos get() = videos + videoTracks.flatMap { it.clips.map(TimedVideoClip::clip) }

fun Project.mapVideo(clipId: String, change: (VideoClip) -> VideoClip): Project = copy(
    videos = videos.map { if (it.id == clipId) change(it) else it },
    videoTracks = videoTracks.map { track -> track.copy(clips = track.clips.map {
        if (it.clip.id == clipId) it.copy(clip = change(it.clip)) else it
    }) },
)

object TrackEditing {
    fun update(project: Project, trackId: String, clipId: String, change: (TimedVideoClip) -> TimedVideoClip): Project {
        require(!project.trackState(trackId).locked) { "Desbloqueie a faixa para editar" }
        return project.copy(videoTracks = project.videoTracks.map { track ->
            if (track.id != trackId) track else track.copy(clips = track.clips.map {
                if (it.clip.id == clipId) change(it) else it
            }.sortedBy { it.startUs })
        })
    }
    fun move(project: Project, trackId: String, clipId: String, startUs: Long): Project =
        update(project, trackId, clipId) { it.copy(startUs = startUs.coerceAtLeast(0)) }

    fun split(project: Project, trackId: String, clipId: String, timeUs: Long): Project {
        require(!project.trackState(trackId).locked) { "Desbloqueie a faixa para editar" }
        return project.copy(videoTracks = project.videoTracks.map { track ->
            if (track.id != trackId) track else track.copy(clips = track.clips.flatMap { item ->
                if (item.clip.id != clipId) listOf(item) else {
                    val parts = Project(videos = listOf(item.clip)).split(0, timeUs - item.startUs).videos
                    if (parts.size == 1) listOf(item) else listOf(TimedVideoClip(item.startUs, parts[0]),
                        TimedVideoClip(item.startUs + parts[0].durationUs, parts[1]))
                }
            })
        })
    }
    fun reorder(project: Project, trackId: String, target: Int): Project {
        val from = project.videoTracks.indexOfFirst { it.id == trackId }
        if (from < 0 || target !in project.videoTracks.indices) return project
        require(!project.trackState(trackId).locked) { "Desbloqueie a faixa para mover" }
        val tracks = project.videoTracks.toMutableList(); tracks.add(target, tracks.removeAt(from))
        return project.copy(videoTracks = tracks)
    }
}
