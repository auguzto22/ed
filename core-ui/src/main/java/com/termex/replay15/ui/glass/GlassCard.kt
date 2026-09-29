package com.termex.replay15.ui.glass

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Reusable GlassCard component with frosted glass background,
 * rounded corners (24-28dp), subtle specular edge stroke and soft elevation.
 */
class GlassCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    var isElevated: Boolean = false
        set(value) {
            field = value
            applyGlassBackground()
        }

    init {
        applyGlassBackground()
        elevation = GlassTheme.dp(context, 3.5f)
    }

    fun setCornerRadiusDp(radiusDp: Float) {
        background = GlassTheme.glassCardDrawable(context, radiusDp = radiusDp, elevated = isElevated)
    }

    private fun applyGlassBackground() {
        background = GlassTheme.glassCardDrawable(context, radiusDp = 26f, elevated = isElevated)
    }
}
