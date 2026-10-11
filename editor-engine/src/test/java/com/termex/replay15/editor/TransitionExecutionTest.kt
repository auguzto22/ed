package com.termex.replay15.editor

import com.termex.replay15.editor.assets.TransitionInstance
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.preview.engine.ActiveClipResolver
import com.termex.replay15.editor.preview.engine.RenderStateEvaluator
import org.junit.Assert.*
import org.junit.Test

class TransitionExecutionTest {
    private fun clip(id: String) = VideoClip(id = id, uri = "file:///$id.mp4", name = id,
        sourceUs = 12 * SECOND, inUs = SECOND, outUs = 11 * SECOND, width = 1920, height = 1080)

    @Test fun shortAndLongTransitionsKeepBothSourceTimesValidWithoutGaps() {
        for (alignment in TransitionAlignment.entries) for (duration in listOf(100_000L, 500_000L, 2 * SECOND)) {
            val clips = listOf(clip("a").copy(speed = 2f), clip("b"), clip("c"))
            val project = Project(videos = clips, transitions = listOf(
                TransitionInstance("ab", "cross_dissolve", "a", "b", duration, easing = Easing.LINEAR, alignment = alignment),
                TransitionInstance("bc", "cube_left", "b", "c", duration, easing = Easing.LINEAR, alignment = alignment)))
            val resolver = ActiveClipResolver(project)
            // Logical overlap must not mutate source trims, introduce a third clip or move the cuts.
            assertEquals(clips, project.videos)
            assertEquals(clips.sumOf { it.durationUs }, project.durationUs)
            for (time in 0 until project.durationUs step 33_333L) {
                val active = resolver.resolve(time)
                assertTrue("No frame at $time", active.videos.isNotEmpty())
                assertTrue(active.videos.size <= 2)
                // Handles read real media around the trim, never outside the file.
                active.videos.forEach { assertTrue(it.sourceTimeUs(time) in 0 until it.clip.sourceUs) }
            }
            for (index in 1..2) {
                val cut = project.startOf(index)
                val (before, after) = TransitionTiming.split(duration, alignment)
                val start = cut - before
                val end = cut + after
                // Deliberately non-monotonic order models scrubbing and repeated paused frames.
                for (time in listOf(start, end - 1, start + duration / 2, start, start + duration / 2)) {
                    val active = resolver.resolve(time)
                    assertEquals("$alignment $duration at $time", 2, active.videos.size)
                    val transition = active.transitions.single()
                    assertEquals(start, transition.startUs)
                    assertEquals(end, transition.endUs)
                    assertEquals((time - start).toFloat() / duration, transition.progress, .00001f)
                }
                assertTrue(resolver.resolve(end).transitions.isEmpty())
                if (start > 0) assertTrue(resolver.resolve(start - 1).transitions.isEmpty())
            }
        }
    }

    @Test fun outgoingClipKeepsMovingThroughTheWindowInsteadOfFreezing() {
        val a = clip("a"); val b = clip("b")
        val project = Project(videos = listOf(a, b), transitions = listOf(
            TransitionInstance("ab", "cross_dissolve", "a", "b", SECOND, easing = Easing.LINEAR)))
        val resolver = ActiveClipResolver(project)
        val cut = a.durationUs
        val samples = (cut - SECOND / 2 until cut + SECOND / 2 step 40_000L).map { time ->
            val active = resolver.resolve(time)
            active.videos.single { it.clip.id == "a" }.sourceTimeUs(time) to active.videos.single { it.clip.id == "b" }.sourceTimeUs(time)
        }
        // Both sides advance every frame: A plays past its out point, B starts before its in point.
        samples.zipWithNext().forEach { (x, y) ->
            assertTrue("A froze: $x -> $y", y.first > x.first)
            assertTrue("B froze: $x -> $y", y.second > x.second)
        }
        assertTrue(samples.last().first > a.outUs)
        assertTrue(samples.first().second < b.inUs)
    }

    @Test fun noTransitionKeepsAHalfOpenCut() {
        val a = clip("a")
        val resolver = ActiveClipResolver(Project(videos = listOf(a, clip("b"))))
        assertEquals("a", resolver.resolve(a.durationUs - 1).videos.single().clip.id)
        assertEquals("b", resolver.resolve(a.durationUs).videos.single().clip.id)
        assertTrue(resolver.resolve(a.durationUs).transitions.isEmpty())
    }

    @Test fun renderInputsKeepIndependentTransformsAndSpeedRampTimeMapping() {
        val a = clip("a").copy(zoom = 1.5f, offsetX = -.2f, fineRotation = 25f, opacity = .4f,
            crop = CropRect(.1f, .1f, .9f, .9f), speedCurve = listOf(SpeedPoint(0, .5f), SpeedPoint(12 * SECOND, 2f)))
        val b = clip("b").copy(zoom = .8f, offsetY = .15f, fineRotation = -15f, opacity = .8f)
        val project = Project(videos = listOf(a, b), transitions = listOf(
            TransitionInstance("ab", "cross_dissolve", "a", "b", SECOND, easing = Easing.LINEAR)))
        val cut = project.startOf(1)
        // A quarter into a centered window: A is still inside its own span, B is in its head handle.
        val time = cut - SECOND / 4
        val snapshot = ActiveClipResolver(project).resolve(time)
        val state = RenderStateEvaluator.evaluate(snapshot, project, 4, 1, 320, 180)
        assertEquals(2, state.layers.size)
        val expectedA = ProjectClipTimeMapper.projectToSource(a, 0L, time).coerceIn(a.inUs, a.outUs - 1L)
        val expectedB = b.inUs - SECOND / 4
        for ((layer, expected) in state.layers.zip(listOf(expectedA, expectedB))) {
            val clip = project.videos.single { it.id == layer.key.clipId }
            assertEquals(expected, layer.sourceTimeUs)
            assertEquals(clip.crop, layer.crop)
            assertEquals(clip.transformAt(expected).opacity, layer.opacity, 0f)
            assertEquals(clip.transformAt(expected).rotation, layer.rotation, 0f)
        }
        assertEquals(.25f, state.transition!!.progress, .00001f)
        assertEquals(cut - SECOND / 2, state.transition!!.startUs)
        assertEquals(cut + SECOND / 2, state.transition!!.endUs)
    }
}
