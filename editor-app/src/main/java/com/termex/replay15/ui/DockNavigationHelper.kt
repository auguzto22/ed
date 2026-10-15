package com.termex.replay15.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.recly.editor.R

enum class DockTab {
    HOME,
    RECORDINGS,
    EDITOR,
    SETTINGS,
    // Compatibilidade legada
    RECORD,
    LIBRARY
}

/**
 * Menu inferior normal (barra de ponta a ponta presa embaixo). O item ativo fica em branco e os
 * outros em cinza; não há mais ilha flutuante nem pílula deslizante.
 */
object DockNavigationHelper {

    private val ACTIVE = Color.parseColor("#FAFAF6")
    private val INACTIVE = Color.parseColor("#9A968C")

    private fun tabToIndex(tab: DockTab): Int = when (tab) {
        DockTab.HOME, DockTab.RECORD -> 0
        DockTab.RECORDINGS, DockTab.LIBRARY -> 1
        DockTab.EDITOR -> 2
        DockTab.SETTINGS -> 3
    }

    private fun select(containers: List<LinearLayout>, tab: DockTab) {
        val activeIndex = tabToIndex(tab)
        containers.forEachIndexed { index, container ->
            val isActive = index == activeIndex
            val tint = if (isActive) ACTIVE else INACTIVE
            (container.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(tint)
            (container.getChildAt(1) as? TextView)?.setTextColor(tint)
            container.isSelected = isActive
        }
    }

    fun bind(
        activity: Activity,
        currentTab: DockTab,
        onRecordClick: (() -> Unit)? = null,
        onTabChanged: ((DockTab) -> Unit)? = null
    ) {
        // A barra encosta na borda de baixo; o espaço dos botões do sistema vira padding interno.
        val bottomDockContainer = activity.findViewById<View>(R.id.bottomDockContainer)
        if (bottomDockContainer != null) {
            bottomDockContainer.setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val navInsets = insets.getInsets(android.view.WindowInsets.Type.navigationBars())
                    view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, navInsets.bottom)
                }
                insets
            }
            bottomDockContainer.requestApplyInsets()
        }

        val navHome = activity.findViewById<LinearLayout>(R.id.navHome) ?: return
        val navRecordings = activity.findViewById<LinearLayout>(R.id.navRecordings)
            ?: activity.findViewById<LinearLayout>(R.id.navLibrary) ?: return
        val navEditor = activity.findViewById<LinearLayout>(R.id.navEditor)
            ?: activity.findViewById<LinearLayout>(R.id.navRecord) ?: return
        val navSettings = activity.findViewById<LinearLayout>(R.id.navSettings) ?: return

        val containers = listOf(navHome, navRecordings, navEditor, navSettings)
        select(containers, currentTab)

        fun wire(view: LinearLayout, tab: DockTab) {
            view.setOnClickListener {
                select(containers, tab)
                onTabChanged?.invoke(tab)
            }
        }
        wire(navHome, DockTab.HOME)
        wire(navRecordings, DockTab.RECORDINGS)
        wire(navEditor, DockTab.EDITOR)
        wire(navSettings, DockTab.SETTINGS)
    }
}
