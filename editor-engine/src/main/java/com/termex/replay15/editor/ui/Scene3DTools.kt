package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.Dialog
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import com.termex.replay15.editor.domain.*
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Panels for real 3D layers: the layer list, and per layer Transformar (model pose with
 * Auto-Key), Câmera (orbit camera, lens and ready-made moves), Ambiente (studio presets,
 * HDR/360° images, tone mapping, bloom, reflective floor) and Tempo.
 *
 * Auto-Key on: every change writes a keyframe at the playhead. Auto-Key off: changes move the
 * whole layer, i.e. the same offset is applied to every keyframe (or to the static pose).
 */
object Scene3DTools {
    enum class Section(val label: String) { TRANSFORM("Transformar"), CAMERA("Câmera"), ENVIRONMENT("Ambiente"), TIME("Tempo") }

    private const val NEARBY_US = 20_000L
    /** Remembered for the session, like Aurea's "Auto-Key: On". */
    internal var autoKey = true

    class Callbacks(
        val seek: (Long) -> Unit,
        val preview: (Project) -> Unit,
        val restore: () -> Unit,
        val apply: (Project) -> Unit,
        val pickModel: () -> Unit,
        val pickEnvironment: (sceneId: String) -> Unit,
    )

    fun list(activity: Activity, project: Project, timeUs: Long, callbacks: Callbacks): Unit = with(activity) {
        val body = column()
        lateinit var dialog: Dialog
        body.addView(sectionTitle("Cenas 3D", "Modelos .glb / .gltf com luz de estúdio, chão reflexivo e câmera própria, por cima do vídeo."))
        body.addView(action(if (project.scenes3D.size >= MAX_SCENES_3D) "Limite de $MAX_SCENES_3D cenas 3D" else "Importar modelo 3D (.glb / .gltf)", true) {
            if (project.scenes3D.size >= MAX_SCENES_3D) return@action
            dialog.dismiss(); callbacks.pickModel()
        })
        if (project.scenes3D.isEmpty()) {
            body.addView(label("Nenhuma cena 3D ainda. FBX e OBJ: exporte como .glb no Blender (Arquivo › Exportar › glTF 2.0).", 12.5f, EditorStyle.MUTED))
        }
        project.scenes3D.forEach { scene ->
            val line = row()
            line.addView(label("${scene.name}\n${timeLabel(scene.startUs)} – ${timeLabel(scene.endUs)}", 13f),
                LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(action("Editar") {
                dialog.dismiss()
                val local = (timeUs - scene.startUs)
                if (local !in 0 until scene.durationUs) callbacks.seek(scene.startUs)
                edit(activity, project, scene.id, if (local in 0 until scene.durationUs) timeUs else scene.startUs, Section.TRANSFORM, callbacks)
            }, LinearLayout.LayoutParams(-2, dp(46)))
            body.addView(line)
        }
        dialog = sheet("Cenas 3D", body)
    }

    fun edit(activity: Activity, project: Project, sceneId: String, timeUs: Long, section: Section, callbacks: Callbacks): Unit = with(activity) {
        val original = project.scenes3D.firstOrNull { it.id == sceneId } ?: return@with
        var current = original
        val local = (timeUs - original.startUs).coerceIn(0L, original.durationUs)
        val body = column()
        lateinit var dialog: Dialog

        fun withScene(scene: Scene3DClip) = project.copy(scenes3D = project.scenes3D.map { if (it.id == sceneId) scene else it })
        fun draft() = callbacks.preview(withScene(current))
        fun commit(next: Scene3DClip = current, close: Boolean = true): Boolean = runCatching { callbacks.apply(withScene(next)) }.fold({
            body.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); if (close) dialog.dismiss(); true
        }, {
            Toast.makeText(this, it.message ?: "Não foi possível alterar a cena 3D", Toast.LENGTH_LONG).show(); false
        })
        fun reopen(target: Section, atUs: Long = timeUs) {
            // Saves what was changed here before switching.
            if (current == original || commit(close = false)) {
                dialog.dismiss()
                val latest = withScene(current)
                edit(activity, latest, sceneId, atUs, target, callbacks)
            }
        }
        fun guarded(change: () -> Scene3DClip) {
            runCatching(change).onSuccess { current = it; draft() }
                .onFailure { Toast.makeText(this, it.message ?: "Valor inválido", Toast.LENGTH_SHORT).show() }
        }

        body.addView(sectionTitle(current.name, "Cursor em ${timeLabel(local)} da cena (${timeLabel(current.durationUs)} no total)."))
        val tabs = row()
        Section.entries.forEach { tab ->
            tabs.addView(action(tab.label, tab == section) { if (tab != section) reopen(tab) }, LinearLayout.LayoutParams(0, dp(46), 1f))
        }
        body.addView(tabs)

        when (section) {
            Section.TRANSFORM -> transformSection(this, body, current, local, { current }, ::guarded) { target -> reopen(Section.TRANSFORM, current.startUs + target) }
            Section.CAMERA -> cameraSection(this, body, current, local, { current }, ::guarded) { target -> reopen(Section.CAMERA, current.startUs + target) }
            Section.ENVIRONMENT -> environmentSection(this, body, current, { current }, ::guarded) {
                if (current == original || commit(close = false)) { dialog.dismiss(); callbacks.pickEnvironment(sceneId) }
            }
            Section.TIME -> timeSection(this, body, project, current, timeUs, ::guarded, { reopen(Section.TIME) }) {
                // Delete the layer.
                runCatching { callbacks.apply(project.copy(scenes3D = project.scenes3D.filterNot { it.id == sceneId })) }
                    .onSuccess { dialog.dismiss() }
            }
        }
        body.addView(action("Aplicar", true) { commit() })
        dialog = sheet("Cena 3D · ${section.label}", body)
        dialog.setOnDismissListener { callbacks.restore() }
    }

    // ---------------------------------------------------------------------------------------
    // Transformar
    // ---------------------------------------------------------------------------------------
    private fun transformSection(activity: Activity, body: LinearLayout, scene: Scene3DClip, local: Long,
        current: () -> Scene3DClip, update: (() -> Scene3DClip) -> Unit, goTo: (Long) -> Unit) = with(activity) {
        val nearby = scene.poseKeyframes.firstOrNull { abs(it.timeUs - local) <= NEARBY_US }
        autoKeyRow(body, if (scene.poseKeyframes.isEmpty()) "Sem keyframes: a pose vale para a cena inteira."
            else "${scene.poseKeyframes.size} keyframes de movimento.")
        navigation(body, scene.poseKeyframes.map { it.timeUs }, local, goTo)
        var pose = scene.poseAt(local)
        fun change(next: ModelPose) {
            val before = pose
            pose = next
            update { applyPose(current(), local, before, next) }
        }
        slider(body, "Posição X (m)", (pose.positionX * 100).roundToInt(), 500, -500, ::meters) { change(pose.copy(positionX = it / 100f)) }
        slider(body, "Posição Y / altura (m)", (pose.positionY * 100).roundToInt(), 500, -200, ::meters) { change(pose.copy(positionY = it / 100f)) }
        slider(body, "Posição Z (m)", (pose.positionZ * 100).roundToInt(), 500, -500, ::meters) { change(pose.copy(positionZ = it / 100f)) }
        slider(body, "Rotação X (graus)", pose.rotationX.roundToInt().coerceIn(-180, 180), 180, -180, ::degrees) { change(pose.copy(rotationX = it.toFloat())) }
        slider(body, "Rotação Y / girar (graus)", pose.rotationY.roundToInt().coerceIn(-360, 360), 360, -360, ::degrees) { change(pose.copy(rotationY = it.toFloat())) }
        slider(body, "Rotação Z (graus)", pose.rotationZ.roundToInt().coerceIn(-180, 180), 180, -180, ::degrees) { change(pose.copy(rotationZ = it.toFloat())) }
        slider(body, "Escala (%)", (pose.scale * 100).roundToInt().coerceIn(10, 500), 500, 10, { "$it%" }) { change(pose.copy(scale = it / 100f)) }
        curveSpinner(body, nearby?.easing ?: Easing.SMOOTH, enabled = nearby != null) { easing ->
            update { current().let { s -> s.copy(poseKeyframes = s.poseKeyframes.map { if (abs(it.timeUs - local) <= NEARBY_US) it.copy(easing = easing) else it }) } }
        }
        // These change several values at once, so the panel reopens to show them.
        val actions = row()
        actions.addView(action("Zerar pose") { change(ModelPose()); goTo(local) }, LinearLayout.LayoutParams(0, dp(46), 1f))
        if (nearby != null) actions.addView(action("Excluir ponto") {
            update { current().let { s -> s.copy(poseKeyframes = s.poseKeyframes.filterNot { abs(it.timeUs - local) <= NEARBY_US }) } }
            goTo(local)
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        if (scene.poseKeyframes.isNotEmpty()) actions.addView(action("Limpar pontos") {
            update { current().let { s -> s.copy(pose = s.poseAt(local), poseKeyframes = emptyList()) } }
            goTo(local)
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        body.addView(actions)
    }

    /** Auto-Key writes [next] at the playhead; otherwise the change is an offset of the whole track. */
    internal fun applyPose(scene: Scene3DClip, local: Long, before: ModelPose, next: ModelPose): Scene3DClip {
        if (autoKey) {
            // The first key also pins the pose the layer had before, so the move starts from it.
            val seeded = if (scene.poseKeyframes.isEmpty() && local > NEARBY_US) scene.withPoseKey(0L, scene.pose) else scene
            return seeded.withPoseKey(local, next)
        }
        if (scene.poseKeyframes.isEmpty()) return scene.copy(pose = next)
        val ratio = if (before.scale > 0f) next.scale / before.scale else 1f
        fun shift(p: ModelPose) = ModelPose(
            (p.positionX + next.positionX - before.positionX).coerceIn(-50f, 50f),
            (p.positionY + next.positionY - before.positionY).coerceIn(-50f, 50f),
            (p.positionZ + next.positionZ - before.positionZ).coerceIn(-50f, 50f),
            (p.rotationX + next.rotationX - before.rotationX).coerceIn(-MAX_TURN_DEGREES, MAX_TURN_DEGREES),
            (p.rotationY + next.rotationY - before.rotationY).coerceIn(-MAX_TURN_DEGREES, MAX_TURN_DEGREES),
            (p.rotationZ + next.rotationZ - before.rotationZ).coerceIn(-MAX_TURN_DEGREES, MAX_TURN_DEGREES),
            (p.scale * ratio).coerceIn(.01f, 100f),
        )
        return scene.copy(pose = shift(scene.pose), poseKeyframes = scene.poseKeyframes.map { it.copy(pose = shift(it.pose)) })
    }

    // ---------------------------------------------------------------------------------------
    // Câmera
    // ---------------------------------------------------------------------------------------
    private fun cameraSection(activity: Activity, body: LinearLayout, scene: Scene3DClip, local: Long,
        current: () -> Scene3DClip, update: (() -> Scene3DClip) -> Unit, goTo: (Long) -> Unit) = with(activity) {
        val nearby = scene.cameraKeyframes.firstOrNull { abs(it.timeUs - local) <= NEARBY_US }
        autoKeyRow(body, if (scene.cameraKeyframes.isEmpty()) "Sem keyframes: a câmera fica parada."
            else "${scene.cameraKeyframes.size} keyframes de câmera.")
        navigation(body, scene.cameraKeyframes.map { it.timeUs }, local, goTo)

        body.addView(sectionTitle("Movimentos prontos", "Criam os keyframes daqui até o fim da cena."))
        val moves = SceneCameraMove.entries
        var move = SceneCameraMove.TURNTABLE
        body.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@with, android.R.layout.simple_spinner_dropdown_item, moves.map { it.label })
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { move = moves[position] }
            }
        }, LinearLayout.LayoutParams(-1, dp(52)))
        body.addView(action("Aplicar movimento a partir daqui") { update { SceneCameraMoves.apply(current(), move, local) }; goTo(local) })

        body.addView(sectionTitle("Câmera neste ponto"))
        var camera = scene.cameraAt(local)
        fun change(next: OrbitCamera) {
            val before = camera
            camera = next
            update { applyCamera(current(), local, before, next) }
        }
        slider(body, "Órbita (graus)", camera.yaw.roundToInt().coerceIn(-360, 360), 360, -360, ::degrees) { change(camera.copy(yaw = it.toFloat())) }
        slider(body, "Altura do olhar (graus)", camera.pitch.roundToInt(), OrbitCamera.MAX_PITCH.toInt(), OrbitCamera.MIN_PITCH.toInt(), ::degrees) {
            change(camera.copy(pitch = it.toFloat()))
        }
        slider(body, "Distância (m)", (camera.distance * 10).roundToInt().coerceIn(3, 300), 300, 3, { meters(it * 10) }) {
            change(camera.copy(distance = it / 10f))
        }
        slider(body, "Lente (mm)", camera.focalLength.roundToInt().coerceIn(12, 200), 200, 12, { "$it mm" }) { change(camera.copy(focalLength = it.toFloat())) }
        slider(body, "Mirar altura (m)", (camera.targetY * 100).roundToInt().coerceIn(-200, 500), 500, -200, ::meters) { change(camera.copy(targetY = it / 100f)) }
        slider(body, "Mirar lado X (m)", (camera.targetX * 100).roundToInt().coerceIn(-500, 500), 500, -500, ::meters) { change(camera.copy(targetX = it / 100f)) }
        slider(body, "Mirar fundo Z (m)", (camera.targetZ * 100).roundToInt().coerceIn(-500, 500), 500, -500, ::meters) { change(camera.copy(targetZ = it / 100f)) }
        slider(body, "Inclinação / roll (graus)", camera.roll.roundToInt().coerceIn(-45, 45), 45, -45, ::degrees) { change(camera.copy(roll = it.toFloat())) }
        curveSpinner(body, nearby?.easing ?: Easing.SMOOTH, enabled = nearby != null) { easing ->
            update { current().let { s -> s.copy(cameraKeyframes = s.cameraKeyframes.map { if (abs(it.timeUs - local) <= NEARBY_US) it.copy(easing = easing) else it }) } }
        }
        val actions = row()
        actions.addView(action("Câmera padrão") { change(OrbitCamera()); goTo(local) }, LinearLayout.LayoutParams(0, dp(46), 1f))
        if (nearby != null) actions.addView(action("Excluir ponto") {
            update { current().let { s -> s.copy(cameraKeyframes = s.cameraKeyframes.filterNot { abs(it.timeUs - local) <= NEARBY_US }) } }
            goTo(local)
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        if (scene.cameraKeyframes.isNotEmpty()) actions.addView(action("Limpar pontos") {
            update { current().let { s -> s.copy(camera = s.cameraAt(local), cameraKeyframes = emptyList()) } }
            goTo(local)
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        body.addView(actions)
    }

    internal fun applyCamera(scene: Scene3DClip, local: Long, before: OrbitCamera, next: OrbitCamera): Scene3DClip {
        if (autoKey) {
            val seeded = if (scene.cameraKeyframes.isEmpty() && local > NEARBY_US) scene.withCameraKey(0L, scene.camera) else scene
            return seeded.withCameraKey(local, next)
        }
        if (scene.cameraKeyframes.isEmpty()) return scene.copy(camera = next)
        fun ratio(a: Float, b: Float) = if (a > 0f) b / a else 1f
        val distance = ratio(before.distance, next.distance)
        val focal = ratio(before.focalLength, next.focalLength)
        fun shift(c: OrbitCamera) = OrbitCamera(
            (c.yaw + next.yaw - before.yaw).coerceIn(-MAX_TURN_DEGREES, MAX_TURN_DEGREES),
            (c.pitch + next.pitch - before.pitch).coerceIn(OrbitCamera.MIN_PITCH, OrbitCamera.MAX_PITCH),
            (c.distance * distance).coerceIn(OrbitCamera.MIN_DISTANCE, OrbitCamera.MAX_DISTANCE),
            (c.targetX + next.targetX - before.targetX).coerceIn(-50f, 50f),
            (c.targetY + next.targetY - before.targetY).coerceIn(-50f, 50f),
            (c.targetZ + next.targetZ - before.targetZ).coerceIn(-50f, 50f),
            (c.focalLength * focal).coerceIn(OrbitCamera.MIN_FOCAL, OrbitCamera.MAX_FOCAL),
            (c.roll + next.roll - before.roll).coerceIn(-180f, 180f),
        )
        return scene.copy(camera = shift(scene.camera), cameraKeyframes = scene.cameraKeyframes.map { it.copy(camera = shift(it.camera)) })
    }

    // ---------------------------------------------------------------------------------------
    // Ambiente
    // ---------------------------------------------------------------------------------------
    private fun environmentSection(activity: Activity, body: LinearLayout, scene: Scene3DClip,
        current: () -> Scene3DClip, update: (() -> Scene3DClip) -> Unit, pickImage: () -> Unit) = with(activity) {
        var env = scene.environment
        fun change(next: Environment3D) { env = next; update { current().copy(environment = next) } }

        body.addView(sectionTitle("Estúdio", "Luz e fundo prontos, sem precisar de imagem."))
        choice(body, StudioPreset.entries.map { it.label }, env.preset.ordinal, enabled = env.environmentPath.isBlank()) {
            change(env.copy(preset = StudioPreset.entries[it]))
        }
        body.addView(sectionTitle("Imagem de ambiente (HDR / 360°)", "Panorama 2:1 .hdr, .jpg ou .png. Ilumina a cena e pode virar o fundo."))
        val imageRow = row()
        imageRow.addView(action(if (env.environmentPath.isBlank()) "Importar imagem" else "Trocar imagem") { pickImage() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        if (env.environmentPath.isNotBlank()) imageRow.addView(action("Usar estúdio") { change(env.copy(environmentPath = "")) },
            LinearLayout.LayoutParams(0, dp(46), 1f))
        body.addView(imageRow)
        slider(body, "Intensidade da luz (%)", (env.lightIntensity * 100).roundToInt(), 400, 0, { "$it%" }) { change(env.copy(lightIntensity = it / 100f)) }
        slider(body, "Girar a luz (graus)", env.lightRotation.roundToInt(), 180, -180, ::degrees) { change(env.copy(lightRotation = it.toFloat())) }
        checkbox(body, "Mostrar ambiente como fundo", env.showBackground) { change(env.copy(showBackground = it)) }

        body.addView(sectionTitle("Imagem"))
        slider(body, "Exposição (EV)", (env.exposure * 10).roundToInt(), 40, -40, { ev -> String.format(java.util.Locale.ROOT, "%+.1f", ev / 10f) }) {
            change(env.copy(exposure = it / 10f))
        }
        body.addView(label("Tonemap", 13f, EditorStyle.MUTED))
        choice(body, ToneMapping.entries.map { it.label }, env.toneMapping.ordinal) { change(env.copy(toneMapping = ToneMapping.entries[it])) }
        slider(body, "Bloom (brilho nas luzes) (%)", (env.bloom * 100).roundToInt(), 100, 0, { "$it%" }) { change(env.copy(bloom = it / 100f)) }

        body.addView(sectionTitle("Chão e sombras"))
        checkbox(body, "Chão", env.floor) { change(env.copy(floor = it)) }
        slider(body, "Reflexo no chão (%)", (env.floorReflection * 100).roundToInt(), 100, 0, { "$it%" }) { change(env.copy(floorReflection = it / 100f)) }
        checkbox(body, "Sombras", env.shadows) { change(env.copy(shadows = it)) }
    }

    // ---------------------------------------------------------------------------------------
    // Tempo
    // ---------------------------------------------------------------------------------------
    private fun timeSection(activity: Activity, body: LinearLayout, project: Project, scene: Scene3DClip, timeUs: Long,
        update: (() -> Scene3DClip) -> Unit, refresh: () -> Unit, delete: () -> Unit) = with(activity) {
        var clip = scene
        fun change(next: () -> Scene3DClip) { update { next().also { clip = it } } }
        body.addView(label("Início ${timeLabel(clip.startUs)} · duração ${timeLabel(clip.durationUs)}", 13f, EditorStyle.MUTED))
        body.addView(action("Começar no cursor (${timeLabel(timeUs)})") {
            change { clip.movedTo(timeUs.coerceIn(0L, (project.durationUs - Scene3DClip.MIN_SCENE_DURATION_US).coerceAtLeast(0L))).let { moved ->
                if (moved.endUs > project.durationUs && project.durationUs > moved.startUs) moved.withDuration(project.durationUs - moved.startUs) else moved
            } }
            refresh()
        })
        val maxTenths = ((project.durationUs - clip.startUs) / 100_000L).toInt().coerceAtLeast(3)
        slider(body, "Duração", (clip.durationUs / 100_000L).toInt().coerceIn(3, maxTenths), maxTenths, 3,
            { "${(it / 10f).toString().replace('.', ',')} s" }) { change { clip.withDuration(it * 100_000L) } }
        body.addView(sectionTitle("Animação do modelo", "Toca a primeira animação gravada no arquivo, em loop."))
        checkbox(body, "Tocar animação", clip.playAnimation) { on -> change { clip.copy(playAnimation = on) } }
        slider(body, "Velocidade (%)", (clip.animationSpeed * 100).roundToInt(), 400, 0, { "$it%" }) { change { clip.copy(animationSpeed = it / 100f) } }
        body.addView(action("Excluir cena 3D") { delete() })
    }

    // ---------------------------------------------------------------------------------------
    // Shared controls
    // ---------------------------------------------------------------------------------------
    private fun Activity.autoKeyRow(body: LinearLayout, status: String) {
        body.addView(CheckBox(this).apply {
            text = "Auto-Key (cada ajuste cria um ponto no cursor)"
            isChecked = autoKey
            setOnCheckedChangeListener { _, checked -> autoKey = checked }
        })
        body.addView(label(if (autoKey) status else "$status Sem Auto-Key, o ajuste move a animação inteira.", 12.5f, EditorStyle.MUTED))
    }

    private fun Activity.navigation(body: LinearLayout, keys: List<Long>, local: Long, goTo: (Long) -> Unit) {
        if (keys.isEmpty()) return
        val nav = row()
        val previous = keys.lastOrNull { it < local - NEARBY_US }
        val next = keys.firstOrNull { it > local + NEARBY_US }
        nav.addView(action("Ponto anterior") { previous?.let(goTo) }.apply { isEnabled = previous != null; alpha = if (isEnabled) 1f else .4f },
            LinearLayout.LayoutParams(0, dp(46), 1f))
        nav.addView(action("Próximo ponto") { next?.let(goTo) }.apply { isEnabled = next != null; alpha = if (isEnabled) 1f else .4f },
            LinearLayout.LayoutParams(0, dp(46), 1f))
        body.addView(nav)
    }

    private fun Activity.curveSpinner(body: LinearLayout, selected: Easing, enabled: Boolean, choose: (Easing) -> Unit) {
        // No Bezier handles here: the keyframe panel of the clip offers them.
        val curves = Easing.entries.filter { it != Easing.BEZIER || selected == Easing.BEZIER }
        body.addView(label(if (enabled) "Curva até o próximo ponto" else "Curva (crie um ponto no cursor para escolher)", 13f, EditorStyle.MUTED))
        body.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@curveSpinner, android.R.layout.simple_spinner_dropdown_item, curves.map { it.label })
            setSelection(curves.indexOf(selected).coerceAtLeast(0))
            isEnabled = enabled
            var shown = curves.indexOf(selected).coerceAtLeast(0)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    // Android also reports the initial selection; only a different item is a choice.
                    if (position == shown) return
                    shown = position
                    choose(curves[position])
                }
            }
        }, LinearLayout.LayoutParams(-1, dp(52)))
    }

    private fun Activity.choice(body: LinearLayout, options: List<String>, selected: Int, enabled: Boolean = true, choose: (Int) -> Unit) {
        body.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@choice, android.R.layout.simple_spinner_dropdown_item, options)
            setSelection(selected)
            isEnabled = enabled
            var shown = selected
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position == shown) return
                    shown = position
                    choose(position)
                }
            }
        }, LinearLayout.LayoutParams(-1, dp(52)))
    }

    private fun Activity.checkbox(body: LinearLayout, text: String, checked: Boolean, change: (Boolean) -> Unit) {
        body.addView(CheckBox(this).apply {
            this.text = text; isChecked = checked
            setOnCheckedChangeListener { _, on -> change(on) }
        })
    }

    private fun meters(centimeters: Int) = String.format(java.util.Locale.ROOT, "%.2f m", centimeters / 100f).replace('.', ',')
    private fun degrees(value: Int) = "$value°"
}
