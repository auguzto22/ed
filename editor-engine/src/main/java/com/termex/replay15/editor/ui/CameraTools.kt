package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.Dialog
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Toast
import com.termex.replay15.editor.domain.*
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Contextual panel for the project 3D camera: ready-made professional moves, the pose at the
 * playhead (with precise roll), per-keyframe speed curves and edge-free framing.
 */
object CameraTools {
    /** Same window [Camera3D.upsert] uses to treat a keyframe as "at the playhead". */
    private const val NEARBY_US = 20_000L
    private const val MAX_MOVE_SLIDER_US = 30_000_000L

    fun show(
        activity: Activity,
        project: Project,
        timeUs: Long,
        seek: (Long) -> Unit,
        preview: (Project) -> Unit = {},
        restore: () -> Unit = {},
        apply: (Project) -> Unit
    ): Unit = with(activity) {
        val initialCamera = project.camera
        var currentCam = initialCamera
        val aspect = project.dimensions().let { (w, h) -> w.toFloat() / h.coerceAtLeast(1) }
        val body = column()
        lateinit var dialog: Dialog

        body.addView(sectionTitle("Câmera 3D", "Movimentos de câmera, perspectiva, rotação e campo de visão sobre o quadro final."))

        val enabledSwitch = CheckBox(this).apply {
            text = "Ativar Câmera 3D no Projeto"
            isChecked = !currentCam.isDefault
        }
        body.addView(enabledSwitch)

        val controlsLayout = column()
        body.addView(controlsLayout)

        fun updateVisibility() {
            controlsLayout.visibility = if (enabledSwitch.isChecked) View.VISIBLE else View.GONE
        }

        val nearby = currentCam.keyframes.minByOrNull { abs(it.timeUs - timeUs) }?.takeIf { abs(it.timeUs - timeUs) <= NEARBY_US }
        val currentKey = currentCam.cameraAt(timeUs)
        var draftX = currentKey.positionX
        var draftY = currentKey.positionY
        var draftZ = currentKey.positionZ
        var draftRotX = currentKey.rotationX
        var draftRotY = currentKey.rotationY
        var draftRotZ = currentKey.rotationZ
        var draftFov = currentKey.fieldOfView
        var draftEasing = nearby?.easing ?: Easing.SMOOTH

        /** The camera with the slider pose as its static pose; the stored track is kept. */
        fun posed(cam: Camera3D): Camera3D = cam.copy(
            fieldOfView = draftFov,
            positionX = draftX,
            positionY = draftY,
            positionZ = draftZ,
            rotationX = draftRotX,
            rotationY = draftRotY,
            rotationZ = draftRotZ,
        )

        /** False when a new keyframe at the playhead would exceed the track limit. */
        fun canKey(cam: Camera3D): Boolean =
            cam.keyframes.size < CameraMoves.MAX_KEYFRAMES || cam.keyframes.any { abs(it.timeUs - timeUs) <= NEARBY_US }

        fun limitReached() = Toast.makeText(this, "Limite de ${CameraMoves.MAX_KEYFRAMES} pontos de câmera", Toast.LENGTH_LONG).show()

        /** Writes the slider pose as a keyframe at the playhead, with the chosen curve. */
        fun keyed(cam: Camera3D): Camera3D {
            val updated = cam.upsert(timeUs, posed(cam))
            return updated.copy(keyframes = updated.keyframes.map { if (it.timeUs == timeUs) it.copy(easing = draftEasing) else it })
        }

        fun draft() {
            val updatedCam = if (enabledSwitch.isChecked) {
                posed(currentCam).copy(keyframes = emptyList()) // Temporary pose; retain the track in currentCam.
            } else {
                Camera3D()
            }
            preview(project.copy(camera = updatedCam))
        }

        enabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) {
                currentCam = Camera3D()
            }
            updateVisibility()
            draft()
        }

        fun commit(cam: Camera3D, message: String? = null) {
            runCatching {
                apply(project.copy(camera = cam))
            }.fold({
                body.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                dialog.dismiss()
            }, {
                Toast.makeText(this, "Não foi possível atualizar a câmera.", Toast.LENGTH_LONG).show()
            })
        }

        // ---------------------------------------------------------------------------------
        // Ready-made moves
        // ---------------------------------------------------------------------------------
        controlsLayout.addView(sectionTitle("Movimentos prontos",
            "Cria os keyframes a partir de ${timeLabel(timeUs)}, continuando da pose atual."))
        val moves = CameraMove.entries
        var selectedMove = CameraMove.PUSH_IN
        val moveDescription = label(selectedMove.description, 12.5f, EditorStyle.MUTED).apply { setPadding(dp(2), dp(4), dp(2), dp(4)) }
        val moveSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@with, android.R.layout.simple_spinner_dropdown_item, moves.map { it.label })
            setSelection(moves.indexOf(selectedMove))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selectedMove = moves[position]
                    moveDescription.text = selectedMove.description
                }
            }
        }
        controlsLayout.addView(moveSpinner, LinearLayout.LayoutParams(-1, dp(52)))
        controlsLayout.addView(moveDescription)

        val remainingUs = (project.durationUs - timeUs).coerceAtLeast(0L)
        val maxMoveUs = minOf(remainingUs, MAX_MOVE_SLIDER_US, CameraMoves.MAX_DURATION_US)
        val canMove = maxMoveUs >= CameraMoves.MIN_DURATION_US
        val minTenths = (CameraMoves.MIN_DURATION_US / 100_000L).toInt()
        val maxTenths = if (canMove) (maxMoveUs / 100_000L).toInt().coerceAtLeast(minTenths) else minTenths
        var moveTenths = 30.coerceIn(minTenths, maxTenths)
        var moveIntensity = 100
        var moveEasing = Easing.SMOOTH
        if (canMove) {
            slider(controlsLayout, "Duração", moveTenths, maxTenths, minTenths, { "${(it / 10f).toString().replace('.', ',')} s" }) {
                moveTenths = it
            }
            slider(controlsLayout, "Intensidade", moveIntensity, (CameraMoves.MAX_INTENSITY * 100).roundToInt(),
                (CameraMoves.MIN_INTENSITY * 100).roundToInt(), { "$it%" }) {
                moveIntensity = it
            }
            // No Bezier handles in this panel, and "Manter" would turn the move into a cut.
            val curves = Easing.entries.filter { it != Easing.BEZIER && it != Easing.HOLD }
            controlsLayout.addView(label("Curva de velocidade", 13f, EditorStyle.MUTED).apply { setPadding(0, dp(10), 0, 0) })
            controlsLayout.addView(Spinner(this).apply {
                adapter = ArrayAdapter(this@with, android.R.layout.simple_spinner_dropdown_item, curves.map { it.label })
                setSelection(curves.indexOf(moveEasing))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        moveEasing = curves[position]
                    }
                }
            }, LinearLayout.LayoutParams(-1, dp(52)))
        } else {
            controlsLayout.addView(label("Leve o cursor para antes do fim do vídeo para criar um movimento.", 12.5f, EditorStyle.MUTED))
        }
        val protectEdges = CheckBox(this).apply {
            text = "Evitar bordas (amplia levemente o quadro)"
            isChecked = true
        }
        controlsLayout.addView(protectEdges)
        controlsLayout.addView(action("Aplicar movimento a partir daqui", true) {
            if (!canMove) {
                Toast.makeText(this, "Não há tempo suficiente até o fim do vídeo.", Toast.LENGTH_LONG).show()
                return@action
            }
            runCatching {
                // Start from the pose on the sliders, so tweaks made here carry into the move.
                // A full track cannot take that extra point; the move then starts from the track itself.
                val source = when {
                    currentCam.keyframes.isEmpty() -> posed(currentCam)
                    canKey(currentCam) -> keyed(currentCam)
                    else -> currentCam
                }
                val spec = CameraMoveSpec(
                    move = selectedMove,
                    startUs = timeUs,
                    durationUs = (moveTenths * 100_000L).coerceIn(CameraMoves.MIN_DURATION_US, maxMoveUs),
                    intensity = moveIntensity / 100f,
                    easing = moveEasing,
                    protectEdges = protectEdges.isChecked,
                    aspect = aspect,
                    seed = (timeUs / 1_000L).toInt(),
                )
                CameraMoves.apply(source, spec)
            }.fold({ result ->
                currentCam = result.camera
                commit(currentCam, if (result.edgesCovered) null
                    else "Movimento aplicado. Neste ângulo não dá para esconder as bordas.")
            }, { error ->
                Toast.makeText(this, error.message ?: "Não foi possível criar o movimento.", Toast.LENGTH_LONG).show()
            })
        })

        // ---------------------------------------------------------------------------------
        // Pose at the playhead
        // ---------------------------------------------------------------------------------
        controlsLayout.addView(sectionTitle("Pose neste ponto",
            if (currentCam.keyframes.isEmpty()) "Sem keyframes: a pose vale para o vídeo inteiro."
            else "${currentCam.keyframes.size} keyframes. A curva controla o caminho até o próximo ponto."))

        val navigation = row()
        fun navigate(label: String, target: CameraKeyframe?) {
            navigation.addView(action(label) {
                if (target != null) {
                    dialog.dismiss()
                    seek(target.timeUs)
                    show(activity, project, target.timeUs, seek, preview, restore, apply)
                }
            }.apply {
                isEnabled = target != null
                alpha = if (isEnabled) 1f else 0.4f
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        navigate("Ponto anterior", currentCam.keyframes.lastOrNull { it.timeUs < timeUs - NEARBY_US })
        navigate("Próximo ponto", currentCam.keyframes.firstOrNull { it.timeUs > timeUs + NEARBY_US })
        controlsLayout.addView(navigation)

        fun degrees(tenths: Int) = "${(tenths / 10f).toString().replace('.', ',')}°"

        val fovSlider = slider(controlsLayout, "Campo de Visão (FOV)", draftFov.roundToInt().coerceIn(15, 120), 120, 15, { "$it°" }) {
            draftFov = it.toFloat()
            draft()
        }

        val xSlider = slider(controlsLayout, "Posição X (%)", (draftX * 100).roundToInt(), 100, -100, { "$it%" }) {
            draftX = it / 100f
            draft()
        }

        val ySlider = slider(controlsLayout, "Posição Y (%)", (draftY * 100).roundToInt(), 100, -100, { "$it%" }) {
            draftY = it / 100f
            draft()
        }

        val zSlider = slider(controlsLayout, "Posição Z (Profundidade)", (draftZ * 100).roundToInt(), 1000, -140, { "${it / 100f}" }) {
            draftZ = it / 100f
            draft()
        }

        val rotXSlider = slider(controlsLayout, "Rotação X (Pitch)", (draftRotX * 10).roundToInt(), 900, -900, ::degrees) {
            draftRotX = it / 10f
            draft()
        }

        val rotYSlider = slider(controlsLayout, "Rotação Y (Yaw)", (draftRotY * 10).roundToInt(), 1800, -1800, ::degrees) {
            draftRotY = it / 10f
            draft()
        }

        val rotZSlider = slider(controlsLayout, "Rotação Z (Roll / horizonte)", (draftRotZ * 10).roundToInt(), 1800, -1800, ::degrees) {
            draftRotZ = it / 10f
            draft()
        }

        fun setSlider(seekBar: SeekBar, value: Int) {
            seekBar.progress = value.coerceIn(seekBar.min, seekBar.max)
        }

        // Precise roll: the slider is too coarse for levelling a horizon on a phone screen.
        val rollRow = row()
        fun roll(label: String, change: (Float) -> Float) {
            rollRow.addView(action(label) {
                draftRotZ = change(draftRotZ).coerceIn(-180f, 180f)
                draftRotZ = (draftRotZ * 10f).roundToInt() / 10f
                setSlider(rotZSlider, (draftRotZ * 10).roundToInt())
                draft()
            }, LinearLayout.LayoutParams(0, dp(46), 1f))
        }
        roll("−1°") { it - 1f }
        roll("−0,1°") { it - .1f }
        roll("Nivelar") { 0f }
        roll("+0,1°") { it + .1f }
        roll("+1°") { it + 1f }
        controlsLayout.addView(rollRow)

        val resetRow = row()
        resetRow.addView(action("Zerar posição") {
            draftX = 0f; draftY = 0f; draftZ = 0f
            setSlider(xSlider, 0); setSlider(ySlider, 0); setSlider(zSlider, 0)
            draft()
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        resetRow.addView(action("Zerar rotação") {
            draftRotX = 0f; draftRotY = 0f; draftRotZ = 0f
            setSlider(rotXSlider, 0); setSlider(rotYSlider, 0); setSlider(rotZSlider, 0)
            draft()
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        resetRow.addView(action("Lente 60°") {
            draftFov = 60f
            setSlider(fovSlider, 60)
            draft()
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        controlsLayout.addView(resetRow)

        // Speed curve of the keyframe at the playhead (towards the next keyframe).
        val keyCurves = Easing.entries.filter { it != Easing.BEZIER || draftEasing == Easing.BEZIER }
        controlsLayout.addView(label("Curva até o próximo ponto", 13f, EditorStyle.MUTED).apply { setPadding(0, dp(10), 0, 0) })
        controlsLayout.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@with, android.R.layout.simple_spinner_dropdown_item, keyCurves.map { it.label })
            setSelection(keyCurves.indexOf(draftEasing).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    draftEasing = keyCurves[position]
                }
            }
        }, LinearLayout.LayoutParams(-1, dp(52)))

        val addKeyframeBtn = action(if (nearby == null) "Adicionar Keyframe de Câmera" else "Atualizar Keyframe de Câmera", true) {
            if (enabledSwitch.isChecked && !canKey(currentCam)) {
                limitReached()
                return@action
            }
            currentCam = if (enabledSwitch.isChecked) posed(keyed(currentCam)) else Camera3D()
            commit(currentCam)
        }
        controlsLayout.addView(addKeyframeBtn)

        if (nearby != null) {
            controlsLayout.addView(action("Excluir este keyframe") {
                val updatedKeyframes = currentCam.keyframes.filterNot { it.timeUs == nearby.timeUs }
                currentCam = currentCam.copy(keyframes = updatedKeyframes)
                commit(currentCam)
            })
        }

        if (currentCam.keyframes.isNotEmpty()) {
            controlsLayout.addView(action("Remover todos os keyframes (mantém esta pose)") {
                currentCam = posed(currentCam).copy(keyframes = emptyList())
                commit(currentCam)
            })
        }

        controlsLayout.addView(action("Enquadrar sem bordas") {
            if (currentCam.keyframes.isNotEmpty() && !canKey(currentCam)) {
                limitReached()
                return@action
            }
            val cam = if (currentCam.keyframes.isEmpty()) posed(currentCam) else posed(keyed(currentCam))
            val framed = runCatching { CameraFraming.protect(cam, aspect) }.getOrNull()
            if (framed == null) {
                Toast.makeText(this, "Neste ângulo não dá para esconder as bordas.", Toast.LENGTH_LONG).show()
            } else {
                currentCam = framed
                commit(currentCam)
            }
        })

        controlsLayout.addView(action("Restaurar Câmera Padrão") {
            currentCam = Camera3D()
            commit(currentCam)
        })

        body.addView(action("Aplicar Câmera", true) {
            if (enabledSwitch.isChecked && currentCam.keyframes.isNotEmpty() && !canKey(currentCam)) {
                limitReached()
                return@action
            }
            currentCam = if (enabledSwitch.isChecked) {
                val posedCam = posed(currentCam)
                if (posedCam.keyframes.isNotEmpty()) posed(keyed(posedCam)) else posedCam
            } else {
                Camera3D()
            }
            commit(currentCam)
        })

        updateVisibility()
        dialog = sheet("Câmera 3D", body)
        dialog.setOnDismissListener { restore() }
    }
}
