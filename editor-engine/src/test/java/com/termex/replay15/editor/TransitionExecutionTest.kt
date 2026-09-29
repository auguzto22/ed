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
        for (duration in listOf(100_000L, 500_000L, 2 * SECOND)) {
            val clips = listOf(clip("a").copy(speed = 2f), clip("b"), clip("c"))
            val project = Project(videos = clips, transitions = listOf(
                TransitionInstance("ab", "cross_dissolve", "a", "b", duration, easing = Easing.LINEAR),
                TransitionInstance("bc", "cube_left", "b", "c", duration, easing = Easing.LINEAR)))
            val resolver = ActiveClipResolver(project)
            // Logical overlap must not mutate source trims or introduce a third clip.
            assertEquals(clips, project.videos)
            for (time in 0 until project.durationUs step 33_333L) {
                val active = resolver.resolve(time)
                assertTrue("No frame at $time", active.videos.isNotEmpty())
                assertTrue(active.videos.size <= 2)
                active.videos.forEach {
                    assertTrue(it.sourceTimeUs(time) in it.clip.inUs until it.clip.outUs)
                }
            }
            for (index in 1..2) {
                val start = project.startOf(index)
                val end = start + duration
                // Deliberately non-monotonic order models scrubbing and repeated paused frames.
                for (time in listOf(start, end - 1, start + duration / 2, start, start + duration / 2)) {
                    val active = resolver.resolve(time)
                    assertEquals(2, active.videos.size)
                    val transition = active.transitions.single()
                    assertEquals(start, transition.startUs)
                    assertEquals(end, transition.endUs)
                    assertEquals((time - start).toFloat() / duration, transition.progress, .00001f)
                }
                assertTrue(resolver.resolve(end).transitions.isEmpty())
            }
        }
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
        val time = project.startOf(1) + SECOND / 2
        val snapshot = ActiveClipResolver(project).resolve(time)
        val state = RenderStateEvaluator.evaluate(snapshot, project, 4, 1, 320, 180)
        assertEquals(2, state.layers.size)
        for ((index, layer) in state.layers.withIndex()) {
            val clip = project.videos[index]
            val expectedSource = ProjectClipTimeMapper.projectToSource(clip, project.startOf(index), time)
                .coerceIn(clip.inUs, clip.outUs - 1L)
            assertEquals(expectedSource, layer.sourceTimeUs)
            assertEquals(clip.crop, layer.crop)
            assertEquals(clip.transformAt(expectedSource).opacity, layer.opacity, 0f)
            assertEquals(clip.transformAt(expectedSource).rotation, layer.rotation, 0f)
        }
        assertEquals(.5f, state.transition!!.progress, .00001f)
        assertEquals(project.startOf(1), state.transition!!.startUs)
    }
}
