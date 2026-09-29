package com.termex.replay15.editor

import com.termex.replay15.editor.domain.ApplyRange
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.SubtitleApplyRangeResolver
import com.termex.replay15.editor.domain.SubtitleRegrouper
import com.termex.replay15.editor.domain.SubtitleStylePatch
import com.termex.replay15.editor.domain.SubtitleWordCue
import com.termex.replay15.editor.domain.TextClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SubtitleEditingTest {
    private fun captions(): List<TextClip> = (0 until 8).map { index ->
        TextClip(text = "caption $index", startUs = index * 1_000_000L, endUs = (index + 1) * 1_000_000L, isCaption = true,
            x = .2f + index / 20f, color = 0xFF00FF00.toInt())
    }

    @Test fun nextFiveMeansCurrentPlusFiveFollowingCaptions() {
        val list = captions()
        assertEquals(listOf(2, 3, 4, 5, 6, 7),
            SubtitleApplyRangeResolver.indices(list, list[2].id, ApplyRange.next(5)))
    }

    @Test fun fontPatchDoesNotCopyColorOrPosition() {
        val source = captions().first()
        val changed = SubtitleStylePatch(fontId = "poppins_regular").applyTo(source)
        assertEquals("poppins_regular", changed.fontId)
        assertEquals(source.color, changed.color)
        assertEquals(source.x, changed.x)
        assertEquals(source.y, changed.y)
    }

    @Test fun batchPatchKeepsNonCaptionTextUntouched() {
        val caption = captions()
        val ordinary = TextClip(text = "ordinary", startUs = 0, endUs = 1_000_000L)
        val project = Project(videos = emptyList(), texts = caption + ordinary)
        val updated = SubtitleApplyRangeResolver.apply(project, caption[1].id, ApplyRange.next(2),
            SubtitleStylePatch(color = 0xFFFF0000.toInt()))
        assertEquals(0xFFFF0000.toInt(), updated.texts.first { it.id == caption[1].id }.color)
        assertEquals(0xFFFF0000.toInt(), updated.texts.first { it.id == caption[3].id }.color)
        assertEquals(ordinary.color, updated.texts.first { it.id == ordinary.id }.color)
        assertNotEquals(updated.texts.first { it.id == caption[0].id }.color, 0xFFFF0000.toInt())
    }
    @Test fun regroupUsesMeasuredCueBoundaries() {
        val source = TextClip(text = "eu fui na casa", startUs = 100, endUs = 4_000, isCaption = true,
            wordCues = listOf(SubtitleWordCue("eu", 100, 800), SubtitleWordCue("fui", 800, 1_500),
                SubtitleWordCue("na", 1_500, 2_000), SubtitleWordCue("casa", 2_000, 4_000)))
        val grouped = SubtitleRegrouper.regroup(Project(texts = listOf(source)), 2).texts
        assertEquals(2, grouped.size)
        assertEquals("eu fui", grouped[0].text)
        assertEquals(100, grouped[0].startUs)
        assertEquals(1_500, grouped[0].endUs)
        assertEquals("na casa", grouped[1].text)
        assertEquals(1_500, grouped[1].startUs)
        assertEquals(4_000, grouped[1].endUs)
    }

    @Test fun regroupAcceptsTheMaximumTwelveWordsPerBlock() {
        val cues = (0 until 12).map { index ->
            SubtitleWordCue("w$index", index * 100L, (index + 1) * 100L)
        }
        val source = TextClip(text = cues.joinToString(" ") { it.text }, startUs = 0, endUs = 1_200,
            isCaption = true, wordCues = cues)

        val grouped = SubtitleRegrouper.regroup(Project(texts = listOf(source)), 12).texts

        assertEquals(1, grouped.size)
        assertEquals(12, grouped.single().wordCues.size)
    }
}
