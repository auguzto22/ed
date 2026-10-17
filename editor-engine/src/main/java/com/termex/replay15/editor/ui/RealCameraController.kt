package com.termex.replay15.editor.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.scene3d.ArSessionRunner
import com.termex.replay15.editor.scene3d.RealCameraMapper

/**
 * "Real Camera": the phone becomes the camera of a 3D scene. While open, the preview camera
 * follows the phone (ARCore 6DoF: walk, lean, turn). Gravar plays the project while writing the
 * camera as keyframes (about 20 per second) over the part that was played; Parar keeps the take.
 * Recentralizar makes the current phone pose the "no motion" pose again, Inverter flips the
 * motion, Sensibilidade scales how far the camera travels (centre = real metres).
 */
class RealCameraController(
    private val activity: Activity,
    private val host: FrameLayout,
    private val hooks: Hooks,
) {
    interface Hooks {
        fun project(): Project
        fun positionUs(): Long
        fun isPlaying(): Boolean
        fun play()
        fun pause()
        fun seek(us: Long)
        /** Shows [project] in the preview without saving it. */
        fun preview(project: Project)
        /** Saves [project] as an undoable edit. */
        fun commit(project: Project)
        fun requestCameraPermission()
        /** The panel closed; restore tools hidden while it was open. */
        fun closed()
    }

    private val main = Handler(Looper.getMainLooper())
    private var runner: ArSessionRunner? = null
    private var panel: LinearLayout? = null
    private lateinit var status: TextView
    private lateinit var recordButton: android.widget.Button
    private lateinit var invertButton: android.widget.Button

    private var sceneId = ""
    private var cameraId = MAIN_CAMERA
    private var mapper = RealCameraMapper(OrbitCamera())
    private var lastPose: FloatArray? = null
    private var tracking = false
    private var invert = false
    private var sensitivity = DEFAULT_SENSITIVITY
    @Volatile private var lastPostNs = 0L
    private var pendingOpen: Pair<String, String>? = null

    private var recording = false
    private var takeStart = 0L
    private var take = ArrayList<OrbitCameraKeyframe>()
    private var recordedUntil = 0L

    val isOpen: Boolean get() = panel != null

    private fun scene(): Scene3DClip? = hooks.project().scenes3D.firstOrNull { it.id == sceneId }
    private fun multiplier() = sensitivity / DEFAULT_SENSITIVITY.toFloat()

    fun open(sceneId: String, cameraId: String) {
        if (isOpen) close()
        val scene = hooks.project().scenes3D.firstOrNull { it.id == sceneId } ?: return
        if (cameraId != MAIN_CAMERA && scene.shotById(cameraId) == null) return
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingOpen = sceneId to cameraId
            hooks.requestCameraPermission()
            return
        }
        val preparation = ArSessionRunner.prepare(activity)
        if (preparation.readiness != ArSessionRunner.Readiness.READY) {
            Toast.makeText(activity, preparation.message, Toast.LENGTH_LONG).show()
            return
        }
        this.sceneId = sceneId; this.cameraId = cameraId
        if (!moveToCameraRange(scene)) return
        hooks.pause()
        build()
        startSession()
    }

    /** Called by the activity when the camera permission dialog finishes. */
    fun onPermissionResult(granted: Boolean) {
        val request = pendingOpen; pendingOpen = null
        if (!granted) { Toast.makeText(activity, "Permita a câmera para usar a Real Camera.", Toast.LENGTH_LONG).show(); return }
        if (request != null) open(request.first, request.second)
    }

    /** The playhead must be where [cameraId] is on air, or the preview would not show the camera moving. */
    private fun moveToCameraRange(scene: Scene3DClip): Boolean {
        val local = scene.localTime(hooks.positionUs())
        val absoluteStart = scene.startUs
        if (scene.isActiveAt(hooks.positionUs()) && scene.activeCameraId(local) == cameraId) return true
        val candidates = ArrayList<Long>()
        if (cameraId == MAIN_CAMERA) {
            candidates += 0L
            scene.cameraShots.forEach { candidates += it.endUs }
        } else {
            candidates += scene.shotById(cameraId)!!.startUs
        }
        val target = candidates.sorted().firstOrNull { it < scene.durationUs && scene.activeCameraId(it) == cameraId }
        if (target == null) {
            Toast.makeText(activity, "Esta câmera não aparece em nenhum momento da cena (outro corte está por cima).", Toast.LENGTH_LONG).show()
            return false
        }
        hooks.seek(absoluteStart + target)
        return true
    }

    private fun build() {
        val context = activity
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.editorShape(0xE61C1C1E.toInt(), 16)
            setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(8))
        }
        status = context.label("Iniciando a câmera…", 12.5f).apply { setPadding(context.dp(4), 0, context.dp(4), context.dp(4)) }
        box.addView(status)
        val buttons = context.row()
        recordButton = context.action("● Gravar", true) { toggleRecording() }
        buttons.addView(recordButton, LinearLayout.LayoutParams(0, context.dp(46), 1f))
        buttons.addView(context.action("Recentralizar") { recenter() }, LinearLayout.LayoutParams(0, context.dp(46), 1f).apply { leftMargin = context.dp(6) })
        invertButton = context.action("Inverter: não") {
            invert = !invert
            invertButton.text = if (invert) "Inverter: sim" else "Inverter: não"
            recenter()
        }
        buttons.addView(invertButton, LinearLayout.LayoutParams(0, context.dp(46), 1f).apply { leftMargin = context.dp(6) })
        box.addView(buttons)
        context.slider(box, "Sensibilidade do movimento", sensitivity, 100, 5, { String.format(java.util.Locale.ROOT, "%.1f×", it / DEFAULT_SENSITIVITY.toFloat()) }) {
            sensitivity = it
            recenter()
        }
        val end = context.row()
        end.addView(context.action("Fixar pose aqui") { pinPose() }, LinearLayout.LayoutParams(0, context.dp(46), 1f))
        end.addView(context.action("Concluir", true) { close() }, LinearLayout.LayoutParams(0, context.dp(46), 1f).apply { leftMargin = context.dp(6) })
        box.addView(end)
        host.addView(box, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(context.dp(6), 0, context.dp(6), context.dp(6)) })
        panel = box
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        main.post(watcher)
    }

    private fun startSession() {
        @Suppress("DEPRECATION")
        val rotation = activity.windowManager.defaultDisplay.rotation
        val width = host.width.coerceAtLeast(1); val height = host.height.coerceAtLeast(1)
        val session = ArSessionRunner(activity.applicationContext, rotation, width, height, object : ArSessionRunner.Listener {
            override fun onPose(matrix: FloatArray) {
                val now = System.nanoTime()
                if (now - lastPostNs < POSE_INTERVAL_NS) return
                lastPostNs = now
                main.post { onPoseMain(matrix) }
            }
            override fun onTrackingLost(message: String) { main.post { onTrackingLostMain(message) } }
            override fun onFailure(message: String) { main.post { onFailureMain(message) } }
        })
        runner = session
        recenterOnNextPose = true
        session.start()
    }

    private var recenterOnNextPose = true

    /** The camera the user is looking at now, used as the origin of the phone's motion. */
    private fun currentCamera(): OrbitCamera {
        val scene = scene() ?: return OrbitCamera()
        return scene.cameraAt(cameraId, scene.localTime(hooks.positionUs()))
    }

    private fun recenter() {
        if (recording) return
        recenterOnNextPose = true
    }

    private fun onPoseMain(pose: FloatArray) {
        if (!isOpen) return
        lastPose = pose
        if (!tracking) { tracking = true; if (!recording) status.text = "Rastreando — mova o celular" }
        if (recenterOnNextPose) {
            mapper = RealCameraMapper(currentCamera()); mapper.reset(pose); recenterOnNextPose = false
        }
        val camera = mapper.map(pose, multiplier(), invert) ?: return
        val scene = scene() ?: return
        val position = hooks.positionUs()
        val local = scene.localTime(position)
        if (recording) {
            val shot = scene.shotById(cameraId)
            val end = minOf(scene.durationUs, shot?.endUs ?: scene.durationUs)
            val inside = scene.isActiveAt(position) && local >= takeStart && local < end
            if (!inside) { if (local >= end || !hooks.isPlaying()) stopRecording(); return }
            if (take.isEmpty() || local - take.last().timeUs >= MIN_KEY_SPACING_US) {
                take += OrbitCameraKeyframe(local, camera, Easing.LINEAR)
                recordedUntil = local
                status.text = "Gravando… ${take.size} pontos"
            }
            val room = MAX_SCENE_KEYFRAMES - scene.cameraKeys(cameraId).count { it.timeUs < takeStart || it.timeUs > recordedUntil }
            if (take.size >= room) { stopRecording(); Toast.makeText(activity, "Limite de pontos de câmera atingido.", Toast.LENGTH_LONG).show(); return }
            runCatching { scene.withCameraRecording(cameraId, takeStart, recordedUntil, take) }.onSuccess { draft(it) }
        } else {
            // Live view: the camera follows the phone and nothing is saved.
            draft(scene.withCameraTrack(cameraId, camera, emptyList()))
        }
    }

    private fun draft(scene: Scene3DClip) {
        val project = hooks.project()
        hooks.preview(project.copy(scenes3D = project.scenes3D.map { if (it.id == scene.id) scene else it }))
    }

    private fun onTrackingLostMain(message: String) {
        if (!isOpen) return
        tracking = false
        status.text = if (recording) "Perdeu o rastreamento (a gravação continua parada nesse trecho). $message" else message
    }

    private fun onFailureMain(message: String) {
        if (!isOpen) return
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        close()
    }

    private fun toggleRecording() {
        if (recording) { stopRecording(); return }
        val scene = scene() ?: return
        if (!tracking || lastPose == null) { Toast.makeText(activity, "Espere o rastreamento começar para gravar.", Toast.LENGTH_SHORT).show(); return }
        var position = hooks.positionUs()
        val shot = scene.shotById(cameraId)
        val end = minOf(scene.durationUs, shot?.endUs ?: scene.durationUs)
        var local = scene.localTime(position)
        if (!scene.isActiveAt(position) || scene.activeCameraId(local) != cameraId || end - local < MIN_TAKE_US) {
            hooks.seek(scene.startUs + (shot?.startUs ?: 0L)); position = hooks.positionUs(); local = scene.localTime(position)
        }
        takeStart = local; recordedUntil = local
        take = ArrayList()
        // The take starts exactly where the camera is now, not from an older origin.
        mapper = RealCameraMapper(currentCamera()); mapper.reset(lastPose!!)
        recording = true
        recordButton.text = "■ Parar"
        status.text = "Gravando…"
        hooks.play()
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        hooks.pause()
        recordButton.text = "● Gravar"
        val scene = scene()
        if (scene != null && take.isNotEmpty()) {
            runCatching { scene.withCameraRecording(cameraId, takeStart, recordedUntil, take) }.fold({ recorded ->
                val project = hooks.project()
                hooks.commit(project.copy(scenes3D = project.scenes3D.map { if (it.id == recorded.id) recorded else it }))
                status.text = "Gravado: ${take.size} pontos. Grave de novo ou conclua."
            }, { Toast.makeText(activity, it.message ?: "Não foi possível salvar a gravação", Toast.LENGTH_LONG).show(); hooks.preview(hooks.project()) })
        } else {
            hooks.preview(hooks.project())
            status.text = "Nada foi gravado."
        }
        take = ArrayList()
        recenterOnNextPose = true
    }

    /** Saves the camera as it is right now as one keyframe at the playhead (Auto-Key rules apply). */
    private fun pinPose() {
        val scene = scene() ?: return
        val pose = lastPose
        if (pose == null || !tracking) { Toast.makeText(activity, "Sem rastreamento no momento.", Toast.LENGTH_SHORT).show(); return }
        val camera = mapper.map(pose, multiplier(), invert) ?: return
        val position = hooks.positionUs()
        if (!scene.isActiveAt(position)) { Toast.makeText(activity, "O cursor está fora desta cena.", Toast.LENGTH_SHORT).show(); return }
        val local = scene.localTime(position)
        runCatching {
            val before = scene.cameraAt(cameraId, local)
            Scene3DTools.applyCamera(scene, local, before, camera, cameraId)
        }.fold({ changed ->
            val project = hooks.project()
            hooks.commit(project.copy(scenes3D = project.scenes3D.map { if (it.id == changed.id) changed else it }))
            recenterOnNextPose = true
            status.text = "Pose fixada em ${timeLabel(local)}."
        }, { Toast.makeText(activity, it.message ?: "Não foi possível fixar a pose", Toast.LENGTH_LONG).show() })
    }

    /** Auto-stops a take when playback ends or leaves the scene. */
    private val watcher = object : Runnable {
        override fun run() {
            if (!isOpen) return
            if (recording && !hooks.isPlaying()) stopRecording()
            main.postDelayed(this, 150L)
        }
    }

    fun close() {
        val box = panel ?: return
        if (recording) stopRecording()
        main.removeCallbacks(watcher)
        panel = null
        runner?.stop(); runner = null
        host.removeView(box)
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lastPose = null; tracking = false
        hooks.preview(hooks.project())
        hooks.closed()
    }

    private companion object {
        const val DEFAULT_SENSITIVITY = 33
        const val POSE_INTERVAL_NS = 33_000_000L
        const val MIN_KEY_SPACING_US = 50_000L
        const val MIN_TAKE_US = 300_000L
    }
}
