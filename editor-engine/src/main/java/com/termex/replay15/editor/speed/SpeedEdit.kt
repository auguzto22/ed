package com.termex.replay15.editor.speed

import com.termex.replay15.editor.domain.AdjustmentClip
import com.termex.replay15.editor.domain.MaskState
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.StickerClip
import com.termex.replay15.editor.domain.SubtitleWordCue
import com.termex.replay15.editor.domain.TextClip
import com.termex.replay15.editor.domain.TimedVideoClip
import com.termex.replay15.editor.domain.VideoClip
import com.termex.replay15.editor.domain.VideoTrack

/**
 * Applies a new speed (constant or curve) to one clip and keeps everything that belongs to that
 * moment of the media in sync.
 *
 * The clip's own keyframes, effects and masks are anchored in source time, so they follow any
 * speed change by construction. Texts, captions (word cues), stickers, markers, adjustment
 * layers, separate audio clips, overlay clips and the 3D camera live in *project* time; when a
 * main-track clip changes duration everything after it moves (ripple), and everything that sat
 * on top of the clip is re-mapped through the clip's source time so a caption stays on the word
 * it was transcribed from and a sound effect stays on its impact.
 *
 * The mapping is [TimeWarp]: identity before the clip, `old → source → new` inside it, and a
 * constant shift after it. It is monotone, so the order of every item is preserved.
 */
object SpeedEdit {

    /**
     * A monotone map from old project time to new project time, evaluated by the Rust engine
     * (`warp.rs`). [prepare] maps every instant an edit needs in a single native call; [map]
     * answers from that batch and only falls back to a single call for an unexpected instant.
     */
    class TimeWarp internal constructor(
        val oldStartUs: Long,
        val newStartUs: Long,
        private val before: VideoClip,
        private val after: VideoClip,
        /** Whether items after the clip move with its new end (main track) or stay (overlay). */
        private val ripple: Boolean,
    ) {
        val oldEndUs: Long = oldStartUs + before.durationUs
        val newEndUs: Long = newStartUs + after.durationUs
        private val mapped = HashMap<Long, Long>()

        fun prepare(times: Collection<Long>) {
            val pending = times.filterNot(mapped::containsKey).distinct()
            if (pending.isEmpty()) return
            val result = NativeSpeed.warpMap(before.timeMap.table, after.timeMap.table, oldStartUs, newStartUs, ripple, pending.toLongArray())
            pending.forEachIndexed { index, time -> mapped[time] = result[index] }
        }

        fun map(timeUs: Long): Long = mapped[timeUs] ?: run { prepare(listOf(timeUs)); mapped.getValue(timeUs) }

        /** True when the item's interval starts on the changed clip or after it (main track). */
        fun affects(startUs: Long): Boolean = if (ripple) startUs >= oldStartUs else startUs in oldStartUs until oldEndUs
    }

    /**
     * Returns [project] with [changed] in place of the clip with the same id.
     *
     * [keepInSync] controls whether project-time items are re-mapped (on by default). Throws
     * [IllegalArgumentException] when the result would break a project invariant (too long, no
     * room on an overlay track), leaving the caller's project untouched.
     */
    fun apply(project: Project, changed: VideoClip, keepInSync: Boolean = true): Project {
        val mainIndex = project.videos.indexOfFirst { it.id == changed.id }
        if (mainIndex >= 0) {
            val before = project.videos[mainIndex]
            val start = project.startOf(mainIndex)
            val videos = project.videos.toMutableList().also { it[mainIndex] = changed }
            val placed = project.copy(videos = videos)
            val warp = TimeWarp(start, start, before, changed, ripple = true)
            val synced = if (keepInSync) retime(placed, warp, excludeClipId = null) else placed
            return synced.withSanitizedTransitions()
        }
        project.videoTracks.forEachIndexed { trackIndex, track ->
            val clipIndex = track.clips.indexOfFirst { it.clip.id == changed.id }
            if (clipIndex < 0) return@forEachIndexed
            val timed = track.clips[clipIndex]
            val clips = track.clips.toMutableList()
            clips[clipIndex] = TimedVideoClip(timed.startUs, changed)
            // A longer overlay clip pushes the rest of its own track; other tracks are untouched.
            var cursor = clips[clipIndex].endUs
            for (index in clipIndex + 1 until clips.size) {
                val next = clips[index]
                if (next.startUs >= cursor) break
                clips[index] = TimedVideoClip(cursor, next.clip)
                cursor = clips[index].endUs
            }
            val tracks = project.videoTracks.toMutableList().also { it[trackIndex] = VideoTrack(track.id, clips) }
            val placed = project.copy(videoTracks = tracks)
            if (!keepInSync) return placed
            val warp = TimeWarp(timed.startUs, timed.startUs, timed.clip, changed, ripple = false)
            return retime(placed, warp, excludeClipId = changed.id)
        }
        throw IllegalArgumentException("Clipe não encontrado")
    }

    private fun retime(project: Project, warp: TimeWarp, excludeClipId: String?): Project {
        warp.prepare(instantsOf(project))
        val texts = project.texts.map { retimeText(it, warp) }
        val stickers = project.stickers.map { retimeSticker(it, warp) }
        val audio = project.audio.map { clip ->
            if (!warp.affects(clip.startUs)) clip else clip.copy(startUs = warp.map(clip.startUs))
        }
        val adjustments = project.adjustmentClips.map { retimeAdjustment(it, warp) }
        val markers = project.markers.map { if (warp.affects(it.timeUs)) it.copy(timeUs = warp.map(it.timeUs)) else it }
        val camera = project.camera.let { camera ->
            if (camera.keyframes.isEmpty()) camera
            else {
                val moved = camera.keyframes.map { it.copy(timeUs = warp.map(it.timeUs)) }
                // Two keys squeezed onto the same instant: keep the later one, as upsert does.
                camera.copy(keyframes = moved.asReversed().distinctBy { it.timeUs }.asReversed())
            }
        }
        val tracks = project.videoTracks.map { track ->
            var cursor = 0L
            VideoTrack(track.id, track.clips.map { timed ->
                val start = if (timed.clip.id == excludeClipId || !warp.affects(timed.startUs)) timed.startUs
                    else warp.map(timed.startUs)
                // Monotone mapping keeps the order; compressing time may make neighbours touch,
                // so a clip never starts before the previous one on its track has ended.
                val placed = maxOf(start, cursor)
                TimedVideoClip(placed, timed.clip).also { cursor = it.endUs }
            })
        }
        return project.copy(texts = texts, stickers = stickers, audio = audio, adjustmentClips = adjustments,
            markers = markers, camera = camera, videoTracks = tracks)
    }

    /** Every project-time instant [retime] may ask for, so the engine maps them in one call. */
    private fun instantsOf(project: Project): List<Long> = buildList {
        project.texts.forEach { text ->
            add(text.startUs); add(text.endUs)
            text.wordCues.forEach { add(it.startUs); add(it.endUs) }
            text.transformKeyframes.forEach { add(it.timeUs) }
            text.mask?.keyframes?.forEach { add(text.startUs + it.timeUs) }
        }
        project.stickers.forEach { sticker ->
            add(sticker.startUs); add(sticker.endUs)
            sticker.transformKeyframes.forEach { add(it.timeUs) }
            sticker.mask?.keyframes?.forEach { add(sticker.startUs + it.timeUs) }
        }
        project.audio.forEach { add(it.startUs) }
        project.adjustmentClips.forEach { add(it.startUs); add(it.endUs) }
        project.markers.forEach { add(it.timeUs) }
        project.camera.keyframes.forEach { add(it.timeUs) }
        project.videoTracks.forEach { track -> track.clips.forEach { add(it.startUs) } }
    }

    private fun mapRange(startUs: Long, endUs: Long, warp: TimeWarp): Pair<Long, Long> {
        val start = warp.map(startUs)
        val end = maxOf(warp.map(endUs), start + MIN_ITEM_US)
        return start to end
    }

    private fun retimeText(text: TextClip, warp: TimeWarp): TextClip {
        if (!warp.affects(text.startUs) && !(text.endUs > warp.oldStartUs && text.startUs < warp.oldEndUs)) return text
        val (start, end) = mapRange(text.startUs, text.endUs, warp)
        val cues = text.wordCues.map { cue ->
            val cueStart = warp.map(cue.startUs).coerceIn(start, end - 1)
            val cueEnd = warp.map(cue.endUs).coerceIn(cueStart + 1, end)
            SubtitleWordCue(cue.text, cueStart, cueEnd)
        }
        return text.copy(startUs = start, endUs = end, wordCues = cues,
            transformKeyframes = text.transformKeyframes.map { it.copy(timeUs = warp.map(it.timeUs)) }.distinctBy { it.timeUs },
            mask = retimeMask(text.mask, text.startUs, start, end - start, warp))
    }

    private fun retimeSticker(sticker: StickerClip, warp: TimeWarp): StickerClip {
        if (!warp.affects(sticker.startUs) && !(sticker.endUs > warp.oldStartUs && sticker.startUs < warp.oldEndUs)) return sticker
        val (start, end) = mapRange(sticker.startUs, sticker.endUs, warp)
        return sticker.copy(startUs = start, endUs = end,
            transformKeyframes = sticker.transformKeyframes.map { it.copy(timeUs = warp.map(it.timeUs)) }.distinctBy { it.timeUs },
            mask = retimeMask(sticker.mask, sticker.startUs, start, end - start, warp))
    }

    private fun retimeAdjustment(clip: AdjustmentClip, warp: TimeWarp): AdjustmentClip {
        if (!warp.affects(clip.startUs) && !(clip.endUs > warp.oldStartUs && clip.startUs < warp.oldEndUs)) return clip
        val (start, end) = mapRange(clip.startUs, clip.endUs, warp)
        return clip.copy(startUs = start, endUs = end)
    }

    /** Mask keyframes are relative to the item's start; they are mapped through absolute time. */
    private fun retimeMask(mask: MaskState?, oldStartUs: Long, newStartUs: Long, newDurationUs: Long, warp: TimeWarp): MaskState? {
        if (mask == null || mask.keyframes.isEmpty()) return mask
        val keys = mask.keyframes.map { key ->
            key.copy(timeUs = (warp.map(oldStartUs + key.timeUs) - newStartUs).coerceIn(0L, newDurationUs))
        }.distinctBy { it.timeUs }
        return mask.copy(keyframes = keys)
    }

    private const val MIN_ITEM_US = 1_000L
}
