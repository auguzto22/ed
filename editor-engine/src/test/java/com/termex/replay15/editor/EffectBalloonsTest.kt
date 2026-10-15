package com.termex.replay15.editor

import com.termex.replay15.editor.assets.EffectInstance
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.timeline.EffectBalloons
import com.termex.replay15.editor.ui.TimelineHitContext
import com.termex.replay15.editor.ui.TimelineHitResolver
import com.termex.replay15.editor.ui.TimelineHitTarget
import com.termex.replay15.editor.ui.TimelineLaneSnapshot
import com.termex.replay15.editor.ui.TimelineSegmentSnapshot
import org.junit.Assert.*
import org.junit.Test

/** Effects as pink balloons on the timeline: where they sit and what moving one writes back. */
class EffectBalloonsTest {
    private fun video(id: String, seconds: Int = 10, effects: List<EffectInstance> = emptyList()) = VideoClip(
        id = id, uri = "content://$id", name = id, sourceUs = seconds * SECOND, width = 640, height = 360, effects = effects)

    @Test fun clipEffectSitsWhereItPlays() {
        val flash = EffectInstance("fx1", "recly_flash", startTimeUs = 2 * SECOND, endTimeUs = 4 * SECOND)
        val project = Project(videos = listOf(video("a"), video("b", effects = listOf(flash))))
        val balloon = EffectBalloons.list(project).single()
        // Clip "b" starts at 10 s on the timeline.
        assertEquals(12 * SECOND, balloon.startUs)
        assertEquals(14 * SECOND, balloon.endUs)
        assertEquals(10 * SECOND, balloon.minUs)
        assertEquals(20 * SECOND, balloon.maxUs)
        assertEquals("Flash", balloon.name)
    }

    @Test fun effectWithoutRangeCoversTheWholeClip() {
        val project = Project(videos = listOf(video("a", effects = listOf(EffectInstance("fx1", "recly_glow")))))
        val balloon = EffectBalloons.list(project).single()
        assertEquals(0L, balloon.startUs)
        assertEquals(10 * SECOND, balloon.endUs)
    }

    @Test fun movingABalloonWritesTheSourceRange() {
        val flash = EffectInstance("fx1", "recly_flash", startTimeUs = 2 * SECOND, endTimeUs = 4 * SECOND)
        val project = Project(videos = listOf(video("a"), video("b", effects = listOf(flash))))
        val key = EffectBalloons.clipEffectKey("b", "fx1")
        val moved = EffectBalloons.withRange(project, key, 15 * SECOND, 17 * SECOND)
        val effect = moved.videos[1].effects.single()
        assertEquals(5 * SECOND, effect.startTimeUs)
        assertEquals(7 * SECOND, effect.endTimeUs)
        // Nothing else changes.
        assertEquals(project.videos[0], moved.videos[0])
        assertEquals(flash.copy(startTimeUs = 5 * SECOND, endTimeUs = 7 * SECOND), effect)
    }

    @Test fun balloonStaysInsideItsClipAndKeepsAMinimumLength() {
        val flash = EffectInstance("fx1", "recly_flash", startTimeUs = 2 * SECOND, endTimeUs = 4 * SECOND)
        val project = Project(videos = listOf(video("a"), video("b", effects = listOf(flash))))
        val key = EffectBalloons.clipEffectKey("b", "fx1")
        val tooFar = EffectBalloons.withRange(project, key, 25 * SECOND, 30 * SECOND).videos[1].effects.single()
        assertEquals(10 * SECOND - EffectBalloons.MIN_DURATION_US, tooFar.startTimeUs)
        assertEquals(10 * SECOND, tooFar.endTimeUs)
        val tooShort = EffectBalloons.withRange(project, key, 12 * SECOND, 12 * SECOND).videos[1].effects.single()
        assertEquals(EffectBalloons.MIN_DURATION_US, tooShort.endTimeUs!! - tooShort.startTimeUs!!)
    }

    @Test fun stretchingOverTheWholeClipClearsTheRange() {
        val flash = EffectInstance("fx1", "recly_flash", startTimeUs = 2 * SECOND, endTimeUs = 4 * SECOND)
        val project = Project(videos = listOf(video("a", effects = listOf(flash))))
        val whole = EffectBalloons.withRange(project, EffectBalloons.clipEffectKey("a", "fx1"), 0L, 10 * SECOND)
        val effect = whole.videos[0].effects.single()
        assertNull(effect.startTimeUs)
        assertNull(effect.endTimeUs)
    }

    @Test fun adjustmentLayerMovesInProjectTime() {
        val adjustment = AdjustmentClip(id = "adj", startUs = SECOND, endUs = 3 * SECOND,
            effects = listOf(EffectInstance("g", "recly_glow")))
        val project = Project(videos = listOf(video("a")), adjustmentClips = listOf(adjustment))
        val moved = EffectBalloons.withRange(project, "adj", 4 * SECOND, 6 * SECOND)
        assertEquals(4 * SECOND, moved.adjustmentClips.single().startUs)
        assertEquals(6 * SECOND, moved.adjustmentClips.single().endUs)
    }

    @Test fun layerTrackClipEffectsAreShownToo() {
        val layer = video("pip", seconds = 4, effects = listOf(EffectInstance("fx", "recly_crt", startTimeUs = SECOND, endTimeUs = 2 * SECOND)))
        val project = Project(videos = listOf(video("a")), videoTracks = listOf(VideoTrack("t1", listOf(TimedVideoClip(3 * SECOND, layer)))))
        val balloon = EffectBalloons.list(project).single()
        assertEquals(4 * SECOND, balloon.startUs)
        assertEquals(5 * SECOND, balloon.endUs)
        val moved = EffectBalloons.withRange(project, balloon.key, 5 * SECOND, 6 * SECOND)
        val effect = moved.videoTracks.single().clips.single().clip.effects.single()
        assertEquals(2 * SECOND, effect.startTimeUs)
        assertEquals(3 * SECOND, effect.endTimeUs)
    }

    @Test fun unknownKeyLeavesTheProjectAlone() {
        val project = Project(videos = listOf(video("a")))
        assertSame(project, EffectBalloons.withRange(project, "nope", 0L, SECOND))
    }

    @Test fun defaultNameIsReadable() {
        assertEquals("Flash 3", EffectBalloons.defaultName("recly_flash_3"))
        assertEquals("Ghost Trail", EffectBalloons.defaultName("ghost_trail"))
    }

    @Test fun effectLaneHitsBodyAndHandles() {
        val segment = TimelineSegmentSnapshot("k", 2 * SECOND, 4 * SECOND)
        fun context(selected: String?) = TimelineHitContext(
            project = Project(videos = listOf(video("a"))), selectedClipIndex = -1, selectedLayerId = selected,
            pixelsPerSecond = 100f, positionUs = 0L, width = 1000f, height = 800f, density = 1f, verticalScroll = 0f,
            gutterWidth = 26f, timeToX = { it / 10_000f }, xToTime = { (it * 10_000f).toLong() },
            visualStart = { 0L }, visualEnd = { 10 * SECOND }, visualTime = { _, _, t -> t }, transitionBoundaryAfter = { 10 * SECOND },
            getLaneAt = { index -> if (index == 0) TimelineLaneSnapshot(4, listOf(segment)) else null },
        )
        // Lane 0 starts at 132dp; 2 s = 200 px, 4 s = 400 px with this mapping.
        val body = TimelineHitResolver.resolve(300f, 145f, context(null))
        assertTrue(body is TimelineHitTarget.EffectBodyTarget)
        val handle = TimelineHitResolver.resolve(398f, 145f, context("k"))
        assertTrue(handle is TimelineHitTarget.EffectTrimTarget)
        assertEquals(1, (handle as TimelineHitTarget.EffectTrimTarget).side)
    }
}
