package com.termex.replay15.editor

import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.render.RenderPlan
import org.junit.Assert.*
import org.junit.Test

class RenderPlanTest {
    private fun clip() = VideoClip(uri = "content://video/1", name = "Source", sourceUs = 10 * SECOND, width = 1920, height = 1080)

    @Test fun sourceTimeIncludesPlacedStartTrimAndSpeed() {
        val clip = clip().copy(inUs = 2 * SECOND, outUs = 8 * SECOND, speed = 2f)
        assertEquals(2 * SECOND, RenderPlan.sourceTime(clip, 4 * SECOND, 0))
        assertEquals(2 * SECOND, RenderPlan.sourceTime(clip, 4 * SECOND, 4 * SECOND))
        assertEquals(4 * SECOND, RenderPlan.sourceTime(clip, 4 * SECOND, 5 * SECOND))
        assertEquals(8 * SECOND, RenderPlan.sourceTime(clip, 4 * SECOND, 20 * SECOND))
    }

    @Test fun renderOrderIsTopFirstAndGapsAreNotActive() {
        val lower = VideoTrack(clips = listOf(TimedVideoClip(SECOND, clip())))
        val upper = VideoTrack(clips = listOf(TimedVideoClip(4 * SECOND, clip())))
        val plan = RenderPlan.layers(Project(videos = listOf(clip()), videoTracks = listOf(lower, upper)))
        assertEquals(listOf(upper.id, lower.id, MAIN_TRACK), plan.map { it.id })
        assertNull(plan[0].activeAt(4 * SECOND - 1))
        assertNotNull(plan[0].activeAt(4 * SECOND))
        assertNull(plan[0].activeAt(14 * SECOND))
    }

    @Test fun removingMainVideoKeepsOverlayDurationAndCanvasOrientation() {
        val portrait = clip().copy(width = 1080, height = 1920)
        val p = Project(videoTracks = listOf(VideoTrack(clips = listOf(TimedVideoClip(SECOND, portrait)))))
        assertEquals(11 * SECOND, p.durationUs)
        assertTrue(p.dimensions().first < p.dimensions().second)
        assertEquals(1, p.allVideos.size)
    }

    @Test fun fractionalSpeedSplitKeepsContiguousBoundaries() {
        listOf(.3f, .7f, 1.3f, 2.7f).forEach { speed ->
            val source = clip().copy(speed = speed)
            val track = VideoTrack(clips = listOf(TimedVideoClip(123_456, source)))
            val p = Project(videoTracks = listOf(track))
            val parts = TrackEditing.split(p, track.id, source.id, 1_234_567).videoTracks[0].clips
            assertEquals(2, parts.size)
            assertEquals(parts[0].endUs, parts[1].startUs)
            assertEquals(parts[0].clip.outUs, parts[1].clip.inUs)
            assertTrue(kotlin.math.abs(parts.last().endUs - track.clips[0].endUs) <= 1)
        }
    }

    @Test fun untouchedConsecutiveCutsUseOneDecoderItemWithoutChangingProject() {
        val original = Project(videos = listOf(clip()))
        val cut = original.splitAt(2 * SECOND).splitAt(5 * SECOND).splitAt(8 * SECOND)
        val rendered = RenderPlan.layers(cut).single().clips
        assertEquals(4, cut.videos.size)
        assertEquals(1, rendered.size)
        assertEquals(0, rendered.single().clip.inUs)
        assertEquals(10 * SECOND, rendered.single().clip.outUs)
        assertEquals(original.durationUs, rendered.single().endUs)
        assertTrue(RenderPlan.playbackBoundaries(cut).isEmpty())
    }

    @Test fun deletedOrStyledPiecesKeepOnlyRequiredPlaybackBoundaries() {
        val cut = Project(videos = listOf(clip())).splitAt(2 * SECOND).splitAt(5 * SECOND).splitAt(8 * SECOND)
        val styled = cut.changeVideo(2) { it.copy(brightness = .1f) }
        assertEquals(3, RenderPlan.layers(styled).single().clips.size)
        assertEquals(listOf(5 * SECOND, 8 * SECOND), RenderPlan.playbackBoundaries(styled))
        val deleted = cut.copy(videos = cut.videos.filterIndexed { index, _ -> index != 1 })
        assertEquals(2, RenderPlan.layers(deleted).single().clips.size)
        assertEquals(listOf(2 * SECOND), RenderPlan.playbackBoundaries(deleted))
    }

    @Test fun transitionEndsAreNotForcedPlaybackSeekBoundaries() {
        val first = clip().copy(id = "first", outUs = 4 * SECOND)
        val second = clip().copy(id = "second", inUs = 4 * SECOND, outUs = 8 * SECOND)
        val transition = com.termex.replay15.editor.assets.TransitionInstance(
            id = "transition",
            transitionId = "cube_left",
            leftClipId = first.id,
            rightClipId = second.id,
            durationUs = SECOND,
        )
        val project = Project(videos = listOf(first, second), transitions = listOf(transition))

        assertTrue(RenderPlan.playbackBoundaries(project).isEmpty())
    }

}
