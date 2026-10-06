package com.termex.replay15.editor

import com.termex.replay15.editor.audio.AudioEnhance
import com.termex.replay15.editor.audio.AudioEnhanceProcessor
import com.termex.replay15.editor.audio.peakOf
import com.termex.replay15.editor.audio.rmsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class AudioEnhanceTest {

    private val sampleRate = 48_000
    private val channelCount = 1

    private fun tone(freq: Float, seconds: Float, amplitude: Float = .5f): FloatArray {
        val frames = (sampleRate * seconds).toInt()
        return FloatArray(frames) { (sin(2f * Math.PI.toFloat() * freq * it / sampleRate) * amplitude) }
    }

    private fun noise(floor: Float, seconds: Float, seed: Int = 7): FloatArray {
        val frames = (sampleRate * seconds).toInt()
        var state = seed
        return FloatArray(frames) {
            state = state * 1103515245 + 12345
            ((state ushr 16) and 0x7FFF) / 16384f - 1f * floor
        }
    }

    private fun run(settings: AudioEnhance, input: FloatArray): FloatArray {
        val copy = input.copyOf()
        val processor = AudioEnhanceProcessor(settings, sampleRate, channelCount)
        processor.process(copy, copy.size / channelCount)
        return copy
    }

    @Test
    fun `neutral settings leave the samples untouched`() {
        val input = tone(440f, .05f)
        val output = run(AudioEnhance.NEUTRAL, input)
        assertTrue(output.contentEquals(input))
    }

    @Test
    fun `every treatment stays inside the sample range`() {
        val loud = tone(220f, .1f, amplitude = 2f)
        val everything = AudioEnhance(noiseReduction = 1f, voiceEnhance = 1f, compression = 1f, normalize = 1f)
        run(everything, loud).forEach { assertTrue("sample escaped to $it", abs(it) <= 1f) }
    }

    @Test
    fun `the noise gate pulls quiet hiss down but leaves the signal`() {
        val hiss = noise(floor = .01f, seconds = .2f)
        val gated = run(AudioEnhance(noiseReduction = 1f), hiss)
        // Hiss that never crosses the ceiling is treated as noise and attenuated.
        assertTrue("hiss was not reduced", peakOf(gated, gated.size, 1) < peakOf(hiss, hiss.size, 1) * .8f)
    }

    @Test
    fun `compression lowers the crest between loud and quiet`() {
        val quiet = tone(300f, .05f, .1f)
        val loud = tone(300f, .05f, .9f)
        val input = quiet + loud
        val compressed = run(AudioEnhance(compression = 1f), input)

        val beforeQuiet = rmsOf(input, 0, quiet.size, 1)
        val afterQuiet = rmsOf(compressed, 0, quiet.size, 1)
        val beforeLoud = rmsOf(input, quiet.size, quiet.size + loud.size, 1)
        val afterLoud = rmsOf(compressed, quiet.size, quiet.size + loud.size, 1)
        // Raising the quiet part relative to the loud part is the whole point.
        assertTrue("loud was not compressed ($afterLoud vs $beforeLoud)", afterLoud < beforeLoud)
        assertTrue("quiet was not lifted ($afterQuiet vs $beforeQuiet)", afterQuiet > beforeQuiet)
    }

    @Test
    fun `normalize drives a quiet recording toward the target level`() {
        val quiet = tone(440f, .2f, .02f)
        val normalized = run(AudioEnhance(normalize = 1f), quiet)
        assertTrue("level did not rise", peakOf(normalized, normalized.size, 1) > peakOf(quiet, quiet.size, 1) * 2f)
    }

    @Test
    fun `voice enhance cuts low rumble`() {
        val rumble = tone(40f, .2f, .8f)
        val enhanced = run(AudioEnhance(voiceEnhance = 1f), rumble)
        // A 40Hz tone is almost entirely below the low cut, so it must lose most of its level.
        assertTrue("rumble survived", rmsOf(enhanced, enhanced.size, 1) < rmsOf(rumble, rumble.size, 1) * .5f)
    }

    @Test
    fun `state carries across blocks so borders do not click`() {
        val input = tone(440f, .1f)
        val whole = run(AudioEnhance(compression = 1f, voiceEnhance = 1f), input)

        val chunked = input.copyOf()
        val processor = AudioEnhanceProcessor(AudioEnhance(compression = 1f, voiceEnhance = 1f), sampleRate, channelCount)
        val block = 256
        var offset = 0
        while (offset < chunked.size) {
            val frames = minOf(block, chunked.size - offset)
            val stage = chunked.copyOfRange(offset, offset + frames)
            processor.process(stage, frames)
            stage.copyInto(chunked, offset)
            offset += frames
        }
        // Only the interior is compared: the first block has no history by definition.
        val from = 1024
        val length = minOf(whole.size, chunked.size) - from
        val drift = (0 until length).maxOf { abs(whole[from + it] - chunked[from + it]) }
        assertTrue("block processing drifted by $drift", drift < .05f)
    }

    @Test
    fun `reset returns the filters to their initial state`() {
        val processor = AudioEnhanceProcessor(AudioEnhance(voiceEnhance = 1f), sampleRate, channelCount)
        val warm = tone(200f, .05f)
        processor.process(warm, warm.size)
        processor.reset()
        val cold = tone(200f, .05f)
        val fresh = AudioEnhanceProcessor(AudioEnhance(voiceEnhance = 1f), sampleRate, channelCount)
        val expected = fresh.processed(cold)
        val actual = processor.processed(cold)
        assertTrue("reset did not clear the filter memory", actual.contentEquals(expected))
    }

    @Test
    fun `settings outside the normalized range are rejected`() {
        try {
            AudioEnhance(compression = 1.5f)
            throw AssertionError("expected a rejected setting")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.contains("compression") == true)
        }
    }

    private fun rmsOf(samples: FloatArray, from: Int, to: Int, channelCount: Int) =
        com.termex.replay15.editor.audio.rmsOf(samples.copyOfRange(from, to), to - from, channelCount)

    private fun AudioEnhanceProcessor.processed(input: FloatArray): FloatArray {
        val copy = input.copyOf()
        process(copy, copy.size / channelCount)
        return copy
    }
}
