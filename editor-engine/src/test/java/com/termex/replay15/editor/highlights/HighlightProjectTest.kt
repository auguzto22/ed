package com.termex.replay15.editor.highlights

import com.termex.replay15.editor.autoedit.MotionSample
import com.termex.replay15.editor.domain.CanvasFill
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.SECOND
import com.termex.replay15.editor.domain.SubtitleWordCue
import com.termex.replay15.editor.domain.TextClip
import com.termex.replay15.editor.domain.VideoClip
import com.termex.replay15.editor.reframe.AutoReframeEngine
import com.termex.replay15.editor.reframe.ReframeAspect
import com.termex.replay15.editor.reframe.ReframePoint
import com.termex.replay15.editor.reframe.ReframeSubject
import org.junit.Assert.*
import org.junit.Test

/** Fases 2 e 3 da integração do ClipStudio: trocas de cena, câmera com salto e momento → projeto. */
class HighlightProjectTest {

    private fun video(seconds: Int = 10, uri: String = "content://video/1") = VideoClip(
        uri = uri, name = "Video.mp4", sourceUs = seconds * SECOND, width = 1920, height = 1080,
    )

    private fun motion(vararg amounts: Float) = amounts.mapIndexed { i, a -> MotionSample(i * 500_000L, a) }

    // ---------- trocas de cena ----------

    @Test
    fun `isolated spike in a still shot is a scene cut at the midpoint`() {
        val cuts = SceneCutDetector.detect(motion(.02f, .03f, .02f, .02f, .9f, .03f, .02f, .02f))
        assertEquals(listOf(1_750_000L), cuts) // entre a amostra de 1,5 s e a de 2 s
    }

    @Test
    fun `continuous motion is not a scene cut`() {
        assertTrue(SceneCutDetector.detect(motion(.5f, .6f, .55f, .7f, .6f, .65f, .5f)).isEmpty())
    }

    @Test
    fun `two spikes closer than the minimum spacing count once`() {
        val cuts = SceneCutDetector.detect(motion(.02f, .02f, .02f, .9f, .9f, .02f, .02f, .02f, .02f))
        assertEquals(1, cuts.size)
    }

    // ---------- câmera com salto na troca de cena ----------

    @Test
    fun `camera holds until the cut and then jumps to the new subject`() {
        val engine = AutoReframeEngine(1920, 1080, ReframeAspect.VERTICAL_9_16, smoothing = .35f, minIntervalUs = 0L)
        val samples = (0..3).map { ReframeSubject(it * SECOND, .3f, .5f, .3f, .5f, .9f) } +
            (4..6).map { ReframeSubject(it * SECOND, .7f, .5f, .3f, .5f, .9f) }
        val cut = 3_500_000L
        val points = engine.plan(samples, durationUs = 7 * SECOND, sceneCutsUs = listOf(cut))

        val atCut = points.single { it.timeUs == cut }
        assertEquals(.7f, atCut.centerX, 1e-4f) // salta direto, sem deslizar pelo meio
        val hold = points.last { it.timeUs < cut }
        assertTrue(hold.timeUs >= cut - 40_000L)
        assertEquals(.3f, hold.centerX, 1e-3f) // a cena anterior fica enquadrada até o corte
        assertTrue(points.zipWithNext().all { (a, b) -> a.timeUs < b.timeUs })
    }

    @Test
    fun `without cuts the path is unchanged`() {
        val engine = AutoReframeEngine(1920, 1080, ReframeAspect.VERTICAL_9_16, minIntervalUs = 0L)
        val samples = (0..5).map { ReframeSubject(it * SECOND, .3f + it * .05f, .5f, .3f, .5f, .9f) }
        assertEquals(engine.plan(samples, 6 * SECOND), engine.plan(samples, 6 * SECOND, emptyList()))
    }

    @Test
    fun `camera does not breathe back to the full frame between points`() {
        val clip = video()
        val engine = AutoReframeEngine(1920, 1080, ReframeAspect.VERTICAL_9_16)
        val keyframes = engine.toKeyframes(clip, listOf(
            ReframePoint(0L, .3f, .5f, 1.4f),
            ReframePoint(2 * SECOND, .35f, .5f, 1.4f),
            ReframePoint(4 * SECOND, .4f, .5f, 1.4f),
        ))
        // Antes, um lead-in com o transform base (zoom 1) era inserido 180 ms antes de cada ponto.
        assertTrue(keyframes.all { kotlin.math.abs(it.zoom - 1.4f) < 1e-3f })
    }

    // ---------- momento → projeto 9:16 ----------

    private fun twoClipProject(texts: List<TextClip> = emptyList()) = Project(
        name = "Live",
        videos = listOf(video(10, "content://video/a"), video(10, "content://video/b")),
        texts = texts,
    )

    @Test
    fun `moment across two clips keeps only the trimmed range`() {
        val source = twoClipProject()
        val result = HighlightProjectBuilder.build(source, 5 * SECOND, 15 * SECOND, "Segredo", nowMs = 1_000L)
        val p = result.project

        assertEquals(2, p.videos.size)
        assertEquals("content://video/a", p.videos[0].uri)
        assertEquals(5 * SECOND, p.videos[0].inUs)
        assertEquals(10 * SECOND, p.videos[0].outUs)
        assertEquals("content://video/b", p.videos[1].uri)
        assertEquals(0L, p.videos[1].inUs)
        assertEquals(5 * SECOND, p.videos[1].outUs)
        assertEquals(10 * SECOND, p.durationUs)
        assertEquals(5 * SECOND, result.sourceStartUs)
        assertEquals(15 * SECOND, result.sourceEndUs)
    }

    @Test
    fun `moment is a new vertical project and the source is untouched`() {
        val source = twoClipProject()
        val p = HighlightProjectBuilder.build(source, 2 * SECOND, 8 * SECOND, "  ", nowMs = 1_000L).project

        assertNotEquals(source.id, p.id)
        assertEquals("Melhor momento", p.name)
        assertEquals(ReframeAspect.VERTICAL_9_16.ratio, p.aspect, 1e-5f)
        assertEquals(CanvasFill.FILL, p.canvasFill)
        assertEquals(1, p.videos.size)
        assertEquals(6 * SECOND, p.durationUs)
        assertEquals(2, source.videos.size)
        assertEquals(0L, source.videos[0].inUs)
    }

    @Test
    fun `captions inside the moment move to the new start and are clipped at the edges`() {
        val inside = TextClip(text = "olá mundo", startUs = 6 * SECOND, endUs = 8 * SECOND, isCaption = true,
            wordCues = listOf(SubtitleWordCue("olá", 6 * SECOND, 7 * SECOND), SubtitleWordCue("mundo", 7 * SECOND, 8 * SECOND)))
        val crossing = TextClip(text = "atravessa", startUs = 14 * SECOND, endUs = 17 * SECOND, isCaption = true)
        val outside = TextClip(text = "fora", startUs = 18 * SECOND, endUs = 19 * SECOND, isCaption = true)
        val p = HighlightProjectBuilder.build(twoClipProject(listOf(inside, crossing, outside)),
            5 * SECOND, 15 * SECOND, "x", nowMs = 1L).project

        assertEquals(listOf("olá mundo", "atravessa"), p.texts.map { it.text })
        val moved = p.texts[0]
        assertEquals(1 * SECOND, moved.startUs)
        assertEquals(3 * SECOND, moved.endUs)
        assertEquals(listOf(1 * SECOND, 2 * SECOND), moved.wordCues.map { it.startUs })
        val clipped = p.texts[1]
        assertEquals(9 * SECOND, clipped.startUs)
        assertEquals(10 * SECOND, clipped.endUs)
        assertTrue(p.texts.all { it.isCaption })
    }

    @Test
    fun `layers that depend on the old timeline are left out`() {
        val source = twoClipProject().copy(markers = emptyList())
        val p = HighlightProjectBuilder.build(source, 0L, 4 * SECOND, "x", nowMs = 1L).project
        assertTrue(p.audio.isEmpty() && p.stickers.isEmpty() && p.videoTracks.isEmpty() && p.compounds.isEmpty())
    }
}
