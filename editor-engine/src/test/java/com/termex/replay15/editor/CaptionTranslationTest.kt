package com.termex.replay15.editor

import com.termex.replay15.editor.captions.CaptionTranslationFailed
import com.termex.replay15.editor.captions.CaptionTranslationInput
import com.termex.replay15.editor.captions.CaptionTranslationOutput
import com.termex.replay15.editor.captions.CaptionTranslationService
import com.termex.replay15.editor.captions.CaptionTranslator
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.SubtitleWordCue
import com.termex.replay15.editor.domain.TextClip
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CaptionTranslationTest {
    @Test fun translatedCaptionsKeepIdsTimesAndStyleAndRegenerateWordCues() = runBlocking {
        val first = TextClip(
            id = "caption-1", text = "ola mundo", startUs = 1_000, endUs = 4_000,
            isCaption = true, color = 0xFF00FF00.toInt(), x = .3f,
            wordCues = listOf(SubtitleWordCue("ola", 1_000, 2_000), SubtitleWordCue("mundo", 2_000, 3_000)),
        )
        val second = TextClip(id = "caption-2", text = "até logo", startUs = 5_000, endUs = 8_000, isCaption = true)
        val ordinary = TextClip(id = "title", text = "Meu vídeo", startUs = 1_000, endUs = 4_000)
        val project = Project(texts = listOf(first, ordinary, second))
        val seen = mutableListOf<CaptionTranslationInput>()
        val translator = object : CaptionTranslator {
            override suspend fun translate(
                segments: List<CaptionTranslationInput>, sourceLanguageCode: String, targetLanguageCode: String,
                checkCancelled: () -> Unit,
            ): List<CaptionTranslationOutput> {
                assertEquals("pt-BR", sourceLanguageCode)
                assertEquals("en-US", targetLanguageCode)
                seen += segments
                return segments.map { CaptionTranslationOutput(it.id, if (it.id == "caption-1") "hello lovely world" else "see you") }
            }
        }

        val updated = CaptionTranslationService(translator, batchSize = 1).translate(project, "en-US")

        val translatedFirst = updated.texts.first { it.id == first.id }
        assertEquals("hello lovely world", translatedFirst.text)
        assertEquals(first.startUs, translatedFirst.startUs)
        assertEquals(first.endUs, translatedFirst.endUs)
        assertEquals(first.color, translatedFirst.color)
        assertEquals(first.x, translatedFirst.x)
        assertEquals(listOf("hello", "lovely", "world"), translatedFirst.wordCues.map { it.text })
        assertEquals(first.wordCues.first().startUs, translatedFirst.wordCues.first().startUs)
        assertEquals(first.wordCues.last().endUs, translatedFirst.wordCues.last().endUs)
        assertEquals(ordinary, updated.texts.first { it.id == ordinary.id })
        assertEquals("see you", updated.texts.first { it.id == second.id }.text)
        assertEquals("ola mundo", seen[1].previousText)
        assertEquals("até logo", seen[0].nextText)
    }

    @Test fun rejectsMissingResultsBeforeReturningAnUpdatedProject() {
        val caption = TextClip(id = "caption", text = "fala", startUs = 0, endUs = 1_000, isCaption = true)
        val translator = object : CaptionTranslator {
            override suspend fun translate(
                segments: List<CaptionTranslationInput>, sourceLanguageCode: String, targetLanguageCode: String,
                checkCancelled: () -> Unit,
            ) = emptyList<CaptionTranslationOutput>()
        }

        assertThrows(CaptionTranslationFailed::class.java) {
            runBlocking { CaptionTranslationService(translator).translate(Project(texts = listOf(caption)), "en-US") }
        }
        assertEquals("fala", caption.text)
    }
}
