package com.termex.replay15.editor

import com.termex.replay15.editor.backgroundremoval.GuidedMatteFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedMatteFilterTest {
    @Test
    fun boxMeanClampsAtBorders() {
        val values = FloatArray(9) { it.toFloat() } // 3x3: 0..8
        val mean = GuidedMatteFilter.boxMean(values, 3, 3, 1)
        assertEquals((0 + 1 + 3 + 4) / 4f, mean[0], 1e-5f)
        assertEquals(4f, mean[4], 1e-5f)
    }

    @Test
    fun uniformMatteStaysUniform() {
        val w = 16; val h = 16
        val guide = FloatArray(w * h) { (it % w) / w.toFloat() }
        val matte = FloatArray(w * h) { 1f }
        val out = GuidedMatteFilter.filter(guide, matte, w, h, 3)
        out.forEach { assertEquals(1f, it, 1e-3f) }
    }

    @Test
    fun matteEdgeSnapsToImageEdge() {
        // Image edge at x = 10; the model put the matte edge 2 px too far right (x = 12).
        val w = 24; val h = 8
        val guide = FloatArray(w * h) { if (it % w >= 10) 1f else 0f }
        val matte = FloatArray(w * h) { if (it % w >= 12) 1f else 0f }
        val out = GuidedMatteFilter.filter(guide, matte, w, h, 4, 1e-4f)
        val row = 4 * w
        assertTrue("background side stays clear", out[row + 9] < 0.05f)
        assertTrue("the hard step moves to the image edge", out[row + 10] - out[row + 9] > 0.4f)
        assertTrue("pixel the model missed is recovered", out[row + 11] > 0.5f)
        assertTrue("far subject stays subject", out[row + 20] > 0.95f)
    }

    @Test
    fun haloOnFlatBackgroundIsWeakened() {
        // Flat wall (0.9) on the left, dark hair (0.1) from x = 12. The matte leaks 3 px onto the
        // wall at full strength; after filtering the leak is partial and the hard edge is at x = 12.
        val w = 24; val h = 8
        val guide = FloatArray(w * h) { if (it % w >= 12) 0.1f else 0.9f }
        val matte = FloatArray(w * h) { if (it % w >= 9) 1f else 0f }
        val out = GuidedMatteFilter.filter(guide, matte, w, h, 4, 1e-4f)
        val row = 4 * w
        for (x in 9..11) assertTrue("leak at x=$x weakened", out[row + x] < 0.75f)
        assertTrue("hard step at the hair edge", out[row + 12] - out[row + 11] > 0.3f)
        assertTrue(out[row + 12] > 0.95f)
    }

    @Test
    fun luminanceOfWhiteAndBlack() {
        val l = GuidedMatteFilter.luminance(intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt()))
        assertEquals(1f, l[0], 1e-3f)
        assertEquals(0f, l[1], 1e-3f)
    }

    @Test
    fun gaussianBlurStraightensASerratedContourWithoutMovingIt() {
        val w = 40; val h = 40
        // Bottom edge of a subject that zig-zags by 2 px between neighbouring columns.
        val edge = IntArray(w) { x -> 20 + if (x % 2 == 0) 1 else -1 }
        val matte = FloatArray(w * h) { i -> if (i / w < edge[i % w]) 1f else 0f }
        val smoothed = GuidedMatteFilter.gaussianBlur(matte, w, h, 1.5f)
        val boundary = (5 until w - 5).map { x -> (0 until h).last { y -> smoothed[y * w + x] >= .5f } + 1 }
        assertTrue("serration left: $boundary", boundary.max() - boundary.min() <= 1)
        assertEquals(20.0, boundary.average(), .6)
        // Flat regions keep full confidence.
        assertEquals(1f, smoothed[5 * w + 20], 1e-4f)
        assertEquals(0f, smoothed[35 * w + 20], 1e-4f)
    }
}
