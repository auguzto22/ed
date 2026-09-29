package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import com.recly.editor.engine.R
import com.termex.replay15.editor.assets.*
import com.termex.replay15.editor.core.isEditorDebuggable
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.preview.AtlasPreviewManager

class TransitionTools(
    private val activity: Activity,
    private val project: () -> Project,
    private val position: () -> Long,
    private val seek: (Long) -> Unit,
    private val apply: (Project) -> Unit,
    private val previewApply: (Project) -> Unit,
    private val playPreviewRegion: (startUs: Long, endUs: Long) -> Unit,
) : AutoCloseable {

    private var activeDialog: Dialog? = null

    fun open(leftClipId: String, rightClipId: String) = with(activity) {
        val openStartMs = System.currentTimeMillis()
        val currentProject = project()
        val leftClip = currentProject.videos.firstOrNull { it.id == leftClipId } ?: return@with
        val rightClip = currentProject.videos.firstOrNull { it.id == rightClipId } ?: return@with

        val definitions = BuiltInTransitions.definitions(this)
        val existing = currentProject.transitionBetween(leftClipId, rightClipId)

        val transEnteringLeft = currentProject.transitions.firstOrNull { it.rightClipId == leftClipId }?.durationUs ?: 0L
        val transLeavingRight = currentProject.transitions.firstOrNull { it.leftClipId == rightClipId }?.durationUs ?: 0L
        val availableLeftHandleUs = (leftClip.durationUs - transEnteringLeft).coerceAtLeast(100_000L)
        val availableRightHandleUs = (rightClip.durationUs - transLeavingRight).coerceAtLeast(100_000L)
        val maxAllowedUs = minOf(availableLeftHandleUs, availableRightHandleUs, 3_000_000L).coerceAtLeast(100_000L)
        var selectedDef = existing?.let { trans -> definitions.firstOrNull { it.id == trans.transitionId } }
            ?: definitions.firstOrNull { it.id == "cross_dissolve" }
            ?: definitions.first()

        var currentDurationUs = (existing?.durationUs ?: selectedDef.defaultDurationUs).coerceIn(100_000L, maxAllowedUs)
        val currentParams = existing?.parameters?.toMutableMap() ?: mutableMapOf()
        var currentEasing = existing?.easing ?: Easing.SMOOTH

        var activeCategory = TransitionCatalog.CATEGORY_ALL
        var searchQuery = ""

        val body = column()
        lateinit var dialog: Dialog

        // Trigger preview region
        fun triggerPreview(newProject: Project) {
            previewApply(newProject)
            val rightIdx = newProject.videos.indexOfFirst { it.id == rightClipId }
            if (rightIdx >= 0) {
                val cutUs = newProject.startOf(rightIdx)
                val region = TransitionPreviewHelper.calculatePreviewRegion(cutUs, newProject.durationUs)
                seek(region.startUs)
                playPreviewRegion(region.startUs, region.endUs)
            }
        }

        // Apply change helper
        fun applyCurrent(preview: Boolean, play: Boolean = false) {
            val trans = TransitionInstance(
                id = existing?.id ?: newId(),
                transitionId = selectedDef.id,
                leftClipId = leftClipId,
                rightClipId = rightClipId,
                durationUs = currentDurationUs,
                parameters = currentParams.toMap(),
                easing = currentEasing,
            )
            val updated = project().withTransition(trans)
            if (preview) {
                if (play) {
                    triggerPreview(updated)
                } else {
                    previewApply(updated)
                }
            } else {
                apply(updated)
            }
        }

        // Search and category tabs container
        val searchBox = EditText(this).apply {
            hint = "Buscar transições..."
            textSize = 13f
            setTextColor(Color.WHITE)
            setHintTextColor(EditorStyle.MUTED)
            background = GradientDrawable().apply {
                setColor(EditorStyle.CARD)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), EditorStyle.BORDER)
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        body.addView(searchBox, LinearLayout.LayoutParams(-1, dp(40)).apply { bottomMargin = dp(8) })

        val tabsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val tabsRow = row()
        tabsScroll.addView(tabsRow)
        body.addView(tabsScroll, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val catalogContainer = column()
        body.addView(catalogContainer)

        val configContainer = column()
        body.addView(configContainer)

        fun renderConfig() {
            configContainer.removeAllViews()

            configContainer.addView(label("Configuração", 14f, EditorStyle.ACCENT).apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(8), 0, dp(4))
            })

            // Duration slider
            slider(
                configContainer,
                "Duração",
                (currentDurationUs / 10_000L).toInt(),
                (maxAllowedUs / 10_000L).toInt(),
                minimum = 10,
                format = { String.format(java.util.Locale.US, "%.2f s", it * 0.01f) },
                onStop = { progressVal ->
                    currentDurationUs = progressVal * 10_000L
                    applyCurrent(preview = true, play = false)
                },
            ) { progressVal ->
                currentDurationUs = progressVal * 10_000L
                applyCurrent(preview = true, play = false)
            }

            // Easing selector
            if (selectedDef.supportsEasing) {
                val easingRow = row()
                easingRow.addView(label("Curva:", 12f, EditorStyle.MUTED), LinearLayout.LayoutParams(-2, -2))
                val easings = listOf(
                    "Smooth" to Easing.SMOOTH,
                    "Linear" to Easing.LINEAR,
                    "In" to Easing.EASE_IN,
                    "Out" to Easing.EASE_OUT,
                )
                easings.forEach { (name, easing) ->
                    val isSelected = currentEasing == easing
                    val btn = action(name, accent = isSelected) {
                        currentEasing = easing
                        applyCurrent(preview = true, play = false)
                        renderConfig()
                    }
                    easingRow.addView(btn, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(4) })
                }
                configContainer.addView(easingRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(6) })
            }

            // Custom Parameters for selected definition
            selectedDef.parameters.forEach { param ->
                val currentVal = currentParams[param.id] ?: param.default
                val steps = 100
                val min = param.min
                val max = param.max
                val stepVal = ((currentVal - min) / (max - min) * steps).toInt().coerceIn(0, steps)

                slider(
                    configContainer,
                    param.name,
                    stepVal,
                    steps,
                    minimum = 0,
                    format = { step ->
                        val real = min + (step.toFloat() / steps) * (max - min)
                        if (param.id == "direction") {
                            when (real.toInt()) {
                                0 -> "Esquerda"
                                1 -> "Direita"
                                2 -> "Cima"
                                else -> "Baixo"
                            }
                        } else {
                            String.format(java.util.Locale.US, "%.2f", real)
                        }
                    },
                    onStop = { step ->
                        val real = min + (step.toFloat() / steps) * (max - min)
                        currentParams[param.id] = real
                        applyCurrent(preview = true, play = false)
                    },
                ) { step ->
                    val real = min + (step.toFloat() / steps) * (max - min)
                    currentParams[param.id] = real
                    applyCurrent(preview = true, play = false)
                }
            }

            // Action row: Preview, Remove & Apply
            val actions = row()
            actions.addView(action("Testar transição") {
                applyCurrent(preview = true, play = true)
            }, LinearLayout.LayoutParams(0, dp(44), 1f))

            if (project().transitionBetween(leftClipId, rightClipId) != null) {
                actions.addView(action("Remover") {
                    val updatedProject = project().withoutTransition(leftClipId, rightClipId)
                    apply(updatedProject)
                    previewApply(updatedProject)
                    dialog.dismiss()
                }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) })
            }

            actions.addView(action("Aplicar", accent = true) {
                applyCurrent(preview = false)
                dialog.dismiss()
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) })

            configContainer.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

        fun renderCatalog() {
            catalogContainer.removeAllViews()

            val recentIds = TransitionCatalog.getRecents(this)
            val favIds = TransitionCatalog.getFavorites(this)
            val filtered = TransitionCatalog.filter(definitions, activeCategory, searchQuery, recentIds, favIds)

            if (filtered.isEmpty()) {
                catalogContainer.addView(label("Nenhuma transição encontrada.", 12f, EditorStyle.MUTED))
                return
            }

            // Grid: 2 columns
            for (i in filtered.indices step 2) {
                val rowLayout = row()
                val itemA = filtered[i]
                val itemB = filtered.getOrNull(i + 1)

                fun buildCard(item: TransitionDefinition): View {
                    val card = column().apply {
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                        val isChosen = item.id == selectedDef.id
                        background = GradientDrawable().apply {
                            setColor(if (isChosen) EditorStyle.CARD_ELEVATED else EditorStyle.CARD)
                            cornerRadius = dp(12).toFloat()
                            setStroke(dp(1), if (isChosen) EditorStyle.ACCENT else EditorStyle.BORDER)
                        }
                    }

                    val header = row()
                    val isFav = TransitionCatalog.isFavorite(this, item.id)
                    val star = ImageView(this).apply {
                        setImageResource(if (isFav) R.drawable.ic_recly_star_filled else R.drawable.ic_recly_star_outline)
                        val pad = dp(3)
                        setPadding(pad, pad, pad, pad)
                        setOnClickListener {
                            TransitionCatalog.toggleFavorite(this@with, item.id)
                            renderCatalog()
                        }
                    }
                    header.addView(star, LinearLayout.LayoutParams(dp(22), dp(22)))
                    header.addView(label(item.category, 10.5f, EditorStyle.MUTED).apply { setPadding(dp(4), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
                    card.addView(header)

                    val imageFrame = FrameLayout(this).apply {
                        outlineProvider = object : android.view.ViewOutlineProvider() {
                            override fun getOutline(view: View, outline: android.graphics.Outline) {
                                outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat())
                            }
                        }
                        clipToOutline = true
                    }
                    val placeholder = View(this).apply {
                        background = GradientDrawable().apply {
                            setColor(0xFF222226.toInt())
                            cornerRadius = dp(8).toFloat()
                        }
                    }
                    imageFrame.addView(placeholder, FrameLayout.LayoutParams(-1, dp(64)))
                    val imageView = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                    imageFrame.addView(imageView, FrameLayout.LayoutParams(-1, dp(64)))
                    card.addView(imageFrame, LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(4); bottomMargin = dp(6) })

                    AtlasPreviewManager.getTransitionPreview(this@with, item.id) { drawable ->
                        imageView.setImageDrawable(drawable)
                        placeholder.visibility = View.GONE
                    }

                    card.addView(label(item.name, 13f, Color.WHITE).apply {
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        maxLines = 1
                    })

                    card.setOnClickListener {
                        selectedDef = item
                        TransitionCatalog.recordUsed(this, item.id)
                        applyCurrent(preview = true, play = true)
                        renderCatalog()
                        renderConfig()
                    }

                    return card
                }

                rowLayout.addView(buildCard(itemA), LinearLayout.LayoutParams(0, -2, 1f))
                if (itemB != null) {
                    rowLayout.addView(buildCard(itemB), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
                } else {
                    rowLayout.addView(View(this), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
                }
                catalogContainer.addView(rowLayout, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            }
        }

        fun renderTabs() {
            tabsRow.removeAllViews()
            TransitionCatalog.UI_CATEGORIES.forEach { cat ->
                val active = cat == activeCategory
                val btn = Button(this).apply {
                    text = cat
                    textSize = 12f
                    isAllCaps = false
                    setTextColor(if (active) Color.WHITE else EditorStyle.MUTED)
                    background = GradientDrawable().apply {
                        setColor(if (active) EditorStyle.CARD else Color.TRANSPARENT)
                        cornerRadius = dp(14).toFloat()
                        if (active) setStroke(dp(1), EditorStyle.ACCENT)
                    }
                    setPadding(dp(12), dp(4), dp(12), dp(4))
                    setOnClickListener {
                        activeCategory = cat
                        renderTabs()
                        renderCatalog()
                    }
                }
                tabsRow.addView(btn, LinearLayout.LayoutParams(-2, dp(32)).apply { marginEnd = dp(4) })
            }
        }

        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString().orEmpty()
                renderCatalog()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        renderTabs()
        renderCatalog()
        renderConfig()

        dialog = sheet("Transições", body)
        if (isEditorDebuggable()) {
            android.util.Log.d("ReclyPerf", "TRANSITIONS_GALLERY_OPENED timeMs=${System.currentTimeMillis() - openStartMs} shaders=0 mediaCodec=0 ${AtlasPreviewManager.dumpMetrics()}")
        }
        dialog.setOnDismissListener {
            previewApply(project())
            activeDialog = null
        }
        activeDialog = dialog
    }

    override fun close() {
        activeDialog?.dismiss()
        activeDialog = null
    }
}
