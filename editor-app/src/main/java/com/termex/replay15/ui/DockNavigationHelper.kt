package com.termex.replay15.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.recly.core.media.ReclyMediaContract
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

object DockNavigationHelper {

    class LiquidNavAnimator(
        private val pill: View,
        private val containers: List<LinearLayout>,
        private val activity: Activity
    ) {
        private var currentTab: DockTab = DockTab.EDITOR
        private var animatorSet: AnimatorSet? = null

        private fun tabToIndex(tab: DockTab): Int = when (tab) {
            DockTab.HOME, DockTab.RECORD -> 0
            DockTab.RECORDINGS, DockTab.LIBRARY -> 1
            DockTab.EDITOR -> 2
            DockTab.SETTINGS -> 3
        }

        fun select(tab: DockTab, animate: Boolean = true) {
            val fromIndex = tabToIndex(currentTab)
            val toIndex = tabToIndex(tab)
            val fromView = containers.getOrNull(fromIndex) ?: return
            val toView = containers.getOrNull(toIndex) ?: return

            animatorSet?.cancel()

            pill.post {
                val targetWidth = (toView.width - 8).coerceAtLeast(44)
                val params = pill.layoutParams
                if (params.width != targetWidth) {
                    params.width = targetWidth
                    pill.layoutParams = params
                }

                val fromX = fromView.left + (fromView.width - targetWidth) / 2f
                val toX = toView.left + (toView.width - targetWidth) / 2f

                if (!animate || fromIndex == toIndex) {
                    pill.translationX = toX
                    pill.scaleX = 1f
                    pill.scaleY = 1f
                } else {
                    pill.pivotX = if (toIndex > fromIndex) 0f else targetWidth.toFloat()

                    val scaleXAnim = ObjectAnimator.ofFloat(pill, View.SCALE_X, 1f, 1.20f, 1.08f, 0.99f, 1f)
                    val scaleYAnim = ObjectAnimator.ofFloat(pill, View.SCALE_Y, 1f, 0.92f, 0.96f, 1.01f, 1f)
                    val transAnim = ObjectAnimator.ofFloat(pill, View.TRANSLATION_X, fromX, toX)

                    val interpolator = PathInterpolator(0.22f, 0.85f, 0.22f, 1f)
                    listOf(scaleXAnim, scaleYAnim, transAnim).forEach { anim ->
                        anim.duration = 380L
                        anim.interpolator = interpolator
                    }

                    animatorSet = AnimatorSet().apply {
                        playTogether(scaleXAnim, scaleYAnim, transAnim)
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                pill.pivotX = targetWidth / 2f
                            }
                        })
                        start()
                    }
                }
            }

            updateItemStyles(tab)
            currentTab = tab
        }

        private fun updateItemStyles(activeTab: DockTab) {
            val activeIndex = tabToIndex(activeTab)
            containers.forEachIndexed { index, container ->
                val isActive = index == activeIndex
                val icon = container.getChildAt(0) as? ImageView
                val text = container.getChildAt(1) as? TextView
                val tint = if (isActive) Color.parseColor("#F5F5F5") else Color.parseColor("#A5A5AA")
                icon?.imageTintList = ColorStateList.valueOf(tint)
                text?.setTextColor(tint)
                val scale = if (isActive) 1.04f else 1f
                container.animate().scaleX(scale).scaleY(scale).setDuration(180).start()
            }
        }
    }

    fun bind(
        activity: Activity,
        currentTab: DockTab,
        onRecordClick: (() -> Unit)? = null,
        onTabChanged: ((DockTab) -> Unit)? = null
    ) {
        val bottomDock = activity.findViewById<View>(R.id.bottomDock)
        if (bottomDock != null) {
            GlassEffectHelper.applyGlassDock(bottomDock, cornerRadiusDp = 34f, elevationDp = 14f)
        }

        val bottomDockContainer = activity.findViewById<View>(R.id.bottomDockContainer)
        if (bottomDockContainer != null) {
            bottomDockContainer.setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val navInsets = insets.getInsets(android.view.WindowInsets.Type.navigationBars())
                    val density = activity.resources.displayMetrics.density
                    val baseMargin = (18 * density).toInt()
                    val lp = view.layoutParams as? ViewGroup.MarginLayoutParams
                    if (lp != null) {
                        lp.bottomMargin = baseMargin + navInsets.bottom
                        view.layoutParams = lp
                    }
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
        val pill = activity.findViewById<View>(R.id.liquidPill) ?: return

        val containers = listOf(navHome, navRecordings, navEditor, navSettings)
        val animator = LiquidNavAnimator(pill, containers, activity)
        animator.select(currentTab, animate = false)

        // 1. Início / Gravador -> Abre o app Recly Recorder
        navHome.setOnClickListener {
            animator.select(DockTab.HOME)
            onTabChanged?.invoke(DockTab.HOME)
            val launchIntent = activity.packageManager.getLaunchIntentForPackage(ReclyMediaContract.RECORDER_PACKAGE_NAME)
            if (launchIntent != null) {
                activity.startActivity(launchIntent)
            } else {
                android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Recly Recorder")
                    .setMessage("O aplicativo Recly Recorder não foi encontrado neste aparelho. Deseja obtê-lo para gravar telas e replays?")
                    .setPositiveButton("Instalar / Abrir") { _, _ ->
                        val marketIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=${ReclyMediaContract.RECORDER_PACKAGE_NAME}")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching {
                            activity.startActivity(marketIntent)
                        }.onFailure {
                            val webIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=${ReclyMediaContract.RECORDER_PACKAGE_NAME}")).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            runCatching { activity.startActivity(webIntent) }
                        }
                    }
                    .setNegativeButton("Agora não", null)
                    .show()
            }
        }

        // 2. Gravações (Vídeos)
        navRecordings.setOnClickListener {
            animator.select(DockTab.RECORDINGS)
            onTabChanged?.invoke(DockTab.RECORDINGS)
        }

        // 3. Editor (Projetos)
        navEditor.setOnClickListener {
            animator.select(DockTab.EDITOR)
            onTabChanged?.invoke(DockTab.EDITOR)
        }

        // 4. Configurações
        navSettings.setOnClickListener {
            animator.select(DockTab.SETTINGS)
            onTabChanged?.invoke(DockTab.SETTINGS)
        }
    }
}
