package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.Dialog
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import com.termex.replay15.editor.domain.*

/**
 * Painel dos balões de animação (lilás na timeline): preset, intensidade e easing, mais
 * duplicar, excluir e "Converter em keyframes". Toda mudança passa por [apply], que é o
 * funil único de edição do editor (desfazer, refazer e sanitização incluídos).
 */
class AnimationBalloonTools(
    private val activity: Activity,
    private val project: () -> Project,
    private val apply: (Project) -> Unit,
    private val position: () -> Long,
    private val select: (String) -> Unit = {},
) {
    private fun report(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()

    /** Cria um balão no cursor (ou no começo do alvo, se o cursor está fora dele) e abre o painel. */
    fun create(targetId: String, preset: MotionPreset = MotionPreset.ZOOM_IN) {
        val created = AnimationEditing.create(project(), targetId, position(), preset)
        if (created == null) { report("Não há espaço para outro movimento aqui"); return }
        apply(created.first)
        select(created.second.id)
        open(created.second.id)
    }

    fun open(id: String): Unit = with(activity) {
        if (isFinishing) return@with
        val clip = project().animationClips.firstOrNull { it.id == id } ?: return@with
        val body = column()
        body.addView(sectionTitle("Movimento", "Vale só dentro do balão. Os keyframes manuais continuam valendo por baixo."))
        lateinit var dialog: Dialog
        fun change(edit: (AnimationClip) -> AnimationClip) {
            apply(AnimationEditing.update(project(), id, edit))
            dialog.dismiss(); open(id)
        }

        body.addView(label("PRESET", 10f, EditorStyle.MUTED))
        val presets = GridLayout(this).apply { columnCount = 2; alignmentMode = GridLayout.ALIGN_BOUNDS }
        MotionPreset.entries.forEach { preset ->
            presets.addView(action(preset.label, accent = preset == clip.preset) { change { it.copy(preset = preset) } },
                GridLayout.LayoutParams().apply {
                    width = 0; height = dp(52); columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                })
        }
        body.addView(presets)

        val intensityValue = label("INTENSIDADE  ${"%.2f".format(clip.intensity)}x", 10f, EditorStyle.MUTED)
        body.addView(intensityValue)
        val steps = 100
        fun intensityAt(progress: Int) = MIN_ANIMATION_INTENSITY +
            (MAX_ANIMATION_INTENSITY - MIN_ANIMATION_INTENSITY) * progress / steps
        body.addView(SeekBar(this).apply {
            max = steps; minimumHeight = dp(52)
            progress = (((clip.intensity - MIN_ANIMATION_INTENSITY) / (MAX_ANIMATION_INTENSITY - MIN_ANIMATION_INTENSITY)) * steps).toInt().coerceIn(0, steps)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) intensityValue.text = "INTENSIDADE  ${"%.2f".format(intensityAt(value))}x"
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                // Uma edição só ao soltar: arrastar não enche o histórico de desfazer.
                override fun onStopTrackingTouch(bar: SeekBar) {
                    val value = intensityAt(bar.progress).coerceIn(MIN_ANIMATION_INTENSITY, MAX_ANIMATION_INTENSITY)
                    apply(AnimationEditing.update(project(), id) { it.copy(intensity = value) })
                }
            })
        })

        body.addView(label("EASING", 10f, EditorStyle.MUTED))
        val easings = GridLayout(this).apply { columnCount = 2; alignmentMode = GridLayout.ALIGN_BOUNDS }
        EASING_CHOICES.forEach { easing ->
            easings.addView(action(easing.label, accent = easing == clip.easing) { change { it.copy(easing = easing) } },
                GridLayout.LayoutParams().apply {
                    width = 0; height = dp(48); columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                })
        }
        body.addView(easings)

        val buttons = row()
        buttons.addView(action("Duplicar") {
            val copy = AnimationEditing.duplicate(project(), id)
            if (copy == null) report("Não cabe outro balão ao lado deste") else {
                apply(copy.first); dialog.dismiss(); select(copy.second.id); open(copy.second.id)
            }
        }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(4) })
        buttons.addView(action("Excluir") {
            apply(AnimationEditing.remove(project(), id)); dialog.dismiss()
        }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(4) })
        body.addView(buttons)
        body.addView(action("Converter em keyframes") {
            val converted = AnimationKeyframes.convert(project(), id)
            if (converted == null) report("Não foi possível converter: limite de keyframes")
            else { apply(converted); dialog.dismiss(); report("Movimento convertido em keyframes editáveis") }
        })
        dialog = sheet("Animação", body)
    }

    private companion object {
        val EASING_CHOICES = listOf(
            Easing.LINEAR, Easing.SMOOTH, Easing.EASE_IN, Easing.EASE_OUT,
            Easing.EASE_IN_OUT, Easing.SPRING, Easing.BOUNCE, Easing.BACK,
        )
    }
}
