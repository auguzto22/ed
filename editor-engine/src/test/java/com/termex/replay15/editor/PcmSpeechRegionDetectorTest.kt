package com.termex.replay15.editor

import com.termex.replay15.editor.captions.PcmSpeechRegionDetector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.sin

class PcmSpeechRegionDetectorTest {
    private fun pcm(durationMs: Int, sample: (Int) -> Int): File {
        val file = File.createTempFile("caption-vad-", ".pcm")
        file.outputStream().buffered().use { out ->
            repeat(durationMs * 16) { index ->
                val value = sample(index).coerceIn(-32768, 32767)
                out.write(value and 255)
                out.write((value ushr 8) and 255)
            }
        }
        return file
    }

    @Test fun silenceProducesNoCandidateSpeech() = runBlocking {
        val file = pcm(2_000) { 0 }
        try { assertTrue(PcmSpeechRegionDetector(file).detect().isEmpty()) } finally { file.delete() }
    }

    @Test fun sustainedLowHumProducesNoCandidateSpeech() = runBlocking {
        val file = pcm(2_000) { index -> (500 * sin(index * .002)).toInt() }
        try { assertTrue(PcmSpeechRegionDetector(file).detect().isEmpty()) } finally { file.delete() }
    }

    @Test fun isolatedAudioIsBoundedWithContext() = runBlocking {
        val file = pcm(2_000) { index -> if (index in 12_800 until 19_200) (8_000 * sin(index * .1)).toInt() else 0 }
        try {
            val regions = PcmSpeechRegionDetector(file).detect()
            assertEquals(1, regions.size)
            assertTrue(regions.single().startMs in 500..800)
            assertTrue(regions.single().endMs in 1_200..1_500)
        } finally { file.delete() }
    }
}
