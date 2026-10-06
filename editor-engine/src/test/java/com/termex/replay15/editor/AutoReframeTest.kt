package com.termex.replay15.editor

import com.termex.replay15.editor.domain.SECOND
import com.termex.replay15.editor.domain.VideoClip
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.TransformKeyframe
import com.termex.replay15.editor.reframe.AutoReframeEngine
import com.termex.replay15.editor.reframe.ReframeAspect
import com.termex.replay15.editor.reframe.ReframeSubject
import com.termex.replay15.editor.reframe.ReframePoint
import com.termex.replay15.editor.history.ProjectHistory
import com.termex.replay15.editor.project.ProjectCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoReframeTest {

    private fun video(seconds: Int = 10) = VideoClip(
        uri = "content://video/1",
        name = "Replay.mp4",
        sourceUs = seconds * SECOND,
        width = 1920,
        height = 1080,
    )

    private fun subject(us: Long, x: Float, y: Float = .5f, height: Float = .5f, confidence: Float = .9f) =
        ReframeSubject(us, x, y, .3f, height, confidence)

    @Test
    fun `vertical target zooms past a landscape subject height`() {
        // 16:9 source cropped to 9:16 keeps full height, so a subject filling half the source
        // must be zoomed until it reaches the target share.
        val engine = AutoReframeEngine(1920, 1080, ReframeAspect.VERTICAL_9_16, subjectShare = .62f, smoothing = 1f)
        val points = engine.plan(listOf(subject(0L, .3f, height = .5f)), durationUs = 10 * SECOND)
        // A single subject is held to the end of the clip so the path can animate.
        assertEquals(2, points.size)
        assertEquals(1.24f, points.first().scale, 1e-3f)
    }

    @Test
    fun `wider target crops vertically and therefore zooms further`() {
        val engine = AutoReframeEngine(1080, 1920, ReframeAspect.LANDSCAPE_16_9, subjectShare = .62f, smoothing = 1f)
        val points = engine.plan(listOf(subject(0L, .5f, height = .1f)), durationUs = 10 * SECOND)
        // cropHeight is 9/16 here, so the subject occupies far more of the visible frame.
        assertEquals(1.96f, points.first().scale, 1e-2f)
    }

    @Test
    fun `subject taller than the crop cannot be pulled back and stops at no zoom`() {
        val engine = AutoReframeEngine(1080, 1920, ReframeAspect.LANDSCAPE_16_9, subjectShare = .62f, smoothing = 1f)
        // Half the source height exceeds the visible crop, so no zoom-out can satisfy the
        // share; the camera must hold at 1.0 instead of requesting an illegal < 1 scale.
        val points = engine.plan(listOf(subject(0L, .5f, height = .5f)), durationUs = 10 * SECOND)
        assertEquals(1f, points.first().scale, 1e-4f)
    }

    @Test
    fun `camera path is smoothed and does not jitter frame to frame`() {
        val engine = AutoReframeEngine(1920, 1080, ReframeAspect.VERTICAL_9_16, smoothing = .3f, minIntervalUs = 0L)
        val samples = (0 until 12).map { subject(it * SECOND, if (it % 2 == 0) .3f else .5f) }
        val points = engine.plan(samples, durationUs = 12 * SECOND)

        assertTrue("path should not be empty", points.isNotEmpty())
        val maxJump = points.zipWithNext().maxOf { (a, b) ->
            kotlin.math.abs(a.centerX - b.centerX) + kotlin.math.abs(a.centerY - b.centerY)
        }
        // A raw follower would swing the full .2 distance on every flip; smoothing must cap it.
        assertTrue("camera jumped $maxJump", maxJump < .2f)
    }

    @Test
    fun `subject far from centre is pulled back towards it`() {
        val engine = AutoReframeEngine(1920, 1080, ReframeAspect.VERTICAL_9_16, smoothing = 1f)
        val left = engine.plan(listOf(subject(0L, .2f)), durationUs = 5 * SECOND).first()
        val right = engine.plan(listOf(subject(0L, .8f)), durationUs = 5 * SECOND).first()
        assertTrue("left subject must ask for a positive offset", left.centerX < .5f)
        assertTrue("right subject must ask for a negative offset", right.centerX > .5f)
    }

    @Test
    fun `keyframes stay inside the domain limits and preserve user animation`() {
        val engine = AutoReframeEngine(1920, 1080, ReframeAspect.VERTICAL_9_16, smoothing = 1f)
        val clip = video()
        val points = engine.plan(
            (0 until 5).map { subject(it * SECOND, .3f + it * .04f) },
            durationUs = 5 * SECOND,
        )
        val keyframes = engine.toKeyframes(clip, points)

        assertTrue(keyframes.size > 1)
        assertTrue(keyframes.zipWithNext().all { (a, b) -> a.sourceUs < b.sourceUs })
        keyframes.forEach {
            assertTrue("zoom ${it.zoom} out of range", it.zoom in .25f..4f)
            assertTrue("x ${it.x} out of range", it.x in -.5f.. .5f)
            assertTrue("y ${it.y} out of range", it.y in -.5f.. .5f)
            assertTrue(it.sourceUs <= clip.sourceUs)
        }
    }

    @Test
    fun `existing keyframes are kept rather than replaced`() {
        val engine = AutoReframeEngine(1920, 1080, ReframeAspect.VERTICAL_9_16, smoothing = 1f)
        val userKeyframe = com.termex.replay15.editor.domain.TransformKeyframe(sourceUs = 2 * SECOND, rotation = 45f)
        val clip = video().copy(keyframes = listOf(userKeyframe))
        val result = engine.toKeyframes(clip, engine.plan(listOf(subject(0L, .3f)), durationUs = 5 * SECOND))
        assertTrue(result.any { it.sourceUs == userKeyframe.sourceUs })
    }

    @Test
    fun `all requested target formats have their intended ratios`() {
        assertEquals(16f / 9f, ReframeAspect.LANDSCAPE_16_9.ratio, 1e-5f)
        assertEquals(9f / 16f, ReframeAspect.VERTICAL_9_16.ratio, 1e-5f)
        assertEquals(1f, ReframeAspect.SQUARE_1_1.ratio, 1e-5f)
        assertEquals(4f / 5f, ReframeAspect.PORTRAIT_4_5.ratio, 1e-5f)
    }

    @Test
    fun `first camera sample at zero is applied without an earlier keyframe`() {
        val clip = video()
        val engine = AutoReframeEngine(1920, 1080, ReframeAspect.VERTICAL_9_16)
        val result = engine.toKeyframes(clip, listOf(ReframePoint(0L, .3f, .5f, 1.4f)))

        assertEquals(1.4f, result.first { it.sourceUs == clip.inUs }.zoom, 1e-4f)
        assertTrue(result.first { it.sourceUs == clip.inUs }.x > 0f)
    }

    @Test
    fun `camera sample updates only camera channels on a colliding user keyframe`() {
        val userKeyframe = TransformKeyframe(sourceUs = 0L, rotation = 45f, opacity = .7f)
        val clip = video().copy(keyframes = listOf(userKeyframe))
        val engine = AutoReframeEngine(1920, 1080, ReframeAspect.VERTICAL_9_16)
        val result = engine.toKeyframes(clip, listOf(ReframePoint(0L, .3f, .5f, 1.4f)))
        val start = result.first { it.sourceUs == clip.inUs }

        assertEquals(1.4f, start.zoom, 1e-4f)
        assertEquals(45f, start.rotation, 1e-4f)
        assertEquals(.7f, start.opacity, 1e-4f)
    }

    @Test
    fun `project aspect and generated camera path serialize and undo together`() {
        val clip = video()
        val original = Project(videos = listOf(clip))
        val cameraPath = AutoReframeEngine(1920, 1080, ReframeAspect.SQUARE_1_1)
            .toKeyframes(clip, listOf(ReframePoint(0L, .35f, .5f, 1.3f)))
        val reframed = original.copy(
            aspect = ReframeAspect.SQUARE_1_1.ratio,
            videos = listOf(clip.copy(keyframes = cameraPath)),
        )
        val history = ProjectHistory(original)
        history.apply(reframed)

        val output = ByteArrayOutputStream()
        ProjectCodec.write(reframed, output)
        val restored = ProjectCodec.read(ByteArrayInputStream(output.toByteArray()))
        assertEquals(reframed.aspect, restored.aspect, 1e-5f)
        assertEquals(cameraPath, restored.videos.single().keyframes)

        assertEquals(reframed, history.current)
        assertTrue(history.undo())
        assertEquals(original, history.current)
        assertTrue(history.redo())
        assertEquals(reframed, history.current)
    }

    @Test
    fun `no subject yields no path and never touches the clip`() {
        val engine = AutoReframeEngine(1920, 1080, ReframeAspect.VERTICAL_9_16)
        val clip = video()
        assertTrue(engine.plan(emptyList(), 10 * SECOND).isEmpty())
        assertTrue(engine.toKeyframes(clip, emptyList()).isEmpty())
    }
}
