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
}
