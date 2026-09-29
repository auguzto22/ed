package com.termex.replay15.ui.glass

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.View

/**
 * Design system tokens & utilities for the White Glassmorphism visual language.
 * Follows iOS concept aesthetics: white frosted glass surfaces (65%-90% opacity),
 * subtle specular edge strokes, smooth diffused shadows and high-contrast typography.
 */
object GlassTheme {

    // Background & Surface
    const val BACKGROUND = 0xFFF6F7F9.toInt()
    const val GLASS_CARD_72 = 0xB8FFFFFF.toInt() // ~72% opacity
    const val GLASS_CARD_88 = 0xE0FFFFFF.toInt() // ~88% opacity
    const val GLASS_CARD_94 = 0xF0FFFFFF.toInt() // ~94% opacity
    const val GLASS_INPUT = 0xCFFFFFFF.toInt()   // ~81% opacity
    const val GLASS_NAVBAR = 0xE8FFFFFF.toInt()  // ~91% opacity

    // Borders & Edge Reflections
    const val STROKE_SPECULAR = 0xE6FFFFFF.toInt()
    const val STROKE_MUTED = 0x0F000000.toInt()
    const val STROKE_ACTIVE = 0x332F6BFF.toInt()

    // Typography
    const val TEXT_PRIMARY = 0xFF111318.toInt()
    const val TEXT_SECONDARY = 0xFF6B7280.toInt()
    const val TEXT_MUTED = 0xFF9CA3AF.toInt()
    const val TEXT_ON_DARK = 0xFFFFFFFF.toInt()

    // Buttons & Highlights
    const val BUTTON_PRIMARY_START = 0xFF1E2128.toInt()
    const val BUTTON_PRIMARY_END = 0xFF111318.toInt()
    const val ACCENT_BLUE = 0xFF2F6BFF.toInt()
    const val ACCENT_BLUE_END = 0xFF1953E8.toInt()
    const val RECORDING_RED = 0xFFFF3B30.toInt()
    const val SUCCESS_GREEN = 0xFF34C759.toInt()
    const val WARNING_AMBER = 0xFFFF9500.toInt()

    fun dp(context: Context, value: Float): Float = value * context.resources.displayMetrics.density
    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun glassCardDrawable(
        context: Context,
        radiusDp: Float = 26f,
        elevated: Boolean = false,
    ): Drawable {
        val r = dp(context, radiusDp)
        val shape = GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            if (elevated) {
                colors = intArrayOf(0xF4FFFFFF.toInt(), 0xE8FFFFFF.toInt())
                setStroke(dp(context, 1f).toInt(), STROKE_MUTED)
            } else {
                colors = intArrayOf(0xD9FFFFFF.toInt(), 0xB8FFFFFF.toInt())
                setStroke(dp(context, 1f).toInt(), STROKE_SPECULAR)
            }
            cornerRadius = r
        }
        return shape
    }

    fun glassButtonDrawable(
        context: Context,
        isPrimary: Boolean,
        radiusDp: Float = 24f,
    ): Drawable {
        val r = dp(context, radiusDp)
        val shape = GradientDrawable().apply {
            if (isPrimary) {
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(BUTTON_PRIMARY_START, BUTTON_PRIMARY_END)
                setStroke(dp(context, 1f).toInt(), 0xFF2C303B.toInt())
            } else {
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(0xE6FFFFFF.toInt(), 0xC8FFFFFF.toInt())
                setStroke(dp(context, 1f).toInt(), STROKE_SPECULAR)
            }
            cornerRadius = r
        }
        val rippleColor = if (isPrimary) 0x33FFFFFF else 0x1A000000
        return RippleDrawable(ColorStateList.valueOf(rippleColor), shape, null)
    }

    fun glassPillDrawable(
        context: Context,
        active: Boolean,
        radiusDp: Float = 22f,
    ): Drawable {
        val r = dp(context, radiusDp)
        val shape = GradientDrawable().apply {
            if (active) {
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(BUTTON_PRIMARY_START, BUTTON_PRIMARY_END)
                setStroke(dp(context, 1f).toInt(), 0xFF2C303B.toInt())
            } else {
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(0xE6FFFFFF.toInt(), 0xCFFFFFFF.toInt())
                setStroke(dp(context, 1f).toInt(), STROKE_SPECULAR)
            }
            cornerRadius = r
        }
        val rippleColor = if (active) 0x33FFFFFF else 0x1A000000
        return RippleDrawable(ColorStateList.valueOf(rippleColor), shape, null)
    }

    fun glassInputDrawable(
        context: Context,
        radiusDp: Float = 24f,
    ): Drawable {
        val r = dp(context, radiusDp)
        return GradientDrawable().apply {
            setColor(GLASS_INPUT)
            cornerRadius = r
            setStroke(dp(context, 1f).toInt(), 0xE0FFFFFF.toInt())
        }
    }

    fun applyGlassBackdropBlur(view: View, blurRadiusPx: Float = 24f) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                view.setRenderEffect(RenderEffect.createBlurEffect(blurRadiusPx, blurRadiusPx, Shader.TileMode.CLAMP))
            } catch (_: Throwable) {
                // Graceful fallback on hardware without RenderEffect support
            }
        }
    }
}
