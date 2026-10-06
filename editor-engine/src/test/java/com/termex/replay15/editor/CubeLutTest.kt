package com.termex.replay15.editor

import com.termex.replay15.editor.media.CubeLut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CubeLutTest {
    @Test
    fun `zero strength is identity and full strength uses lut`() {
        val white = CubeLut(2, List(8) { floatArrayOf(1f, 1f, 1f) })
        val identity = white.pixels(0f)
        val full = white.pixels(1f)
        assertEquals(0xFF000000.toInt(), identity.first())
        assertEquals(0xFFFFFFFF.toInt(), identity.last())
        assertTrue(full.all { it == 0xFFFFFFFF.toInt() })
    }

    @Test
    fun `invalid cube shape and strength are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CubeLut(2, List(7) { floatArrayOf(0f, 0f, 0f) })
        }
        val valid = CubeLut(2, List(8) { floatArrayOf(0f, 0f, 0f) })
        assertThrows(IllegalArgumentException::class.java) { valid.pixels(Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { valid.pixels(1.1f) }
    }

    @Test
    fun `trilinear lookup preserves identity and blends by intensity`() {
        val identity = CubeLut(2, List(8) { index ->
            floatArrayOf((index % 2).toFloat(), (index / 2 % 2).toFloat(), (index / 4).toFloat())
        })
        val input = floatArrayOf(.23f, .67f, .41f)
        val full = identity.apply(input[0], input[1], input[2])
        assertEquals(input[0], full[0], .0001f)
        assertEquals(input[1], full[1], .0001f)
        assertEquals(input[2], full[2], .0001f)
        val pixel = 0xFF336699.toInt()
        assertEquals(pixel, identity.applyPixel(pixel, 0f))
        assertEquals(pixel, identity.applyPixel(pixel, 1f))
        val white = CubeLut(2, List(8) { floatArrayOf(1f, 1f, 1f) })
        assertEquals(0xFF808080.toInt(), white.applyPixel(0xFF000000.toInt(), .5f))
    }
}
