package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.Dialog
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.*
import com.termex.replay15.editor.animation.*
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.render.EditorFonts
import java.util.concurrent.Executors

class AnimationTools(private val activity: Activity, private val project: () -> Project,
    private val apply: (Project) -> Unit, private val pick: () -> Unit) : AutoCloseable {
    private val repository = AnimationRepository(activity)
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    private fun report(message: String) { if (!closed) Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }

    /** One collection shown as a chip at the top of the library. */
    private class Shelf(val name: String, val filter: (AnimationPreset) -> Boolean)

    fun open(clipId: String) {
        if (closed) return
        worker.execute {
            val result = runCatching { repository.list() }
            activity.runOnUiThread { if (!closed && !activity.isFinishing) result.fold({ library(clipId, it) }, { report("Não foi possível abrir as animações") }) }
        }
    }

    /**
     * Library as a searchable grid of live previews, filtered by chips (favorites, recents and
     * one chip per category label). Tiles are added a page at a time: the catalog has hundreds of
     * presets and each tile animates.
     */
    private fun library(clipId: String, definitions: List<AnimationPreset>) = with(activity) {
        val body = column(); lateinit var dialog: Dialog
        val collections = listOf(
            Shelf("Todas") { true },
            Shelf("Favoritas") { repository.favorite(it.id) },
            Shelf("Recentes") { it.id in repository.recent() },
        ) + definitions.map { it.category.displayLabel() }.distinct().map { name ->
            Shelf(name) { it.category.displayLabel() == name }
        }
        var current = collections.first()
        var limit = PAGE

        val search = searchField("Buscar animação (ex.: mola, zoom, esquerda)")
        body.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        val (scroller, chips) = chipScroller()
        body.addView(scroller, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        val count = label("", 12f, EditorStyle.MUTED).apply { setPadding(dp(2), dp(6), dp(2), dp(4)) }
        body.addView(count)
        val grid = GridLayout(this).apply { columnCount = COLUMNS; useDefaultMargins = false }
        body.addView(grid, LinearLayout.LayoutParams(-1, -2))
        val more = action("Mostrar mais") {}
        body.addView(more, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        body.addView(action("Importar animação (.json)") { dialog.dismiss(); pick() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        fun render() {
            val query = searchKey(search.text.toString())
            val shown = definitions.filter { current.filter(it) && (query.isEmpty() || searchKey(it.name).contains(query)) }
            count.text = when (shown.size) { 0 -> "Nenhuma animação"; 1 -> "1 animação"; else -> "${shown.size} animações" }
            grid.removeAllViews()
            if (shown.isEmpty()) {
                val message = if (current.name == "Favoritas" && query.isEmpty()) "Toque e segure uma animação para favoritar."
                    else "Nada encontrado. Tente outra busca ou coleção."
                grid.addView(emptyState(message), GridLayout.LayoutParams().apply {
                    width = 0; columnSpec = GridLayout.spec(0, COLUMNS, 1f)
                })
            }
            shown.take(limit).forEach { preset ->
                grid.addView(tile(preset) { dialog.dismiss(); configure(clipId, preset) }, GridLayout.LayoutParams().apply {
                    width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(4), dp(4), dp(4), dp(6))
                })
            }
            more.visibility = if (shown.size > limit) View.VISIBLE else View.GONE
            more.text = "Mostrar mais (${shown.size - limit})"
        }
        fun renderChips() {
            chips.removeAllViews()
            collections.forEach { collection ->
                chips.addChip(chip(collection.name, collection === current) {
                    current = collection; limit = PAGE; renderChips(); render()
                })
            }
        }
        more.setOnClickListener { limit += PAGE; render() }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { limit = PAGE; render() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        renderChips(); render()
        dialog = sheet("Animações", body, maxHeightRatio = .82f,
            subtitle = "Viram keyframes editáveis do clipe. Toque para aplicar.")
    }

    /** Grid tile: live preview, name, and a star when the preset is a favorite. */
    private fun tile(preset: AnimationPreset, onClick: () -> Unit): View = with(activity) {
        val box = column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(8))
            isClickable = true; isFocusable = true
            contentDescription = "${preset.name}, ${preset.category.displayLabel()}"
            background = editorRipple(editorShape(EditorStyle.CARD_ELEVATED, 16, EditorStyle.HAIRLINE))
        }
        val frame = FrameLayout(this)
        frame.addView(AnimationPreviewView(this, preset), FrameLayout.LayoutParams(-1, -1))
        val star = ImageView(this).apply {
            setImageResource(com.recly.editor.engine.R.drawable.ic_recly_star_filled)
            setColorFilter(0xFFFFD23F.toInt())
            visibility = if (repository.favorite(preset.id)) View.VISIBLE else View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        frame.addView(star, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(5), dp(5), 0) })
        box.addView(frame, LinearLayout.LayoutParams(-1, dp(76)))
        box.addView(label(preset.name, 11.5f, EditorStyle.TEXT).apply {
            gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(2), dp(6), dp(2), 0)
            typeface = EditorFonts.get(this@with, TextFont.OUTFIT, android.graphics.Typeface.BOLD)
        })
        box.setOnClickListener { onClick() }
        box.setOnLongClickListener {
            repository.toggleFavorite(preset.id)
            val favorite = repository.favorite(preset.id)
            star.visibility = if (favorite) View.VISIBLE else View.GONE
            box.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            Toast.makeText(this, if (favorite) "Adicionada às favoritas" else "Removida das favoritas", Toast.LENGTH_SHORT).show()
            true
        }
        installEditorMotion(box)
        box
    }

    private fun configure(clipId: String, preset: AnimationPreset) = with(activity) {
        project().allVideos.firstOrNull { it.id == clipId } ?: return@with
        val body = column(); lateinit var dialog: Dialog
        val loop = preset.category == AnimationCategory.LOOP
        // Tenths of a second: entrance/exit 0.1–5 s, loop period 0.3–10 s.
        var tenths = if (loop) 20 else 6
        val preview = AnimationPreviewView(this, preset, tenths * 100L)
        body.addView(FrameLayout(this).apply {
            setPadding(dp(4), dp(8), dp(4), dp(4))
            addView(preview, FrameLayout.LayoutParams(-1, dp(168)))
        })
        val error = label("", 12f, EditorStyle.ACTION_TEXT).apply { visibility = View.GONE }
        slider(body, if (loop) "Período de cada repetição" else "Duração", tenths, if (loop) 100 else 50, minimum = if (loop) 3 else 1,
            format = { String.format(java.util.Locale.ROOT, "%.1f s", it / 10f) }) {
            tenths = it; preview.durationMs = it * 100L; error.visibility = View.GONE
        }
        body.addView(error)
        val favoriteRow = row().apply { setPadding(0, dp(10), 0, 0) }
        val on = repository.favorite(preset.id)
        favoriteRow.addChip(chip(if (on) "★  Nas favoritas" else "☆  Favoritar", on) { toggleFavoriteChip(preset, favoriteRow) })
        body.addView(favoriteRow)
        body.addView(action("Aplicar animação", true) {
            runCatching {
                val next = project().mapVideo(clipId) { preset.applyTo(it, tenths * SECOND / 10L) }
                apply(next); repository.used(preset.id)
            }.fold({ dialog.dismiss() }, {
                error.text = it.message ?: "Não foi possível aplicar a animação"; error.visibility = View.VISIBLE
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        body.addView(label("Substitui os keyframes no trecho da animação; seus outros pontos são preservados. Desfazer volta ao estado anterior.",
            11.5f, EditorStyle.MUTED).apply { setPadding(dp(2), dp(10), dp(2), 0) })
        body.addView(label("Licença: ${preset.license}", 11f, EditorStyle.DISABLED).apply { setPadding(dp(2), dp(4), dp(2), 0) })
        dialog = sheet(preset.name, body, subtitle = preset.category.displayLabel())
    }

    /** Rebuilds the favorite chip so its selected look always matches the stored state. */
    private fun toggleFavoriteChip(preset: AnimationPreset, holder: LinearLayout): Unit = with(activity) {
        repository.toggleFavorite(preset.id)
        val on = repository.favorite(preset.id)
        holder.removeAllViews()
        holder.addChip(chip(if (on) "★  Nas favoritas" else "☆  Favoritar", on) { toggleFavoriteChip(preset, holder) })
    }

    fun importPackage(uri: Uri) {
        if (closed) return
        worker.execute {
            val result = runCatching {
                activity.contentResolver.openInputStream(uri)?.use { repository.install(it) } ?: error("Arquivo indisponível")
            }
            activity.runOnUiThread { if (!closed) result.fold({ report("Animação instalada: ${it.name}") }, { report("Pacote recusado: ${it.message}") }) }
        }
    }
    override fun close() { closed = true; worker.shutdownNow() }

    private companion object {
        const val COLUMNS = 3
        const val PAGE = 30
    }
}

/** Category name with Portuguese accents, for display only (the enum labels are persisted IDs elsewhere). */
fun AnimationCategory.displayLabel(): String = when (this) {
    AnimationCategory.IN, AnimationCategory.ENTRANCE -> "Entrada"
    AnimationCategory.OUT, AnimationCategory.EXIT -> "Saída"
    AnimationCategory.LOOP -> "Repetição"
    AnimationCategory.CELEBRATION -> "Celebração"
    else -> label
}
