package com.termex.replay15.editor

import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.project.*
import com.termex.replay15.editor.render.ClipSpeedProvider
import com.termex.replay15.editor.render.RenderPlan
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import kotlin.math.abs
import kotlin.math.roundToLong

class TimeMappingTest {
    private fun clip() = VideoClip(uri = "content://video/1", name = "Ramp", sourceUs = 10 * SECOND, width = 1920, height = 1080,
        speedCurve = listOf(SpeedPoint(0, .25f), SpeedPoint(4 * SECOND, 4f), SpeedPoint(10 * SECOND, .5f)))

    @Test fun constantSpeedsPreserveLegacyTiming() {
        listOf(.1f, .3f, .7f, 1f, 1.3f, 2.7f, 16f).forEach { speed ->
            val c = clip().copy(speedCurve = emptyList(), speed = speed, inUs = 123_456, outUs = 8_234_567)
            assertEquals(((c.outUs - c.inUs) / speed.toDouble()).roundToLong(), c.timeMap.durationUs)
            assertEquals(c.durationUs, c.timeMap.durationUs)
            for (time in 0..c.durationUs step 100_000) {
                assertEquals((c.inUs + (time * speed.toDouble()).roundToLong()).coerceAtMost(c.outUs), c.timeMap.sourceAt(time))
            }
        }
    }
    @Test fun rampIsMonotonicAndInvertible() {
        val c = clip().copy(inUs = 231_123, outUs = 9_734_532)
        var previous = -1L
        for (time in 0..c.durationUs step 1000) {
            val source = c.timeMap.sourceAt(time)
            assertTrue(source >= previous); previous = source
            assertTrue(abs(time - c.timeMap.timelineAt(source)) <= 3)
        }
        assertEquals(c.outUs, c.timeMap.sourceAt(c.durationUs))
        assertEquals(c.inUs, c.timeMap.sourceAt(-1))
    }
    @Test fun media3ProviderMatchesTimelineSegmentSchedule() {
        val c = clip().copy(inUs = 231_123, outUs = 9_734_532)
        val provider = ClipSpeedProvider(c)
        var input = 0L; var output = 0L
        while (input < c.outUs - c.inUs) {
            val speed = provider.getSpeed(input)
            val change = provider.getNextSpeedChangeTimeUs(input)
            val end = if (change < 0) c.outUs - c.inUs else change
            assertTrue(end > input)
            assertTrue(abs(c.timeMap.timelineAt(c.inUs + input) - output) <= 1)
            output += ((end - input) / speed.toDouble()).roundToLong(); input = end
        }
        assertEquals(c.durationUs, output)
    }
    @Test fun splitKeepsSourceAnchoredRampsAndKeyframes() {
        val c = clip().copy(keyframes = listOf(TransformKeyframe(SECOND), TransformKeyframe(8 * SECOND, zoom = 2f)))
        val p = Project(videos = listOf(c))
        val split = p.split(0, 1_234_567)
        assertEquals(2, split.videos.size)
        assertTrue(abs(split.durationUs - p.durationUs) <= 1)
        val a = split.videos[0]; val b = split.videos[1]
        assertEquals(a.outUs, b.inUs)
        assertEquals(c.speedCurve, b.speedCurve)
        assertEquals(a.outUs, b.keyframes.first().sourceUs)
        assertEquals(c.keyframes.last(), b.keyframes.last())
        for (time in 0..b.durationUs step 100_000) {
            assertTrue(abs(c.timeMap.sourceAt(a.durationUs + time) - b.timeMap.sourceAt(time)) <= 4)
        }
    }
    @Test fun trimCanRecoverSourceOnBothSidesOfRamp() {
        val original = clip()
        val trimmed = original.copy(inUs = 2 * SECOND, outUs = 6 * SECOND)
        assertEquals(0L, trimmed.timeMap.extendedSourceAt(-100 * SECOND))
        assertEquals(original.sourceUs, trimmed.timeMap.extendedSourceAt(100 * SECOND))
        assertTrue(trimmed.timeMap.extendedSourceAt(-SECOND) < trimmed.inUs)
        assertTrue(trimmed.timeMap.extendedSourceAt(trimmed.durationUs + SECOND) > trimmed.outUs)
    }
    @Test fun overlayPreviewUsesSameMappingAsMain() {
        val c = clip(); val start = 3 * SECOND
        assertEquals(c.timeMap.sourceAt(SECOND), RenderPlan.sourceTime(c, start, start + SECOND))
    }
    @Test fun sharedMapperConvertsProjectAndSourceBothWays() {
        val c = clip().copy(speedCurve = emptyList(), inUs = 2 * SECOND, outUs = 8 * SECOND, speed = 2f)
        val start = 5 * SECOND
        assertEquals(4 * SECOND, ProjectClipTimeMapper.projectToSource(c, start, 6 * SECOND))
        assertEquals(6 * SECOND, ProjectClipTimeMapper.sourceToProject(c, start, 4 * SECOND))
    }
    @Test fun veryLongClipHasBoundedSampleSchedule() {
        val c = clip().copy(sourceUs = MAX_PROJECT_US, outUs = MAX_PROJECT_US,
            speedCurve = listOf(SpeedPoint(0, 1f), SpeedPoint(MAX_PROJECT_US, 16f)))
        assertTrue(c.timeMap.segments.size <= 2080)
        assertTrue(c.durationUs > 0)
    }
    @Test fun invalidRampsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { SpeedPoint(0, Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { clip().copy(speedCurve = listOf(SpeedPoint(2, 1f), SpeedPoint(1, 2f))) }
        assertThrows(IllegalArgumentException::class.java) { clip().copy(speedCurve = List(33) { SpeedPoint(it.toLong(), 1f) }) }
        assertThrows(IllegalArgumentException::class.java) { clip().copy(speedCurve = listOf(SpeedPoint(100 * SECOND, 1f))) }
    }
    @Test fun curvesPitchAndBezierSurviveSaveUndoAndReopenForBothVideoTracks() {
        val c = clip().copy(preservePitch = true, keyframes = listOf(TransformKeyframe(0, easing = Easing.BEZIER,
            bezier = CubicBezier(.15f, -.3f, .7f, 1.4f))))
        val p = Project(videos = listOf(c), videoTracks = listOf(VideoTrack(clips = listOf(TimedVideoClip(SECOND, c.copy(id = newId()))))))
        val bytes = ByteArrayOutputStream(); ProjectCodec.write(p, bytes)
        assertEquals(p, ProjectCodec.read(bytes.toByteArray().inputStream()))
        val history = com.termex.replay15.editor.history.ProjectHistory(p)
        history.apply(p.copy(videos = listOf(c.copy(speedCurve = emptyList()))))
        history.undo(); assertEquals(p, history.current)
    }
    @Test fun schemaEightDefaultsToLegacyConstantSpeed() {
        val p = Project(videos = listOf(clip().copy(speedCurve = emptyList())))
        val bytes = ByteArrayOutputStream(); ProjectCodec.write(p, bytes)
        val tail = ByteArrayOutputStream(); MotionCodec.write(p, DataOutputStream(tail))
        val old = bytes.toByteArray().copyOf(bytes.size() - tail.size())
        java.nio.ByteBuffer.wrap(old).putInt(4, 8)
        assertEquals(p, ProjectCodec.read(old.inputStream()))
    }
}
