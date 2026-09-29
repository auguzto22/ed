package com.termex.replay15.editor

import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.media.CubeLut
import com.termex.replay15.editor.media.SubtitleDocument
import com.termex.replay15.editor.project.ProjectCodec
import java.io.*
import org.junit.Assert.*
import org.junit.Test

class StudioEditingTest {

    @Test fun neutralClipAvoidsCustomGpuPipeline() {
        val clip = clip()
        assertFalse(clip.needsStudioEffect())
        assertTrue(clip.copy(opacity = .8f).needsStudioEffect())
        assertTrue(clip.copy(grade = StudioGrade(exposure = .2f)).needsStudioEffect())
        assertTrue(clip.copy(keyframes = listOf(TransformKeyframe(0, opacity = .7f))).needsStudioEffect())
    }
    private fun clip() = VideoClip(uri = "file:///video.mp4", name = "Video", sourceUs = 10 * SECOND, width = 1920, height = 1080)
    @Test fun interpolationRespectsSourceTimeAndEasing() {
        val clip = clip().copy(keyframes = listOf(TransformKeyframe(SECOND, x = -.5f, easing = Easing.LINEAR), TransformKeyframe(5 * SECOND, zoom = 3f, x = .5f)))
        assertEquals(2f, clip.transformAt(3 * SECOND).zoom, .0001f)
        assertEquals(0f, clip.transformAt(3 * SECOND).x, .0001f)
        assertEquals(1f, clip.transformAt(0).zoom, .0001f)
        assertEquals(3f, clip.transformAt(9 * SECOND).zoom, .0001f)
        assertEquals(.25f, Easing.EASE_IN.apply(.5f), .0001f)
        assertEquals(.75f, Easing.EASE_OUT.apply(.5f), .0001f)
        assertEquals(0f, Easing.HOLD.apply(.9f), .0001f)
    }
    @Test fun splitAndTrimKeepAnimatedSourceCoordinates() {
        val clip = clip().copy(inUs = SECOND, speed = 2f, keyframes = listOf(TransformKeyframe(0), TransformKeyframe(10 * SECOND, zoom = 3f)))
        val original = Project(videos = listOf(clip))
        val split = original.split(0, 2 * SECOND)
        assertEquals(5 * SECOND, split.videos[1].inUs)
        assertEquals(clip.transformAt(5 * SECOND), split.videos[1].transformAt(5 * SECOND))
        assertEquals(original.durationUs, split.durationUs)
    }
    @Test fun duplicateOrUnorderedKeyframesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { clip().copy(keyframes = listOf(TransformKeyframe(SECOND), TransformKeyframe(SECOND))) }
        assertThrows(IllegalArgumentException::class.java) { clip().copy(keyframes = listOf(TransformKeyframe(11 * SECOND))) }
    }
    @Test fun allStudioFieldsSurviveSaveAndReload() {
        val grade = StudioGrade(.5f, .2f, -.3f, .6f, .2f, .4f, listOf(.02f, .2f, .55f, .8f, .98f),
            MaskShape.CIRCLE, .65f, .03f, true, true, 0xFF113355.toInt(), .4f, "/some/look.cube", .7f)
        val project = Project(videos = listOf(clip().copy(grade = grade, keyframes = listOf(TransformKeyframe(SECOND, 2f, -.2f, .3f, -25f, .7f, Easing.EASE_OUT)))),
            markers = listOf(TimelineMarker(timeUs = 2 * SECOND, name = "Beat")), canvasFill = CanvasFill.FILL, backgroundColor = 0xFF120923.toInt(),
            texts = listOf(TextClip(text = "Studio", startUs = 0, endUs = SECOND, fontId = TextFont.PLAYFAIR.id)))
        val stream = ByteArrayOutputStream(); ProjectCodec.write(project, stream)
        assertEquals(project, ProjectCodec.read(stream.toByteArray().inputStream()))
    }
    @Test fun versionFiveProjectStillLoadsWithNeutralDefaults() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { o ->
            o.writeInt(0x52313545); o.writeInt(5); o.writeUTF("legacy"); o.writeUTF("Legacy project"); o.writeFloat(0f)
            o.writeInt(720); o.writeInt(30); o.writeInt(8_000_000)
            repeat(4) { o.writeInt(0) }; o.writeInt(192_000)
        }
        val project = ProjectCodec.read(bytes.toByteArray().inputStream())
        assertEquals("Legacy project", project.name); assertEquals(CanvasFill.FIT, project.canvasFill)
        assertEquals(0xFF000000.toInt(), project.backgroundColor); assertTrue(project.markers.isEmpty())
    }
    @Test fun neutralCurvePreservesEverySample() {
        repeat(101) { assertEquals(it / 100f, StudioGrade().curveAt(it / 100f), .00001f) }
        assertThrows(IllegalArgumentException::class.java) { StudioGrade(curve = listOf(0f, 1f)) }
    }
    private fun identityCube() = "TITLE \"Identity\"\nLUT_3D_SIZE 2\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 1 1 1\n" +
        (0..1).flatMap { b -> (0..1).flatMap { g -> (0..1).map { r -> "$r $g $b" } } }.joinToString("\n")
    @Test fun cubeOrderingMatchesMedia3Hald() {
        val lut = CubeLut.parse(identityCube().reader()); val pixels = lut.pixels(1f)
        assertEquals(0xFFFF0000.toInt(), pixels[4]); assertEquals(0xFF0000FF.toInt(), pixels[1])
        assertEquals(0xFF00FF00.toInt(), pixels[2]); assertEquals(0xFFFFFFFF.toInt(), pixels[7])
        assertArrayEquals(lut.pixels(0f), pixels)
    }
    @Test fun incompleteOrUnsupportedCubeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { CubeLut.parse("LUT_3D_SIZE 2\n0 0 0".reader()) }
        assertThrows(IllegalArgumentException::class.java) { CubeLut.parse(identityCube().replace("DOMAIN_MAX 1 1 1", "DOMAIN_MAX 2 2 2").reader()) }
        assertThrows(IllegalArgumentException::class.java) { CubeLut.parse(identityCube().replace("0 0 0\n1", "NaN 0 0\n1").reader()) }
    }
    @Test fun subtitlesAreNotSilentlyTruncatedAtThirtyTwo() {
        val srt = (0..49).joinToString("\n\n") { "$it\n00:00:00,000 --> 00:00:01,000\nCaption $it" }
        assertEquals(50, SubtitleDocument.parse(srt, 5 * SECOND).size)
        assertThrows(IllegalArgumentException::class.java) { SubtitleDocument.parse(srt, 5 * SECOND, 32) }
    }
    @Test fun subtitlesRoundTripWithMarkupAndDurationClamping() {
        val subtitles = SubtitleDocument.parse("1\r\n00:00:00,500 --> 00:00:03,000\r\n<b>Hello</b> &amp; friends", 2 * SECOND)
        assertEquals("Hello & friends", subtitles.single().text); assertEquals(2 * SECOND, subtitles.single().endUs)
        val exported = SubtitleDocument.write(subtitles, 2 * SECOND)
        val reimported = SubtitleDocument.parse(exported, 2 * SECOND)
        assertEquals(subtitles.single().text, reimported.single().text)
        assertEquals(subtitles.single().startUs, reimported.single().startUs)
    }
}
