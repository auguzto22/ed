package com.termex.replay15.editor.project

import com.termex.replay15.editor.domain.MaskKeyframe
import com.termex.replay15.editor.domain.MaskPathPoint
import com.termex.replay15.editor.domain.MaskState
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.VideoClip
import com.termex.replay15.editor.tracking.LayerGeometry

/**
 * Schema 30: video masks moved from GL texture space (y up) to the shared y-down layer space used
 * by text/sticker masks, the sliders, the path editor and the tracking frames. Tracks are stored in
 * that same space now and move the mask relative to its placement instead of replacing it.
 *
 * Projects saved earlier are converted on load so every mask keeps showing where it used to.
 */
internal object LegacyMaskSpaceMigration {
    fun migrate(project: Project): Project = project.copy(
        videos = project.videos.map(::migrate),
        videoTracks = project.videoTracks.map { track -> track.copy(clips = track.clips.map { it.copy(clip = migrate(it.clip)) }) },
    )

    private fun migrate(clip: VideoClip): VideoClip {
        if (clip.mask == null && clip.trackingTracks.isEmpty()) return clip
        val tracks = clip.trackingTracks.map { track -> track.copy(points = track.points.map(LayerGeometry::fromLegacyMaskSpace)) }
        val mask = clip.mask?.let { flip(it) }?.let { mask ->
            // Old tracking replaced the geometry with the tracked box; with relative tracking the
            // same result comes from starting the mask exactly on the box of the first frame.
            val anchor = mask.trackingTrackId?.let { id -> tracks.firstOrNull { it.id == id } }?.points?.first()
            if (anchor == null) mask else mask.copy(
                centerX = anchor.centerX, centerY = anchor.centerY,
                width = anchor.width.coerceIn(.01f, 2f), height = anchor.height.coerceIn(.01f, 2f),
                keyframes = mask.keyframes.map { it.copy(centerX = anchor.centerX, centerY = anchor.centerY,
                    width = anchor.width.coerceIn(.01f, 2f), height = anchor.height.coerceIn(.01f, 2f)) },
            )
        }
        return clip.copy(mask = mask, trackingTracks = tracks)
    }

    /** Mirror vertically: y -> 1 - y, and rotations change direction. */
    private fun flip(mask: MaskState): MaskState = mask.copy(
        centerY = 1f - mask.centerY,
        rotation = flipAngle(mask.rotation),
        customPath = mask.customPath.map { MaskPathPoint(it.x, 1f - it.y) },
        keyframes = mask.keyframes.map { key: MaskKeyframe -> key.copy(centerY = 1f - key.centerY, rotation = flipAngle(key.rotation)) },
    )

    private fun flipAngle(degrees: Float): Float = (-degrees).coerceIn(-180f, 180f)
}
