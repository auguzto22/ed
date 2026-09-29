package com.termex.replay15.editor

import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.project.ProjectCodec
import com.termex.replay15.editor.core.EditorSessionController
import com.termex.replay15.editor.history.ProjectHistory
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class TrackEditingTest {
    private fun clip() = VideoClip(uri = "file:///video.mp4", name = "clip", sourceUs = 10 * SECOND, width = 1280, height = 720)
    @Test fun timedLayerSurvivesReopenAndPreservesSource() {
        val track = VideoTrack(clips = listOf(TimedVideoClip(4 * SECOND, clip().copy(inUs = SECOND, speed = 2f))))
        val p = Project(videos = listOf(clip()), videoTracks = listOf(track), trackStates = mapOf(track.id to TrackState("Facecam", muted = true)), createdAt = 1, modifiedAt = 2)
        val out = ByteArrayOutputStream(); ProjectCodec.write(p, out)
        assertEquals(p, ProjectCodec.read(out.toByteArray().inputStream()))
        assertNull(track.activeAt(4 * SECOND - 1)); assertNotNull(track.activeAt(4 * SECOND))
        assertNull(track.activeAt(track.clips[0].endUs))
    }
    @Test fun splitMoveAndUndoKeepSourceTrimAndDuration() {
        val clip = clip().copy(inUs = SECOND, outUs = 9 * SECOND, speed = 2f)
        val track = VideoTrack(clips = listOf(TimedVideoClip(2 * SECOND, clip)))
        val p = Project(videos = listOf(clip()), videoTracks = listOf(track))
        val cut = TrackEditing.split(p, track.id, clip.id, 4 * SECOND)
        val second = cut.videoTracks[0].clips[1]
        assertEquals(5 * SECOND, second.clip.inUs)
        val moved = TrackEditing.move(cut, track.id, second.clip.id, 8 * SECOND)
        assertEquals(8 * SECOND, moved.videoTracks[0].clips[1].startUs)
        val history = ProjectHistory(p); history.apply(cut); history.apply(moved)
        history.undo(); assertEquals(cut, history.current); history.undo(); assertEquals(p, history.current)
    }
    @Test fun overlapAndLockedTrackAreRejected() {
        val first = TimedVideoClip(0, clip())
        assertThrows(IllegalArgumentException::class.java) { VideoTrack(clips = listOf(first, TimedVideoClip(SECOND, clip()))) }
        val track = VideoTrack(clips = listOf(first))
        val p = Project(videoTracks = listOf(track), trackStates = mapOf(track.id to TrackState(locked = true)))
        assertThrows(IllegalArgumentException::class.java) { TrackEditing.move(p, track.id, first.clip.id, SECOND) }
        val session = EditorSessionController(ProjectHistory(p))
        assertThrows(IllegalArgumentException::class.java) { session.apply(p.copy(videoTracks = emptyList())) }
    }
    @Test fun visualOrderSoloMuteAndDurationAreIndependent() {
        val a = VideoTrack(clips = listOf(TimedVideoClip(2 * SECOND, clip())))
        val b = VideoTrack(clips = listOf(TimedVideoClip(0, clip())))
        val p = Project(videos = listOf(clip()), videoTracks = listOf(a, b), trackStates = mapOf(a.id to TrackState(solo = true, muted = true)))
        assertEquals(12 * SECOND, p.durationUs)
        assertTrue(p.visualEnabled(a.id)); assertFalse(p.visualEnabled(b.id)); assertFalse(p.audioEnabled(a.id))
        assertEquals(listOf(b, a), TrackEditing.reorder(p, a.id, 1).videoTracks)
    }
}
