package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.Dialog
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Toast
import com.termex.replay15.editor.domain.MaskState
import com.termex.replay15.editor.domain.MaskType
import com.termex.replay15.editor.domain.TrackingTrack

/** One editor for video, image, text and sticker mask state. Drafts never enter project history. */
object MaskTools {
    fun show(
        activity: Activity,
        initial: MaskState?,
        localTimeUs: Long,
        trackingTracks: List<TrackingTrack> = emptyList(),
        preview: (MaskState?) -> Unit,
        apply: (MaskState?) -> Unit,
        restore: () -> Unit,
        onRequestTracking: (() -> Unit)? = null,
    ) = with(activity) {
        val body = column()
        lateinit var dialog: Dialog
        var candidate = initial ?: MaskState.default()
        var current = candidate.at(localTimeUs)

        fun geometry(change: (MaskState) -> MaskState) {
            current = change(candidate.at(localTimeUs))
            candidate = if (candidate.keyframes.isEmpty()) {
                current.copy(keyframes = emptyList())
            } else {
                candidate.upsertKeyframe(localTimeUs, current)
            }
            preview(candidate)
        }

        fun static(change: (MaskState) -> MaskState) {
            candidate = change(candidate)
            current = candidate.at(localTimeUs)
            preview(candidate)
        }

        body.addView(sectionTitle("Mascara profissional",
            "Valores normalizados, nao destrutivos e avaliados no tempo atual."))
        val pathEditor = MaskPathEditorView(this).apply {
            setPoints(candidate.customPath)
            visibility = if (candidate.type == MaskType.CUSTOM_PATH) View.VISIBLE else View.GONE
            onChange = { points -> static { it.copy(customPath = points) } }
        }
        val shape = Spinner(this).apply {
            adapter = ArrayAdapter(this@with, android.R.layout.simple_spinner_dropdown_item, MaskType.entries.map { it.label })
            setSelection(candidate.type.ordinal)
        }
        body.addView(shape, LinearLayout.LayoutParams(-1, dp(56)))
        shape.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val type = MaskType.entries[position]
                if (candidate.type != type) static {
                    it.copy(type = type, customPath = if (type == MaskType.CUSTOM_PATH && it.customPath.size < 3)
                        MaskState.DEFAULT_CUSTOM_PATH else it.customPath)
                }
                pathEditor.visibility = if (type == MaskType.CUSTOM_PATH) View.VISIBLE else View.GONE
                if (type == MaskType.CUSTOM_PATH) pathEditor.setPoints(candidate.customPath)
            }
        }

        body.addView(sectionTitle("Acompanhamento", "Vincule uma máscara a um track interno sem criar keyframes visíveis."))
        if (trackingTracks.isEmpty()) {
            body.addView(label("Nenhum track disponível. Crie um rastreio local para habilitar este vínculo.", 12f, EditorStyle.MUTED))
        } else {
            val options = listOf("Sem rastreio") + trackingTracks.map { "Track ${it.id.take(8)}" }
            val tracking = Spinner(this).apply {
                adapter = ArrayAdapter(this@with, android.R.layout.simple_spinner_dropdown_item, options)
                setSelection((candidate.trackingTrackId?.let { id -> trackingTracks.indexOfFirst { it.id == id } + 1 } ?: 0).coerceAtLeast(0))
            }
            body.addView(tracking, LinearLayout.LayoutParams(-1, dp(56)))
            tracking.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val trackId = trackingTracks.getOrNull(position - 1)?.id
                    if (candidate.trackingTrackId != trackId) static { it.copy(trackingTrackId = trackId) }
                }
            }
        }
        if (onRequestTracking != null) {
            body.addView(action("🎯 Criar / Gerenciar Rastreamento...") {
                dialog.dismiss()
                onRequestTracking()
            }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(4); bottomMargin = dp(8) })
        }

        val centerX = slider(body, "Centro X (%)", (current.centerX * 100).toInt(), 100) {
            geometry { state -> state.copy(centerX = it / 100f) }
        }
        val centerY = slider(body, "Centro Y (%)", (current.centerY * 100).toInt(), 100) {
            geometry { state -> state.copy(centerY = it / 100f) }
        }
        val width = slider(body, "Largura (%)", (current.width * 100).toInt(), 200, 1) {
            geometry { state -> state.copy(width = it / 100f) }
        }
        val height = slider(body, "Altura (%)", (current.height * 100).toInt(), 200, 1) {
            geometry { state -> state.copy(height = it / 100f) }
        }
        val rotation = slider(body, "Rotacao", current.rotation.toInt() + 180, 360,
            format = { "${it - 180}°" }) {
            geometry { state -> state.copy(rotation = (it - 180).toFloat()) }
        }
        val feather = slider(body, "Feather (%)", (current.feather * 1000).toInt(), 500,
            format = { String.format(java.util.Locale.ROOT, "%.1f", it / 10f) }) {
            geometry { state -> state.copy(feather = it / 1000f) }
        }
        val expansion = slider(body, "Expansao (%)", (current.expansion * 100).toInt(), 100, -100) {
            geometry { state -> state.copy(expansion = it / 100f) }
        }
        slider(body, "Intensidade (%)", (candidate.opacity * 100).toInt(), 100) {
            static { state -> state.copy(opacity = it / 100f) }
        }
        val inverted = CheckBox(this).apply {
            text = "Inverter mascara"
            isChecked = candidate.inverted
            setOnCheckedChangeListener { _, checked -> static { it.copy(inverted = checked) } }
        }
        body.addView(inverted)

        body.addView(sectionTitle("Caminho personalizado",
            "Arraste os pontos. Toque duas vezes para adicionar e segure para remover."))
        body.addView(pathEditor, LinearLayout.LayoutParams(-1, dp(240)))

        fun updateControls(values: MaskState) {
            centerX.progress = (values.centerX * 100).toInt()
            centerY.progress = (values.centerY * 100).toInt()
            width.progress = (values.width * 100).toInt()
            height.progress = (values.height * 100).toInt()
            rotation.progress = values.rotation.toInt() + 180
            feather.progress = (values.feather * 1000).toInt()
            expansion.progress = (values.expansion * 100).toInt()
        }

        val keyframeRow = row()
        keyframeRow.addView(action("Adicionar / atualizar keyframe") {
            candidate = candidate.upsertKeyframe(localTimeUs, candidate.at(localTimeUs))
            current = candidate.at(localTimeUs)
            preview(candidate)
            Toast.makeText(this@with, "Keyframe de mascara salvo", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, dp(56), 1f))
        keyframeRow.addView(action("Remover keyframe") {
            val evaluated = candidate.at(localTimeUs)
            candidate = candidate.removeKeyframe(localTimeUs)
            if (candidate.keyframes.isEmpty()) candidate = evaluated.copy(keyframes = emptyList())
            current = candidate.at(localTimeUs)
            updateControls(current)
            preview(candidate)
        }, LinearLayout.LayoutParams(0, dp(56), 1f))
        body.addView(sectionTitle("Animacao", "Centro, tamanho, rotacao, feather e expansao podem ter keyframes."))
        body.addView(keyframeRow)

        body.addView(action("Aplicar mascara", true) {
            apply(candidate)
            dialog.dismiss()
        })
        body.addView(action("Remover mascara") {
            apply(null)
            dialog.dismiss()
        })
        dialog = sheet("Mascara de camada", body)
        dialog.setOnDismissListener { restore() }
    }
}
