package com.termex.replay15.editor.render

import android.content.Context
import android.graphics.Typeface
import com.termex.replay15.editor.domain.TextFont
import com.termex.replay15.editor.font.FontRepository

object EditorFonts {
    fun get(context: Context, fontId: String, style: Int = Typeface.NORMAL): Typeface =
        FontRepository(context).typeface(fontId, style)

    fun get(context: Context, font: TextFont, style: Int = Typeface.NORMAL): Typeface =
        get(context, font.id, style)

    fun get(context: Context, fontId: String, weight: Int, italic: Boolean): Typeface =
        FontRepository(context).typefaceWithWeight(fontId, weight, italic)
}
