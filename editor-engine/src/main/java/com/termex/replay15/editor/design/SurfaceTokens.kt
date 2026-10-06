package com.termex.replay15.editor.design

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.Window
import androidx.annotation.StyleRes

/**
 * Helper utilities for building UI with the Recly design system.
 * Provides programmatic access to design tokens for code-based UI construction.
 */
object SurfaceTokens {

    /**
     * Creates a surface card drawable with the standard Recly card appearance.
     * Use this instead of inline shape definitions in code.
     */
    fun cardBackground(elevated: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            setColor(if (elevated) DesignTokens.CARD_ELEVATED else DesignTokens.CARD)
            setStroke(1, if (elevated) DesignTokens.BORDER_MEDIUM else DesignTokens.BORDER_SUBTLE)
            cornerRadius = dp(DesignTokens.RADIUS_MD).toFloat()
        }

    /**
     * Creates a panel/sheet background with standard Recly panel appearance.
     */
    fun panelBackground(): GradientDrawable =
        GradientDrawable().apply {
            setColor(DesignTokens.SURFACE)
            setStroke(1, DesignTokens.BORDER_SUBTLE)
            cornerRadius = dp(DesignTokens.RADIUS_LG).toFloat()
        }

    /**
     * Creates an elevated card with highlighted border (selected state).
     */
    fun cardSelected(): GradientDrawable =
        GradientDrawable().apply {
            setColor(DesignTokens.CARD_HIGHLIGHT)
            setStroke(1, DesignTokens.BORDER_BRIGHT)
            cornerRadius = dp(DesignTokens.RADIUS_MD).toFloat()
        }

    /**
     * Standard accent button background (white pill).
     */
    fun accentButton(): GradientDrawable =
        GradientDrawable().apply {
            setColor(DesignTokens.ACCENT_WHITE)
            cornerRadius = dp(DesignTokens.RADIUS_MD).toFloat()
        }

    /**
     * Standard secondary button background (dark pill with border).
     */
    fun secondaryButton(): GradientDrawable =
        GradientDrawable().apply {
            setColor(DesignTokens.CARD)
            setStroke(1, DesignTokens.BORDER_MEDIUM)
            cornerRadius = dp(DesignTokens.RADIUS_MD).toFloat()
        }

    /**
     * Search bar background.
     */
    fun searchBackground(): GradientDrawable =
        GradientDrawable().apply {
            setColor(DesignTokens.SURFACE_ELEVATED)
            setStroke(1, DesignTokens.BORDER_SUBTLE)
            cornerRadius = dp(DesignTokens.RADIUS_SM).toFloat()
        }

    /**
     * Chip background (for album chips, category pills).
     */
    fun chipBackground(selected: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            setColor(if (selected) DesignTokens.SURFACE_ELEVATED else Color.TRANSPARENT)
            setStroke(1, if (selected) DesignTokens.BORDER_BRIGHT else DesignTokens.BORDER_MEDIUM)
            cornerRadius = dp(DesignTokens.RADIUS_SM).toFloat()
        }

    /**
     * Standard selection overlay color (for thumbnails).
     */
    val selectionOverlayColor = DesignTokens.SELECTION_OVERLAY

    /**
     * Standard scrim color for dialogs.
     */
    val scrimColor = DesignTokens.SCRIM

    // ── Dialog helpers ──────────────────────────────────────────────────────────

    /**
     * Creates a bottom sheet dialog styled with Recly design tokens.
     */
    fun bottomSheet(context: Context, title: String, items: Array<CharSequence>,
                    checked: Int = -1, onSelect: (Int) -> Unit): AlertDialog {
        val builder = AlertDialog.Builder(context, com.termex.replay15.editor.design.SurfaceTokens.dialogStyle(context))
        builder.setTitle(title)
        builder.setSingleChoiceItems(items, checked) { dialog, which ->
            onSelect(which)
            dialog.dismiss()
        }
        builder.setNegativeButton("Cancelar") { d, _ -> d.dismiss() }
        return builder.create().apply {
            window?.setGravity(android.view.Gravity.BOTTOM)
        }
    }

    /**
     * Creates a Recly-styled alert dialog.
     */
    fun alert(context: Context, title: String, message: String,
              positive: String = "OK", negative: String? = null,
              onPositive: () -> Unit = {}, onNegative: (() -> Unit)? = null): AlertDialog {
        val builder = AlertDialog.Builder(context, dialogStyle(context))
        builder.setTitle(title)
        builder.setMessage(message)
        builder.setPositiveButton(positive) { d, _ -> d.dismiss(); onPositive() }
        negative?.let {
            builder.setNegativeButton(it) { d, _ -> d.dismiss(); onNegative?.invoke() }
        }
        return builder.create()
    }

    /**
     * Returns a dialog theme resource ID compatible with the AlertDialog.Builder style.
     */
    @StyleRes
    fun dialogStyle(context: Context): Int = android.R.style.Theme_DeviceDefault_Dialog_Alert

    /**
     * Apply frosted glass panel appearance to a view (backdrop + blur simulation).
     * Falls back to a semi-transparent dark background on older devices.
     */
    fun applyGlassPanel(view: View, cornerRadiusDp: Int = DesignTokens.RADIUS_LG,
                        elevationDp: Float = DesignTokens.ELEVATION_2) {
        view.background = GradientDrawable().apply {
            setColor(DesignTokens.SURFACE_ELEVATED)
            setStroke(1, DesignTokens.BORDER_SUBTLE)
            cornerRadius = dp(cornerRadiusDp).toFloat()
        }
        view.elevation = dp(elevationDp).toFloat()
    }

    /**
     * Apply glass card appearance.
     */
    fun applyGlassCard(view: View, cornerRadiusDp: Int = DesignTokens.RADIUS_MD,
                        elevationDp: Float = DesignTokens.ELEVATION_1) {
        view.background = cardBackground(elevated = false)
        view.elevation = dp(elevationDp).toFloat()
    }

    // ── Color utilities ─────────────────────────────────────────────────────────

    /**
     * Returns the ARGB color for a design token key.
     */
    fun color(key: String): Int = when (key) {
        "bg" -> DesignTokens.BG.toInt()
        "surface" -> DesignTokens.SURFACE.toInt()
        "card" -> DesignTokens.CARD.toInt()
        "border" -> DesignTokens.BORDER_MEDIUM.toInt()
        "text.primary" -> DesignTokens.TEXT_PRIMARY.toInt()
        "text.secondary" -> DesignTokens.TEXT_SECONDARY.toInt()
        "text.muted" -> DesignTokens.TEXT_DISABLED.toInt()
        "accent.white" -> DesignTokens.ACCENT_WHITE.toInt()
        "red" -> DesignTokens.RED.toInt()
        "green" -> DesignTokens.GREEN.toInt()
        "yellow" -> DesignTokens.YELLOW.toInt()
        "selection" -> DesignTokens.SELECTION_OVERLAY.toInt()
        else -> DesignTokens.TEXT_PRIMARY.toInt()
    }

    // ── Pixel conversion ────────────────────────────────────────────────────────

    private fun dp(dp: Int): Int = (dp * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
    private fun dp(dp: Float): Int = (dp * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
}
