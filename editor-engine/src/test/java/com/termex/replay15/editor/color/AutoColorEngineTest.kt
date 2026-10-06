package com.termex.replay15.editor.color

import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.StudioGrade
import com.termex.replay15.editor.domain.VideoClip
import com.termex.replay15.editor.history.ProjectHistory
import com.termex.replay15.editor.project.ProjectCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoColorEngineTest {

    @Test
    fun `neutral statistics produce no correction`() {
        val adjustment = AutoColorEngine.calculate(
            FrameColorStats(
                meanLuma = .46f,
                p5Luma = .1f,
                p95Luma = .85f,
                meanR = .46f,
                meanG = .46f,
                meanB = .46f,
                meanSaturation = .32f,
            ),
        )

        assertEquals(0f, adjustment.brightness, .001f)
        assertEquals(0f, adjustment.contrast, .001f)
        assertEquals(0f, adjustment.saturation, .001f)
        assertEquals(0f, adjustment.temperature, .001f)
        assertEquals(0f, adjustment.exposure, .001f)
        assertEquals(0f, adjustment.shadows, .001f)
        assertEquals(0f, adjustment.highlights, .001f)
    }

    @Test
    fun `underexposed low contrast frame gets exposure and shadow lift`() {
        val adjustment = AutoColorEngine.calculate(
            FrameColorStats(.25f, .02f, .60f, .25f, .25f, .25f, .1f),
        )

        assertTrue(adjustment.brightness > 0f)
        assertTrue(adjustment.exposure > 0f)
        assertTrue(adjustment.contrast > 0f)
        assertTrue(adjustment.shadows > 0f)
        assertTrue(adjustment.highlights > 0f)
        assertTrue(adjustment.saturation > 0f)
    }

    @Test
    fun `raised blacks and clipped whites receive negative tonal corrections`() {
        val adjustment = AutoColorEngine.calculate(
            FrameColorStats(.70f, .40f, .99f, .70f, .70f, .70f, .32f),
        )

        assertTrue("raised blacks should lower shadows", adjustment.shadows < 0f)
        assertTrue("clipped whites should lower highlights", adjustment.highlights < 0f)
        assertTrue(adjustment.exposure < 0f)
    }

    @Test
    fun `white balance correction warms a blue cast`() {
        val adjustment = AutoColorEngine.calculate(
            FrameColorStats(.46f, .1f, .85f, .35f, .46f, .62f, .32f),
        )

        assertTrue(adjustment.temperature > 0f)
    }

    @Test
    fun `applying corrections updates existing clip and grade fields while preserving other grade settings`() {
        val clip = VideoClip(
            uri = "content://video/auto-color",
            name = "Color test",
            sourceUs = 3_000_000L,
            width = 1920,
            height = 1080,
            brightness = .1f,
            contrast = .1f,
            saturation = 10f,
            temperature = .1f,
            grade = StudioGrade(vignette = .3f, lutPath = "look.cube"),
        )
        val adjustment = AutoColorAdjustment(
            brightness = .2f,
            contrast = .1f,
            saturation = 8f,
            temperature = -.1f,
            exposure = .5f,
            shadows = .4f,
            highlights = -.3f,
        )

        val corrected = adjustment.applyTo(clip)

        assertEquals(.3f, corrected.brightness, .001f)
        assertEquals(.2f, corrected.contrast, .001f)
        assertEquals(18f, corrected.saturation, .001f)
        assertEquals(0f, corrected.temperature, .001f)
        assertEquals(.5f, corrected.grade.exposure, .001f)
        assertEquals(.4f, corrected.grade.shadows, .001f)
        assertEquals(-.3f, corrected.grade.highlights, .001f)
        assertEquals(.3f, corrected.grade.vignette, .001f)
        assertEquals("look.cube", corrected.grade.lutPath)
    }

    @Test
    fun `auto color values serialize and undo as one project edit`() {
        val clip = VideoClip(
            uri = "content://video/auto-color",
            name = "Color test",
            sourceUs = 3_000_000L,
            width = 1920,
            height = 1080,
        )
        val project = Project(videos = listOf(clip))
        val correctedClip = AutoColorAdjustment(
            brightness = .12f,
            contrast = .08f,
            saturation = 12f,
            temperature = -.1f,
            exposure = .3f,
            shadows = .2f,
            highlights = -.15f,
        ).applyTo(clip)
        val corrected = project.copy(videos = listOf(correctedClip))
        val history = ProjectHistory(project)
        history.apply(corrected)

        val bytes = ByteArrayOutputStream().also { ProjectCodec.write(corrected, it) }.toByteArray()
        val restored = ProjectCodec.read(ByteArrayInputStream(bytes)).videos.single()
        assertEquals(correctedClip.brightness, restored.brightness, .001f)
        assertEquals(correctedClip.temperature, restored.temperature, .001f)
        assertEquals(correctedClip.grade.exposure, restored.grade.exposure, .001f)
        assertEquals(correctedClip.grade.shadows, restored.grade.shadows, .001f)
        assertEquals(correctedClip.grade.highlights, restored.grade.highlights, .001f)

        assertTrue(history.undo())
        assertEquals(project, history.current)
        assertTrue(history.redo())
        assertEquals(corrected, history.current)
    }
}
