package com.termex.replay15.ui.glass

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.widget.EditText

/**
 * Reusable GlassInput component for search and text fields:
 * Frosted translucent surface, 24dp rounded corners, subtle edge reflection,
 * clean typography with high readability.
 */
@SuppressLint("AppCompatCustomView") // The app intentionally uses platform Activity and native widgets.
class GlassInput @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle,
) : EditText(context, attrs, defStyleAttr) {

    init {
        background = GlassTheme.glassInputDrawable(context, radiusDp = 24f)
        setTextColor(GlassTheme.TEXT_PRIMARY)
        setHintTextColor(GlassTheme.TEXT_MUTED)
        textSize = 14f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        val padH = GlassTheme.dp(context, 16)
        val padV = GlassTheme.dp(context, 10)
        setPadding(padH, padV, padH, padV)
    }
}
