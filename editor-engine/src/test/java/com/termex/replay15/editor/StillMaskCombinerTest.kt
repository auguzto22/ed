package com.termex.replay15.editor

import com.termex.replay15.editor.backgroundremoval.StillMaskCombiner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StillMaskCombinerTest {
    private fun subject(x: Int, y: Int, w: Int, h: Int, value: Float = 1f) =
        StillMaskCombiner.Subject(x, y, w, h, FloatArray(w * h) { value })

    /** Person model claims the columns [from, to) of a 10x10 image (mask at the same size). */
    private fun personColumns(from: Int, to: Int) = FloatArray(100) { if (it % 10 in from until to) 1f else 0f }

    @Test
    fun keepsPersonAndDropsTouchingFurniture() {
        val person = subject(3, 0, 5, 10)
        val chair = subject(0, 4, 3, 6)
        val out = StillMaskCombiner.combine(10, 10, listOf(person, chair), personColumns(3, 8), 10, 10)!!
        assertEquals(1f, out[5 * 10 + 5], 0f)
        assertEquals(0f, out[5 * 10 + 1], 0f)
    }

    @Test
    fun keepsEverySubjectWhenThereIsNoPerson() {
        val pet = subject(0, 0, 3, 3)
        val toy = subject(6, 6, 3, 3)
        val out = StillMaskCombiner.combine(10, 10, listOf(pet, toy), FloatArray(100), 10, 10)!!
        assertEquals(1f, out[1 * 10 + 1], 0f)
        assertEquals(1f, out[7 * 10 + 7], 0f)
    }

    @Test
    fun usesSubjectEdgesNotTheCoarsePersonMask() {
        // Person model is a blurry blob wider than the subject; the output must follow the subject.
        val person = subject(4, 0, 2, 10)
        val out = StillMaskCombiner.combine(10, 10, listOf(person), personColumns(2, 8), 10, 10)!!
        assertEquals(0f, out[3], 0f)
        assertEquals(1f, out[4], 0f)
        assertEquals(0f, out[6], 0f)
    }

    @Test
    fun clipsSubjectsOutsideTheImageAndMergesWithMax() {
        val a = subject(-2, -2, 4, 4, .4f)
        val b = subject(0, 0, 2, 2, .9f)
        val out = StillMaskCombiner.combine(10, 10, listOf(a, b), null, 0, 0)!!
        assertEquals(.9f, out[0], 0f)
        assertEquals(0f, out[2], 0f)
    }

    @Test
    fun keepsSubjectsWhenThePersonIsNotOneOfThem() {
        // The selfie model "found" a person where no subject is (a dark shape). The real subject
        // must survive; this used to return null and erase dark product photos.
        val bag = subject(0, 0, 2, 10)
        val out = StillMaskCombiner.combine(10, 10, listOf(bag), personColumns(5, 9), 10, 10)!!
        assertEquals(1f, out[5 * 10 + 1], 0f)
        assertEquals(0f, out[5 * 10 + 6], 0f)
        assertNull(StillMaskCombiner.combine(10, 10, emptyList(), personColumns(5, 9), 10, 10))
    }

    @Test
    fun weaklyDetectedDarkSubjectIsRescaledAboveThePrecisionThreshold() {
        // Core at 0.5 (below the default 0.65 threshold), background inside the box at 0.1.
        val dark = StillMaskCombiner.Subject(0, 0, 10, 10, FloatArray(100) { if (it % 10 in 2..7) .5f else .1f })
        val out = StillMaskCombiner.combine(10, 10, listOf(dark), null, 0, 0)!!
        assertEquals(1f, out[5 * 10 + 5], 1e-4f)
        assertTrue(out[5 * 10 + 0] < .65f)
        // A confident subject is not touched.
        val sure = subject(0, 0, 4, 4, .95f)
        assertEquals(.95f, StillMaskCombiner.combine(10, 10, listOf(sure), null, 0, 0)!![0], 0f)
    }

    @Test
    fun emptyCutAndWeakPersonAreRecognised() {
        assertTrue(StillMaskCombiner.keepsAlmostNothing(FloatArray(10_000) { if (it < 20) 1f else 0f }))
        assertFalse(StillMaskCombiner.keepsAlmostNothing(FloatArray(10_000) { if (it < 2_000) 1f else 0f }))
        assertTrue(StillMaskCombiner.isConfidentPerson(FloatArray(100) { if (it < 20) .97f else 0f }))
        assertFalse(StillMaskCombiner.isConfidentPerson(FloatArray(100) { if (it < 20) .6f else 0f }))
    }

    @Test
    fun personMaskAtLowerResolutionIsSampledBilinearly() {
        // 2x2 person mask: right half is person. Image is 10x10.
        val lowRes = floatArrayOf(0f, 1f, 0f, 1f)
        val right = subject(6, 0, 4, 10)
        val left = subject(0, 0, 4, 10)
        val out = StillMaskCombiner.combine(10, 10, listOf(right, left), lowRes, 2, 2)!!
        assertEquals(1f, out[8], 0f)
        assertEquals(0f, out[1], 0f)
    }
}
