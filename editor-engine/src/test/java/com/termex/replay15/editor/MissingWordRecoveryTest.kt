package com.termex.replay15.editor

import com.termex.replay15.editor.captions.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MissingWordRecoveryTest {
    private fun word(text: String, start: Long, end: Long) = CaptionWord(text = text, startMs = start,
        endMs = end, confidence = .9f, acousticConfidence = null, alignmentConfidence = null)
    private fun vad(vararg intervals: IntRange): VadTimeline = VadTimeline(10,
        List(200) { frame -> if (intervals.any { frame * 10 in it }) 1f else 0f })
    private val region = SpeechRegion(0, 2_000, .9f)

    @Test fun findsShortInternalWordAndMeasuresAcousticCoverage() {
        val words = listOf(word("criar", 100, 400), word("sistema", 600, 900))
        val audio = vad(100..390, 420..570, 600..890)
        val detector = MissingWordDetector()
        val gaps = detector.findUnexplainedSpeech(region, words, audio)
        assertEquals(1, gaps.size)
        assertEquals(SpeechGapKind.INTERNAL, gaps.single().kind)
        assertTrue(detector.quality(region, words, audio).speechCoverage < 1f)
        assertEquals(0, detector.quality(region, words + word("um", 420, 580), audio).unresolvedSpeechGaps)
        assertFalse(CaptionMissingWordFinalVerifier().accepted(detector.quality(region, words, audio)))
        assertTrue(CaptionMissingWordFinalVerifier().accepted(detector.quality(region, words + word("um", 420, 580), audio)))
    }

    @Test fun findsFirstAndLastWordsWithoutMistakingSilenceForSpeech() {
        val words = listOf(word("eu", 400, 600), word("isso", 900, 1_100))
        val audio = vad(100..290, 400..590, 900..1090, 1300..1490)
        assertEquals(listOf(SpeechGapKind.LEADING, SpeechGapKind.TRAILING),
            MissingWordDetector().findUnexplainedSpeech(region, words, audio).map { it.kind })
        assertTrue(MissingWordDetector().findUnexplainedSpeech(region, words, vad(400..590, 900..1090)).isEmpty())
    }

    @Test fun recoveryRequiresIndependentAgreementAndAcousticActivity() = runBlocking {
        val gap = SpeechGap(420, 540, 1f, word("criar", 100, 400), word("sistema", 560, 900), SpeechGapKind.INTERNAL)
        val requests = mutableListOf<Pair<SpeechRegion, AudioVariant>>()
        val engine = MissingWordRecoveryEngine(transcribe = { window, variant ->
            requests += window to variant
            listOf(word("um", 430, 530))
        })
        val result = engine.recover(gap, region, 2_000, vad(420..530), listOf(word("um", 430, 530)))
        assertEquals("um", result.single().text)
        assertEquals(VerificationState.RECOVERED, result.single().verificationState)
        assertTrue(requests.distinct().size > 1)
        assertEquals(2, requests.size)
        assertTrue(requests.any { it.second == AudioVariant.RAW })
        assertTrue(engine.recover(gap, region, 2_000, vad(), listOf(word("um", 430, 530))).isEmpty())
    }

    @Test fun conflictingHypothesesRemainUnresolvedAndOverlapDoesNotDuplicate() = runBlocking {
        val gap = SpeechGap(420, 540, 1f, word("eu", 100, 400), word("fazer", 560, 900), SpeechGapKind.INTERNAL)
        var count = 0
        val engine = MissingWordRecoveryEngine(transcribe = { _, _ ->
            count++
            listOf(word(if (count % 2 == 0) "vou" else "posso", 430, 530))
        })
        assertTrue(engine.recover(gap, region, 2_000, vad(420..530), listOf(word("vou", 430, 530))).isEmpty())
        assertEquals(2, count)
        assertEquals(1, mergeTimedWords(listOf(word("vou", 430, 530)), listOf(word("vou", 435, 535))).size)
        assertEquals(1, mergeTimedWords(listOf(word("vou", 430, 530)), listOf(word("posso", 435, 535))).size)
    }
}
