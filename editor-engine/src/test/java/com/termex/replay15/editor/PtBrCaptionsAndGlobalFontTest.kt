package com.termex.replay15.editor

import com.termex.replay15.editor.captions.*
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.project.ProjectCodec
import com.termex.replay15.editor.history.ProjectHistory
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class PtBrCaptionsAndGlobalFontTest {
    @Test fun informalSpeechAndBrandCasingArePreserved() {
        val normalizer = PtBrTextNormalizer(setOf("Mauro"))
        listOf(
            "mano isso aqui tá muito da hora" to "Mano isso aqui tá muito da hora",
            "véi olha esse negócio" to "Véi olha esse negócio",
            "oxe cadê meu vídeo" to "Oxe cadê meu vídeo",
            "bora fazer essa parada" to "Bora fazer essa parada",
            "eu não tankei isso não" to "Eu não tankei isso não",
            "meu fps caiu muito" to "Meu FPS caiu muito",
            "o preview tá travando" to "O preview tá travando",
            "coloca um keyframe nesse negócio" to "Coloca um keyframe nesse negócio",
            "eu vou editar isso no capcut" to "Eu vou editar isso no CapCut",
            "abre o projeto no recly" to "Abre o projeto no Recly",
            "o carro tá ligado" to "O carro tá ligado",
            "mauro fez a live" to "Mauro fez a live"
        ).forEach { (input, expected) -> assertEquals(expected, normalizer.normalize(input)) }
    }

    @Test fun contextualRescoringRequiresIndependentAcousticAgreement() {
        fun word(text: String, confidence: Float) = CaptionWord(text = text, startMs = 100,
            endMs = 350, confidence = confidence, acousticConfidence = null, alignmentConfidence = null)
        val scorer = PtBrContextRescorer(PtBrLexiconRepository.fromTerms(setOf("brabo", "tá ligado")))
        val primary = word("bravo", .4f)
        assertEquals("brabo", scorer.choose(primary, "ficou", null,
            listOf(word("brabo", .88f)), listOf(word("brabo", .86f)))?.text)
        assertNull(scorer.choose(primary, "ficou", null,
            listOf(word("brabo", .88f)), listOf(word("bravo", .86f))))
        val correct = word("ligado", .93f)
        assertNull(scorer.choose(correct, "tá", null,
            listOf(word("ligado", .90f)), listOf(word("ligado", .90f))))
    }

    @Test fun twentyCaptionsInheritGlobalFontExceptAnOverrideAndRoundTrip() {
        val clips = (1..20).map { index -> TextClip(text = "Legenda $index", startUs = index * SECOND,
            endUs = index * SECOND + SECOND / 2, isCaption = true) }
        var project = Project(texts = clips)
        project = CaptionStyleResolver.applyGlobalFont(project, "montserrat_regular")
        assertTrue(project.texts.all { CaptionStyleResolver.resolve(project, it).fontId == "montserrat_regular" })
        project = project.copy(texts = project.texts.mapIndexed { index, clip ->
            if (index == 6) clip.copy(captionFontOverride = "anton_regular") else clip
        })
        project = CaptionStyleResolver.applyGlobalFont(project, "poppins_regular")
        assertEquals("anton_regular", CaptionStyleResolver.resolve(project, project.texts[6]).fontId)
        assertTrue(project.texts.filterIndexed { index, _ -> index != 6 }
            .all { CaptionStyleResolver.resolve(project, it).fontId == "poppins_regular" })
        val saved = ByteArrayOutputStream().also { ProjectCodec.write(project, it) }
        val loaded = ProjectCodec.read(saved.toByteArray().inputStream())
        assertEquals(project, loaded)
        val reset = CaptionStyleResolver.useGlobalFontForAll(project)
        assertTrue(reset.texts.all { CaptionStyleResolver.resolve(reset, it).fontId == "poppins_regular" })
        assertEquals("anton_regular", CaptionStyleResolver.resolve(project, project.texts[6]).fontId)
    }

    @Test fun correctionMemoryNeedsRepetitionAndContext() {
        val file = Files.createTempDirectory("caption-memory").resolve("user_dictionary.json").toFile()
        val memory = CaptionCorrectionMemory(file)
        memory.record("aplicativo ficou da ora", "aplicativo ficou da hora")
        assertEquals(0f, memory.bonus("ora", "hora", "da", null), 0f)
        memory.record("aplicativo ficou da ora", "aplicativo ficou da hora")
        assertTrue(CaptionCorrectionMemory(file).bonus("ora", "hora", "da", null) > 0f)
    }

    @Test fun applyingGlobalFontToAllIsOneUndoableEdit() {
        val start = Project(texts = listOf(
            TextClip(text = "Uma", startUs = 0, endUs = SECOND, isCaption = true,
                captionFontOverride = "anton_regular"),
            TextClip(text = "Duas", startUs = SECOND, endUs = 2 * SECOND, isCaption = true)
        ), captionGlobalFontId = "montserrat_regular")
        val history = ProjectHistory(start)
        history.apply(CaptionStyleResolver.useGlobalFontForAll(
            CaptionStyleResolver.applyGlobalFont(start, "poppins_regular")))
        assertTrue(history.current.texts.all { CaptionStyleResolver.resolve(history.current, it).fontId == "poppins_regular" })
        history.undo()
        assertEquals(start, history.current)
        history.redo()
        assertEquals("poppins_regular", history.current.captionGlobalFontId)
    }

    @Test fun selectedLanguagePersistsAndKeepsBrandCasing() {
        val project = Project(captionLanguage = "en-US")
        val bytes = ByteArrayOutputStream().also { ProjectCodec.write(project, it) }
        assertEquals("en-US", ProjectCodec.read(bytes.toByteArray().inputStream()).captionLanguage)
        val source = CaptionProject(listOf(CaptionSegment(
            words = listOf(CaptionWord(text = "iphone", startMs = 0, endMs = 300,
                confidence = .9f, acousticConfidence = null, alignmentConfidence = null)),
            language = "en-US", speakerId = null, overlappingSpeech = false,
            qualityScore = .9f, verificationState = VerificationState.UNCHECKED)), emptyList())
        val media = Project(videos = listOf(VideoClip(uri = "content://clip", name = "Clip",
            sourceUs = SECOND, width = 1080, height = 1920)), captionLanguage = "en-US")
        assertEquals("iPhone", CaptionTimelineMapper().map(media, source).texts.single().text)
    }
}
