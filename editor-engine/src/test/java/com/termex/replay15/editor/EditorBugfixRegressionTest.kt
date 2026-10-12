package com.termex.replay15.editor

import com.termex.replay15.editor.backgroundremoval.MatteRefiner
import com.termex.replay15.editor.domain.BackgroundRemovalEffect
import com.termex.replay15.editor.domain.CanvasFill
import com.termex.replay15.editor.domain.CropRect
import com.termex.replay15.editor.domain.TrackingPoint
import com.termex.replay15.editor.domain.TrackingTrack
import com.termex.replay15.editor.domain.VideoFilter
import com.termex.replay15.editor.presets.PresetCatalog
import com.termex.replay15.editor.preview.engine.audio.PreviewTransportClock
import com.termex.replay15.editor.tracking.LayerGeometry
import com.termex.replay15.editor.domain.MaskState
import com.termex.replay15.editor.tracking.TrackingRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorBugfixRegressionTest {

    @Test
    fun `drained audio track no longer freezes the transport`() {
        var now = 0L
        var audio: Long? = null
        val clock = PreviewTransportClock(audioPositionProvider = { audio }, nowNs = { now })
        clock.setDuration(10_000_000L)
        clock.start()
        repeat(30) { i -> now += 16_000_000L; audio = (i + 1) * 16_000L; clock.positionUs() }
        val beforeSilence = clock.positionUs()
        // Video -> silent photo: the AudioTrack keeps PLAYING with a frozen head.
        repeat(120) { now += 16_000_000L; clock.positionUs() }
        assertTrue(clock.positionUs() > beforeSilence + 1_500_000L)
    }

    @Test
    fun `built-in presets reference real filters`() {
        PresetCatalog.all().filter { it.isBuiltIn }.forEach { preset ->
            val grade = preset.colorGrade ?: return@forEach
            assertTrue("${preset.name} filter ${grade.filterIndex}", grade.filterIndex in VideoFilter.entries.indices)
            // The look comes from the filter; repeating its values as manual adjustments doubled it.
            assertEquals(0f, grade.brightness, 0f)
            assertEquals(0f, grade.contrast, 0f)
            assertEquals(0f, grade.saturation, 0f)
        }
        assertEquals(VideoFilter.NOIR.ordinal, PresetCatalog.all().first { it.name == "Noir" }.colorGrade!!.filterIndex)
    }

    @Test
    fun `tracking selection is mapped from canvas to source`() {
        val identity = LayerGeometry(1920, 1080, CropRect(), 0, false, 1f, 0f, 0f, 0f, 1920, 1080, CanvasFill.FILL)
        val (x, y) = identity.canvasToSource(.25f, .75f)
        assertEquals(.25f, x, 1e-4f); assertEquals(.75f, y, 1e-4f)

        // Landscape clip fitted in a portrait canvas: only a band of the canvas shows the clip.
        val fitted = LayerGeometry(1920, 1080, CropRect(), 0, false, 1f, 0f, 0f, 0f, 1080, 1920, CanvasFill.FIT)
        val band = (1080f / 1920f) * (1080f / 1920f)
        val region = fitted.regionToSource(TrackingRegion(.5f, .5f, .2f, band * .5f))
        assertEquals(.5f, region.centerY, 1e-3f)
        assertEquals(.5f, region.height, 1e-2f)

        val zoomed = LayerGeometry(1920, 1080, CropRect(), 0, false, 2f, .25f, 0f, 0f, 1920, 1080, CanvasFill.FILL)
        assertEquals(.5f, zoomed.canvasToSource(.75f, .5f).first, 1e-4f)
    }

    @Test
    fun `tracked points already live in mask space and legacy points are flipped`() {
        val track = TrackingTrack("track-1", listOf(TrackingPoint(0L, .4f, .2f, .1f, .1f, 1f)))
        val mask = LayerGeometry.toMaskSpace(track).points.single()
        assertEquals(.4f, mask.centerX, 1e-6f)
        assertEquals(.2f, mask.centerY, 1e-6f)
        assertEquals(.8f, LayerGeometry.fromLegacyMaskSpace(mask).centerY, 1e-6f)
    }

    @Test
    fun `tracking selection goes through the crop to the full frame`() {
        // Right half of the frame, bottom half: the quad shows only that region.
        val cropped = LayerGeometry(1920, 1080, CropRect(.5f, .5f, 1f, 1f), 0, false, 1f, 0f, 0f, 0f, 1920, 1080, CanvasFill.FILL)
        val (x, y) = cropped.canvasToSource(.5f, .5f)
        assertEquals(.75f, x, 1e-4f); assertEquals(.75f, y, 1e-4f)
        val (left, top) = cropped.canvasToSource(0f, 0f)
        assertEquals(.5f, left, 1e-4f); assertEquals(.5f, top, 1e-4f)
    }

    @Test
    fun `a linked track moves the mask relative to where it was placed`() {
        val track = TrackingTrack("track-1", listOf(
            TrackingPoint(0L, .3f, .3f, .1f, .1f, 1f),
            TrackingPoint(1_000_000L, .5f, .4f, .2f, .1f, 1f),
        ))
        val mask = MaskState.default().copy(centerX = .35f, centerY = .3f, width = .4f, height = .2f, trackingTrackId = track.id)
        val start = mask.at(0L, track)
        assertEquals(.35f, start.centerX, 1e-5f); assertEquals(.4f, start.width, 1e-5f)
        val later = mask.at(1_000_000L, track)
        assertEquals(.55f, later.centerX, 1e-5f)
        assertEquals(.4f, later.centerY, 1e-5f)
        assertEquals(.8f, later.width, 1e-5f)
        assertEquals(.2f, later.height, 1e-5f)
    }

    @Test
    fun `matte edge snaps to the colour boundary`() {
        val width = 64; val height = 8
        val pixels = IntArray(width * height) { if (it % width < 32) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }
        // Coarse mask whose edge is misplaced by one eighth of the width.
        val mask = FloatArray(8) { if (it < 5) 1f else 0f }
        MatteRefiner.apply(pixels, width, height, mask, 8, 1, BackgroundRemovalEffect(enabled = true, edgeSmoothing = 0f))
        fun alpha(x: Int) = pixels[4 * width + x] ushr 24
        assertTrue(alpha(10) > 240)
        assertTrue(alpha(60) < 15)
        assertTrue("alpha=${alpha(36)}", alpha(36) < 128)
    }
}
