package com.termex.replay15.editor

import com.termex.replay15.editor.captions.CaptionCorrectionContext
import com.termex.replay15.editor.captions.CaptionCorrectionService
import com.termex.replay15.editor.captions.CaptionTextCorrection
import com.termex.replay15.editor.captions.CaptionTextCorrector
import com.termex.replay15.editor.captions.CorrectionFailed
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.SubtitleWordCue
import com.termex.replay15.editor.domain.TextClip
import com.termex.replay15.editor.history.ProjectHistory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CaptionCorrectionServiceTest {
    @Test fun correctsCaptionsWithContextAndOneUndoEntry() = runBlocking {
        val first = TextClip(
            id = "caption-1", text = "O meu Recli", startUs = 1_000, endUs = 4_000,
            isCaption = true, x = .3f,
            wordCues = listOf(
                SubtitleWordCue("O", 1_000, 1_500),
                SubtitleWordCue("meu", 1_500, 2_500),
                SubtitleWordCue("Recli", 2_500, 3_500),
            ),
        )
        val second = TextClip(id = "caption-2", text = "ele e muito lega", startUs = 5_000, endUs = 8_000, isCaption = true)
        val title = TextClip(id = "title", text = "Meu vídeo", startUs = 0, endUs = 4_000)
        val original = Project(name = "Podcast Recly", captionVocabulary = setOf("Recly"), texts = listOf(first, title, second))
        var requestedLanguage = ""
        var requestedTerms = emptySet<String>()
        val contexts = mutableListOf<CaptionCorrectionContext>()
        val corrector = object : CaptionTextCorrector {
            override suspend fun correctCaptions(
                segments: List<CaptionCorrectionContext>, languageCode: String, terms: Set<String>,
                projectContext: String?, checkCancelled: () -> Unit,
            ): List<CaptionTextCorrection> {
                requestedLanguage = languageCode
                requestedTerms = terms
                contexts += segments
                return segments.map { segment ->
                    CaptionTextCorrection(segment.id, when (segment.id) {
                        "caption-1" -> "O meu Recly."
                        else -> "ele é muito legal."
                    })
                }
            }
        }

        val result = CaptionCorrectionService(corrector, batchSize = 1).correct(original)

        assertEquals("pt-BR", requestedLanguage)
        assertEquals(setOf("Recly"), requestedTerms)
        assertEquals("ele e muito lega", contexts[0].nextText)
        assertEquals("O meu Recli", contexts[1].previousText)
        assertEquals(2, result.correctedCount)
        val correctedFirst = result.project.texts.first { it.id == first.id }
        assertEquals("O meu Recly.", correctedFirst.text)
        assertEquals(first.startUs, correctedFirst.startUs)
        assertEquals(first.endUs, correctedFirst.endUs)
        assertEquals(first.x, correctedFirst.x)
        assertEquals(listOf("O", "meu", "Recly."), correctedFirst.wordCues.map { it.text })
        assertEquals(first.wordCues.map { it.startUs to it.endUs }, correctedFirst.wordCues.map { it.startUs to it.endUs })
        assertEquals(title, result.project.texts.first { it.id == title.id })

        val history = ProjectHistory(original)
        history.apply(result.project)
        assertEquals("O meu Recly.", history.current.texts.first { it.id == first.id }.text)
        history.undo()
        assertEquals("O meu Recli", history.current.texts.first { it.id == first.id }.text)
    }

    @Test fun retainsARewriteThatWouldChangeTheCaptionMeaning() = runBlocking {
        val caption = TextClip(id = "caption", text = "Hoje vamos jogar", startUs = 0, endUs = 1_000, isCaption = true)
        val corrector = StubCorrector { listOf(CaptionTextCorrection("caption", "Amanhã vou vender o carro")) }

        val result = CaptionCorrectionService(corrector).correct(Project(texts = listOf(caption)))

        assertEquals("Hoje vamos jogar", result.project.texts.single().text)
        assertEquals(0, result.correctedCount)
        assertEquals(1, result.keptOriginalCount)
    }

    @Test fun rejectsMissingCaptionIdsWithoutReturningPartialCorrections() {
        val caption = TextClip(id = "caption", text = "fala", startUs = 0, endUs = 1_000, isCaption = true)
        val corrector = StubCorrector { emptyList() }

        assertThrows(CorrectionFailed::class.java) {
            runBlocking { CaptionCorrectionService(corrector).correct(Project(texts = listOf(caption))) }
        }
        assertEquals("fala", caption.text)
    }

    private class StubCorrector(
        private val response: (List<CaptionCorrectionContext>) -> List<CaptionTextCorrection>,
    ) : CaptionTextCorrector {
        override suspend fun correctCaptions(
            segments: List<CaptionCorrectionContext>, languageCode: String, terms: Set<String>,
            projectContext: String?, checkCancelled: () -> Unit,
        ): List<CaptionTextCorrection> = response(segments)
    }
}
