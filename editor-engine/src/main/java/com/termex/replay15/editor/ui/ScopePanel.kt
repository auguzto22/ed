package com.termex.replay15.editor.ui

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.ProjectClipTimeMapper
import com.termex.replay15.editor.scopes.ScopeFrame
import com.termex.replay15.editor.scopes.ScopeFrameSampler

/**
 * Reads a scope measurement and turns it into the sentences a colourist actually needs.
 *
 * Separated from the panel so the thresholds are testable and the wording cannot drift away
 * from the numbers it describes.
 */
object ScopeReadout {

    fun headline(frame: ScopeFrame): String {
        val spread = frame.tonalSpread()
        return when {
            frame.clippedHighlights > .02f -> "Estourado: ${percent(frame.clippedHighlights)} da imagem esta no branco."
            frame.clippedShadows > .04f -> "Preto queimado: ${percent(frame.clippedShadows)} da imagem esta no preto."
            spread < .18f -> "Contraste baixo: os tons ocupam so ${percent(spread)} da faixa."
            spread > .95f -> "Contraste alto: pretos e brancos estao colados nos extremos."
            else -> "Nivel saudavel: pretos e brancos usados, sem queima relevante."
        }
    }

    fun saturationNote(frame: ScopeFrame): String {
        val saturation = frame.saturation
        return when {
            saturation < .08f -> "Quase sem cor (${percent(saturation)}). A imagem esta lavada."
            saturation > .72f -> "Cor muito saturada (${percent(saturation)}). Pode parecer falso."
            else -> "Saturacao em ${percent(saturation)}."
        }
    }

    fun channelNote(frame: ScopeFrame): String? {
        val levels = frame.channelLevels
        val total = levels.sum()
        if (total <= 0f) return null
        val dominant = when (levels.indices.maxByOrNull { levels[it] }) {
            0 -> "vermelho"; 1 -> "verde"; else -> "azul"
        }
        // The cast is measured as a share of the average level per channel, which is what a
        // colourist reads off the parade. A third is a neutral image; much past forty percent
        // the frame is genuinely tinted.
        return if (levels.max() / total > .40f) "O canal $dominant domina: a imagem esta com tinta $dominant."
        else null
    }

    private fun percent(value: Float) = "${(value * 100).toInt()}%"
}

/** The scope panel: a view, a mode selector and the readings for the frame under the playhead. */
class ScopePanel(private val context: Context, private val sampler: ScopeFrameSampler) {

    private val view = ScopeView(context)
    private val notes = TextView(context).apply {
        setTextColor(EditorStyle.MUTED)
        textSize = 12f
        setPadding(context.dp(4), context.dp(6), context.dp(4), 0)
    }

    var mode: ScopeMode = ScopeMode.WAVEFORM
        private set
    private var current: ScopeMode = ScopeMode.WAVEFORM
    private val modes = ScopeMode.entries

    val root: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(170)))
        addView(notes)
    }

    fun spinner(): Spinner = Spinner(context).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
            modes.map { it.label })
        setSelection(modes.indexOfFirst { it == current }.coerceAtLeast(0))
        onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: android.view.View?, position: Int, id: Long) {
                current = modes[position]
                mode = current
                view.mode = current
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    fun show(project: Project, clipIndex: Int, timeUs: Long) {
        val clip = project.videos.getOrNull(clipIndex)
        if (clip == null) { view.frame = null; return }
        // The scope shows the source at the playhead, so trim and speed are mapped back before
        // sampling; otherwise the reading would describe a different moment than the preview.
        val sourceTime = ProjectClipTimeMapper.projectToSource(clip, project.startOf(clipIndex), timeUs)
        val frame = sampler.sample(clip.uri, sourceTime, clip.sourceUs)
        if (frame == null) {
            view.frame = null
            notes.text = "Nao foi possivel medir este quadro."
            return
        }
        view.frame = frame
        notes.text = buildList {
            add(ScopeReadout.headline(frame))
            add(ScopeReadout.saturationNote(frame))
            ScopeReadout.channelNote(frame)?.let(::add)
            add("Luma ${frame.lumaMin}-${frame.lumaMax} · media ${frame.lumaAverage}")
        }.joinToString("\n")
    }
}
