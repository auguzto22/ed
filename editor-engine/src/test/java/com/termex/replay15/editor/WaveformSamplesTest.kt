package com.termex.replay15.editor

import com.termex.replay15.editor.audio.WaveformSamples
import org.junit.Assert.*
import org.junit.Test

class WaveformSamplesTest {
    @Test fun waveformUsesActualPeaksAndKeepsSilence() {
        val wave = WaveformSamples(1_000_000, FloatArray(10))
        wave.add(105_000, -.8f); wave.add(115_000, .2f); wave.add(350_000, .4f)
        assertEquals(0f, wave.at(10_000), 0f); assertEquals(.8f, wave.at(150_000), 0f)
        assertEquals(.4f, wave.at(350_000), 0f); assertEquals(0f, wave.at(1_000_000), 0f)
        wave.add(150_000, Float.NaN); assertEquals(.8f, wave.at(150_000), 0f)
    }
}
