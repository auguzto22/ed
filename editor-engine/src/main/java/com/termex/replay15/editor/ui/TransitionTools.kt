package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.*
import com.recly.editor.engine.R
import com.termex.replay15.editor.assets.*
import com.termex.replay15.editor.core.isEditorDebuggable
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.preview.AtlasPreviewManager
import kotlin.math.roundToInt

/**
 * Transition panel for one cut, in the editor's canvas style.
 *
 * Everything the user touches only updates the preview (no history entries, no undo spam). The
 * project changes once, on "Aplicar", "Aplicar em todos os cortes" or "Remover"; closing the panel
 * any other way puts the preview back exactly as it was.
 */
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
        val original = project()
        val leftIndex = original.videos.indexOfFirst { it.id == leftClipId }
        if (leftIndex < 0 || original.videos.getOrNull(leftIndex + 1)?.id != rightClipId) return@with
        val definitions = BuiltInTransitions.definitions(this)
        val state = TransitionDraft(original, leftClipId, rightClipId, definitions)
        var committed = false
        var group = TransitionGroups.GROUPS.firstOrNull { g ->
            state.definition?.let { d -> TransitionGroups.items(g, definitions).any { it.id == d.id } } == true
        } ?: TransitionGroups.GROUPS.first()
        var groupLabel = group.label

        val body = column()
        lateinit var dialog: Dialog

        fun previewRegion(draft: Project): TransitionPreviewRegion =
            state.window(draft)?.let { TransitionPreviewHelper.calculatePreviewRegion(it, draft.durationUs) }
                ?: TransitionPreviewHelper.calculatePreviewRegion(state.cutUs(), draft.durationUs)

        /** Shows the current choice; [play] runs it once, otherwise parks on its middle frame. */
        fun preview(play: Boolean) {
            val draft = state.draftProject()
            previewApply(draft)
            if (play) {
                val region = previewRegion(draft)
                seek(region.startUs)
                playPreviewRegion(region.startUs, region.endUs)
            } else {
                seek(state.window(draft)?.midpointUs ?: state.cutUs())
            }
        }

        body.addView(label(cutDescription(original, leftIndex), 12f, EditorStyle.MUTED).apply {
            setPadding(0, 0, 0, dp(10))
        })

        val chipsRow = row()
        body.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipsRow)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val tilesRow = row().apply { gravity = Gravity.TOP }
        val tilesScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tilesRow)
        }
        body.addView(tilesScroll, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        val settings = column()
        body.addView(settings)
        val actions = row()
        body.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        lateinit var renderTiles: () -> Unit
        lateinit var renderSettings: () -> Unit
        lateinit var renderActions: () -> Unit

        fun groupsToShow(): List<TransitionGroups.Group> {
            val extra = mutableListOf<TransitionGroups.Group>()
            val favorites = TransitionCatalog.getFavorites(this)
            if (favorites.isNotEmpty()) extra += TransitionGroups.Group(FAVORITES, ids = definitions.map { it.id }.filter { it in favorites })
            val recents = TransitionCatalog.getRecents(this)
            if (recents.isNotEmpty()) extra += TransitionGroups.Group(RECENTS, ids = recents)
            return extra + TransitionGroups.GROUPS
        }

        fun renderChips() {
            chipsRow.removeAllViews()
            val groups = groupsToShow()
            group = groups.firstOrNull { it.label == groupLabel } ?: groups.first { it.label == TransitionGroups.GROUPS.first().label }
            groups.forEach { g ->
                chipsRow.addView(pillButton(g.label, g.label == groupLabel) {
                    groupLabel = g.label
                    renderChips()
                    renderTiles()
                    tilesScroll.scrollTo(0, 0)
                }, LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(6) })
            }
        }

        fun tile(name: String, chosen: Boolean, favorite: Boolean, fill: (ImageView, View) -> Unit,
                 longClick: (() -> Unit)?, click: () -> Unit): View = column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            contentDescription = name
            setPadding(dp(4), dp(4), dp(4), dp(6))
            background = RippleDrawable(android.content.res.ColorStateList.valueOf(0x22FFFFFF), GradientDrawable().apply {
                setColor(if (chosen) EditorStyle.CARD_ELEVATED else Color.TRANSPARENT)
                cornerRadius = dp(14).toFloat()
            }, null)
            val frame = FrameLayout(context).apply {
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
                    }
                }
                clipToOutline = true
                background = GradientDrawable().apply {
                    setColor(0xFF26262A.toInt())
                    cornerRadius = dp(10).toFloat()
                }
            }
            val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            val overlay = View(context)
            frame.addView(image, FrameLayout.LayoutParams(-1, -1))
            frame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
            if (favorite) {
                frame.addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_recly_star_filled)
                    imageTintList = android.content.res.ColorStateList.valueOf(EditorStyle.CAPTION)
                }, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(4), dp(4), 0) })
            }
            // Selected: red frame, the editor's colour for the chosen item.
            frame.foreground = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(if (chosen) 2.5f else 1f), if (chosen) EditorStyle.ACTION else EditorStyle.BORDER)
            }
            fill(image, overlay)
            addView(frame, LinearLayout.LayoutParams(dp(64), dp(64)))
            addView(label(name, 11f, if (chosen) EditorStyle.TEXT else EditorStyle.MUTED).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(0, dp(5), 0, 0)
                if (chosen) setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(dp(72), -2))
            setOnClickListener { click() }
            if (longClick != null) setOnLongClickListener { longClick(); true }
        }

        renderTiles = {
            tilesRow.removeAllViews()
            val favorites = TransitionCatalog.getFavorites(this)
            tilesRow.addView(tile("Nenhuma", state.definition == null, false, { image, _ ->
                image.setImageDrawable(EditorGlyph("Fechar", EditorStyle.MUTED, this))
                image.scaleType = ImageView.ScaleType.CENTER
            }, null) {
                state.select(null)
                renderTiles(); renderSettings(); renderActions()
                preview(play = true)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(2) })
            TransitionGroups.items(group, definitions).forEach { item ->
                tilesRow.addView(tile(item.name, item.id == state.definition?.id, item.id in favorites, { image, overlay ->
                    AtlasPreviewManager.getTransitionPreview(this, item.id) { drawable ->
                        image.setImageDrawable(drawable)
                        overlay.visibility = View.GONE
                    }
                }, longClick = {
                    TransitionCatalog.toggleFavorite(this, item.id)
                    renderChips(); renderTiles()
                }) {
                    state.select(item)
                    TransitionCatalog.recordUsed(this, item.id)
                    renderTiles(); renderSettings(); renderActions()
                    preview(play = true)
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(2) })
            }
            installEditorMotion(tilesRow)
        }

        fun segmented(parent: LinearLayout, title: String, options: List<String>, selected: Int, choose: (Int) -> Unit) {
            parent.addView(label(title, 13f, EditorStyle.MUTED).apply { setPadding(0, dp(10), 0, dp(6)) })
            val bar = row()
            options.forEachIndexed { index, option ->
                bar.addView(pillButton(option, index == selected) { choose(index) },
                    LinearLayout.LayoutParams(0, dp(40), 1f).apply { if (index > 0) marginStart = dp(6) })
            }
            parent.addView(bar)
        }

        renderSettings = {
            settings.removeAllViews()
            val definition = state.definition
            if (definition == null) {
                settings.addView(label(
                    if (state.hadTransition) "Sem transição: o corte volta a ser seco." else "Escolha uma transição para ver na prévia.",
                    12f, EditorStyle.MUTED).apply { setPadding(0, 0, 0, 0) })
            } else {
                // Duration, stepped in frames of the project.
                val durationSlider = slider(settings, "Duração", state.frames(), state.maxFrames(), state.minFrames(),
                    format = { frames -> state.durationLabel((frames * 1_000_000L / state.fps)) },
                    onStop = { frames -> state.setFrames(frames); preview(play = true) },
                ) { frames -> state.setFrames(frames); preview(play = false) }
                val presets = row()
                TransitionDraft.DURATION_PRESETS_US.forEachIndexed { index, us ->
                    val fits = us <= state.maxDurationUs()
                    val text = String.format(java.util.Locale("pt", "BR"), if (us % 1_000_000L == 0L) "%.0f s" else "%.2f s", us / 1e6)
                        .replace(",50 s", ",5 s")
                    presets.addView(pillButton(text, fits && kotlin.math.abs(state.durationUs - us) < 1_000_000L / state.fps) {
                        if (!fits) return@pillButton
                        state.setDurationUs(us)
                        durationSlider.progress = state.frames()
                        renderSettings()
                        preview(play = true)
                    }.apply { alpha = if (fits) 1f else 0.35f; isEnabled = fits },
                        LinearLayout.LayoutParams(0, dp(36), 1f).apply { if (index > 0) marginStart = dp(6) })
                }
                settings.addView(presets, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })

                segmented(settings, "Posição em relação ao corte",
                    listOf("Antes", "No corte", "Depois"),
                    TransitionDraft.ALIGNMENTS.indexOf(state.alignment)) { index ->
                    state.setAlignment(TransitionDraft.ALIGNMENTS[index])
                    renderSettings()
                    preview(play = true)
                }

                if (definition.supportsEasing) {
                    segmented(settings, "Curva", TransitionDraft.EASING_PRESETS.map { it.label },
                        TransitionDraft.EASING_PRESETS.indexOf(state.easing)) { index ->
                        state.easing = TransitionDraft.EASING_PRESETS[index]
                        renderSettings()
                        preview(play = true)
                    }
                }

                state.editableParameters.forEach { parameter ->
                    if (parameter.id == "invert") {
                        segmented(settings, parameter.name, listOf("Normal", "Invertido"),
                            if (state.valueOf(parameter.id) > 0.5f) 1 else 0) { index ->
                            state.setValue(parameter.id, index.toFloat())
                            renderSettings()
                            preview(play = true)
                        }
                        return@forEach
                    }
                    val steps = 100
                    fun real(step: Int) = parameter.min + step / steps.toFloat() * (parameter.max - parameter.min)
                    val initial = ((state.valueOf(parameter.id) - parameter.min) / (parameter.max - parameter.min) * steps)
                        .roundToInt().coerceIn(0, steps)
                    slider(settings, parameter.name, initial, steps, 0,
                        format = { step ->
                            if (parameter.id == "angle") "${Math.toDegrees(real(step).toDouble()).roundToInt()}°"
                            else String.format(java.util.Locale("pt", "BR"), "%.2f", real(step))
                        },
                        onStop = { step -> state.setValue(parameter.id, real(step)); preview(play = true) },
                    ) { step -> state.setValue(parameter.id, real(step)); preview(play = false) }
                }
            }
            installEditorMotion(settings)
        }

        renderActions = {
            actions.removeAllViews()
            val cuts = original.videos.size - 1
            if (state.hadTransition) {
                actions.addView(action("Remover") {
                    committed = true
                    apply(original.withoutTransition(leftClipId, rightClipId))
                    dialog.dismiss()
                }, LinearLayout.LayoutParams(0, dp(48), 0.8f).apply { marginEnd = dp(6) })
            }
            if (cuts > 1) {
                actions.addView(action(if (state.definition == null) "Remover de todos" else "Aplicar em todos") {
                    committed = true
                    apply(state.applyToAllCuts())
                    dialog.dismiss()
                }, LinearLayout.LayoutParams(0, dp(48), 1.2f).apply { marginEnd = dp(6) })
            }
            actions.addView(action("Aplicar", accent = true) {
                committed = true
                apply(state.draftProject())
                dialog.dismiss()
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            installEditorMotion(actions)
        }

        renderChips()
        renderTiles()
        renderSettings()
        renderActions()

        dialog = sheet("Transição", body)
        // Keep the preview fully visible above the panel: nothing is dimmed behind it.
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.setOnDismissListener {
            if (!committed) previewApply(project())
            activeDialog = null
        }
        activeDialog = dialog
        // Scroll the strip to the current transition.
        state.definition?.let { chosen ->
            val index = TransitionGroups.items(group, definitions).indexOfFirst { it.id == chosen.id }
            if (index > 2) tilesScroll.post { tilesScroll.scrollTo((index - 1) * dp(76), 0) }
        }
        if (state.hadTransition) preview(play = false)
        if (isEditorDebuggable()) {
            android.util.Log.d("ReclyPerf", "TRANSITIONS_PANEL_OPENED timeMs=${System.currentTimeMillis() - openStartMs} ${AtlasPreviewManager.dumpMetrics()}")
        }
    }

    private fun cutDescription(project: Project, leftIndex: Int): String {
        val left = project.videos[leftIndex]
        val right = project.videos[leftIndex + 1]
        fun short(name: String) = if (name.length > 18) name.take(17) + "…" else name
        return "Corte ${leftIndex + 1} de ${project.videos.size - 1} · ${short(left.name)} → ${short(right.name)} · ${timeLabel(project.startOf(leftIndex + 1))}"
    }

    override fun close() {
        activeDialog?.dismiss()
        activeDialog = null
    }

    private companion object {
        const val FAVORITES = "★ Favoritas"
        const val RECENTS = "Recentes"
    }
}
