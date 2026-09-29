package com.termex.replay15.editor

import com.termex.replay15.editor.captions.*
import org.junit.Assert.*
import org.junit.Test

class CaptionDirtyRegionTest {
    private fun word(start: Long, end: Long, confidence: Float = .95f) = CaptionWord(
        text = "fala", startMs = start, endMs = end, confidence = confidence,
        acousticConfidence = null, alignmentConfidence = null)

    @Test fun cleanSpeechHasNoDirtyRegion() {
        val region = SpeechRegion(0, 2_000, 1f)
        val words = listOf(word(100, 500), word(600, 1_000))
        val quality = CaptionSegmentQuality(.95f, null, .9f, 0)
        assertTrue(CaptionDirtyRegionDetector().detect(region, words, emptyList(), quality).isEmpty())
    }

    @Test fun nearbyGapsShareOneRecoveryWindow() {
        val gaps = listOf(10_200L to 10_450L, 10_700L to 10_950L, 11_200L to 11_450L)
            .map { (start, end) -> CaptionDirtyRegion(start, end, setOf(DirtyReason.MISSING_SPEECH)) }
        assertEquals(listOf(CaptionDirtyRegion(10_200, 11_450, setOf(DirtyReason.MISSING_SPEECH))),
            mergeNearbyDirtyRegions(gaps, 700))
    }

    @Test fun oneMissingWordCreatesOneDirtyRegion() {
        val region = SpeechRegion(0, 2_000, 1f)
        val gap = SpeechGap(420, 580, 1f, word(100, 400), word(600, 900), SpeechGapKind.INTERNAL)
        val quality = CaptionSegmentQuality(.95f, null, .7f, 1)
        assertEquals(listOf(CaptionDirtyRegion(420, 580, setOf(DirtyReason.MISSING_SPEECH))),
            CaptionDirtyRegionDetector().detect(region, listOf(word(100, 400), word(600, 900)), listOf(gap), quality))
    }

    @Test fun shortAcousticPauseIsNotMissingWord() {
        val vad = VadTimeline(10, List(200) { frame -> if (frame in 51..59) 1f else 0f })
        val region = SpeechRegion(0, 2_000, 1f)
        assertTrue(MissingWordDetector().findUnexplainedSpeech(region,
            listOf(word(100, 500), word(620, 900)), vad).isEmpty())
    }

    @Test fun lowConfidenceNeedsAgreementFromBothLocalSignals() {
        val original = word(100, 300, .4f)
        val dirty = CaptionDirtyRegion(100, 300, setOf(DirtyReason.LOW_CONFIDENCE))
        val processed = word(105, 295, .9f)
        val raw = word(110, 300, .85f)
        assertEquals(.4f, confirmLocalWords(listOf(original), dirty, listOf(processed), emptyList()).single().confidence!!)
        val confirmed = confirmLocalWords(listOf(original), dirty, listOf(processed), listOf(raw)).single()
        assertEquals(.85f, confirmed.confidence!!)
        assertEquals(VerificationState.RECOVERED, confirmed.verificationState)
    }

    @Test fun initialPassSkipsLongSilenceAndOverlapsLongSpeech() {
        val windows = planInitialCaptionWindows(listOf(
            SpeechRegion(100, 2_000, 1f), SpeechRegion(30_000, 32_000, 1f)))
        assertEquals(listOf(CaptionWindow(100, 2_000), CaptionWindow(30_000, 32_000)), windows)
        val long = planInitialCaptionWindows(listOf(SpeechRegion(0, 31_000, 1f)))
        assertTrue(long.size > 1)
        assertEquals(700, long[0].endMs - long[1].startMs)
    }
}
