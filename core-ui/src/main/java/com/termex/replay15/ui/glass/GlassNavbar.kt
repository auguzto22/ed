package com.termex.replay15.ui.glass

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.widget.LinearLayout

/**
 * Reusable GlassNavbar component:
 * Floating pill navigation dock with frosted glass background,
 * 36dp rounded corners, fine specular edge and diffuse soft shadow.
 */
class GlassNavbar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    init {
        orientation = HORIZONTAL
        val bg = GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            colors = intArrayOf(
                0xF0FFFFFF.toInt(),
                0xE4FFFFFF.toInt(),
                0xD0FFFFFF.toInt()
            )
            cornerRadius = GlassTheme.dp(context, 36f)
            setStroke(GlassTheme.dp(context, 1.2f).toInt(), 0xFFFFFFFF.toInt())
        }
        background = bg
        elevation = GlassTheme.dp(context, 8f)
    }
}
