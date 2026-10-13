package com.termex.replay15.editor

import com.termex.replay15.editor.autoedit.*
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.reference.ReferenceSlots
import org.junit.Assert.*
import org.junit.Test

class PunchInZoomTest {
    /** "ola pessoal. hoje eu vou mostrar ..." — a phrase every ~1.5 s for 12 s. */
    private fun talkingWords(): List<TimedWord> = (0 until 8).flatMap { phrase ->
        val start = 300_000L + phrase * 1_500_000L
        listOf(
            TimedWord("frase", start, start + 300_000L),
            TimedWord("numero", start + 320_000L, start + 700_000L),
            TimedWord("$phrase.", start + 720_000L, start + 1_000_000L),
        )
    }

    @Test fun phraseStartsFollowPausesAndPunctuation() {
        val starts = PunchInPlanner.phraseStarts(talkingWords())
        assertEquals((0 until 8).map { 300_000L + it * 1_500_000L }, starts)
    }

    @Test fun podcastZoomsArePunchInsHeldBetweenPhrasesAndAimedAtTheFace() {
        val faces = (0..12).map { FaceSample(it * SECOND, .62f, .34f, .3f) }
        val analysis = ClipAnalysis("clip", 12 * SECOND, words = talkingWords(), faces = faces)
        val plan = AutoEditPlanner.plan(analysis, AutoEditOptions(style = AutoEditStyle.PODCAST, captions = false,
            cleanPauses = false, autoReframe = false, audioEnhancement = false))
        assertTrue("no punch-ins: ${plan.zooms}", plan.zooms.size >= 2)
        assertTrue(plan.zooms.all { it.hold && it.scale >= 1.1f && it.durationUs >= 700_000L })
        assertTrue(plan.zooms.all { it.focusX == .62f && it.focusY == .34f })
        // Alternating: back to the normal framing before the next punch-in.
        assertTrue(plan.zooms.zipWithNext().all { (a, b) -> a.timeUs + a.durationUs < b.timeUs })
        AutoEditPlanner.validate(plan)
    }

    @Test fun punchInKeyframesCutInHoldAndCutBack() {
        val source = VideoClip(uri = "file:///clip.mp4", name = "clip", sourceUs = 10 * SECOND, width = 1080, height = 1920)
        val project = Project(videos = listOf(source), export = ExportSettings.forSource(source))
        val plan = AutoEditPlan(source.id, source.durationUs, AutoEditOptions(autoReframe = false),
            zooms = listOf(ZoomDecision(2 * SECOND, 3 * SECOND, 1.2f, .9f, "frase", hold = true, focusX = .5f, focusY = .3f)))
        val clip = AutoEditExecutor.execute(project, 0, plan).videos.single()
        assertEquals(1f, clip.transformAt(1_900_000L).zoom, 1e-4f)
        // Jumps at the phrase start, no easing in.
        assertEquals(1.2f, clip.transformAt(2 * SECOND).zoom, 1e-4f)
        assertEquals(1.2f, clip.transformAt(4_900_000L).zoom, 1e-4f)
        assertEquals(1f, clip.transformAt(5 * SECOND).zoom, 1e-4f)
        // Face in the upper third: the frame shifts down to keep it in place, never past the edge.
        val shifted = clip.transformAt(3 * SECOND)
        assertEquals(.04f, shifted.y, 1e-4f)
        assertTrue(kotlin.math.abs(shifted.y) <= (1.2f - 1f) / 2f)
    }

    @Test fun punchInSurvivesAPauseCutInsideIt() {
        val source = VideoClip(uri = "file:///clip.mp4", name = "clip", sourceUs = 10 * SECOND, width = 1080, height = 1920)
        val project = Project(videos = listOf(source), export = ExportSettings.forSource(source))
        val plan = AutoEditPlan(source.id, source.durationUs, AutoEditOptions(autoReframe = false),
            cuts = listOf(CutDecision(3 * SECOND, 3_500_000L, .9f, "silencio")),
            zooms = listOf(ZoomDecision(2 * SECOND, 3 * SECOND, 1.2f, .9f, "frase", hold = true)))
        val pieces = AutoEditExecutor.execute(project, 0, plan).videos
        assertEquals(2, pieces.size)
        // The piece after the removed pause starts still zoomed in.
        assertEquals(1.2f, pieces[1].transformAt(pieces[1].inUs).zoom, 1e-4f)
    }

    @Test fun unfilledReferenceSlotsAreNotCaptionsAndAreLeftOutOfTheExport() {
        val card = TextClip(text = "TEXTO DO CARD", startUs = 0, endUs = 2 * SECOND, y = .9f)
        val written = TextClip(text = "Meu titulo", startUs = 0, endUs = 2 * SECOND, y = .1f)
        val slot = StickerClip(uri = "file:///data/files/reference-placeholders/slot_56.png", name = "Espaco: imagem", startUs = 0, endUs = SECOND)
        val photo = StickerClip(uri = "file:///data/files/photo.png", name = "foto", startUs = 0, endUs = SECOND)
        assertFalse(ClipAnalyzer.isCaptionOf(card, 0, 10 * SECOND))
        val project = Project(texts = listOf(card, written), stickers = listOf(slot, photo))
        assertEquals(2, ReferenceSlots.unfilledCount(project))
        val export = ReferenceSlots.withoutUnfilled(project)
        assertEquals(listOf(written), export.texts)
        assertEquals(listOf(photo), export.stickers)
    }
}
