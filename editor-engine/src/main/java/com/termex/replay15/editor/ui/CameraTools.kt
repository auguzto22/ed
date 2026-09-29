package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.Dialog
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast
import com.termex.replay15.editor.domain.*
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Contextual panel and keyframing tools for 3D Camera controls (FOV, position, rotation, timeline keyframing).
 */
object CameraTools {
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
        val body = column()
        lateinit var dialog: Dialog

        body.addView(sectionTitle("Câmera 3D", "Perspectiva, rotação, profundidade e campo de visão."))

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

        // Camera Keyframing at current time
        val nearby = currentCam.keyframes.minByOrNull { abs(it.timeUs - timeUs) }?.takeIf { abs(it.timeUs - timeUs) <= 20_000 }
        val currentKey = currentCam.cameraAt(timeUs)
        var draftX = currentKey.positionX
        var draftY = currentKey.positionY
        var draftZ = currentKey.positionZ
        var draftRotX = currentKey.rotationX
        var draftRotY = currentKey.rotationY
        var draftRotZ = currentKey.rotationZ
        var draftFov = currentKey.fieldOfView

        fun draft() {
            val updatedCam = if (enabledSwitch.isChecked) {
                currentCam.copy(
                    fieldOfView = draftFov,
                    positionX = draftX,
                    positionY = draftY,
                    positionZ = draftZ,
                    rotationX = draftRotX,
                    rotationY = draftRotY,
                    rotationZ = draftRotZ,
                    keyframes = emptyList(), // Temporary pose; retain the track in currentCam.
                )
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

        // Keyframe navigation
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
        navigate("Ponto anterior", currentCam.keyframes.lastOrNull { it.timeUs < timeUs })
        navigate("Próximo ponto", currentCam.keyframes.firstOrNull { it.timeUs > timeUs })
        controlsLayout.addView(navigation)

        slider(controlsLayout, "Campo de Visão (FOV)", draftFov.toInt(), 120, 15, { "$it°" }) {
            draftFov = it.toFloat()
            draft()
        }

        slider(controlsLayout, "Posição X (%)", (draftX * 100).toInt(), 100, -100, { "$it%" }) {
            draftX = it / 100f
            draft()
        }

        slider(controlsLayout, "Posição Y (%)", (draftY * 100).toInt(), 100, -100, { "$it%" }) {
            draftY = it / 100f
            draft()
        }

        slider(controlsLayout, "Posição Z (Profundidade)", (draftZ * 100).roundToInt(), 1000, -140, { "${it / 100f}" }) {
            draftZ = it / 100f
            draft()
        }

        slider(controlsLayout, "Rotação X (Pitch)", draftRotX.toInt(), 90, -90, { "$it°" }) {
            draftRotX = it.toFloat()
            draft()
        }

        slider(controlsLayout, "Rotação Y (Yaw)", draftRotY.toInt(), 180, -180, { "$it°" }) {
            draftRotY = it.toFloat()
            draft()
        }

        slider(controlsLayout, "Rotação Z (Roll)", draftRotZ.toInt(), 180, -180, { "$it°" }) {
            draftRotZ = it.toFloat()
            draft()
        }

        fun commit(cam: Camera3D) {
            runCatching {
                apply(project.copy(camera = cam))
            }.fold({
                body.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                dialog.dismiss()
            }, {
                Toast.makeText(this, "Não foi possível atualizar a câmera.", Toast.LENGTH_LONG).show()
            })
        }

        val addKeyframeBtn = action(if (nearby == null) "Adicionar Keyframe de Câmera" else "Atualizar Keyframe de Câmera", true) {
            val updatedKeyframes = currentCam.upsert(timeUs, currentCam.copy(
                positionX = draftX, positionY = draftY, positionZ = draftZ,
                rotationX = draftRotX, rotationY = draftRotY, rotationZ = draftRotZ,
                fieldOfView = draftFov)).keyframes
            currentCam = if (enabledSwitch.isChecked) {
                currentCam.copy(
                    fieldOfView = draftFov,
                    positionX = draftX,
                    positionY = draftY,
                    positionZ = draftZ,
                    rotationX = draftRotX,
                    rotationY = draftRotY,
                    rotationZ = draftRotZ,
                    keyframes = updatedKeyframes,
                )
            } else {
                Camera3D()
            }
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

        controlsLayout.addView(action("Restaurar Câmera Padrão") {
            currentCam = Camera3D()
            commit(currentCam)
        })

        body.addView(action("Aplicar Câmera", true) {
            currentCam = if (enabledSwitch.isChecked) {
                currentCam.copy(
                    fieldOfView = draftFov,
                    positionX = draftX,
                    positionY = draftY,
                    positionZ = draftZ,
                    rotationX = draftRotX,
                    rotationY = draftRotY,
                    rotationZ = draftRotZ,
                )
            } else {
                Camera3D()
            }
            if (currentCam.keyframes.isNotEmpty()) currentCam = currentCam.upsert(timeUs, currentCam)
            commit(currentCam)
        })

        updateVisibility()
        dialog = sheet("Câmera 3D", body)
        dialog.setOnDismissListener { restore() }
    }
}
