package com.termex.replay15.editor

import com.termex.replay15.editor.domain.ChromaKeyState
import com.termex.replay15.editor.domain.StudioGrade
import com.termex.replay15.editor.domain.chromaKeyState
import com.termex.replay15.editor.domain.withChromaKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChromaKeyTest {
    @Test
    fun `legacy studio grade round trips through chroma key state`() {
        val state = ChromaKeyState(true, 0xFF123456.toInt(), .31f, .12f, .6f, -.03f)
        val roundTrip = StudioGrade().withChromaKey(state).chromaKeyState()
        assertEquals(state, roundTrip)
    }

    @Test
    fun `preview draw shader owns matte and spill suppression`() {
        val source = File("src/main/java/com/termex/replay15/editor/preview/engine/GpuPreviewRenderer.kt").readText()
        assertTrue(source.contains("uniform vec4 uChroma"))
        assertTrue(source.contains("smoothstep(threshold"))
        assertTrue(source.contains("dominance*uKeyEdge.y"))
    }
}
