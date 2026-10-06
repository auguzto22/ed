package com.termex.replay15.editor.color

import com.termex.replay15.editor.domain.StudioGrade
import com.termex.replay15.editor.domain.VideoClip
import org.junit.Assert.*
import org.junit.Test

class ColorMatchEngineTest {

    @Test
    fun `matching warmer reference to cooler target adjusts temperature positively`() {
        // Reference is cool (B > R): meanR=0.3, meanB=0.6 -> B - R = 0.3
        val coolRef = FrameColorStats(
            meanLuma = 0.45f,
            p5Luma = 0.05f,
            p95Luma = 0.85f,
            meanR = 0.30f,
            meanG = 0.45f,
            meanB = 0.60f,
            meanSaturation = 0.25f,
        )

        // Target is warm (R > B): meanR=0.6, meanB=0.3 -> B - R = -0.3
        val warmTarget = FrameColorStats(
            meanLuma = 0.45f,
            p5Luma = 0.05f,
            p95Luma = 0.85f,
            meanR = 0.60f,
            meanG = 0.45f,
            meanB = 0.30f,
            meanSaturation = 0.25f,
        )

        val result = ColorMatchEngine.match(coolRef, warmTarget)

        // Target should be cooled down (temperatureOffset > 0 in direction of B-R differential)
        assertTrue("Should adjust temperature towards cool reference", result.temperatureOffset > 0f)
    }

    @Test
    fun `matching brighter reference to darker target boosts exposure`() {
        val brightRef = FrameColorStats(
            meanLuma = 0.65f,
            p5Luma = 0.15f,
            p95Luma = 0.95f,
            meanR = 0.65f,
            meanG = 0.65f,
            meanB = 0.65f,
            meanSaturation = 0.20f,
        )

        val darkTarget = FrameColorStats(
            meanLuma = 0.30f,
            p5Luma = 0.02f,
            p95Luma = 0.65f,
            meanR = 0.30f,
            meanG = 0.30f,
            meanB = 0.30f,
            meanSaturation = 0.20f,
        )

        val result = ColorMatchEngine.match(brightRef, darkTarget)

        assertTrue("Should increase exposure", result.exposureOffset > 0f)
        assertTrue("Should increase brightness", result.brightnessOffset > 0f)
        assertTrue("Should lift shadows", result.shadowsOffset > 0f)
    }

    @Test
    fun `matching identical stats produces zero delta`() {
        val stats = FrameColorStats(
            meanLuma = 0.45f,
            p5Luma = 0.08f,
            p95Luma = 0.85f,
            meanR = 0.45f,
            meanG = 0.45f,
            meanB = 0.45f,
            meanSaturation = 0.28f,
        )

        val result = ColorMatchEngine.match(stats, stats)

        assertEquals(0f, result.exposureOffset, 0.001f)
        assertEquals(0f, result.brightnessOffset, 0.001f)
        assertEquals(0f, result.contrastOffset, 0.001f)
        assertEquals(0f, result.temperatureOffset, 0.001f)
        assertEquals(0f, result.saturationOffset, 0.001f)
    }

    @Test
    fun `applying match result with 50 percent strength scales offsets by half`() {
        val targetClip = VideoClip(
            id = "target",
            uri = "content://target",
            name = "Target",
            sourceUs = 5_000_000L,
            width = 1920,
            height = 1080,
            brightness = 0f,
            contrast = 0f,
            saturation = 0f,
            temperature = 0f,
            grade = StudioGrade(exposure = 0f),
        )

        val result = ColorMatchResult(
            brightnessOffset = 0.2f,
            contrastOffset = 0.1f,
            saturationOffset = 20f,
            temperatureOffset = -0.1f,
            exposureOffset = 0.4f,
        )

        val applied = result.applyTo(targetClip, strength = 0.5f)

        assertEquals(0.1f, applied.brightness, 0.001f)
        assertEquals(0.05f, applied.contrast, 0.001f)
        assertEquals(10f, applied.saturation, 0.001f)
        assertEquals(-0.05f, applied.temperature, 0.001f)
        assertEquals(0.2f, applied.grade.exposure, 0.001f)
    }
}

