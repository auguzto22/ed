package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.Dialog
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.*
import com.termex.replay15.editor.domain.*
import kotlin.math.abs

object KeyframeTools {
    fun show(
        activity: Activity,
        clip: VideoClip,
        sourceUs: Long,
        seek: (Long) -> Unit,
        preview: (VideoClip) -> Unit = {},
        restore: () -> Unit = {},
        apply: (VideoClip) -> Unit
    ) = show(activity, clip, sourceUs, seek, preview, restore, null, apply)

    fun show(
        activity: Activity,
        clip: VideoClip,
        sourceUs: Long,
        seek: (Long) -> Unit,
        preview: (VideoClip) -> Unit = {},
        restore: () -> Unit = {},
        project: Project? = null,
        apply: (VideoClip) -> Unit
    ): Unit = with(activity) {
        val source = sourceUs.coerceIn(clip.inUs, clip.outUs)
        val nearby = clip.keyframes.filter { it.sourceUs in clip.inUs..clip.outUs }.minByOrNull { abs(it.sourceUs - source) }?.takeIf { abs(it.sourceUs - source) < 20_000 }
        val at = nearby?.sourceUs ?: source
        var key = clip.transformAt(at).copy(sourceUs = at)
        var is3D = clip.is3D
        var selectedParentId = clip.parentId
        val body = column(); lateinit var dialog: Dialog

        body.addView(sectionTitle("Movimento no tempo", "${timeLabel(clip.timeMap.timelineAt(at))} do clipe. A curva controla o movimento ate o proximo ponto."))

        val navigation = row()
        fun navigate(label: String, target: TransformKeyframe?) {
            navigation.addView(action(label) {
                if (target != null) { dialog.dismiss(); seek(target.sourceUs); show(activity, clip, target.sourceUs, seek, preview, restore, project, apply) }
            }.apply { isEnabled = target != null; alpha = if (isEnabled) 1f else .4f }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        navigate("Ponto anterior", clip.keyframes.lastOrNull { it.sourceUs < at && it.sourceUs >= clip.inUs })
        navigate("Proximo ponto", clip.keyframes.firstOrNull { it.sourceUs > at && it.sourceUs <= clip.outUs })
        body.addView(navigation)

        fun draft() = preview(clip.copy(
            is3D = is3D,
            parentId = selectedParentId,
            keyframes = (clip.keyframes.filterNot { it.sourceUs == at } + key).sortedBy { it.sourceUs }
        ))

        // 3D Mode Toggle
        val mode3DToggle = CheckBox(this).apply {
            text = "Modo 2.5D / 3D (Eixo Z, Rotação 3D, Âncora)"
            isChecked = is3D
        }
        body.addView(mode3DToggle)

        // 2D Standard Controls
        slider(body, "Zoom (%)", (key.zoom * 100).toInt(), 400, 25) { key = key.copy(zoom = it / 100f); draft() }
        slider(body, "Horizontal (%)", (key.x * 100).toInt(), 50, -50) { key = key.copy(x = it / 100f); draft() }
        slider(body, "Vertical (%)", (key.y * 100).toInt(), 50, -50) { key = key.copy(y = it / 100f); draft() }
        slider(body, "Rotacao Z (graus)", key.rotation.toInt(), 180, -180) { key = key.copy(rotation = it.toFloat()); draft() }
        slider(body, "Opacidade (%)", (key.opacity * 100).toInt(), 100) { key = key.copy(opacity = it / 100f); draft() }

        // 3D Contextual Controls
        val controls3D = column()
        slider(controls3D, "Profundidade Z", key.z.toInt(), 1000, -1000, { "$it" }) { key = key.copy(z = it.toFloat()); draft() }
        slider(controls3D, "Rotacao X / Tilt (graus)", key.rotationX.toInt(), 180, -180, { "$it°" }) { key = key.copy(rotationX = it.toFloat()); draft() }
        slider(controls3D, "Rotacao Y / Pan (graus)", key.rotationY.toInt(), 180, -180, { "$it°" }) { key = key.copy(rotationY = it.toFloat()); draft() }
        slider(controls3D, "Pivo / Ancora X (%)", (key.anchorX * 100).toInt(), 100, -100, { "$it%" }) { key = key.copy(anchorX = it / 100f); draft() }
        slider(controls3D, "Pivo / Ancora Y (%)", (key.anchorY * 100).toInt(), 100, -100, { "$it%" }) { key = key.copy(anchorY = it / 100f); draft() }
        slider(controls3D, "Escala Z (%)", (key.scaleZ * 100).toInt(), 400, 25) { key = key.copy(scaleZ = it / 100f); draft() }

        body.addView(controls3D)
        controls3D.visibility = if (is3D) View.VISIBLE else View.GONE

        mode3DToggle.setOnCheckedChangeListener { _, isChecked ->
            is3D = isChecked
            controls3D.visibility = if (is3D) View.VISIBLE else View.GONE
            draft()
        }

        // Parenting Selector
        if (project != null) {
            val eligibleParents = project.videos.filter { other ->
                other.id != clip.id && !Matrix4.detectCycle(clip.id, other.id) { id -> project.videos.find { it.id == id }?.parentId }
            }
            if (eligibleParents.isNotEmpty() || selectedParentId != null) {
                body.addView(sectionTitle("Parenting / Vinculo hierarquico", "Vincula escala, rotacao e posicao a outra camada."))
                val parentSpinner = Spinner(this)
                val parentOptions = listOf("Nenhum (Camada independente)") + eligibleParents.map { "${if (it.isNullObject) "[Null] " else ""}${it.name}" }
                parentSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, parentOptions)
                val currentIdx = if (selectedParentId == null) 0 else eligibleParents.indexOfFirst { it.id == selectedParentId } + 1
                parentSpinner.setSelection(currentIdx.coerceAtLeast(0))
                parentSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        selectedParentId = if (position == 0) null else eligibleParents.getOrNull(position - 1)?.id
                        draft()
                    }
                }
                body.addView(parentSpinner, LinearLayout.LayoutParams(-1, dp(52)))
            }
        }

        // Easing and Bezier controls
        val graph = EasingGraphView(this).apply { easing = key.easing; bezier = key.bezier }
        val easing = Spinner(this).apply {
            adapter = ArrayAdapter(this@with, android.R.layout.simple_spinner_dropdown_item, Easing.entries.map { it.label }); setSelection(key.easing.ordinal)
        }
        body.addView(easing, LinearLayout.LayoutParams(-1, dp(52)))
        body.addView(graph, LinearLayout.LayoutParams(-1, dp(220)))
        val fields = row()
        val inputs = listOf("X1", "Y1", "X2", "Y2").map { label ->
            EditText(this).apply {
                hint = label; contentDescription = "Bezier $label"; isSingleLine = true
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                fields.addView(this, LinearLayout.LayoutParams(0, dp(56), 1f))
            }
        }
        fun values() = with(key.bezier) { listOf(x1, y1, x2, y2).forEachIndexed { i, value -> inputs[i].setText(String.format(java.util.Locale.ROOT, "%.3f", value)) } }
        values(); body.addView(fields)
        graph.onEdit = { key = key.copy(bezier = it); values(); draft() }
        val update = action("Atualizar alcas Bezier") {
            runCatching {
                val parsed = inputs.map { it.text.toString().replace(',', '.').toFloat() }
                key = key.copy(bezier = CubicBezier(parsed[0], parsed[1], parsed[2], parsed[3]))
                graph.bezier = key.bezier; graph.invalidate(); values()
            }.onFailure { inputs[0].error = "X: 0 a 1; Y: -2 a 3" }
        }
        body.addView(update)
        easing.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                key = key.copy(easing = Easing.entries[position]); graph.easing = key.easing; graph.invalidate()
                draft()
                fields.visibility = if (key.easing == Easing.BEZIER) View.VISIBLE else View.GONE
                update.visibility = fields.visibility
            }
        }
        fun commit(next: VideoClip) {
            runCatching { apply(next) }.fold({ body.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); dialog.dismiss() }, {
                Toast.makeText(this, "Nao foi possivel alterar esta faixa.", Toast.LENGTH_LONG).show()
            })
        }
        body.addView(action(if (nearby == null) "Adicionar keyframe" else "Atualizar keyframe", true) {
            if (nearby == null && clip.keyframes.size >= 200) Toast.makeText(this, "Limite de 200 pontos por clipe", Toast.LENGTH_LONG).show()
            else commit(clip.copy(
                is3D = is3D,
                parentId = selectedParentId,
                keyframes = (clip.keyframes.filterNot { it.sourceUs == at } + key).sortedBy { it.sourceUs }
            ))
        })
        if (nearby != null) body.addView(action("Excluir este ponto") { commit(clip.copy(keyframes = clip.keyframes - nearby)) })
        if (clip.keyframes.isNotEmpty()) body.addView(action("Remover todos os keyframes") { commit(clip.copy(keyframes = emptyList())) })
        dialog = sheet("Keyframes e curvas", body)
        dialog.setOnDismissListener { restore() }
    }
}
