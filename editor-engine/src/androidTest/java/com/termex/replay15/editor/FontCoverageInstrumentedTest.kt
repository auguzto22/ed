package com.termex.replay15.editor

import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.termex.replay15.editor.font.FontCatalog
import com.termex.replay15.editor.font.FontRepository
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FontCoverageInstrumentedTest {
    private val required = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789" +
        "áàâãéêíóôõúçÁÀÂÃÉÊÍÓÔÕÚÇ"

    @Test fun bundledFontsCoverPortugueseAndAreCached() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repository = FontRepository(context)
        FontCatalog.builtIns.filter { it.file.isNotBlank() }.forEach { asset ->
            val first = repository.typeface(asset.id)
            assertSame("Typeface must be reused for ${asset.id}", first, repository.typeface(asset.id))
            val paint = Paint().apply { typeface = first }
            required.forEach { character ->
                assertTrue("${asset.id} misses $character", paint.hasGlyph(character.toString()))
            }
        }
    }
}
