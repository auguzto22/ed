package com.termex.replay15.ui.glass

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Reusable GlassModal dialog/sheet builder with frosted white glass background,
 * 28dp rounded corners, fine specular border, high-contrast typography and subtle backdrop dimming.
 */
object GlassModal {

    fun showCustomModal(
        context: Context,
        title: String,
        subtitle: String? = null,
        setupBody: (LinearLayout, Dialog) -> Unit,
    ): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.let { win ->
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            win.setDimAmount(0.35f)
            win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(
                    0xF6FFFFFF.toInt(),
                    0xEAFFFFFF.toInt()
                )
                cornerRadius = GlassTheme.dp(context, 28f)
                setStroke(GlassTheme.dp(context, 1.2f).toInt(), 0xFFFFFFFF.toInt())
            }
            background = bg
            val p = GlassTheme.dp(context, 22)
            setPadding(p, p, p, p)
            elevation = GlassTheme.dp(context, 12f)
        }

        val titleView = TextView(context).apply {
            text = title
            textSize = 18f
            setTextColor(GlassTheme.TEXT_PRIMARY)
            setTypeface(typeface, Typeface.BOLD)
        }
        container.addView(titleView)

        if (!subtitle.isNullOrBlank()) {
            val subView = TextView(context).apply {
                text = subtitle
                textSize = 12.5f
                setTextColor(GlassTheme.TEXT_SECONDARY)
                setPadding(0, GlassTheme.dp(context, 4), 0, GlassTheme.dp(context, 16))
                setLineSpacing(GlassTheme.dp(context, 2).toFloat(), 1f)
            }
            container.addView(subView)
        } else {
            val spacer = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(1, GlassTheme.dp(context, 14))
            }
            container.addView(spacer)
        }

        val bodyLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        setupBody(bodyLayout, dialog)
        container.addView(bodyLayout)

        val closeBtn = Button(context).apply {
            text = "Fechar"
            isAllCaps = false
            textSize = 13.5f
            val bg = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
            }
            background = bg
            setTextColor(GlassTheme.TEXT_SECONDARY)
            setOnClickListener { dialog.dismiss() }
        }
        val closeParams = LinearLayout.LayoutParams(-1, GlassTheme.dp(context, 44)).apply {
            topMargin = GlassTheme.dp(context, 8)
        }
        container.addView(closeBtn, closeParams)

        scroll.addView(container)
        dialog.setContentView(scroll)
        dialog.show()
        return dialog
    }
}
