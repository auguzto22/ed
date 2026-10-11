package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.widget.*
import com.termex.replay15.editor.domain.*
import kotlin.math.roundToInt
import kotlin.math.roundToLong

object SpeedTools {
    val NORMAL_PRESETS = listOf(.25f, .5f, .75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f, 8f)

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

        body.addView(sectionTitle("Velocidade", "A velocidade faz parte do clipe e será usada na timeline, áudio, prévia e exportação."))
        val modeRow = row()
        val normalPanel = column()
        val curvePanel = column()
        lateinit var normalMode: Button
        lateinit var curveMode: Button
        fun styleChoice(button: Button, selected: Boolean) {
            button.background = GradientDrawable().apply {
                setColor(if (selected) Color.WHITE else EditorStyle.CARD)
                setStroke(dp(1), if (selected) Color.WHITE else EditorStyle.BORDER)
                cornerRadius = dp(12).toFloat()
            }
            button.setTextColor(if (selected) Color.BLACK else Color.WHITE)
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

        val current = label("", 15f, EditorStyle.ACCENT).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
        normalPanel.addView(current)
        val presetGrid = GridLayout(this).apply { columnCount = 5 }
        val presetButtons = mutableListOf<Pair<Float, Button>>()
        lateinit var speedSlider: SeekBar
        fun chooseNormal(value: Float, sendPreview: Boolean = true) {
            normalSpeed = value.coerceIn(MIN_CLIP_SPEED, MAX_CLIP_SPEED)
            draft = clip.copy(speed = normalSpeed, speedCurve = emptyList(), preservePitch = draft.preservePitch)
            current.text = "Selecionado: ${formatSpeed(normalSpeed)}   •   Duração ${timeLabel(draft.durationUs)}"
            presetButtons.forEach { (preset, button) -> styleChoice(button, kotlin.math.abs(preset - normalSpeed) < .001f) }
            speedSlider.progress = (normalSpeed * 100).roundToInt()
            if (sendPreview) preview(draft)
        }
        NORMAL_PRESETS.forEach { value ->
            val button = action(formatSpeed(value)) { chooseNormal(value) }
            presetButtons += value to button
            presetGrid.addView(button, GridLayout.LayoutParams().apply {
                width = 0; height = dp(48); columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        normalPanel.addView(presetGrid)
        speedSlider = slider(normalPanel, "Valor personalizado", (normalSpeed * 100).roundToInt(),
            (MAX_CLIP_SPEED * 100).roundToInt(), (MIN_CLIP_SPEED * 100).roundToInt(),
            format = { formatSpeed(it / 100f) }) { chooseNormal(it / 100f) }

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
        val pitch = CheckBox(this).apply {
            text = "Preservar tom da voz"; isChecked = clip.preservePitch
            setOnCheckedChangeListener { _, checked -> draft = draft.copy(preservePitch = checked); preview(draft) }
        }
        body.addView(pitch)
        body.addView(action("Aplicar velocidade", true) {
            runCatching { apply(draft) }.fold({ committed = true; dialog.dismiss() }, {
                Toast.makeText(this, "Não foi possível aplicar. Verifique o espaço na faixa e a duração do projeto.", Toast.LENGTH_LONG).show()
            })
        })

        chooseNormal(normalSpeed, false); updateCurve(false)
        dialog = sheet("Velocidade", body)
        dialog.setOnDismissListener { if (!committed) restore() }
        showMode(clip.speedCurve.isNotEmpty())
    }
}
