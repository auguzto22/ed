package com.termex.replay15.editor

import com.termex.replay15.editor.autoedit.*
import com.termex.replay15.editor.domain.*
import org.junit.Assert.*
import org.junit.Test

class AutoEditBugfixTest {
    private fun clip(width: Int = 1920, height: Int = 1080) =
        VideoClip(uri = "file:///clip.mp4", name = "clip", sourceUs = 10 * SECOND, width = width, height = height)

    /** A caption as the caption engine makes it: flagged, styled, with measured word timing. */
    private fun generatedCaption(start: Long, words: List<Pair<String, LongRange>>) = TextClip(
        text = words.joinToString(" ") { it.first }, startUs = start, endUs = words.last().second.last,
        y = .7f, size = .05f, color = 0xFFFFD400.toInt(), fontId = TextFont.BEBAS.id, isCaption = true,
        animation = TextAnimation.KARAOKE, wordStyle = SubtitleWordStyle(activeColor = 0xFFFF0000.toInt()),
        wordCues = words.map { (text, range) -> SubtitleWordCue(text, range.first, range.last) },
    )

    @Test fun measuredWordTimingIsUsedInsteadOfGuessingByLetters() {
        val caption = generatedCaption(1_000_000, listOf("oi" to 1_000_000L..1_100_000L, "pessoal" to 1_700_000L..2_000_000L))
        val words = ClipAnalyzer.wordsFromCaptions(listOf(caption), 0, 10 * SECOND)
        assertEquals(listOf(1_000_000L, 1_700_000L), words.map { it.startUs })
        assertEquals(listOf(1_100_000L, 2_000_000L), words.map { it.endUs })
    }

    @Test fun generatedCaptionsKeepStyleAndWordTimingThroughACut() {
        val source = clip()
        val caption = generatedCaption(1_500_000, listOf(
            "antes" to 1_500_000L..1_900_000L, "do" to 2_000_000L..2_200_000L, "corte" to 3_200_000L..3_600_000L,
        ))
        val project = Project(videos = listOf(source), texts = listOf(caption))
        val plan = AutoEditPlan(source.id, source.durationUs, AutoEditOptions(autoReframe = false),
            cuts = listOf(CutDecision(2_400_000, 3_000_000, .9f, "silencio")),
            captions = listOf(CaptionDecision(1_500_000, 3_600_000, "antes do corte")))
        // Used to throw: the cut moved the caption but left its word cues behind.
        val edited = AutoEditExecutor.execute(project, 0, plan)
        val kept = edited.texts.single()
        assertEquals(caption.id, kept.id)
        assertEquals(TextAnimation.KARAOKE, kept.animation)
        assertEquals(TextFont.BEBAS.id, kept.fontId)
        assertEquals(caption.wordStyle, kept.wordStyle)
        assertEquals(3, kept.wordCues.size)
        assertEquals(3_600_000L - 600_000L, kept.endUs)
        assertEquals(2_600_000L, kept.wordCues.last().startUs)
        assertTrue(kept.wordCues.all { it.startUs >= kept.startUs && it.endUs <= kept.endUs })
    }

    @Test fun untimedCaptionsAreResplitWithTheirOwnLook() {
        val source = clip()
        val imported = TextClip(text = "uma frase longa importada de um srt", startUs = 0, endUs = 3 * SECOND,
            y = .82f, color = 0xFF00FF00.toInt(), fontId = TextFont.CAVEAT.id)
        val project = Project(videos = listOf(source), texts = listOf(imported))
        val plan = AutoEditPlan(source.id, source.durationUs, AutoEditOptions(autoReframe = false),
            captions = listOf(CaptionDecision(0, 1_400_000, "uma frase longa"), CaptionDecision(1_500_000, 3 * SECOND, "importada de um srt")))
        val edited = AutoEditExecutor.execute(project, 0, plan)
        assertEquals(listOf("uma frase longa", "importada de um srt"), edited.texts.map { it.text })
        assertTrue(edited.texts.all { it.fontId == TextFont.CAVEAT.id && it.color == 0xFF00FF00.toInt() && it.y == .82f && it.isCaption })
    }

    @Test fun maskedStickerSurvivesACutThatShortensIt() {
        val source = clip()
        val mask = MaskState(MaskType.entries.first(), .5f, .5f, .5f, .5f, 0f, 0f, 0f, 1f, false,
            keyframes = listOf(MaskKeyframe(0, .5f, .5f, .5f, .5f, 0f, 0f, 0f), MaskKeyframe(2_500_000, .4f, .5f, .5f, .5f, 0f, 0f, 0f)))
        val sticker = StickerClip(uri = "file:///s.png", name = "s", startUs = 0, endUs = 3 * SECOND, mask = mask)
        val project = Project(videos = listOf(source), stickers = listOf(sticker))
        val plan = AutoEditPlan(source.id, source.durationUs, AutoEditOptions(captions = false, autoReframe = false),
            cuts = listOf(CutDecision(1 * SECOND, 2 * SECOND, .9f, "silencio")))
        val edited = AutoEditExecutor.execute(project, 0, plan)
        assertEquals(2 * SECOND, edited.stickers.single().durationUs)
        assertEquals(1, edited.stickers.single().mask!!.keyframes.size)
    }

    @Test fun verticalClipIsNotReframed() {
        val motion = (1..10).map { MotionSample(it * 500_000L, .5f, if (it % 2 == 0) .9f else .1f, .5f) }
        val options = AutoEditOptions(captions = false, cleanPauses = false, smartZoom = false, audioEnhancement = false)
        val vertical = AutoEditPlanner.plan(ClipAnalysis("c", 6 * SECOND, motion = motion, sourceAspect = 9f / 16f), options)
        assertTrue(vertical.reframes.isEmpty())
        assertTrue(vertical.warnings.none { it.contains("foco visual") })
        val horizontal = AutoEditPlanner.plan(ClipAnalysis("c", 6 * SECOND, motion = motion, sourceAspect = 16f / 9f), options)
        assertTrue(horizontal.reframes.isNotEmpty())
        assertEquals(9f / 16f, ClipAnalyzer.displayedAspect(clip(1080, 1920)), 1e-4f)
        assertEquals(9f / 16f, ClipAnalyzer.displayedAspect(clip(1920, 1080).copy(rotation = 90)), 1e-4f)
    }

    @Test fun loudMusicAndConstantMotionAreNotHighlightsButARealSpikeIs() {
        val options = AutoEditOptions(style = AutoEditStyle.DYNAMIC, captions = false, cleanPauses = false, autoReframe = false, audioEnhancement = false)
        val audio = (0 until 100).map { AudioSample(it * 100_000L, (it + 1) * 100_000L, .3f, .7f) }
        val dancing = (1..19).map { MotionSample(it * 500_000L, .55f) }
        val flat = AutoEditPlanner.plan(ClipAnalysis("c", 10 * SECOND, audio, dancing, hasAudio = true), options)
        assertTrue("every frame was a highlight: ${flat.zooms.size}", flat.zooms.isEmpty())
        val withDrop = dancing.map { if (it.timeUs == 6_000_000L) it.copy(amount = .95f) else it }
        val audioDrop = audio.map { if (it.startUs == 6_000_000L) it.copy(peak = 1f) else it }
        val spike = AutoEditPlanner.plan(ClipAnalysis("c", 10 * SECOND, audioDrop, withDrop, hasAudio = true), options)
        assertEquals(listOf(6_000_000L), spike.zooms.map { it.timeUs })
    }
}
