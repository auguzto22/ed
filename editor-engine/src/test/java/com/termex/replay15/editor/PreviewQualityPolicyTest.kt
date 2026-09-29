package com.termex.replay15.editor

import com.termex.replay15.editor.core.PreviewQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewQualityPolicyTest {
    @Test fun lowRamAndHeavyCompositionsUse480() {
        assertEquals(480, choose(memory = 512, lowRam = true, surface = 1080, layers = 1, fps = 30))
        assertEquals(480, choose(memory = 512, lowRam = false, surface = 1080, layers = 4, fps = 30))
        assertEquals(480, choose(memory = 512, lowRam = false, surface = 1080, layers = 1, fps = 120))
    }

    @Test fun typicalDevicesUse720() {
        assertEquals(720, choose(memory = 256, lowRam = false, surface = 1080, layers = 1, fps = 30))
        assertEquals(720, choose(memory = 512, lowRam = false, surface = 720, layers = 1, fps = 30))
        assertEquals(720, choose(memory = 512, lowRam = false, surface = 1080, layers = 3, fps = 30))
    }

    @Test fun capableDeviceAndLargeSurfaceUse1080() {
        assertEquals(1080, choose(memory = 512, lowRam = false, surface = 1080, layers = 2, fps = 60))
    }

    private fun choose(memory: Int, lowRam: Boolean, surface: Int, layers: Int, fps: Int) =
        PreviewQualityPolicy.choose(PreviewQualityPolicy.Inputs(memory, lowRam, surface, layers, fps))
}
