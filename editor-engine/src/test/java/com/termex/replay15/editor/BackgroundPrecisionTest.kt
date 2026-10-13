package com.termex.replay15.editor

import com.termex.replay15.editor.domain.BackgroundPrecision
import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundPrecisionTest {
    @Test
    fun sliderIsLinearOverTheUsableRange() {
        assertEquals(0.25f, BackgroundPrecision.thresholdFor(0), 1e-6f)
        assertEquals(0.5f, BackgroundPrecision.thresholdFor(50), 1e-6f)
        assertEquals(0.75f, BackgroundPrecision.thresholdFor(100), 1e-6f)
        assertEquals(0.625f, BackgroundPrecision.thresholdFor(75), 1e-6f)
    }

    @Test
    fun roundTripsAndClamps() {
        for (p in 0..100) assertEquals(p.toFloat(), BackgroundPrecision.percentFor(BackgroundPrecision.thresholdFor(p)).toFloat(), 0f)
        assertEquals(100f, BackgroundPrecision.percentFor(1f).toFloat(), 0f)
        assertEquals(0f, BackgroundPrecision.percentFor(0f).toFloat(), 0f)
        assertEquals(0.75f, BackgroundPrecision.thresholdFor(140), 1e-6f)
    }
}
