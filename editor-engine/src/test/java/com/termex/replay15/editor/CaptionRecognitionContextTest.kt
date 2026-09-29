package com.termex.replay15.editor

import com.termex.replay15.editor.captions.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CaptionRecognitionContextTest {
    @Test fun pronunciationHintsNeverBecomeReplacementText() {
        val vocabulary = BrazilianMixedVocabulary.default()
        assertEquals("podcast", vocabulary.entryFor("pódkésti")?.canonical)
        assertFalse(vocabulary.contains("pódkésti"))
        assertTrue(vocabulary.canonicalTerms.contains("podcast"))
        assertTrue(vocabulary.canonicalTerms.containsAll(setOf("Instagram", "ChatGPT", "CapCut", "online", "offline")))
    }

    @Test fun projectNamesBecomeWeakDynamicEntities() {
        val entities = DynamicEntityContext.fromMetadata("Podcast com Douglas Viegas")
        assertTrue(entities.contains("Douglas Viegas"))
        assertTrue(entities.contains("Douglas"))
        assertEquals(0f, entities.bonus("Douglas", .4f), 0f)
        assertTrue(entities.bonus("Douglas", .9f) > 0f)
    }

    @Test fun vocabularyDoesNotOverrideClearAcousticEvidence() {
        fun word(text: String, confidence: Float) = CaptionWord(text = text, startMs = 100, endMs = 400,
            confidence = confidence, acousticConfidence = confidence, alignmentConfidence = null)
        val region = SpeechRegion(0, 1_000, 1f)
        val reranker = CaptionHypothesisReranker(
            CaptionRerankingWeights(acousticWeight = 1f, vocabularyWeight = .1f),
            BrazilianMixedVocabulary.default())
        val selected = reranker.choose(listOf(
            TranscriptionHypothesis(listOf(word("podcast", .53f)), region, "pt-BR", AudioVariant.PROCESSED),
            TranscriptionHypothesis(listOf(word("pode", .90f)), region, "pt-BR", AudioVariant.PROCESSED),
        ))
        assertEquals("pode", selected?.words?.single()?.text)
    }

    @Test fun mixedLanguagePresentationKeepsCanonicalBrandCasingWithoutChangingWords() {
        assertEquals("Vi no Insta", PtBrTextNormalizer().normalize("vi no insta"))
        assertEquals("Os melhores podcasts", PtBrTextNormalizer().normalize("os melhores podcasts"))
    }

    @Test fun initialWindowsOverlapOnlyAtAudioEvidenceBoundaries() {
        val windows = planInitialCaptionWindows(
            listOf(SpeechRegion(0, 70_000, 1f)),
            InitialWindowConfig(maxWindowMs = 30_000, targetWindowMs = 20_000, overlapMs = 3_000)
        )
        assertTrue(windows.zipWithNext().all { (a, b) -> a.endMs > b.startMs })
        assertTrue(windows.zipWithNext().all { (a, b) -> a.endMs - b.startMs == 3_000L })
    }

    @Test fun structuredCorrectionsArePersistedAndNeedRepetition() {
        val file = Files.createTempDirectory("caption-structured-memory").resolve("corrections.json").toFile()
        val record = CaptionCorrectionRecord("audio:10-20", "melhores pode", "melhores podcasts",
            CaptionErrorType.FOREIGN_WORD, "vosk-test", 10, 20)
        CaptionCorrectionMemory(file).apply {
            record(record)
            assertEquals(emptySet<String>(), confirmedTerms())
            record(record)
        }
        val restored = CaptionCorrectionMemory(file)
        assertTrue(restored.confirmedTerms().contains("podcasts"))
        assertTrue(restored.bonus("pode", "podcasts", "melhores", null) > 0f)
    }
}
