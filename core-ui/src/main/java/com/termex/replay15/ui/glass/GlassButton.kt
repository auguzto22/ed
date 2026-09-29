package com.termex.replay15.ui.glass

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.widget.Button

/**
 * Reusable GlassButton supporting:
 * - PRIMARY: Sleek graphite/black pill (#111318) with crisp white text (as in Apple/iOS concept hero buttons).
 * - SECONDARY: Frosted white glass with specular outline and dark text.
 * - PILL: Interactive toggle pill for filters and duration selectors.
 */
@SuppressLint("AppCompatCustomView") // The app intentionally uses platform Activity and native widgets.
class GlassButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : Button(context, attrs, defStyleAttr) {

    enum class Style {
        PRIMARY,
        SECONDARY,
        PILL_ACTIVE,
        PILL_INACTIVE,
    }

    var buttonStyle: Style = Style.PRIMARY
        set(value) {
            field = value
            applyStyle()
        }

    init {
        isAllCaps = false
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        stateListAnimator = null
        val padH = GlassTheme.dp(context, 16)
        val padV = GlassTheme.dp(context, 10)
        setPadding(padH, padV, padH, padV)
        applyStyle()
    }

    fun setPrimary(primary: Boolean) {
        buttonStyle = if (primary) Style.PRIMARY else Style.SECONDARY
    }

    fun setPillState(active: Boolean) {
        buttonStyle = if (active) Style.PILL_ACTIVE else Style.PILL_INACTIVE
    }

    private fun applyStyle() {
        when (buttonStyle) {
            Style.PRIMARY -> {
                background = GlassTheme.glassButtonDrawable(context, isPrimary = true, radiusDp = 24f)
                setTextColor(GlassTheme.TEXT_ON_DARK)
                typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
                elevation = GlassTheme.dp(context, 2f)
            }
            Style.SECONDARY -> {
                background = GlassTheme.glassButtonDrawable(context, isPrimary = false, radiusDp = 24f)
                setTextColor(GlassTheme.TEXT_PRIMARY)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                elevation = GlassTheme.dp(context, 1f)
            }
            Style.PILL_ACTIVE -> {
                background = GlassTheme.glassPillDrawable(context, active = true, radiusDp = 22f)
                setTextColor(GlassTheme.TEXT_ON_DARK)
                typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
                elevation = GlassTheme.dp(context, 1.5f)
            }
            Style.PILL_INACTIVE -> {
                background = GlassTheme.glassPillDrawable(context, active = false, radiusDp = 22f)
                setTextColor(GlassTheme.TEXT_SECONDARY)
                typeface = Typeface.create("sans-serif-normal", Typeface.NORMAL)
                elevation = 0f
            }
        }
    }
}
