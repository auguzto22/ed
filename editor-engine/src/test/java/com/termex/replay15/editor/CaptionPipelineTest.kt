package com.termex.replay15.editor

import com.termex.replay15.editor.captions.*
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.VideoClip
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CaptionPipelineTest {
    private val region = SpeechRegion(1000, 2500, .9f)
    private fun word(text: String, start: Long, end: Long, confidence: Float? = .95f) =
        CaptionWord(text = text, startMs = start, endMs = end, confidence = confidence,
            acousticConfidence = confidence, alignmentConfidence = confidence)

    @Test fun cleanSpeechMapsMeasuredTiming() = runBlocking {
        val engine = CaptionGenerationController(SpeechRegionDetector { listOf(region) },
            CaptionLanguageDetector { "pt-BR" }, CaptionTranscriber { req ->
                TranscriptionHypothesis(listOf(word("tô", 1100, 1300), word("aqui", 1350, 1700)), req.region, "pt-BR", req.variant)
            })
        val output = engine.generate(5000, CaptionMode.ULTRA_PRECISE, CaptionProgress { _, _, _ -> }, CaptionCancellation {})
        assertEquals("tô aqui", output.segments.single().text)
        val project = Project(videos = listOf(VideoClip(uri = "file:///test.mp4", name = "test", sourceUs = 5_000_000, width = 100, height = 100)))
        assertEquals(1_100_000, CaptionTimelineMapper().map(project, output).texts.single().startUs)
    }

    @Test fun unknownAcousticConfidenceNeverAccepted() = runBlocking {
        val engine = CaptionGenerationController(SpeechRegionDetector { listOf(region) }, CaptionLanguageDetector { null },
            CaptionTranscriber { req -> TranscriptionHypothesis(listOf(word("recly", 1100, 1500, null)), req.region, null, req.variant) })
        val output = engine.generate(5000, CaptionMode.PRECISE, CaptionProgress { _, _, _ -> }, CaptionCancellation {})
        assertTrue(output.segments.isEmpty())
        assertEquals(listOf(region), output.reviewRegions)
        assertEquals("recly", output.reviewHypotheses.single().words.single().text)
        assertEquals(VerificationState.ALIGNMENT_FAILED, output.reviewHypotheses.single().words.single().verificationState)
    }

    @Test fun silenceDoesNotCallRecognizer() = runBlocking {
        var calls = 0
        val engine = CaptionGenerationController(SpeechRegionDetector { emptyList() }, CaptionLanguageDetector { null }, CaptionTranscriber { calls++; null })
        assertTrue(engine.generate(5000, CaptionMode.ULTRA_PRECISE, CaptionProgress { _, _, _ -> }, CaptionCancellation {}).segments.isEmpty())
        assertEquals(0, calls)
    }

    @Test fun retryUsesDifferentInputsAndRejectsUncertainResult() = runBlocking {
        val requests = mutableListOf<RecognitionRequest>()
        val engine = CaptionGenerationController(SpeechRegionDetector { listOf(region) }, CaptionLanguageDetector { null },
            CaptionTranscriber { req -> requests += req; TranscriptionHypothesis(listOf(word("recli", 1100, 1500, .3f)), req.region, null, req.variant) })
        val output = engine.generate(5000, CaptionMode.ULTRA_PRECISE, CaptionProgress { _, _, _ -> }, CaptionCancellation {})
        assertTrue(output.segments.isEmpty())
        assertTrue(requests.distinct().size > 1)
        assertEquals(AudioVariant.RAW, requests.last().variant)
    }

    @Test fun canceledGenerationCannotApplyLateResult() {
        val session = CaptionGenerationSession()
        val oldId = session.begin()
        session.cancel()
        assertNull(session.apply(oldId, Project(), CaptionProject(emptyList(), emptyList())))
    }
}
