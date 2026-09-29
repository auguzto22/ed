package com.termex.replay15.editor

import com.termex.replay15.editor.core.PacingSamples
import org.junit.Assert.*
import org.junit.Test

class PacingSamplesTest {
    @Test fun percentilesExposeLongTailInsteadOfOnlyAverage() {
        val samples = PacingSamples()
        repeat(98) { samples.add(16_000_000) }
        samples.add(50_000_000)
        samples.add(100_000_001)
        val report = samples.summary()
        assertTrue(report.contains("p50Ms=16.0"))
        assertTrue(report.contains("p99Ms=50.0"))
        assertTrue(report.contains("maxMs=100.000001"))
        assertTrue(report.contains("gt100=1"))
    }
    @Test fun boundedStorageReportsOverwritesAndResetsBetweenWindows() {
        val samples = PacingSamples(2)
        samples.add(99_000_000)
        samples.add(20_000_000)
        samples.add(30_000_000)
        assertTrue(samples.summary().contains("samples=2 overwritten=1 avgMs=25.0"))
        samples.clear()
        assertEquals("samples=0", samples.summary())
        samples.add(-1)
        assertEquals("samples=0", samples.summary())
    }
}
