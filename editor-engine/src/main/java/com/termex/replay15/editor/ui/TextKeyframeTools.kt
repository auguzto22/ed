package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.Dialog
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.*
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.transform.TransformKeyframe
import com.termex.replay15.editor.transform.TransformState
import kotlin.math.abs

/**
 * Dedicated keyframe controls, easing curves, and navigation for text layers.
 */
object TextKeyframeTools {

    fun toggleKeyframe(
        text: TextClip,
        timeUs: Long,
        onApplied: (TextClip, String) -> Unit,
    ) {
        val targetUs = timeUs.coerceIn(text.startUs, text.endUs)
        val toleranceUs = 35_000L
        val nearby = text.transformKeyframes.firstOrNull { abs(it.timeUs - targetUs) <= toleranceUs }

        if (nearby != null) {
            // Keyframe exists at cursor: remove it
            val updated = text.copy(transformKeyframes = text.transformKeyframes.filterNot { it.timeUs == nearby.timeUs })
            onApplied(updated, "Keyframe removido")
        } else {
            // No keyframe at cursor: add one with current evaluated transform
            val currentTransform = text.transformAt(targetUs)
            val newKey = TransformKeyframe(timeUs = targetUs, transform = currentTransform, easing = Easing.SMOOTH)
            val updated = text.copy(transformKeyframes = (text.transformKeyframes + newKey).sortedBy { it.timeUs })
            onApplied(updated, "Keyframe adicionado em ${timeLabel(targetUs - text.startUs)}")
        }
    }

    fun show(
        activity: Activity,
        text: TextClip,
        timeUs: Long,
        seek: (Long) -> Unit,
        preview: (TextClip) -> Unit = {},
        restore: () -> Unit = {},
        apply: (TextClip) -> Unit,
    ): Unit = with(activity) {
        val atTarget = timeUs.coerceIn(text.startUs, text.endUs)
        val nearby = text.transformKeyframes.minByOrNull { abs(it.timeUs - atTarget) }?.takeIf { abs(it.timeUs - atTarget) < 35_000L }
        val at = nearby?.timeUs ?: atTarget
        var transform = text.transformAt(at)
        var easingValue = nearby?.easing ?: Easing.SMOOTH
        var bezierValue = nearby?.bezier ?: CubicBezier()

        val body = column()
        lateinit var dialog: Dialog

        body.addView(sectionTitle("Keyframes de Texto", "${timeLabel(at - text.startUs)} do texto. Posição, escala, rotação e opacidade."))

        // Navigation row
        val navigation = row()
        fun navigate(label: String, target: TransformKeyframe?) {
            navigation.addView(action(label) {
                if (target != null) {
                    dialog.dismiss()
                    seek(target.timeUs)
                    show(activity, text, target.timeUs, seek, preview, restore, apply)
                }
            }.apply {
                isEnabled = target != null
                alpha = if (isEnabled) 1f else 0.4f
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        navigate("Anterior", text.transformKeyframes.lastOrNull { it.timeUs < at })
        navigate("Próximo", text.transformKeyframes.firstOrNull { it.timeUs > at })
        body.addView(navigation)

        fun draft() {
            val key = TransformKeyframe(at, transform, easingValue, bezierValue)
            val keys = (text.transformKeyframes.filterNot { it.timeUs == at } + key).sortedBy { it.timeUs }
            preview(text.copy(transformKeyframes = keys))
        }

        // Sliders for precise adjustment
        slider(body, "Escala (%)", (transform.scaleX * 100).toInt(), 300, 20) {
            transform = transform.copy(scaleX = it / 100f, scaleY = it / 100f)
            draft()
        }
        slider(body, "Horizontal (%)", (transform.x * 100).toInt(), 100, 0) {
            transform = transform.copy(x = it / 100f)
            draft()
        }
        slider(body, "Vertical (%)", (transform.y * 100).toInt(), 100, 0) {
            transform = transform.copy(y = it / 100f)
            draft()
        }
        slider(body, "Rotação (graus)", transform.rotation.toInt(), 180, -180) {
            transform = transform.copy(rotation = it.toFloat())
            draft()
        }
        slider(body, "Opacidade (%)", (transform.opacity * 100).toInt(), 100, 10) {
            transform = transform.copy(opacity = it / 100f)
            draft()
        }

        // Easing selector
        body.addView(sectionTitle("Curva de interpolação", "Transição até o próximo keyframe"))
        val easingSpinner = Spinner(this)
        easingSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Easing.entries.map { it.label })
        easingSpinner.setSelection(easingValue.ordinal)
        body.addView(easingSpinner)

        val graph = EasingGraphView(this).apply {
            easing = easingValue
            bezier = bezierValue
        }
        body.addView(graph, LinearLayout.LayoutParams(-1, dp(180)))

        val fields = row()
        val inputs = listOf("X1", "Y1", "X2", "Y2").map { label ->
            EditText(this).apply {
                hint = label
                contentDescription = "Bezier $label"
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                fields.addView(this, LinearLayout.LayoutParams(0, dp(56), 1f))
            }
        }
        fun updateBezierInputs() = with(bezierValue) {
            listOf(x1, y1, x2, y2).forEachIndexed { i, value ->
                inputs[i].setText(String.format(java.util.Locale.ROOT, "%.3f", value))
            }
        }
        updateBezierInputs()
        body.addView(fields)

        fields.visibility = if (easingValue == Easing.BEZIER) View.VISIBLE else View.GONE
        graph.onEdit = {
            bezierValue = it
            updateBezierInputs()
            draft()
        }

        easingSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                easingValue = Easing.entries[pos]
                graph.easing = easingValue
                graph.invalidate()
                fields.visibility = if (easingValue == Easing.BEZIER) View.VISIBLE else View.GONE
                draft()
            }
        }

        fun commit(next: TextClip) {
            runCatching { apply(next) }.fold(
                {
                    body.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    dialog.dismiss()
                },
                {
                    Toast.makeText(this, "Não foi possível alterar este texto.", Toast.LENGTH_LONG).show()
                },
            )
        }

        // Commit buttons
        body.addView(action(if (nearby == null) "Adicionar Keyframe" else "Atualizar Keyframe", true) {
            if (nearby == null && text.transformKeyframes.size >= 200) {
                Toast.makeText(this, "Limite de 200 keyframes por texto", Toast.LENGTH_LONG).show()
            } else {
                val key = TransformKeyframe(at, transform, easingValue, bezierValue)
                val keys = (text.transformKeyframes.filterNot { it.timeUs == at } + key).sortedBy { it.timeUs }
                commit(text.copy(transformKeyframes = keys))
            }
        })

        if (nearby != null) {
            body.addView(action("Excluir este keyframe") {
                commit(text.copy(transformKeyframes = text.transformKeyframes.filterNot { it.timeUs == nearby.timeUs }))
            })
        }

        if (text.transformKeyframes.isNotEmpty()) {
            body.addView(action("Remover todos os keyframes") {
                commit(text.copy(transformKeyframes = emptyList()))
            })
        }

        dialog = sheet("Keyframes de Texto", body)
        dialog.setOnDismissListener { restore() }
    }
}
