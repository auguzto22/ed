package com.termex.replay15.editor.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Ready-made, professional camera moves for the project camera ([Camera3D]).
 *
 * The project camera films the already composed frame (videos, text and stickers) as one flat
 * plane at Z = 0, in preview (GpuPreviewRenderer) and in export (SceneCameraEffect). Every move
 * here is therefore designed for a single plane: it frames, travels and tilts over the whole
 * picture. [CameraFraming] can keep that plane covering the whole output, so a move never
 * reveals the background colour at the edges.
 *
 * Moves are relative to the camera pose at the start time, so they chain: a push-in followed by
 * an orbit continues from where the push-in ended.
 */
enum class CameraMove(val label: String, val description: String) {
    PUSH_IN("Aproximar (push-in)", "A câmera avança devagar em direção à cena."),
    PULL_OUT("Afastar (pull-out)", "A câmera recua e abre o enquadramento."),
    CRASH_ZOOM("Zoom rápido (crash zoom)", "Fecha o campo de visão de uma vez. Combina com curvas rápidas."),
    TRUCK_LEFT("Deslizar para a esquerda", "A câmera anda de lado para a esquerda."),
    TRUCK_RIGHT("Deslizar para a direita", "A câmera anda de lado para a direita."),
    PEDESTAL_UP("Subir", "A câmera sobe na vertical."),
    PEDESTAL_DOWN("Descer", "A câmera desce na vertical."),
    ORBIT_LEFT("Órbita para a esquerda", "Gira ao redor do centro do quadro, sempre olhando para ele."),
    ORBIT_RIGHT("Órbita para a direita", "Gira ao redor do centro do quadro, sempre olhando para ele."),
    TILT_REVEAL("Revelar de cima", "Começa olhando acima da cena e inclina até enquadrá-la."),
    DUTCH_ANGLE("Ângulo holandês", "Inclina o horizonte aos poucos, para tensão ou estilo."),
    HANDHELD("Câmera na mão", "Tremor orgânico sutil, como uma filmagem sem tripé."),
}

data class CameraMoveSpec(
    val move: CameraMove,
    val startUs: Long,
    val durationUs: Long,
    /** 1 = the default amount; 0.25 is subtle, 2 is strong. */
    val intensity: Float = 1f,
    /** Speed curve of the move. Ignored by [CameraMove.HANDHELD], whose motion is organic. */
    val easing: Easing = Easing.SMOOTH,
    /** Keeps the frame covering the whole output (no background showing at the edges). */
    val protectEdges: Boolean = true,
    /** Output width / height. */
    val aspect: Float,
    /** Varies the handheld shake pattern; the same seed always gives the same shake. */
    val seed: Int = 0,
) {
    init {
        require(startUs >= 0L) { "Início inválido" }
        require(durationUs in CameraMoves.MIN_DURATION_US..CameraMoves.MAX_DURATION_US) { "Duração inválida" }
        require(intensity.isFinite() && intensity in CameraMoves.MIN_INTENSITY..CameraMoves.MAX_INTENSITY) { "Intensidade inválida" }
        require(aspect.isFinite() && aspect > 0f) { "Proporção inválida" }
    }

    val endUs: Long get() = startUs + durationUs
}

/**
 * @property edgesCovered false when edge protection was asked for but the shot cannot be
 * covered by a reasonable zoom (an extreme base angle); the move is then kept unprotected.
 */
data class CameraMoveResult(val camera: Camera3D, val edgesCovered: Boolean)

object CameraMoves {
    const val MIN_DURATION_US = 300_000L
    const val MAX_DURATION_US = 30_000_000L
    const val MIN_INTENSITY = .25f
    const val MAX_INTENSITY = 2f

    /** Same limit enforced by [Camera3D]. */
    const val MAX_KEYFRAMES = 200

    /** Distance from the eye to the Z = 0 plane at rest; [Camera3D.toViewMatrix] always uses 60°. */
    private val REST_DISTANCE = Camera3D().canonicalDistance(60f)

    /** Closest the eye may get to the plane; [Camera3D.toViewMatrix] clamps at 0.25. */
    private const val MIN_EYE_Z = .3f

    private const val ORBIT_SEGMENTS = 16
    private const val HANDHELD_KEYS_PER_SECOND = 10.0
    private const val HANDHELD_FADE_US = 300_000L

    /**
     * Writes [spec] into [camera]'s keyframe track. Keyframes inside
     * [CameraMoveSpec.startUs]..[CameraMoveSpec.endUs] are replaced; the rest of the track is kept.
     *
     * @throws IllegalArgumentException when the track has no room for the move's keyframes.
     */
    fun apply(camera: Camera3D, spec: CameraMoveSpec): CameraMoveResult {
        val base = camera.cameraAt(spec.startUs).copy(keyframes = emptyList())
        val kept = camera.keyframes.filter { it.timeUs < spec.startUs || it.timeUs > spec.endUs }
        val budget = MAX_KEYFRAMES - kept.size
        require(budget >= minimumKeys(spec.move)) {
            "A câmera já tem muitos keyframes. Remova alguns pontos e tente de novo."
        }
        var keys = generate(base, spec, budget)
        var covered = true
        if (spec.protectEdges) {
            val protected = CameraFraming.protect(keys, spec.aspect)
            covered = protected != null
            if (protected != null) keys = protected
        }
        return CameraMoveResult(camera.copy(keyframes = (kept + keys).sortedBy { it.timeUs }), covered)
    }

    private fun minimumKeys(move: CameraMove): Int = when (move) {
        CameraMove.ORBIT_LEFT, CameraMove.ORBIT_RIGHT -> 5
        CameraMove.HANDHELD -> 5
        else -> 2
    }

    private fun generate(base: Camera3D, spec: CameraMoveSpec, budget: Int): List<CameraKeyframe> {
        val i = spec.intensity
        return when (spec.move) {
            CameraMove.PUSH_IN -> twoKeys(base, spec, base.copy(positionZ = closer(base.positionZ, .35f * i)))
            CameraMove.PULL_OUT -> twoKeys(base, spec, base.copy(positionZ = base.positionZ + .45f * i))
            CameraMove.CRASH_ZOOM -> twoKeys(base, spec, base.copy(fieldOfView = zoomFov(base.fieldOfView, 1f + .8f * i)))
            CameraMove.TRUCK_LEFT -> twoKeys(base, spec, base.copy(positionX = base.positionX - .06f * i))
            CameraMove.TRUCK_RIGHT -> twoKeys(base, spec, base.copy(positionX = base.positionX + .06f * i))
            // UI Y grows downwards: going up means a smaller positionY.
            CameraMove.PEDESTAL_UP -> twoKeys(base, spec, base.copy(positionY = base.positionY - .05f * i))
            CameraMove.PEDESTAL_DOWN -> twoKeys(base, spec, base.copy(positionY = base.positionY + .05f * i))
            // Positive pitch looks up (+Y world), above the picture, then settles on the base pose.
            CameraMove.TILT_REVEAL -> twoKeys(base.copy(rotationX = base.rotationX + 8f * i), spec, base)
            CameraMove.DUTCH_ANGLE -> twoKeys(base, spec, base.copy(rotationZ = base.rotationZ + 6f * i))
            CameraMove.ORBIT_LEFT -> orbit(base, spec, -18f * i, budget)
            CameraMove.ORBIT_RIGHT -> orbit(base, spec, 18f * i, budget)
            CameraMove.HANDHELD -> handheld(base, spec, budget)
        }
    }

    /** Moves the eye [amount] towards the plane without passing the near limit. */
    private fun closer(positionZ: Float, amount: Float): Float {
        val minimum = MIN_EYE_Z - REST_DISTANCE
        return maxOf(positionZ - amount, minOf(positionZ, minimum))
    }

    /** Field of view that magnifies the picture by [zoom] relative to [fov]. */
    internal fun zoomFov(fov: Float, zoom: Float): Float {
        val half = Math.toRadians(fov.coerceIn(15f, 120f).toDouble() / 2.0)
        val narrowed = Math.toDegrees(2.0 * atan(tan(half) / zoom.toDouble())).toFloat()
        return narrowed.coerceIn(15f, 120f)
    }

    private fun twoKeys(from: Camera3D, spec: CameraMoveSpec, to: Camera3D): List<CameraKeyframe> = listOf(
        key(spec.startUs, from, spec.easing),
        key(spec.endUs, to, Easing.SMOOTH),
    )

    /**
     * Rotates the camera rig around the vertical axis through the point of the plane in front
     * of the eye. Sampled densely (linear segments over eased progress) because an arc cannot be
     * expressed by component-wise interpolation of two keyframes.
     */
    private fun orbit(base: Camera3D, spec: CameraMoveSpec, degrees: Float, budget: Int): List<CameraKeyframe> {
        val segments = minOf(ORBIT_SEGMENTS, budget - 1)
        val eyeX = base.positionX * 2f
        val radius = (REST_DISTANCE + base.positionZ).coerceIn(.25f, 50f)
        return List(segments + 1) { index ->
            val fraction = index.toFloat() / segments
            val progress = spec.easing.apply(fraction)
            val theta = Math.toRadians((degrees * progress).toDouble())
            val pose = base.copy(
                positionX = (eyeX + radius * sin(theta).toFloat()) / 2f,
                positionZ = radius * cos(theta).toFloat() - REST_DISTANCE,
                rotationY = base.rotationY + degrees * progress,
            )
            key(timeAt(spec, index, segments), pose, if (index == segments) Easing.SMOOTH else Easing.LINEAR)
        }
    }

    /** Deterministic organic shake, faded in and out so it starts and ends exactly on the base pose. */
    private fun handheld(base: Camera3D, spec: CameraMoveSpec, budget: Int): List<CameraKeyframe> {
        val wanted = ceil(spec.durationUs / 1_000_000.0 * HANDHELD_KEYS_PER_SECOND).toInt()
        val segments = wanted.coerceIn(4, budget - 1)
        val i = spec.intensity
        val fadeUs = minOf(HANDHELD_FADE_US, spec.durationUs / 3)
        return List(segments + 1) { index ->
            val timeUs = timeAt(spec, index, segments)
            val local = timeUs - spec.startUs
            val seconds = local / 1_000_000.0
            val envelope = if (index == 0 || index == segments) 0f else
                minOf(smooth(local.toFloat() / fadeUs), smooth((spec.durationUs - local).toFloat() / fadeUs))
            fun shake(channel: Int, amplitude: Float) = amplitude * i * envelope * noise(seconds, spec.seed, channel)
            val pose = base.copy(
                positionX = base.positionX + shake(0, .004f),
                positionY = base.positionY + shake(1, .004f),
                rotationX = base.rotationX + shake(2, .5f),
                rotationY = base.rotationY + shake(3, .6f),
                rotationZ = base.rotationZ + shake(4, .9f),
            )
            key(timeUs, pose, if (index == segments) Easing.SMOOTH else Easing.LINEAR)
        }
    }

    private fun smooth(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Smooth band-limited noise in about [-1, 1]; frequencies stay well below the key rate. */
    private fun noise(seconds: Double, seed: Int, channel: Int): Float {
        fun phase(n: Int): Double {
            var h = seed * 374761393 + channel * 668265263 + n * 2147483647
            h = (h xor (h ushr 13)) * 1274126177
            h = h xor (h ushr 16)
            return (h and 0xFFFF) / 65536.0 * 2.0 * PI
        }
        val value = .62 * sin(2.0 * PI * .53 * seconds + phase(1)) +
            .38 * sin(2.0 * PI * 1.37 * seconds + phase(2))
        return value.toFloat()
    }

    private fun timeAt(spec: CameraMoveSpec, index: Int, segments: Int): Long =
        if (index == segments) spec.endUs else spec.startUs + spec.durationUs * index / segments

    private fun key(timeUs: Long, pose: Camera3D, easing: Easing) = CameraKeyframe(
        timeUs = timeUs,
        positionX = pose.positionX, positionY = pose.positionY, positionZ = pose.positionZ,
        rotationX = pose.rotationX, rotationY = pose.rotationY, rotationZ = pose.rotationZ,
        fieldOfView = pose.fieldOfView.coerceIn(10f, 160f),
        focusDistance = pose.focusDistance, depthOfFieldAmount = pose.depthOfFieldAmount,
        easing = easing,
    )
}

/**
 * Keeps the camera's view of the composed frame free of uncovered edges.
 *
 * The frame is the plane spanned by (-1..1, -1..1, 0) in [Camera3D.SceneMatrix] input space.
 * Narrowing the field of view is an exact, uniform magnification around the centre of the
 * output, so the required zoom can be measured in normalized device coordinates and applied
 * through the field of view without changing the move itself.
 */
object CameraFraming {
    /** Largest magnification applied; beyond this the shot would lose too much resolution. */
    const val MAX_ZOOM = 4f
    private const val MARGIN = 1.015f

    /**
     * Magnification needed so the projected frame covers the whole output for [camera]'s
     * static pose (keyframes ignored). 1 means no edges are visible; [Float.POSITIVE_INFINITY]
     * means no zoom can hide them (the frame is behind the eye or off the output centre).
     */
    fun requiredZoom(camera: Camera3D, aspect: Float): Float {
        val pose = camera.copy(keyframes = emptyList())
        if (pose.isDefault) return 1f
        val matrix = Camera3D.SceneMatrix().evaluate(pose, aspect)
        val qx = FloatArray(4); val qy = FloatArray(4)
        val corners = arrayOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)
        val point = FloatArray(4)
        corners.forEachIndexed { index, (x, y) ->
            Matrix4.transformPoint(matrix, x, y, 0f, 1f, point)
            if (point[3] <= 1e-5f) return Float.POSITIVE_INFINITY
            qx[index] = point[0] / point[3]; qy[index] = point[1] / point[3]
        }
        // Normalize to counter-clockwise winding.
        var area = 0f
        for (index in 0 until 4) {
            val next = (index + 1) % 4
            area += qx[index] * qy[next] - qx[next] * qy[index]
        }
        if (abs(area) < 1e-6f) return Float.POSITIVE_INFINITY
        if (area < 0f) { qx.reverse(); qy.reverse() }
        var zoom = 1f
        for ((cx, cy) in corners) {
            // Largest s with s * corner still inside the quad, for a ray from the output centre.
            var reach = Float.POSITIVE_INFINITY
            for (index in 0 until 4) {
                val next = (index + 1) % 4
                val ex = qx[next] - qx[index]; val ey = qy[next] - qy[index]
                // Inside test for p: cross(e, p - a) >= 0. The centre must be inside.
                val centre = ex * (-qy[index]) - ey * (-qx[index])
                if (centre < 0f) return Float.POSITIVE_INFINITY
                val towards = ex * cy - ey * cx
                if (towards < 0f) reach = minOf(reach, centre / -towards)
            }
            if (reach <= 1e-6f) return Float.POSITIVE_INFINITY
            zoom = maxOf(zoom, 1f / reach)
        }
        return zoom
    }

    /**
     * Narrows the field of view of every keyframe by one shared factor, the largest needed
     * anywhere along the track, so the frame never shows its edges while the move plays.
     * Returns null when the edges cannot be hidden within [MAX_ZOOM].
     */
    fun protect(keys: List<CameraKeyframe>, aspect: Float): List<CameraKeyframe>? {
        if (keys.isEmpty()) return keys
        val zoom = trackZoom(keys, aspect)
        if (!zoom.isFinite() || zoom > MAX_ZOOM) return null
        if (zoom <= 1.0005f) return keys
        val factor = (zoom * MARGIN).coerceAtMost(MAX_ZOOM)
        return keys.map { it.copy(fieldOfView = CameraMoves.zoomFov(it.fieldOfView, factor)) }
    }

    /**
     * Applies [protect] to a whole camera: its track, or its static pose when it has none.
     * Returns null when the edges cannot be hidden within [MAX_ZOOM].
     */
    fun protect(camera: Camera3D, aspect: Float): Camera3D? {
        if (camera.keyframes.isNotEmpty()) return protect(camera.keyframes, aspect)?.let { camera.copy(keyframes = it) }
        val zoom = requiredZoom(camera, aspect)
        if (!zoom.isFinite() || zoom > MAX_ZOOM) return null
        if (zoom <= 1.0005f) return camera
        return camera.copy(fieldOfView = CameraMoves.zoomFov(camera.fieldOfView, (zoom * MARGIN).coerceAtMost(MAX_ZOOM)))
    }

    /** Largest [requiredZoom] along the track, sampled densely enough to catch eased overshoot. */
    private fun trackZoom(keys: List<CameraKeyframe>, aspect: Float): Float {
        val track = Camera3D(keyframes = keys)
        var zoom = 1f
        fun sample(timeUs: Long) { zoom = maxOf(zoom, requiredZoom(track.cameraAt(timeUs), aspect)) }
        val perSegment = maxOf(8, 96 / maxOf(1, keys.size - 1))
        keys.zipWithNext().forEach { (a, b) ->
            for (step in 0 until perSegment) sample(a.timeUs + (b.timeUs - a.timeUs) * step / perSegment)
        }
        sample(keys.last().timeUs)
        return zoom
    }
}
