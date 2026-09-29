package com.termex.replay15.editor

import com.termex.replay15.editor.font.FontCatalog
import com.termex.replay15.editor.font.FontCategory
import com.termex.replay15.editor.font.FontSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FontCatalogTest {
    @Test fun `catalog ids are stable unique identifiers and never paths`() {
        val fonts = FontCatalog.builtIns
        assertEquals(fonts.size, fonts.map { it.id }.distinct().size)
        assertTrue(fonts.all { it.id.matches(Regex("[a-z0-9_]{1,80}")) })
        assertTrue(fonts.none { '/' in it.id || '.' in it.id })
    }

    @Test fun `every bundled font records license and Portuguese support`() {
        val bundled = FontCatalog.builtIns.filter { it.file.isNotBlank() }
        assertTrue(bundled.isNotEmpty())
        assertTrue(bundled.all { it.license.isNotBlank() })
        assertTrue(bundled.all { it.supportsPortuguese && it.missingPortugueseCharacters.isEmpty() })
    }

    @Test fun `all eleven Recly Originals are real catalog assets`() {
        val expected = setOf(
            "recly_sans_regular", "recly_wide_bold", "recly_poster_bold",
            "recly_pixel_regular", "recly_arcade_regular", "recly_mono_regular",
            "recly_signature_regular", "recly_brush_regular", "recly_gothic_regular",
            "recly_editorial_regular", "recly_urban_regular",
        )
        val originals = FontCatalog.builtIns.filter { it.isOriginalRecly }
        assertEquals(expected, originals.map { it.id }.toSet())
        assertTrue(originals.all { it.source == FontSource.RECLY_ORIGINAL })
        assertTrue(originals.all { it.file.endsWith(".ttf") && it.file.isNotBlank() })
        assertTrue(originals.all { FontCategory.RECLY_ORIGINALS in it.categories })
    }
}
