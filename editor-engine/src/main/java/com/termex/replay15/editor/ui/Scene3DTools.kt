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
 * Panels for real 3D layers: the layer list, and per layer Transformar (the model or a null,
 * with Auto-Key and parenting), Câmera (main camera and camera cuts, lens, ready-made moves,
 * Real Camera), Ambiente (studio presets, HDR/360° images, tone mapping, bloom, reflective
 * floor) and Tempo.
 *
 * Auto-Key on: every change writes a keyframe at the playhead. Auto-Key off: changes move the
 * whole track, i.e. the same offset is applied to every keyframe (or to the static value).
 */
object Scene3DTools {
    enum class Section(val label: String) { TRANSFORM("Transformar"), CAMERA("Câmera"), ENVIRONMENT("Ambiente"), TIME("Tempo") }

    private const val NEARBY_US = 20_000L
    /** Remembered for the session, like Aurea's "Auto-Key: On". */
    internal var autoKey = true
    /** Object (model or null) and camera last edited per scene, so the panel reopens on them. */
    private val selectedTarget = HashMap<String, String>()
    private val selectedCamera = HashMap<String, String>()

    class Callbacks(
        val seek: (Long) -> Unit,
        val preview: (Project) -> Unit,
        val restore: () -> Unit,
        val apply: (Project) -> Unit,
        val pickModel: () -> Unit,
        val pickEnvironment: (sceneId: String) -> Unit,
        val openRealCamera: (sceneId: String, cameraId: String) -> Unit,
    )

    fun selectTarget(sceneId: String, target: String) { selectedTarget[sceneId] = target }
    fun targetOf(sceneId: String): String = selectedTarget[sceneId] ?: MODEL_TARGET
    fun cameraOf(sceneId: String): String = selectedCamera[sceneId] ?: MAIN_CAMERA
    fun selectCamera(sceneId: String, cameraId: String) { selectedCamera[sceneId] = cameraId }

    fun list(activity: Activity, project: Project, timeUs: Long, callbacks: Callbacks): Unit = with(activity) {
        val body = column()
        lateinit var dialog: Dialog
        body.addView(sectionTitle("Cenas 3D", "Modelos 3D com luz de estúdio, chão reflexivo, nulos e câmeras próprias, por cima do vídeo."))
        body.addView(action(if (project.scenes3D.size >= MAX_SCENES_3D) "Limite de $MAX_SCENES_3D cenas 3D" else "Importar modelo 3D", true) {
            if (project.scenes3D.size >= MAX_SCENES_3D) return@action
            dialog.dismiss(); callbacks.pickModel()
        })
        body.addView(label("Aceita .glb, .gltf, .fbx e .obj. Escolha vários arquivos juntos (modelo, .mtl, .bin e texturas) ou um .zip com tudo.",
            12.5f, EditorStyle.MUTED))
        project.scenes3D.forEach { scene ->
            val line = row()
            line.addView(label("${scene.name}\n${timeLabel(scene.startUs)} – ${timeLabel(scene.endUs)}", 13f),
                LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(action("Editar") {
                dialog.dismiss()
                val inside = timeUs in scene.startUs until scene.endUs
                if (!inside) callbacks.seek(scene.startUs)
                edit(activity, project, scene.id, if (inside) timeUs else scene.startUs, Section.TRANSFORM, callbacks)
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
        fun commit(close: Boolean = true): Boolean = runCatching { callbacks.apply(withScene(current)) }.fold({
            body.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); if (close) dialog.dismiss(); true
        }, {
            Toast.makeText(this, it.message ?: "Não foi possível alterar a cena 3D", Toast.LENGTH_LONG).show(); false
        })
        /** Saves what changed here, then reopens [target] at scene-local [atLocal] (seeking there when it moved). */
        fun reopen(target: Section, atLocal: Long = local) {
            if (current == original || commit(close = false)) {
                dialog.dismiss()
                val latest = withScene(current)
                val scene = latest.scenes3D.first { it.id == sceneId }
                val at = scene.startUs + atLocal.coerceIn(0L, scene.durationUs - 1)
                if (atLocal != local) callbacks.seek(at)
                edit(activity, latest, sceneId, at, target, callbacks)
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
            Section.TRANSFORM -> transformSection(this, body, current, local, { current }, ::guarded,
                refresh = { reopen(Section.TRANSFORM) }, goTo = { reopen(Section.TRANSFORM, it) })
            Section.CAMERA -> cameraSection(this, body, current, local, { current }, ::guarded,
                refresh = { reopen(Section.CAMERA) }, goTo = { reopen(Section.CAMERA, it) },
                realCamera = { cameraId -> if (current == original || commit(close = false)) { dialog.dismiss(); callbacks.openRealCamera(sceneId, cameraId) } })
            Section.ENVIRONMENT -> environmentSection(this, body, current, { current }, ::guarded) {
                if (current == original || commit(close = false)) { dialog.dismiss(); callbacks.pickEnvironment(sceneId) }
            }
            Section.TIME -> timeSection(this, body, project, current, timeUs, ::guarded, { reopen(Section.TIME) }) {
                runCatching { callbacks.apply(project.copy(scenes3D = project.scenes3D.filterNot { it.id == sceneId })) }
                    .onSuccess { dialog.dismiss() }
            }
        }
        body.addView(action("Aplicar", true) { commit() })
        dialog = sheet("Cena 3D · ${section.label}", body)
        dialog.setOnDismissListener { callbacks.restore() }
    }

    // ---------------------------------------------------------------------------------------
    // Transformar: the model or a null
    // ---------------------------------------------------------------------------------------
    private fun transformSection(activity: Activity, body: LinearLayout, scene: Scene3DClip, local: Long,
        current: () -> Scene3DClip, update: (() -> Scene3DClip) -> Unit, refresh: () -> Unit, goTo: (Long) -> Unit) = with(activity) {
        val target = selectedTarget[scene.id]?.takeIf { it == MODEL_TARGET || scene.nullById(it) != null } ?: MODEL_TARGET
        val targets = listOf(MODEL_TARGET) + scene.nulls.map { it.id }
        body.addView(label("Objeto", 13f, EditorStyle.MUTED))
        choice(body, targets.map { if (it == MODEL_TARGET) "Modelo: ${scene.name}" else "Nulo: ${scene.nullById(it)!!.name}" }, targets.indexOf(target)) {
            selectedTarget[scene.id] = targets[it]; refresh()
        }
        val objectRow = row()
        objectRow.addView(action("Novo nulo") {
            if (scene.nulls.size >= MAX_SCENE_NULLS) { Toast.makeText(this, "Limite de $MAX_SCENE_NULLS nulos", Toast.LENGTH_SHORT).show(); return@action }
            val created = Null3D(name = "Nulo ${scene.nulls.size + 1}")
            update { current().copy(nulls = current().nulls + created) }
            selectedTarget[scene.id] = created.id
            refresh()
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        if (target != MODEL_TARGET) objectRow.addView(action("Excluir nulo") {
            update { removeNull(current(), target) }
            selectedTarget[scene.id] = MODEL_TARGET
            refresh()
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        body.addView(objectRow)
        parentChoice(body, scene, target, update, refresh)

        val keys = scene.poseKeys(target)
        val nearby = keys.firstOrNull { abs(it.timeUs - local) <= NEARBY_US }
        autoKeyRow(body, if (keys.isEmpty()) "Sem keyframes: a pose vale para a cena inteira." else "${keys.size} keyframes de movimento.")
        navigation(body, keys.map { it.timeUs }, local, goTo)
        var pose = scene.poseAt(target, local)
        fun change(next: ModelPose) {
            val before = pose
            pose = next
            update { applyPose(current(), local, before, next, target) }
        }
        slider(body, "Posição X (m)", (pose.positionX * 100).roundToInt(), 500, -500, ::meters) { change(pose.copy(positionX = it / 100f)) }
        slider(body, "Posição Y / altura (m)", (pose.positionY * 100).roundToInt(), 500, -200, ::meters) { change(pose.copy(positionY = it / 100f)) }
        slider(body, "Posição Z (m)", (pose.positionZ * 100).roundToInt(), 500, -500, ::meters) { change(pose.copy(positionZ = it / 100f)) }
        slider(body, "Rotação X (graus)", pose.rotationX.roundToInt().coerceIn(-180, 180), 180, -180, ::degrees) { change(pose.copy(rotationX = it.toFloat())) }
        slider(body, "Rotação Y / girar (graus)", pose.rotationY.roundToInt().coerceIn(-360, 360), 360, -360, ::degrees) { change(pose.copy(rotationY = it.toFloat())) }
        slider(body, "Rotação Z (graus)", pose.rotationZ.roundToInt().coerceIn(-180, 180), 180, -180, ::degrees) { change(pose.copy(rotationZ = it.toFloat())) }
        slider(body, "Escala (%)", (pose.scale * 100).roundToInt().coerceIn(10, 500), 500, 10, { "$it%" }) { change(pose.copy(scale = it / 100f)) }
        curveSpinner(body, nearby?.easing ?: Easing.SMOOTH, enabled = nearby != null) { easing ->
            update { current().let { s -> s.withPoseTrack(target, s.poseBase(target), s.poseKeys(target).map { if (abs(it.timeUs - local) <= NEARBY_US) it.copy(easing = easing) else it }) } }
        }
        // These change several values at once, so the panel reopens to show them.
        val actions = row()
        actions.addView(action("Zerar pose") { change(ModelPose()); refresh() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        if (nearby != null) actions.addView(action("Excluir ponto") {
            update { current().let { s -> s.withPoseTrack(target, s.poseBase(target), s.poseKeys(target).filterNot { abs(it.timeUs - local) <= NEARBY_US }) } }
            refresh()
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        if (keys.isNotEmpty()) actions.addView(action("Limpar pontos") {
            update { current().let { s -> s.withPoseTrack(target, s.poseAt(target, local), emptyList()) } }
            refresh()
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        body.addView(actions)
    }

    /** Removes a null; whatever was attached to it is attached to the null's own parent. */
    internal fun removeNull(scene: Scene3DClip, id: String): Scene3DClip {
        val removed = scene.nullById(id) ?: return scene
        val heir = removed.parentId
        fun reparent(parent: String?) = if (parent == id) heir else parent
        return scene.copy(
            nulls = scene.nulls.filterNot { it.id == id }.map { it.copy(parentId = reparent(it.parentId)) },
            modelParentId = reparent(scene.modelParentId),
            cameraParentId = reparent(scene.cameraParentId),
            cameraShots = scene.cameraShots.map { it.copy(parentId = reparent(it.parentId)) },
        )
    }

    private fun Activity.parentChoice(body: LinearLayout, scene: Scene3DClip, target: String,
        update: (() -> Scene3DClip) -> Unit, refresh: () -> Unit) {
        val eligible = scene.eligibleParents(target)
        val isCamera = target == MAIN_CAMERA || scene.shotById(target) != null
        val currentParent = if (isCamera) scene.cameraParentOf(target) else scene.parentOf(target)
        if (eligible.isEmpty() && currentParent == null) {
            body.addView(label("Crie um nulo (em Transformar) para vincular objetos e câmeras a ele.", 12.5f, EditorStyle.MUTED))
            return
        }
        body.addView(label("Vínculo (segue o nulo escolhido)", 13f, EditorStyle.MUTED))
        val options = listOf<String?>(null) + eligible.map { it.id }
        choice(body, options.map { id -> if (id == null) "Nenhum" else scene.nullById(id)!!.name }, options.indexOf(currentParent).coerceAtLeast(0)) { index ->
            update { scene.withParent(target, options[index]) }
            refresh()
        }
    }

    /** Auto-Key writes [next] at the playhead; otherwise the change is an offset of the whole track. */
    internal fun applyPose(scene: Scene3DClip, local: Long, before: ModelPose, next: ModelPose, target: String = MODEL_TARGET): Scene3DClip {
        val keys = scene.poseKeys(target)
        if (autoKey) {
            // The first key also pins the pose the object had before, so the move starts from it.
            val seeded = if (keys.isEmpty() && local > NEARBY_US) scene.withPoseKey(0L, scene.poseBase(target), target = target) else scene
            return seeded.withPoseKey(local, next, target = target)
        }
        if (keys.isEmpty()) return scene.withPoseTrack(target, next, emptyList())
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
        return scene.withPoseTrack(target, shift(scene.poseBase(target)), keys.map { it.copy(pose = shift(it.pose)) })
    }

    // ---------------------------------------------------------------------------------------
    // Câmera: main camera and camera cuts
    // ---------------------------------------------------------------------------------------
    private fun cameraSection(activity: Activity, body: LinearLayout, scene: Scene3DClip, local: Long,
        current: () -> Scene3DClip, update: (() -> Scene3DClip) -> Unit, refresh: () -> Unit, goTo: (Long) -> Unit,
        realCamera: (String) -> Unit) = with(activity) {
        val cameraId = selectedCamera[scene.id]?.takeIf { it == MAIN_CAMERA || scene.shotById(it) != null } ?: scene.activeCameraId(local)
        val ids = listOf(MAIN_CAMERA) + scene.cameraShots.map { it.id }
        fun cameraName(id: String) = if (id == MAIN_CAMERA) "Câmera principal" else scene.shotById(id)!!.name
        body.addView(label("Câmera (no ar agora: ${cameraName(scene.activeCameraId(local))})", 13f, EditorStyle.MUTED))
        choice(body, ids.map(::cameraName), ids.indexOf(cameraId)) { selectedCamera[scene.id] = ids[it]; refresh() }

        val shotRow = row()
        shotRow.addView(action("Novo corte aqui") {
            if (scene.cameraShots.size >= MAX_CAMERA_SHOTS) { Toast.makeText(this, "Limite de $MAX_CAMERA_SHOTS câmeras", Toast.LENGTH_SHORT).show(); return@action }
            val start = local.coerceAtMost(scene.durationUs - Scene3DClip.MIN_SCENE_DURATION_US).coerceAtLeast(0L)
            val shot = CameraShot(name = "Câmera ${scene.cameraShots.size + 2}", startUs = start,
                endUs = minOf(scene.durationUs, start + 2_000_000L).coerceAtLeast(start + Scene3DClip.MIN_SCENE_DURATION_US),
                camera = scene.cameraAt(cameraId, local), parentId = scene.cameraParentOf(cameraId))
            update { current().copy(cameraShots = current().cameraShots + shot) }
            selectedCamera[scene.id] = shot.id
            refresh()
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        shotRow.addView(action("Duplicar câmera") {
            if (scene.cameraShots.size >= MAX_CAMERA_SHOTS) { Toast.makeText(this, "Limite de $MAX_CAMERA_SHOTS câmeras", Toast.LENGTH_SHORT).show(); return@action }
            val source = scene.shotById(cameraId)
            val copy = CameraShot(name = "${cameraName(cameraId)} cópia",
                startUs = source?.startUs ?: local.coerceAtMost(scene.durationUs - Scene3DClip.MIN_SCENE_DURATION_US).coerceAtLeast(0L),
                endUs = source?.endUs ?: scene.durationUs,
                camera = scene.cameraBase(cameraId), keyframes = scene.cameraKeys(cameraId), parentId = scene.cameraParentOf(cameraId))
            update { current().copy(cameraShots = current().cameraShots + copy) }
            selectedCamera[scene.id] = copy.id
            refresh()
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        body.addView(shotRow)

        scene.shotById(cameraId)?.let { shot ->
            body.addView(label("Corte de ${timeLabel(shot.startUs)} a ${timeLabel(shot.endUs)} da cena. Fora dele, entra a câmera principal.", 12.5f, EditorStyle.MUTED))
            val maxTenths = (scene.durationUs / 100_000L).toInt().coerceAtLeast(3)
            slider(body, "Início do corte", (shot.startUs / 100_000L).toInt().coerceIn(0, maxTenths - 3), maxTenths - 3, 0, ::tenths) { value ->
                update {
                    val s = current().shotById(shot.id)!!
                    val start = (value * 100_000L).coerceAtMost(s.endUs - Scene3DClip.MIN_SCENE_DURATION_US)
                    current().copy(cameraShots = current().cameraShots.map { if (it.id == shot.id) it.copy(startUs = start) else it })
                }
            }
            slider(body, "Fim do corte", (shot.endUs / 100_000L).toInt().coerceIn(3, maxTenths), maxTenths, 3, ::tenths) { value ->
                update {
                    val s = current().shotById(shot.id)!!
                    val end = (value * 100_000L).coerceIn(s.startUs + Scene3DClip.MIN_SCENE_DURATION_US, current().durationUs)
                    current().copy(cameraShots = current().cameraShots.map { if (it.id == shot.id) it.copy(endUs = end) else it })
                }
            }
            val shotActions = row()
            shotActions.addView(action("Corte no cursor") {
                update {
                    val s = current().shotById(shot.id)!!
                    val start = local.coerceAtMost(s.endUs - Scene3DClip.MIN_SCENE_DURATION_US).coerceAtLeast(0L)
                    current().copy(cameraShots = current().cameraShots.map { if (it.id == shot.id) it.copy(startUs = start) else it })
                }
                refresh()
            }, LinearLayout.LayoutParams(0, dp(46), 1f))
            shotActions.addView(action("Excluir câmera") {
                update { current().copy(cameraShots = current().cameraShots.filterNot { it.id == shot.id }) }
                selectedCamera[scene.id] = MAIN_CAMERA
                refresh()
            }, LinearLayout.LayoutParams(0, dp(46), 1f))
            body.addView(shotActions)
        }
        parentChoice(body, scene, cameraId, update, refresh)

        body.addView(action("Real Camera: gravar o movimento do celular") { realCamera(cameraId) })

        val keys = scene.cameraKeys(cameraId)
        val nearby = keys.firstOrNull { abs(it.timeUs - local) <= NEARBY_US }
        autoKeyRow(body, if (keys.isEmpty()) "Sem keyframes: esta câmera fica parada." else "${keys.size} keyframes nesta câmera.")
        navigation(body, keys.map { it.timeUs }, local, goTo)

        body.addView(sectionTitle("Movimentos prontos", "Criam os keyframes daqui até o fim desta câmera."))
        val moves = SceneCameraMove.entries
        var move = SceneCameraMove.TURNTABLE
        body.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@with, android.R.layout.simple_spinner_dropdown_item, moves.map { it.label })
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { move = moves[position] }
            }
        }, LinearLayout.LayoutParams(-1, dp(52)))
        body.addView(action("Aplicar movimento a partir daqui") { update { SceneCameraMoves.apply(current(), move, local, cameraId) }; refresh() })

        body.addView(sectionTitle("Câmera neste ponto"))
        var camera = scene.cameraAt(cameraId, local)
        fun change(next: OrbitCamera) {
            val before = camera
            camera = next
            update { applyCamera(current(), local, before, next, cameraId) }
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
            update { current().let { s -> s.withCameraTrack(cameraId, s.cameraBase(cameraId), s.cameraKeys(cameraId).map { if (abs(it.timeUs - local) <= NEARBY_US) it.copy(easing = easing) else it }) } }
        }
        val actions = row()
        actions.addView(action("Câmera padrão") { change(OrbitCamera()); refresh() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        if (nearby != null) actions.addView(action("Excluir ponto") {
            update { current().let { s -> s.withCameraTrack(cameraId, s.cameraBase(cameraId), s.cameraKeys(cameraId).filterNot { abs(it.timeUs - local) <= NEARBY_US }) } }
            refresh()
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        if (keys.isNotEmpty()) actions.addView(action("Limpar pontos") {
            update { current().let { s -> s.withCameraTrack(cameraId, s.cameraAt(cameraId, local), emptyList()) } }
            refresh()
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        body.addView(actions)
    }

    internal fun applyCamera(scene: Scene3DClip, local: Long, before: OrbitCamera, next: OrbitCamera, cameraId: String = MAIN_CAMERA): Scene3DClip {
        val keys = scene.cameraKeys(cameraId)
        if (autoKey) {
            val seeded = if (keys.isEmpty() && local > NEARBY_US) scene.withCameraKey(0L, scene.cameraBase(cameraId), cameraId = cameraId) else scene
            return seeded.withCameraKey(local, next, cameraId = cameraId)
        }
        if (keys.isEmpty()) return scene.withCameraTrack(cameraId, next, emptyList())
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
        return scene.withCameraTrack(cameraId, shift(scene.cameraBase(cameraId)), keys.map { it.copy(camera = shift(it.camera)) })
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
        slider(body, "Duração", (clip.durationUs / 100_000L).toInt().coerceIn(3, maxTenths), maxTenths, 3, ::tenths) { change { clip.withDuration(it * 100_000L) } }
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
        choice(body, curves.map { it.label }, curves.indexOf(selected).coerceAtLeast(0), enabled) { choose(curves[it]) }
    }

    private fun Activity.choice(body: LinearLayout, options: List<String>, selected: Int, enabled: Boolean = true, choose: (Int) -> Unit) {
        body.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@choice, android.R.layout.simple_spinner_dropdown_item, options)
            setSelection(selected.coerceIn(0, (options.size - 1).coerceAtLeast(0)))
            isEnabled = enabled
            var shown = selected
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    // Android also reports the initial selection; only a different item is a choice.
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
    private fun tenths(value: Int) = "${(value / 10f).toString().replace('.', ',')} s"
}
