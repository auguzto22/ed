package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.termex.replay15.editor.domain.StickerClip
import com.termex.replay15.editor.domain.StickerEditing
import com.termex.replay15.editor.transform.TransformState
import kotlin.math.roundToInt

/**
 * Básico do sticker, no jeito do CapCut: abas Posição · Zoom · Girar · Opacidade, cada valor numa
 * régua que corre sob o ponteiro, "Redefinir" à esquerda e ✓ à direita.
 *
 * A posição aparece em pixels do quadro a partir do centro (X para a direita, Y para cima), como
 * o CapCut mostra "Eixo X -433". Com keyframes, a mudança vai para o keyframe no cursor (o mesmo
 * que o arraste na prévia faz).
 */
object StickerBasicTools {
    private enum class Tab(val label: String) { POSITION("Posição"), ZOOM("Zoom"), ROTATE("Girar"), OPACITY("Opacidade") }

    fun show(
        activity: Activity,
        sticker: StickerClip,
        timeUs: Long,
        canvasWidth: Int,
        canvasHeight: Int,
        preview: (StickerClip) -> Unit,
        restore: () -> Unit,
        apply: (StickerClip) -> Unit,
    ) = with(activity) {
        val body = column()
        lateinit var dialog: Dialog
        var committed = false
        val original = sticker.transformAt(timeUs.coerceIn(sticker.startUs, sticker.endUs))
        var state: TransformState = original
        val w = canvasWidth.coerceAtLeast(1).toFloat()
        val h = canvasHeight.coerceAtLeast(1).toFloat()

        fun draft() = StickerEditing.applyTransform(sticker, state, timeUs)
        fun push() = preview(draft())

        // Abas.
        val tabs = row().apply { setPadding(0, 0, 0, dp(6)) }
        body.addView(tabs)
        val pages = LinkedHashMap<Tab, LinearLayout>()
        val tabViews = LinkedHashMap<Tab, TextView>()
        fun select(tab: Tab) {
            pages.forEach { (key, page) -> page.visibility = if (key == tab) View.VISIBLE else View.GONE }
            tabViews.forEach { (key, view) ->
                val on = key == tab
                view.setTextColor(if (on) EditorStyle.TEXT else EditorStyle.MUTED)
                view.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            }
        }
        var current = Tab.POSITION

        // Uma linha "rótulo · régua · valor".
        class RulerRow(val ruler: ValueRulerView, val valueLabel: TextView, val format: (Float) -> String) {
            fun show(v: Float) { ruler.value = v; valueLabel.text = format(v) }
        }
        fun rulerRow(page: LinearLayout, title: String, min: Float, max: Float, perTick: Float, majorEvery: Int,
                     format: (Float) -> String, onChange: (Float) -> Unit): RulerRow {
            val line = row().apply { setPadding(0, dp(6), 0, dp(6)) }
            line.addView(label(title, 14f, EditorStyle.MUTED), LinearLayout.LayoutParams(dp(64), -2))
            val ruler = ValueRulerView(this, min, max, perTick, majorEvery)
            line.addView(ruler, LinearLayout.LayoutParams(0, dp(40), 1f))
            val valueLabel = label("", 15f).apply { gravity = Gravity.END; setTypeface(typeface, Typeface.BOLD) }
            line.addView(valueLabel, LinearLayout.LayoutParams(dp(58), -2))
            page.addView(line)
            val result = RulerRow(ruler, valueLabel, format)
            ruler.onValueChange = { v -> valueLabel.text = format(v); onChange(v); push() }
            return result
        }

        fun page(tab: Tab): LinearLayout {
            val page = column()
            pages[tab] = page
            val view = label(tab.label, 15f).apply {
                setPadding(dp(4), dp(6), dp(18), dp(6)); isClickable = true
                setOnClickListener { current = tab; select(tab) }
            }
            tabViews[tab] = view
            tabs.addView(view)
            body.addView(page)
            return page
        }

        val positionPage = page(Tab.POSITION)
        val xRow = rulerRow(positionPage, "Eixo X", -w / 2f, w / 2f, w / 100f, 10, { "${it.roundToInt()}" }) {
            state = state.copy(x = (.5f + it / w).coerceIn(0f, 1f))
        }
        val yRow = rulerRow(positionPage, "Eixo Y", -h / 2f, h / 2f, h / 100f, 10, { "${it.roundToInt()}" }) {
            state = state.copy(y = (.5f - it / h).coerceIn(0f, 1f))
        }
        val zoomPage = page(Tab.ZOOM)
        val zoomRow = rulerRow(zoomPage, "Escala", 5f, 1000f, 5f, 10, { "${it.roundToInt()}%" }) {
            state = state.copy(scaleX = it / 100f, scaleY = it / 100f)
        }
        val rotatePage = page(Tab.ROTATE)
        val rotateRow = rulerRow(rotatePage, "Ângulo", -180f, 180f, 2f, 15, { "${it.roundToInt()}°" }) {
            state = state.copy(rotation = it)
        }
        val opacityPage = page(Tab.OPACITY)
        val opacityRow = rulerRow(opacityPage, "Opacidade", 5f, 100f, 1f, 10, { "${it.roundToInt()}%" }) {
            state = state.copy(opacity = it / 100f)
        }

        fun refresh() {
            xRow.show((state.x - .5f) * w)
            yRow.show((.5f - state.y) * h)
            zoomRow.show(state.scaleX * 100f)
            rotateRow.show(state.rotation)
            opacityRow.show(state.opacity * 100f)
        }

        // Rodapé: ↺ Redefinir · ✓
        val footer = row().apply { setPadding(0, dp(12), 0, 0) }
        footer.addView(label("↺  Redefinir", 15f).apply {
            setPadding(dp(4), dp(10), dp(12), dp(10)); isClickable = true
            setOnClickListener {
                state = when (current) {
                    Tab.POSITION -> state.copy(x = .5f, y = .5f)
                    Tab.ZOOM -> state.copy(scaleX = 1f, scaleY = 1f)
                    Tab.ROTATE -> state.copy(rotation = 0f)
                    Tab.OPACITY -> state.copy(opacity = 1f)
                }
                refresh(); push()
            }
        })
        footer.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        footer.addView(editorIconButton("Aplicar", com.recly.editor.engine.R.drawable.ic_recly_check, accent = true) {
            committed = true
            apply(draft())
            dialog.dismiss()
        }, LinearLayout.LayoutParams(dp(52), dp(48)))
        body.addView(footer)

        refresh()
        select(Tab.POSITION)
        dialog = sheet("Básico", body, dim = 0f)
        dialog.setOnDismissListener { if (!committed) restore() }
    }
}
