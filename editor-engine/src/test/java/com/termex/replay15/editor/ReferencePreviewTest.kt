package com.termex.replay15.editor

import com.termex.replay15.editor.domain.VideoFilter
import com.termex.replay15.editor.preview.ReferencePreviewFrames
import com.termex.replay15.editor.render.FilterColorMath
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Prévias tipo GIF das galerias, feitas sobre o vídeo/foto de referência. */
class ReferencePreviewTest {

    private fun asset(relative: String): File =
        sequenceOf(File("src/main/assets/editor/$relative"), File("editor-engine/src/main/assets/editor/$relative"))
            .firstOrNull { it.exists() } ?: File("src/main/assets/editor/$relative")

    /** A matriz do ColorMatrixColorFilter precisa dar a mesma cor que o filtro de verdade. */
    @Test
    fun colorMatrixMatchesFilterMathForEveryFilter() {
        val steps = listOf(0f, .13f, .37f, .5f, .71f, .94f, 1f)
        VideoFilter.entries.forEach { filter ->
            val m = FilterColorMath.colorMatrixFor(filter)
            assertEquals(20, m.size)
            for (r in steps) for (g in steps) for (b in steps) {
                val expected = FilterColorMath.applyFilter(r, g, b, filter, 1f)
                val src = floatArrayOf(r, g, b)
                for (row in 0..2) {
                    val v = (m[row * 5] * src[0] + m[row * 5 + 1] * src[1] + m[row * 5 + 2] * src[2] + m[row * 5 + 4] / 255f)
                        .coerceIn(0f, 1f)
                    assertEquals("${filter.name} canal $row em ($r,$g,$b)", expected[row], v, 1e-4f)
                }
            }
            assertEquals("${filter.name}: alfa intacto", 1f, m[18], 0f)
        }
    }

    @Test
    fun referenceLoopShipsAsVerticalStrip() {
        val file = asset("previews/reference/reference_loop.webp")
        assertTrue("faltou ${file.path} (tools/assets/reference_previews.py)", file.isFile)
        val bytes = file.readBytes()
        assertEquals("RIFF", String(bytes, 0, 4)); assertEquals("WEBP", String(bytes, 8, 4))
        // VP8 com perdas: largura/altura em 14 bits a partir do byte 26.
        assertEquals("VP8 ", String(bytes, 12, 4))
        val w = (bytes[26].toInt() and 0xFF) or ((bytes[27].toInt() and 0x3F) shl 8)
        val h = (bytes[28].toInt() and 0xFF) or ((bytes[29].toInt() and 0x3F) shl 8)
        assertEquals(ReferencePreviewFrames.FRAME_COUNT * w, h)
    }

    @Test
    fun everyBuiltInTransitionHasAnAnimatedPreview() {
        val catalog = JSONArray(asset("transitions.json").readText())
        val missing = (0 until catalog.length()).map { catalog.getJSONObject(it).getString("id") }
            .filterNot { asset("previews/reference/transitions/$it.webp").isFile }
        assertTrue("transições sem prévia animada: $missing", missing.isEmpty())
    }

    @Test
    fun effectTimeAdvancesWithTheLoop() {
        val times = (0 until ReferencePreviewFrames.FRAME_COUNT).map(ReferencePreviewFrames::timeSecAt)
        assertEquals(.5f, times.first(), 1e-6f)
        assertTrue(times.zipWithNext().all { (a, b) -> b > a })
    }
}
