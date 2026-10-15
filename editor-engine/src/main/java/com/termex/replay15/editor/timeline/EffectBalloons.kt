package com.termex.replay15.editor.timeline

import com.termex.replay15.editor.domain.*

/**
 * Effects shown as pink balloons on the timeline, like texts, stickers and audio.
 *
 * Two kinds share the "FX" lanes:
 * - adjustment layers ([AdjustmentClip]), already in project time;
 * - effects placed on a video clip ([VideoClip.effects]), whose range is in the clip's source time
 *   (null = the whole clip). They are converted through the clip's [ClipTimeMap], so a balloon
 *   sits exactly where the effect plays, and moving it writes the range back in source time.
 *
 * No project data changes shape: a balloon is only a view of what is already stored.
 */
object EffectBalloons {
    /** Shortest effect a balloon can be trimmed to. */
    const val MIN_DURATION_US = 100_000L

    data class Balloon(
        /** Unique on the timeline: the adjustment id, or [clipEffectKey] for a clip effect. */
        val key: String,
        val startUs: Long,
        val endUs: Long,
        /** Where the balloon may move: the whole project, or the clip it belongs to. */
        val minUs: Long,
        val maxUs: Long,
        val adjustmentId: String? = null,
        val clipId: String? = null,
        val effectId: String? = null,
        val assetId: String? = null,
        val name: String,
    )

    private const val CLIP_PREFIX = "fx~"

    fun clipEffectKey(clipId: String, effectId: String) = "$CLIP_PREFIX$clipId~$effectId"

    /** Every effect on the timeline, adjustment layers first, then clip effects by start. */
    fun list(project: Project, effectName: (String) -> String = ::defaultName): List<Balloon> {
        val result = ArrayList<Balloon>()
        project.adjustmentClips.forEach { adjustment ->
            result += Balloon(adjustment.id, adjustment.startUs, adjustment.endUs, 0L, project.durationUs,
                adjustmentId = adjustment.id, name = adjustment.name)
        }
        placedClips(project).forEach { (clipStart, clip) ->
            clip.effects.filter { it.enabled }.forEach { effect ->
                val range = projectRange(clipStart, clip, effect.startTimeUs, effect.endTimeUs) ?: return@forEach
                result += Balloon(clipEffectKey(clip.id, effect.id), range.first, range.second,
                    clipStart, clipStart + clip.durationUs, clipId = clip.id, effectId = effect.id,
                    assetId = effect.assetId, name = effectName(effect.assetId))
            }
        }
        return result
    }

    fun find(project: Project, key: String): Balloon? = list(project).firstOrNull { it.key == key }

    /**
     * The project with the effect behind [key] moved/trimmed to [startUs]..[endUs] (project time),
     * clamped to where it may live. Unknown keys return the project unchanged.
     */
    fun withRange(project: Project, key: String, startUs: Long, endUs: Long): Project {
        val balloon = find(project, key) ?: return project
        val (start, end) = clamp(balloon, startUs, endUs) ?: return project
        if (balloon.adjustmentId != null) {
            return project.copy(adjustmentClips = project.adjustmentClips.map {
                if (it.id == balloon.adjustmentId) it.copy(startUs = start, endUs = end) else it
            })
        }
        val clipId = balloon.clipId ?: return project
        val placed = placedClips(project).firstOrNull { it.second.id == clipId } ?: return project
        val (clipStart, clip) = placed
        val a = clip.timeMap.sourceAt((start - clipStart).coerceIn(0L, clip.durationUs))
        val b = clip.timeMap.sourceAt((end - clipStart).coerceIn(0L, clip.durationUs))
        // A reversed clip maps later timeline to earlier source; the stored range is ascending.
        val sourceStart = minOf(a, b)
        val sourceEnd = maxOf(a, b)
        if (sourceEnd <= sourceStart) return project
        // Covering the whole clip again means "the whole clip": stored as no range, as before.
        val whole = start <= clipStart && end >= clipStart + clip.durationUs
        val updated = clip.copy(effects = clip.effects.map {
            if (it.id != balloon.effectId) it
            else if (whole) it.copy(startTimeUs = null, endTimeUs = null)
            else it.copy(startTimeUs = sourceStart, endTimeUs = sourceEnd)
        })
        return replaceClip(project, updated)
    }

    /** Keeps the balloon inside its limits and at least [MIN_DURATION_US] long. */
    fun clamp(balloon: Balloon, startUs: Long, endUs: Long): Pair<Long, Long>? {
        val span = balloon.maxUs - balloon.minUs
        if (span < MIN_DURATION_US) return null
        val start = startUs.coerceIn(balloon.minUs, balloon.maxUs - MIN_DURATION_US)
        val end = endUs.coerceIn(start + MIN_DURATION_US, balloon.maxUs)
        return start to end
    }

    /** "recly_flash_3" -> "Flash 3", used when the catalog name is not at hand. */
    fun defaultName(assetId: String): String = assetId.removePrefix("recly_").split('_')
        .filter { it.isNotBlank() }
        .joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
        .ifBlank { "Efeito" }

    /** Main-track clips at their start, then every layer track clip at its start. */
    private fun placedClips(project: Project): List<Pair<Long, VideoClip>> =
        project.videos.indices.map { project.startOf(it) to project.videos[it] } +
            project.videoTracks.flatMap { track -> track.clips.map { it.startUs to it.clip } }

    private fun projectRange(clipStart: Long, clip: VideoClip, sourceStart: Long?, sourceEnd: Long?): Pair<Long, Long>? {
        val a = if (sourceStart == null) 0L else clip.timeMap.timelineAt(sourceStart).coerceIn(0L, clip.durationUs)
        val b = if (sourceEnd == null) clip.durationUs else clip.timeMap.timelineAt(sourceEnd).coerceIn(0L, clip.durationUs)
        val start = clipStart + minOf(a, b)
        val end = clipStart + maxOf(a, b)
        return if (end > start) start to end else null
    }

    private fun replaceClip(project: Project, clip: VideoClip): Project {
        if (project.videos.any { it.id == clip.id }) {
            return project.copy(videos = project.videos.map { if (it.id == clip.id) clip else it })
        }
        return project.copy(videoTracks = project.videoTracks.map { track ->
            track.copy(clips = track.clips.map { if (it.clip.id == clip.id) it.copy(clip = clip) else it })
        })
    }
}
