package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.widget.*
import com.termex.replay15.editor.domain.*
import kotlin.math.roundToLong

object SpeedTools {
    /** "4.7s", como o CapCut mostra a duração do clipe. */
    private fun secondsLabel(us: Long): String =
        String.format(java.util.Locale.ROOT, "%.1fs", us / SECOND.toDouble())

    fun show(
        activity: Activity,
        clip: VideoClip,
        preview: (VideoClip) -> Unit = {},
        restore: () -> Unit = {},
        apply: (VideoClip) -> Unit,
    ) = with(activity) {
        val body = column(); lateinit var dialog: Dialog
        var committed = false
        var draft = clip
        var normalSpeed = clip.speed
        var curvePoints = clip.speedCurve.ifEmpty {
            listOf(SpeedPoint(clip.inUs, clip.speed), SpeedPoint(clip.outUs, clip.speed))
        }

                val modeRow = row()
        val normalPanel = column()
        val curvePanel = column()
        lateinit var normalMode: Button
        lateinit var curveMode: Button
        fun styleChoice(button: Button, selected: Boolean) {
            button.background = GradientDrawable().apply {
                setColor(if (selected) EditorStyle.ACCENT else EditorStyle.CARD)
                setStroke(dp(1), if (selected) EditorStyle.ACCENT else EditorStyle.BORDER)
                cornerRadius = dp(12).toFloat()
            }
            button.setTextColor(if (selected) EditorStyle.ON_ACCENT else EditorStyle.TEXT)
        }
        fun showMode(curve: Boolean) {
            normalPanel.visibility = if (curve) View.GONE else View.VISIBLE
            curvePanel.visibility = if (curve) View.VISIBLE else View.GONE
            styleChoice(normalMode, !curve); styleChoice(curveMode, curve)
            draft = if (curve) clip.copy(speedCurve = curvePoints, preservePitch = draft.preservePitch)
                else clip.copy(speed = normalSpeed, speedCurve = emptyList(), preservePitch = draft.preservePitch)
            preview(draft)
        }
        normalMode = action("NORMAL") { showMode(false) }
        curveMode = action("CURVA") { showMode(true) }
        modeRow.addView(normalMode, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(4) })
        modeRow.addView(curveMode, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(4) })
        body.addView(modeRow)

        // Modo normal no jeito do CapCut: "Duração: antes → depois" e uma régua 0,1× … 16×.
        val originalDuration = clip.copy(speed = 1f, speedCurve = emptyList()).durationUs
        val durationRow = row().apply { setPadding(dp(2), dp(14), dp(2), dp(6)) }
        val durationBefore = label("Duração: ${secondsLabel(originalDuration)}", 15f, EditorStyle.MUTED)
        val durationArrow = label("  →  ", 15f, EditorStyle.ACTION_TEXT).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
        val durationAfter = label("", 15f, EditorStyle.TEXT).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
        durationRow.addView(durationBefore); durationRow.addView(durationArrow); durationRow.addView(durationAfter)
        normalPanel.addView(durationRow)
        val ruler = SpeedRulerView(this)
        normalPanel.addView(ruler, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(6) })
        fun chooseNormal(value: Float, sendPreview: Boolean = true) {
            normalSpeed = value.coerceIn(MIN_CLIP_SPEED, MAX_CLIP_SPEED)
            draft = clip.copy(speed = normalSpeed, speedCurve = emptyList(), preservePitch = draft.preservePitch)
            durationAfter.text = secondsLabel(draft.durationUs)
            if (kotlin.math.abs(ruler.speed - normalSpeed) > .001f) ruler.speed = normalSpeed
            if (sendPreview) preview(draft)
        }
        ruler.onSpeedChange = { chooseNormal(it) }

        curvePanel.addView(sectionTitle("Curva de velocidade", "Arraste os pontos. O eixo horizontal representa o arquivo original; cortes e splits preservam o mapeamento."))
        val graph = SpeedCurveView(this, clip.sourceUs).apply { points = curvePoints }
        curvePanel.addView(graph, LinearLayout.LayoutParams(-1, dp(230)))
        val duration = label("", 13f, EditorStyle.ACCENT); curvePanel.addView(duration)
        val pointLabel = label("", 13f); curvePanel.addView(pointLabel)
        fun number(hint: String) = EditText(this).apply {
            this.hint = hint; isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            curvePanel.addView(this)
        }
        val time = number("Tempo no arquivo, em segundos")
        val pointSpeed = number("Velocidade, de ${formatSpeed(MIN_CLIP_SPEED)} a ${formatSpeed(MAX_CLIP_SPEED)}")
        fun updateCurve(sendPreview: Boolean = true) {
            graph.selected = graph.selected.coerceIn(graph.points.indices)
            curvePoints = graph.points
            val point = graph.points[graph.selected]
            pointLabel.text = "Ponto ${graph.selected + 1} de ${graph.points.size}   •   ${formatSpeed(point.speed)}"
            time.setText(String.format(java.util.Locale.ROOT, "%.6f", point.sourceUs / SECOND.toDouble()))
            pointSpeed.setText(String.format(java.util.Locale.ROOT, "%.2f", point.speed))
            draft = clip.copy(speedCurve = curvePoints, preservePitch = draft.preservePitch)
            duration.text = "Duração resultante: ${timeLabel(draft.durationUs)}"
            graph.invalidate()
            if (sendPreview && curvePanel.visibility == View.VISIBLE) preview(draft)
        }
        graph.onEdit = { _, _ -> updateCurve() }
        curvePanel.addView(action("Atualizar ponto pelos valores") {
            runCatching {
                val seconds = time.text.toString().replace(',', '.').toDouble().also { require(it.isFinite()) }
                val value = pointSpeed.text.toString().replace(',', '.').toFloat()
                val point = SpeedPoint((seconds * SECOND).roundToLong(), value)
                require(point.sourceUs <= clip.sourceUs)
                val changed = graph.points.toMutableList().apply { set(graph.selected, point) }.sortedBy { it.sourceUs }
                require(changed.zipWithNext().all { (a, b) -> a.sourceUs < b.sourceUs })
                graph.points = changed; graph.selected = changed.indexOf(point); updateCurve()
            }.onFailure { time.error = "Use tempos diferentes e velocidade entre ${formatSpeed(MIN_CLIP_SPEED)} e ${formatSpeed(MAX_CLIP_SPEED)}" }
        })
        val navigation = row()
        navigation.addView(action("Anterior") { graph.selected = (graph.selected - 1).coerceAtLeast(0); updateCurve(false) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        navigation.addView(action("Próximo") { graph.selected = (graph.selected + 1).coerceAtMost(graph.points.lastIndex); updateCurve(false) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        curvePanel.addView(navigation)
        val operations = row()
        operations.addView(action("Adicionar ponto") {
            if (graph.points.size < 32) {
                val bounds = (listOf(0L) + graph.points.map { it.sourceUs } + clip.sourceUs).distinct().sorted()
                val gap = bounds.zipWithNext().maxByOrNull { it.second - it.first }
                if (gap != null && gap.second - gap.first > 1) {
                    val at = gap.first + (gap.second - gap.first) / 2
                    val map = clip.copy(inUs = 0, outUs = clip.sourceUs, speedCurve = graph.points).timeMap
                    val point = SpeedPoint(at, map.speedAtInput(at))
                    graph.points = (graph.points + point).sortedBy { it.sourceUs }; graph.selected = graph.points.indexOf(point); updateCurve()
                }
            } else Toast.makeText(this, "Limite de 32 pontos", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        operations.addView(action("Excluir ponto") {
            if (graph.points.size > 1) { graph.points = graph.points.filterIndexed { index, _ -> index != graph.selected }; updateCurve() }
        }, LinearLayout.LayoutParams(0, dp(52), 1f)); curvePanel.addView(operations)
        val presets = linkedMapOf("Acelerar" to listOf(.5f, 1f, 4f), "Desacelerar" to listOf(4f, 1f, .5f),
            "Impacto" to listOf(1f, 4f, .25f, .5f, 1f), "Onda" to listOf(1f, .4f, 2f, .4f, 1f))
        curvePanel.addView(action("Ritmos prontos") {
            choiceSheet("Curvas de ritmo", presets.keys.toList()) { index ->
                val speeds = presets.values.elementAt(index)
                graph.points = speeds.mapIndexed { i, value -> SpeedPoint(clip.inUs + (clip.outUs - clip.inUs) * i / (speeds.size - 1), value) }
                graph.selected = 0; updateCurve()
            }
        })

        body.addView(normalPanel); body.addView(curvePanel)
        // Rodapé como no CapCut: "Alterar tom do áudio" à esquerda e ✓ para aplicar à direita.
        // Marcado = o tom muda junto com a velocidade (preservePitch desligado).
        val footer = row().apply { setPadding(0, dp(10), 0, 0) }
        val pitch = CheckBox(this).apply {
            text = "Alterar tom do áudio"; isChecked = !clip.preservePitch
            setTextColor(EditorStyle.TEXT); textSize = 15f
            buttonTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(EditorStyle.ACTION, EditorStyle.MUTED))
            setOnCheckedChangeListener { _, checked -> draft = draft.copy(preservePitch = !checked); preview(draft) }
        }
        footer.addView(pitch, LinearLayout.LayoutParams(0, -2, 1f))
        footer.addView(editorIconButton("Aplicar velocidade", com.recly.editor.engine.R.drawable.ic_recly_check, accent = true) {
            runCatching { apply(draft) }.fold({ committed = true; dialog.dismiss() }, {
                Toast.makeText(this, "Não foi possível aplicar. Verifique o espaço na faixa e a duração do projeto.", Toast.LENGTH_LONG).show()
            })
        }, LinearLayout.LayoutParams(dp(52), dp(48)))
        body.addView(footer)

        chooseNormal(normalSpeed, false); updateCurve(false)
        dialog = sheet("Velocidade", body)
        dialog.setOnDismissListener { if (!committed) restore() }
        showMode(clip.speedCurve.isNotEmpty())
    }
}
