package com.termex.replay15.editor

import com.termex.replay15.editor.ui.EditorStyle
import com.termex.replay15.editor.ui.toolColor
import com.termex.replay15.ui.BlitzAppearance
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Config. > Aparência: tema claro/escuro e botões coloridos/pretos. */
class EditorAppearanceTest {
    @After fun reset() = BlitzAppearance.applyInMemory(BlitzAppearance.Theme.DARK, BlitzAppearance.Buttons.COLORED)

    @Test fun darkThemeKeepsTheCurrentBlitzPalette() {
        BlitzAppearance.applyInMemory(BlitzAppearance.Theme.DARK, BlitzAppearance.Buttons.COLORED)
        assertEquals(0xFF000000.toInt(), EditorStyle.BG)
        assertEquals(0xFF111113.toInt(), EditorStyle.PANEL)
        assertEquals(0xFF1C1C1F.toInt(), EditorStyle.CARD_ELEVATED)
        assertEquals(0xFF2A2A2E.toInt(), EditorStyle.BORDER)
        assertEquals(0xFFF5F5F7.toInt(), EditorStyle.TEXT)
        assertEquals(0xFF8E8E93.toInt(), EditorStyle.MUTED)
        assertEquals(0xFFFFFFFF.toInt(), EditorStyle.ACCENT)
        assertEquals(0xFFFF5A6E.toInt(), EditorStyle.ACTION_TEXT)
        assertEquals(EditorStyle.TOOL_CUT, toolColor("Dividir"))
        assertEquals(EditorStyle.TRACK_TEXT, toolColor("Legendas"))
    }

    @Test fun lightThemeTextIsReadable() {
        BlitzAppearance.applyInMemory(BlitzAppearance.Theme.LIGHT, BlitzAppearance.Buttons.COLORED)
        assertTrue(contrast(EditorStyle.TEXT, EditorStyle.BG) >= 7.0)
        assertTrue(contrast(EditorStyle.TEXT, EditorStyle.PANEL) >= 7.0)
        assertTrue(contrast(EditorStyle.MUTED, EditorStyle.PANEL) >= 4.5)
        assertTrue(contrast(EditorStyle.ACTION_TEXT, EditorStyle.PANEL) >= 4.5)
        assertTrue(contrast(EditorStyle.CAPTION_TEXT, EditorStyle.BG) >= 4.5)
        assertTrue(contrast(EditorStyle.ON_ACCENT, EditorStyle.ACCENT) >= 7.0)
        // Ícones coloridos ficam mais fechados no claro para não sumir no branco.
        for (tool in listOf("Dividir", "Ajustar", "Texto", "Áudio", "Stickers", "Efeitos")) {
            assertTrue("$tool sem contraste", contrast(toolColor(tool), EditorStyle.PANEL) >= 3.0)
        }
    }

    @Test fun contentColorsDoNotChangeWithTheTheme() {
        BlitzAppearance.applyInMemory(BlitzAppearance.Theme.LIGHT, BlitzAppearance.Buttons.BLACK)
        // Vão para dentro do vídeo exportado: iguais nos dois temas.
        assertEquals(0xFFFFC94A.toInt(), EditorStyle.CAPTION)
        assertEquals(0xFFE5163A.toInt(), EditorStyle.ACTION)
        assertEquals(0xFFFFFFFF.toInt(), EditorStyle.WHITE)
        assertEquals(0xFF141414.toInt(), EditorStyle.INK)
    }

    @Test fun blackButtonsAreNeutralExceptDelete() {
        BlitzAppearance.applyInMemory(BlitzAppearance.Theme.DARK, BlitzAppearance.Buttons.BLACK)
        for (tool in listOf("Dividir", "Texto", "Áudio", "Efeitos", "Transição", "Auto Edit", "Ajustar")) {
            assertEquals(tool, EditorStyle.TEXT, toolColor(tool))
        }
        assertEquals(EditorStyle.DANGER, toolColor("Excluir"))
        BlitzAppearance.applyInMemory(BlitzAppearance.Theme.LIGHT, BlitzAppearance.Buttons.BLACK)
        assertEquals(EditorStyle.TEXT, toolColor("Dividir"))
        assertNotEquals(0xFFF5F5F7.toInt(), toolColor("Dividir"))
    }

    @Test fun changingTheAppearanceBumpsTheVersion() {
        val before = BlitzAppearance.version
        BlitzAppearance.applyInMemory(BlitzAppearance.Theme.LIGHT, BlitzAppearance.Buttons.COLORED)
        assertTrue(BlitzAppearance.needsRecreate(before))
    }

    private fun contrast(a: Int, b: Int): Double {
        fun channel(c: Int): Double { val v = c / 255.0; return if (v <= .03928) v / 12.92 else Math.pow((v + .055) / 1.055, 2.4) }
        fun lum(c: Int) = .2126 * channel((c shr 16) and 0xFF) + .7152 * channel((c shr 8) and 0xFF) + .0722 * channel(c and 0xFF)
        val la = lum(a); val lb = lum(b)
        return (maxOf(la, lb) + .05) / (minOf(la, lb) + .05)
    }
}
