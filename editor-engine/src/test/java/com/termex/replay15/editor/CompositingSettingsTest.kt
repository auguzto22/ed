package com.termex.replay15.editor

import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.project.ProjectCodec
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class CompositingSettingsTest {
    @Test fun advancedMasksHslAndChannelCurvesRoundTripOnBothLayers() {
        val grade = StudioGrade(mask = MaskShape.DIAMOND, maskX = .2f, maskY = -.1f, maskRotation = 35f,
            maskAspect = 2f, maskOpacity = .8f, chromaEnabled = true, chromaSmoothness = .13f, chromaSpill = .7f, chromaEdge = -.1f,
            hsl = List(8) { HslAdjustment(it * 10f, -.2f, .1f) },
            channelCurves = listOf(listOf(.1f, .3f, .5f, .7f, .9f), StudioGrade().curve, StudioGrade().curve))
        val clip = VideoClip(uri = "file:///video", name = "Video", sourceUs = SECOND, width = 720, height = 1280, grade = grade)
        val p = Project(videos = listOf(clip), videoTracks = listOf(VideoTrack(clips = listOf(TimedVideoClip(0, clip.copy(id = newId()))))))
        val output = ByteArrayOutputStream(); ProjectCodec.write(p, output)
        assertEquals(p, ProjectCodec.read(output.toByteArray().inputStream()))
        assertEquals(3, MaskShape.CINEMA.ordinal)
    }
    @Test fun invalidGradeInputsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { StudioGrade(maskAspect = 0f) }
        assertThrows(IllegalArgumentException::class.java) { StudioGrade(chromaSmoothness = 0f) }
        assertThrows(IllegalArgumentException::class.java) { StudioGrade(hsl = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { StudioGrade(channelCurves = List(3) { listOf(0f, 1f) }) }
        assertThrows(IllegalArgumentException::class.java) { HslAdjustment(hue = Float.NaN) }
    }
}
