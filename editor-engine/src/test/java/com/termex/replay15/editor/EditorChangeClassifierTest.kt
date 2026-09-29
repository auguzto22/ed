package com.termex.replay15.editor

import com.termex.replay15.editor.assets.EffectInstance
import com.termex.replay15.editor.core.EditorChangeClassifier
import com.termex.replay15.editor.core.EditorChangeClassifier.ChangeKind
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.SECOND
import com.termex.replay15.editor.domain.TimedVideoClip
import com.termex.replay15.editor.domain.VideoClip
import com.termex.replay15.editor.domain.VideoTrack
import com.termex.replay15.editor.domain.TrackState
import com.termex.replay15.editor.domain.MAIN_TRACK
import com.termex.replay15.editor.domain.TransformKeyframe
import com.termex.replay15.editor.domain.CropRect
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorChangeClassifierTest {
    private fun clip(id: String = "video") = VideoClip(
        id = id, uri = "content://video/$id", name = "$id.mp4",
        sourceUs = 15 * SECOND, width = 1920, height = 1080,
    )

    @Test fun uniformsAndTransformsOnlyRedraw() {
        val effect = EffectInstance("fx", "recly_glow", intensity = .4f)
        val before = Project(videos = listOf(clip().copy(effects = listOf(effect))))
        val after = before.copy(videos = listOf(before.videos.single().copy(
            zoom = 1.4f, brightness = .2f, effects = listOf(effect.copy(intensity = .9f)),
        )))
        assertEquals(ChangeKind.RENDER_ONLY, EditorChangeClassifier.classify(before, after))
    }

    @Test fun trimAndSpeedRequireDecoderSeek() {
        val before = Project(videos = listOf(clip()))
        assertEquals(ChangeKind.DECODER_SEEK, EditorChangeClassifier.classify(
            before, before.copy(videos = listOf(before.videos.single().copy(inUs = SECOND, speed = 1.5f))),
        ))
    }

    @Test fun movingTimedClipChangesTimelineMapping() {
        val timed = TimedVideoClip(SECOND, clip())
        val before = Project(videoTracks = listOf(VideoTrack(clips = listOf(timed))))
        val after = before.copy(videoTracks = listOf(before.videoTracks.single().copy(
            clips = listOf(timed.copy(startUs = 2 * SECOND)),
        )))
        assertEquals(ChangeKind.TIMELINE_MAPPING, EditorChangeClassifier.classify(before, after))
    }

    @Test fun uriChangeSwitchesActiveSource() {
        val before = Project(videos = listOf(clip()))
        val after = before.copy(videos = listOf(before.videos.single().copy(uri = "content://video/relinked")))
        assertEquals(ChangeKind.ACTIVE_SOURCE, EditorChangeClassifier.classify(before, after))
    }

    @Test fun addingOrRemovingLayerIsStructural() {
        val before = Project(videos = listOf(clip()))
        val after = before.copy(videos = before.videos + clip("second"))
        assertEquals(ChangeKind.STRUCTURAL_LAYER, EditorChangeClassifier.classify(before, after))
    }
    @Test fun entireGestureIncludingCommitAndCancelStaysRealtime() {
        val base = Project(videos = listOf(clip()))
        var previous = base
        repeat(50) { step ->
            val fraction = step / 100f
            val next = base.copy(videos = listOf(base.videos.single().copy(
                offsetX = fraction, offsetY = -fraction, zoom = 1f + fraction,
                fineRotation = step.toFloat(), opacity = 1f - fraction,
                crop = CropRect(.05f, .05f, .95f, .95f),
                keyframes = listOf(TransformKeyframe(0, x = fraction)),
            )))
            assertEquals("Gesture event $step", ChangeKind.RENDER_ONLY, EditorChangeClassifier.classify(previous, next))
            previous = next
        }
        assertEquals(ChangeKind.RENDER_ONLY, EditorChangeClassifier.classify(previous, previous))
        assertEquals(ChangeKind.RENDER_ONLY, EditorChangeClassifier.classify(previous, base))
    }

    @Test fun revealingOrSoloingVideoReconcilesDecoderDemand() {
        val visible = Project(videos = listOf(clip()))
        val hidden = visible.copy(trackStates = mapOf(MAIN_TRACK to TrackState(visible = false)))
        assertEquals(ChangeKind.ACTIVE_SOURCE, EditorChangeClassifier.classify(hidden, visible))
        assertEquals(ChangeKind.ACTIVE_SOURCE, EditorChangeClassifier.classify(visible, hidden))
    }

    @Test fun lockingSelectionDoesNotChangeDecoderDemand() {
        val before = Project(videos = listOf(clip()))
        val locked = before.copy(trackStates = mapOf(MAIN_TRACK to TrackState(locked = true)))
        assertEquals(ChangeKind.RENDER_ONLY, EditorChangeClassifier.classify(before, locked))
    }

}
