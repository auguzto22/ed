package com.termex.replay15.editor.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A real 3D scene layer: a glTF/GLB model rendered with physically based lighting, a studio or
 * HDR environment, a reflective floor and its own orbit camera. It is composited over the video
 * tracks (below stickers and text) in preview and export, at the same place in the z-order.
 *
 * All keyframe times are relative to the layer start, so moving the layer on the timeline keeps
 * its animation intact.
 */
const val MAX_SCENES_3D = 8
private const val MAX_SCENE_KEYFRAMES = 200
private const val NEARBY_KEY_US = 20_000L

enum class StudioPreset(val label: String) {
    NEUTRAL("Estúdio neutro"),
    DARK("Escuro"),
    PRODUCT("Produto"),
    SKY("Céu"),
}

enum class ToneMapping(val label: String) {
    PBR_NEUTRAL("PBR Neutral"),
    AGX("AgX"),
    ACES("ACES"),
    FILMIC("Filmic"),
}

data class Environment3D(
    val preset: StudioPreset = StudioPreset.NEUTRAL,
    /** Equirectangular .hdr, .jpg or .png copied into app storage; blank uses [preset]. */
    val environmentPath: String = "",
    /** Multiplier of the environment light. */
    val lightIntensity: Float = 1f,
    /** Turns the environment (and its key light) around the scene, in degrees. */
    val lightRotation: Float = 0f,
    /** Draws the environment behind the model; off makes the layer transparent over the video. */
    val showBackground: Boolean = true,
    /** Exposure compensation in EV. */
    val exposure: Float = 0f,
    val toneMapping: ToneMapping = ToneMapping.PBR_NEUTRAL,
    /** Bloom strength; 0 turns bloom off. */
    val bloom: Float = .15f,
    val floor: Boolean = true,
    /** Strength of the model's reflection on the floor; 0 turns the reflection off. */
    val floorReflection: Float = .35f,
    val shadows: Boolean = true,
) {
    init {
        require(environmentPath.length <= 1000)
        require(lightIntensity.isFinite() && lightIntensity in 0f..4f)
        require(lightRotation.isFinite() && lightRotation in -180f..180f)
        require(exposure.isFinite() && exposure in -4f..4f)
        require(bloom.isFinite() && bloom in 0f..1f)
        require(floorReflection.isFinite() && floorReflection in 0f..1f)
    }
}

/**
 * Placement of the model. The model is first normalized so its largest side measures 2 units,
 * centred on the vertical axis and standing on the floor (y = 0); this pose is applied on top.
 */
data class ModelPose(
    val positionX: Float = 0f,
    val positionY: Float = 0f,
    val positionZ: Float = 0f,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,
    val scale: Float = 1f,
) {
    init {
        require(listOf(positionX, positionY, positionZ).all { it.isFinite() && it in -50f..50f })
        require(listOf(rotationX, rotationY, rotationZ).all { it.isFinite() && abs(it) <= MAX_TURN_DEGREES })
        require(scale.isFinite() && scale in .01f..100f)
    }

    /** [t] may leave 0..1 with overshooting curves (Elástico, Antecipação); results stay valid. */
    fun mix(other: ModelPose, t: Float) = ModelPose(
        position(lerp(positionX, other.positionX, t)), position(lerp(positionY, other.positionY, t)),
        position(lerp(positionZ, other.positionZ, t)),
        angle(lerp(rotationX, other.rotationX, t)), angle(lerp(rotationY, other.rotationY, t)), angle(lerp(rotationZ, other.rotationZ, t)),
        // Scale interpolates geometrically so a 1x -> 4x zoom feels even, like a camera zoom.
        geometric(scale, other.scale, t).coerceIn(.01f, 100f),
    )

    /** Column-major 4x4: T(position) * Rz * Ry * Rx * S(scale). */
    fun toMatrix(out: FloatArray = FloatArray(16)): FloatArray {
        Matrix4.setIdentity(out)
        Matrix4.translate(out, positionX, positionY, positionZ)
        Matrix4.rotateEuler(out, rotationX, rotationY, rotationZ)
        Matrix4.scale(out, scale, scale, scale)
        return out
    }
}

/**
 * Orbit camera of a 3D scene: it looks at [targetX]/[targetY]/[targetZ] from [distance], turned
 * by [yaw] around the vertical axis and raised by [pitch]. Yaw is not wrapped, so animating it from
 * 0 to 360 makes a full turn.
 */
data class OrbitCamera(
    val yaw: Float = 30f,
    val pitch: Float = 12f,
    val distance: Float = 5f,
    val targetX: Float = 0f,
    val targetY: Float = .6f,
    val targetZ: Float = 0f,
    /** Lens focal length in millimetres (full-frame equivalent). */
    val focalLength: Float = 50f,
    val roll: Float = 0f,
) {
    init {
        require(yaw.isFinite() && abs(yaw) <= MAX_TURN_DEGREES)
        require(pitch.isFinite() && pitch in MIN_PITCH..MAX_PITCH)
        require(distance.isFinite() && distance in MIN_DISTANCE..MAX_DISTANCE)
        require(listOf(targetX, targetY, targetZ).all { it.isFinite() && it in -50f..50f })
        require(focalLength.isFinite() && focalLength in MIN_FOCAL..MAX_FOCAL)
        require(roll.isFinite() && roll in -180f..180f)
    }

    /** [t] may leave 0..1 with overshooting curves (Elástico, Antecipação); results stay valid. */
    fun mix(other: OrbitCamera, t: Float) = OrbitCamera(
        angle(lerp(yaw, other.yaw, t)),
        lerp(pitch, other.pitch, t).coerceIn(MIN_PITCH, MAX_PITCH),
        geometric(distance, other.distance, t).coerceIn(MIN_DISTANCE, MAX_DISTANCE),
        position(lerp(targetX, other.targetX, t)), position(lerp(targetY, other.targetY, t)),
        position(lerp(targetZ, other.targetZ, t)),
        geometric(focalLength, other.focalLength, t).coerceIn(MIN_FOCAL, MAX_FOCAL),
        lerp(roll, other.roll, t).coerceIn(-180f, 180f),
    )

    /** Eye, target and up vectors (each 3 floats) for a look-at camera. */
    fun basis(): CameraBasis {
        val yawRad = Math.toRadians(yaw.toDouble())
        val pitchRad = Math.toRadians(pitch.toDouble())
        val horizontal = cos(pitchRad)
        val eye = floatArrayOf(
            (targetX + distance * sin(yawRad) * horizontal).toFloat(),
            (targetY + distance * sin(pitchRad)).toFloat(),
            (targetZ + distance * cos(yawRad) * horizontal).toFloat(),
        )
        val target = floatArrayOf(targetX, targetY, targetZ)
        val forward = normalize(floatArrayOf(target[0] - eye[0], target[1] - eye[1], target[2] - eye[2]))
        val right = normalize(cross(forward, floatArrayOf(0f, 1f, 0f)))
        val up = cross(right, forward)
        val rollRad = Math.toRadians(roll.toDouble())
        val c = cos(rollRad).toFloat(); val s = sin(rollRad).toFloat()
        val rolled = normalize(floatArrayOf(
            up[0] * c + right[0] * s, up[1] * c + right[1] * s, up[2] * c + right[2] * s,
        ))
        return CameraBasis(eye, target, rolled)
    }

    companion object {
        const val MIN_PITCH = -10f
        const val MAX_PITCH = 85f
        const val MIN_DISTANCE = .3f
        const val MAX_DISTANCE = 200f
        const val MIN_FOCAL = 12f
        const val MAX_FOCAL = 300f
    }
}

class CameraBasis(val eye: FloatArray, val target: FloatArray, val up: FloatArray)

data class ModelKeyframe(
    val timeUs: Long,
    val pose: ModelPose,
    val easing: Easing = Easing.SMOOTH,
    val bezier: CubicBezier = CubicBezier(),
) { init { require(timeUs >= 0L) } }

data class OrbitCameraKeyframe(
    val timeUs: Long,
    val camera: OrbitCamera,
    val easing: Easing = Easing.SMOOTH,
    val bezier: CubicBezier = CubicBezier(),
) { init { require(timeUs >= 0L) } }

data class Scene3DClip(
    val id: String = newId(),
    val name: String,
    /** Absolute path of the .glb/.gltf copied into app storage. */
    val modelPath: String,
    val startUs: Long,
    val endUs: Long,
    val pose: ModelPose = ModelPose(),
    val poseKeyframes: List<ModelKeyframe> = emptyList(),
    val camera: OrbitCamera = OrbitCamera(),
    val cameraKeyframes: List<OrbitCameraKeyframe> = emptyList(),
    val environment: Environment3D = Environment3D(),
    /** Plays the model's first embedded glTF animation, looped. */
    val playAnimation: Boolean = true,
    val animationSpeed: Float = 1f,
) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        require(name.length in 1..180)
        require(modelPath.length in 1..1000)
        require(startUs >= 0L && endUs > startUs)
        require(poseKeyframes.size <= MAX_SCENE_KEYFRAMES && cameraKeyframes.size <= MAX_SCENE_KEYFRAMES)
        require(poseKeyframes.zipWithNext().all { (a, b) -> a.timeUs < b.timeUs })
        require(cameraKeyframes.zipWithNext().all { (a, b) -> a.timeUs < b.timeUs })
        require(animationSpeed.isFinite() && animationSpeed in 0f..4f)
    }

    val durationUs: Long get() = endUs - startUs

    fun isActiveAt(projectTimeUs: Long): Boolean = projectTimeUs in startUs until endUs

    /** Time inside the layer, clamped to its range. */
    fun localTime(projectTimeUs: Long): Long = (projectTimeUs - startUs).coerceIn(0L, durationUs)

    fun poseAt(localUs: Long): ModelPose = interpolate(poseKeyframes, localUs, pose, ModelKeyframe::timeUs,
        ModelKeyframe::pose, ModelKeyframe::easing, ModelKeyframe::bezier) { a, b, t -> a.mix(b, t) }

    fun cameraAt(localUs: Long): OrbitCamera = interpolate(cameraKeyframes, localUs, camera, OrbitCameraKeyframe::timeUs,
        OrbitCameraKeyframe::camera, OrbitCameraKeyframe::easing, OrbitCameraKeyframe::bezier) { a, b, t -> a.mix(b, t) }

    /** Seconds of embedded animation to show at [localUs]; the renderer loops it over its length. */
    fun animationSeconds(localUs: Long): Float = if (!playAnimation) 0f else localUs / 1_000_000f * animationSpeed

    /** True when consecutive frames of this layer can differ, so they must be re-rendered. */
    val isAnimated: Boolean
        get() = poseKeyframes.size > 1 || cameraKeyframes.size > 1 || (playAnimation && animationSpeed > 0f)

    /** Writes [state] as a pose keyframe at [localUs], replacing one within 20 ms and keeping its curve. */
    fun withPoseKey(localUs: Long, state: ModelPose, easing: Easing? = null): Scene3DClip {
        require(localUs >= 0L)
        val nearby = poseKeyframes.minByOrNull { abs(it.timeUs - localUs) }?.takeIf { abs(it.timeUs - localUs) <= NEARBY_KEY_US }
        require(nearby != null || poseKeyframes.size < MAX_SCENE_KEYFRAMES) { "Limite de $MAX_SCENE_KEYFRAMES pontos" }
        val key = ModelKeyframe(nearby?.timeUs ?: localUs, state, easing ?: nearby?.easing ?: Easing.SMOOTH, nearby?.bezier ?: CubicBezier())
        return copy(poseKeyframes = (poseKeyframes.filterNot { it === nearby } + key).sortedBy { it.timeUs })
    }

    fun withCameraKey(localUs: Long, state: OrbitCamera, easing: Easing? = null): Scene3DClip {
        require(localUs >= 0L)
        val nearby = cameraKeyframes.minByOrNull { abs(it.timeUs - localUs) }?.takeIf { abs(it.timeUs - localUs) <= NEARBY_KEY_US }
        require(nearby != null || cameraKeyframes.size < MAX_SCENE_KEYFRAMES) { "Limite de $MAX_SCENE_KEYFRAMES pontos" }
        val key = OrbitCameraKeyframe(nearby?.timeUs ?: localUs, state, easing ?: nearby?.easing ?: Easing.SMOOTH, nearby?.bezier ?: CubicBezier())
        return copy(cameraKeyframes = (cameraKeyframes.filterNot { it === nearby } + key).sortedBy { it.timeUs })
    }

    /** Moves the layer so it starts at [newStartUs], keeping its length. */
    fun movedTo(newStartUs: Long): Scene3DClip = copy(startUs = newStartUs.coerceAtLeast(0L), endUs = newStartUs.coerceAtLeast(0L) + durationUs)

    /** Changes the length; keyframes after the new end are kept but simply never reached. */
    fun withDuration(newDurationUs: Long): Scene3DClip = copy(endUs = startUs + newDurationUs.coerceAtLeast(MIN_SCENE_DURATION_US))

    companion object {
        const val MIN_SCENE_DURATION_US = 300_000L
    }
}

/** Ready-made camera moves for a 3D scene, written as keyframes over the whole layer. */
enum class SceneCameraMove(val label: String) {
    TURNTABLE("Giro 360° (vitrine)"),
    ORBIT_HALF("Meia volta"),
    PUSH_IN("Aproximar"),
    PULL_OUT("Afastar"),
    CRANE_UP("Subir a câmera"),
    LOW_REVEAL("Revelação de baixo"),
    DOLLY_ZOOM("Dolly zoom (vertigo)"),
}

object SceneCameraMoves {
    fun apply(clip: Scene3DClip, move: SceneCameraMove, fromLocalUs: Long = 0L): Scene3DClip {
        val start = fromLocalUs.coerceIn(0L, clip.durationUs)
        val end = clip.durationUs
        require(end - start >= Scene3DClip.MIN_SCENE_DURATION_US) { "Pouco tempo até o fim da camada" }
        val base = clip.cameraAt(start)
        val (from, to, easing) = when (move) {
            SceneCameraMove.TURNTABLE -> Triple(base, base.copy(yaw = angle(base.yaw + 360f)), Easing.LINEAR)
            SceneCameraMove.ORBIT_HALF -> Triple(base, base.copy(yaw = angle(base.yaw + 180f)), Easing.EASE_IN_OUT)
            SceneCameraMove.PUSH_IN -> Triple(base, base.copy(distance = (base.distance * .6f).coerceAtLeast(OrbitCamera.MIN_DISTANCE)), Easing.SMOOTH)
            SceneCameraMove.PULL_OUT -> Triple(base, base.copy(distance = (base.distance * 1.6f).coerceAtMost(OrbitCamera.MAX_DISTANCE)), Easing.SMOOTH)
            SceneCameraMove.CRANE_UP -> Triple(base, base.copy(pitch = (base.pitch + 35f).coerceAtMost(OrbitCamera.MAX_PITCH)), Easing.SMOOTH)
            SceneCameraMove.LOW_REVEAL -> Triple(
                base.copy(pitch = 1f, distance = (base.distance * .75f).coerceAtLeast(OrbitCamera.MIN_DISTANCE), yaw = angle(base.yaw - 40f)),
                base, Easing.EASE_OUT)
            SceneCameraMove.DOLLY_ZOOM -> {
                // Keeps the subject the same size on screen: distance and focal length grow together.
                val factor = 2.2f
                val distance = (base.distance * factor).coerceAtMost(OrbitCamera.MAX_DISTANCE)
                val focal = (base.focalLength * distance / base.distance).coerceIn(OrbitCamera.MIN_FOCAL, OrbitCamera.MAX_FOCAL)
                Triple(base, base.copy(distance = distance, focalLength = focal), Easing.EASE_IN_OUT)
            }
        }
        val kept = clip.cameraKeyframes.filter { it.timeUs < start - NEARBY_KEY_US }
        require(kept.size + 2 <= MAX_SCENE_KEYFRAMES) { "Limite de $MAX_SCENE_KEYFRAMES pontos" }
        val keys = kept + OrbitCameraKeyframe(start, from, easing) + OrbitCameraKeyframe(end, to, Easing.SMOOTH)
        return clip.copy(cameraKeyframes = keys.sortedBy { it.timeUs })
    }
}

/** Rotations are not wrapped so they can spin; 100 turns either way is the limit. */
const val MAX_TURN_DEGREES = 36_000f

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun position(value: Float) = value.coerceIn(-50f, 50f)
private fun angle(value: Float) = value.coerceIn(-MAX_TURN_DEGREES, MAX_TURN_DEGREES)

private fun geometric(a: Float, b: Float, t: Float): Float =
    if (a > 0f && b > 0f) (a * Math.pow((b / a).toDouble(), t.toDouble())).toFloat() else lerp(a, b, t)

private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
    a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0],
)

private fun normalize(v: FloatArray): FloatArray {
    val length = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).coerceAtLeast(1e-9f)
    return floatArrayOf(v[0] / length, v[1] / length, v[2] / length)
}

private inline fun <K, V> interpolate(
    keys: List<K>, timeUs: Long, fallback: V,
    time: (K) -> Long, value: (K) -> V, easing: (K) -> Easing, bezier: (K) -> CubicBezier,
    mix: (V, V, Float) -> V,
): V {
    if (keys.isEmpty()) return fallback
    val upper = keys.upperBoundTime(timeUs, time)
    if (upper == 0) return value(keys.first())
    if (upper == keys.size) return value(keys.last())
    val a = keys[upper - 1]; val b = keys[upper]
    val span = (time(b) - time(a)).coerceAtLeast(1L)
    val t = easing(a).apply((timeUs - time(a)).toFloat() / span, bezier(a))
    return mix(value(a), value(b), t)
}
