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
/** Large enough for a Real Camera recording (about 3 minutes at 20 samples per second). */
const val MAX_SCENE_KEYFRAMES = 4000
const val MAX_SCENE_NULLS = 16
const val MAX_CAMERA_SHOTS = 16
/** Target id of the model itself (nulls use their own ids). */
const val MODEL_TARGET = "model"
/** Camera id of the scene's main camera (shots use their own ids). */
const val MAIN_CAMERA = "main"
private const val NEARBY_KEY_US = 20_000L
private val SCENE_ID = Regex("[a-zA-Z0-9-]{1,80}")

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
        const val MIN_PITCH = -85f
        const val MAX_PITCH = 85f
        const val MIN_DISTANCE = .3f
        const val MAX_DISTANCE = 200f
        const val MIN_FOCAL = 12f
        const val MAX_FOCAL = 300f
    }
}

class CameraBasis(val eye: FloatArray, val target: FloatArray, val up: FloatArray)

/**
 * The orbit camera that looks along [forward] from [eye] with [up], keeping [reference]'s
 * distance and lens. Yaw is unwrapped next to [reference]'s yaw so recorded motion never jumps
 * by a full turn. Pitch outside the orbit range is clamped.
 */
fun OrbitCamera.Companion.fromBasis(eye: FloatArray, forward: FloatArray, up: FloatArray, reference: OrbitCamera): OrbitCamera {
    val f = normalize(forward)
    val back = floatArrayOf(-f[0], -f[1], -f[2])
    val pitch = Math.toDegrees(kotlin.math.asin(back[1].coerceIn(-1f, 1f).toDouble())).toFloat()
        .coerceIn(OrbitCamera.MIN_PITCH, OrbitCamera.MAX_PITCH)
    var yaw = Math.toDegrees(kotlin.math.atan2(back[0].toDouble(), back[2].toDouble())).toFloat()
    var delta = (yaw - reference.yaw) % 360f
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    yaw = angle(reference.yaw + delta)
    val d = reference.distance
    val target = floatArrayOf(position(eye[0] + f[0] * d), position(eye[1] + f[1] * d), position(eye[2] + f[2] * d))
    val right = normalize(cross(f, floatArrayOf(0f, 1f, 0f)))
    val up0 = cross(right, f)
    val u = normalize(up)
    val roll = Math.toDegrees(kotlin.math.atan2((u[0] * right[0] + u[1] * right[1] + u[2] * right[2]).toDouble(),
        (u[0] * up0[0] + u[1] * up0[1] + u[2] * up0[2]).toDouble())).toFloat().coerceIn(-180f, 180f)
    return reference.copy(yaw = yaw, pitch = pitch, targetX = target[0], targetY = target[1], targetZ = target[2], roll = roll)
}

/** A null object: an invisible, animatable transform that other objects can be attached to. */
data class Null3D(
    val id: String = newId(),
    val name: String,
    val pose: ModelPose = ModelPose(),
    val poseKeyframes: List<ModelKeyframe> = emptyList(),
    val parentId: String? = null,
) {
    init {
        require(id.matches(SCENE_ID) && id != MODEL_TARGET && id != MAIN_CAMERA)
        require(name.length in 1..80)
        require(parentId != id)
        require(poseKeyframes.size <= MAX_SCENE_KEYFRAMES && poseKeyframes.zipWithNext().all { (a, b) -> a.timeUs < b.timeUs })
    }
}

/**
 * An extra camera that takes over between [startUs] and [endUs] (scene-local). Overlapping
 * shots: the one that starts later wins, so a shot placed inside another one cuts into it.
 * Keyframe times are scene-local, like every other track of the scene.
 */
data class CameraShot(
    val id: String = newId(),
    val name: String,
    val startUs: Long,
    val endUs: Long,
    val camera: OrbitCamera = OrbitCamera(),
    val keyframes: List<OrbitCameraKeyframe> = emptyList(),
    val parentId: String? = null,
) {
    init {
        require(id.matches(SCENE_ID) && id != MAIN_CAMERA && id != MODEL_TARGET)
        require(name.length in 1..80)
        require(startUs >= 0L && endUs > startUs)
        require(keyframes.size <= MAX_SCENE_KEYFRAMES && keyframes.zipWithNext().all { (a, b) -> a.timeUs < b.timeUs })
    }
}

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
    val nulls: List<Null3D> = emptyList(),
    /** Null the model is attached to, if any. */
    val modelParentId: String? = null,
    /** Null the main camera is attached to, if any. */
    val cameraParentId: String? = null,
    val cameraShots: List<CameraShot> = emptyList(),
) {
    init {
        require(id.matches(SCENE_ID))
        require(name.length in 1..180)
        require(modelPath.length in 1..1000)
        require(startUs >= 0L && endUs > startUs)
        require(poseKeyframes.size <= MAX_SCENE_KEYFRAMES && cameraKeyframes.size <= MAX_SCENE_KEYFRAMES)
        require(poseKeyframes.zipWithNext().all { (a, b) -> a.timeUs < b.timeUs })
        require(cameraKeyframes.zipWithNext().all { (a, b) -> a.timeUs < b.timeUs })
        require(animationSpeed.isFinite() && animationSpeed in 0f..4f)
        require(nulls.size <= MAX_SCENE_NULLS && nulls.map { it.id }.distinct().size == nulls.size) { "Nulos repetidos" }
        require(cameraShots.size <= MAX_CAMERA_SHOTS && cameraShots.map { it.id }.distinct().size == cameraShots.size) { "Câmeras repetidas" }
        val nullIds = nulls.mapTo(HashSet()) { it.id }
        val parents = listOf(modelParentId, cameraParentId) + nulls.map { it.parentId } + cameraShots.map { it.parentId }
        require(parents.all { it == null || it in nullIds }) { "Vínculo com um nulo que não existe" }
        val parentOf = nulls.associate { it.id to it.parentId }
        nulls.forEach { start ->
            var current = start.parentId
            var steps = 0
            while (current != null) {
                require(current != start.id && steps++ <= MAX_SCENE_NULLS) { "Vínculo circular entre nulos" }
                current = parentOf[current]
            }
        }
    }

    val durationUs: Long get() = endUs - startUs

    // -------------------------------------------------------------------------------------
    // Objects (the model or a null) and their hierarchy
    // -------------------------------------------------------------------------------------
    fun nullById(id: String): Null3D? = nulls.firstOrNull { it.id == id }

    fun parentOf(target: String): String? = if (target == MODEL_TARGET) modelParentId else nullById(target)?.parentId

    fun poseBase(target: String): ModelPose = if (target == MODEL_TARGET) pose else requireNotNull(nullById(target)).pose

    fun poseKeys(target: String): List<ModelKeyframe> = if (target == MODEL_TARGET) poseKeyframes else requireNotNull(nullById(target)).poseKeyframes

    fun poseAt(target: String, localUs: Long): ModelPose = interpolate(poseKeys(target), localUs, poseBase(target), ModelKeyframe::timeUs,
        ModelKeyframe::pose, ModelKeyframe::easing, ModelKeyframe::bezier) { a, b, t -> a.mix(b, t) }

    fun withPoseTrack(target: String, base: ModelPose, keys: List<ModelKeyframe>): Scene3DClip =
        if (target == MODEL_TARGET) copy(pose = base, poseKeyframes = keys)
        else copy(nulls = nulls.map { if (it.id == target) it.copy(pose = base, poseKeyframes = keys) else it }.also {
            require(nulls.any { n -> n.id == target }) { "Nulo não encontrado" }
        })

    /** Attaches [target] (the model, a null or a camera) to [parentId], or detaches it with null. */
    fun withParent(target: String, parentId: String?): Scene3DClip = when {
        target == MODEL_TARGET -> copy(modelParentId = parentId)
        target == MAIN_CAMERA -> copy(cameraParentId = parentId)
        cameraShots.any { it.id == target } -> copy(cameraShots = cameraShots.map { if (it.id == target) it.copy(parentId = parentId) else it })
        else -> copy(nulls = nulls.map { if (it.id == target) it.copy(parentId = parentId) else it })
    }

    /** Nulls [target] may be attached to without creating a loop. */
    fun eligibleParents(target: String): List<Null3D> = nulls.filter { candidate ->
        var current: String? = candidate.id
        var steps = 0
        while (current != null && steps++ <= MAX_SCENE_NULLS) {
            if (current == target) return@filter false
            current = nullById(current)?.parentId
        }
        true
    }

    /** Matrix of [parentId]'s whole chain (identity for no parent), scene space, column-major. */
    fun parentMatrix(parentId: String?, localUs: Long, out: FloatArray = FloatArray(16)): FloatArray {
        Matrix4.setIdentity(out)
        var current = parentId
        val chain = ArrayList<String>(4)
        while (current != null && chain.size <= MAX_SCENE_NULLS) { chain += current; current = nullById(current)?.parentId }
        // Root first: M = P_root * ... * P_parent.
        for (index in chain.indices.reversed()) Matrix4.multiply(out, out, poseAt(chain[index], localUs).toMatrix())
        return out
    }

    /** Scene-space matrix of the model or a null (before the model's own normalization). */
    fun objectMatrix(target: String, localUs: Long, out: FloatArray = FloatArray(16)): FloatArray {
        parentMatrix(parentOf(target), localUs, out)
        Matrix4.multiply(out, out, poseAt(target, localUs).toMatrix())
        return out
    }

    // -------------------------------------------------------------------------------------
    // Cameras: the main camera and the shots that cut over it
    // -------------------------------------------------------------------------------------
    fun shotById(id: String): CameraShot? = cameraShots.firstOrNull { it.id == id }

    fun activeShot(localUs: Long): CameraShot? = cameraShots.filter { localUs in it.startUs until it.endUs }.maxByOrNull { it.startUs }

    fun activeCameraId(localUs: Long): String = activeShot(localUs)?.id ?: MAIN_CAMERA

    fun cameraBase(cameraId: String): OrbitCamera = if (cameraId == MAIN_CAMERA) camera else requireNotNull(shotById(cameraId)).camera

    fun cameraKeys(cameraId: String): List<OrbitCameraKeyframe> = if (cameraId == MAIN_CAMERA) cameraKeyframes else requireNotNull(shotById(cameraId)).keyframes

    fun cameraParentOf(cameraId: String): String? = if (cameraId == MAIN_CAMERA) cameraParentId else shotById(cameraId)?.parentId

    fun cameraAt(cameraId: String, localUs: Long): OrbitCamera = interpolate(cameraKeys(cameraId), localUs, cameraBase(cameraId),
        OrbitCameraKeyframe::timeUs, OrbitCameraKeyframe::camera, OrbitCameraKeyframe::easing, OrbitCameraKeyframe::bezier) { a, b, t -> a.mix(b, t) }

    fun withCameraTrack(cameraId: String, base: OrbitCamera, keys: List<OrbitCameraKeyframe>): Scene3DClip =
        if (cameraId == MAIN_CAMERA) copy(camera = base, cameraKeyframes = keys)
        else copy(cameraShots = cameraShots.map { if (it.id == cameraId) it.copy(camera = base, keyframes = keys) else it }.also {
            require(cameraShots.any { s -> s.id == cameraId }) { "Câmera não encontrada" }
        })

    /** Eye, target and up of the camera that is on air at [localUs], in scene space. */
    fun worldCamera(localUs: Long): CameraBasis {
        val id = activeCameraId(localUs)
        val local = cameraAt(id, localUs).basis()
        val parent = cameraParentOf(id) ?: return local
        val m = parentMatrix(parent, localUs)
        fun point(v: FloatArray) = Matrix4.transformPoint(m, v[0], v[1], v[2], 1f).copyOf(3)
        val up = Matrix4.transformPoint(m, local.up[0], local.up[1], local.up[2], 0f).copyOf(3)
        return CameraBasis(point(local.eye), point(local.target), normalize(up))
    }

    /** Lens of the camera that is on air at [localUs]. */
    fun focalLengthAt(localUs: Long): Float = cameraAt(activeCameraId(localUs), localUs).focalLength

    fun isActiveAt(projectTimeUs: Long): Boolean = projectTimeUs in startUs until endUs

    /** Time inside the layer, clamped to its range. */
    fun localTime(projectTimeUs: Long): Long = (projectTimeUs - startUs).coerceIn(0L, durationUs)

    /** Pose of the model. */
    fun poseAt(localUs: Long): ModelPose = poseAt(MODEL_TARGET, localUs)

    /** The main camera (shots excluded). */
    fun cameraAt(localUs: Long): OrbitCamera = cameraAt(MAIN_CAMERA, localUs)

    /** Seconds of embedded animation to show at [localUs]; the renderer loops it over its length. */
    fun animationSeconds(localUs: Long): Float = if (!playAnimation) 0f else localUs / 1_000_000f * animationSpeed

    /** True when consecutive frames of this layer can differ, so they must be re-rendered. */
    val isAnimated: Boolean
        get() = poseKeyframes.size > 1 || cameraKeyframes.size > 1 || (playAnimation && animationSpeed > 0f) ||
            cameraShots.isNotEmpty() || nulls.any { it.poseKeyframes.size > 1 }

    /** Writes [state] as a pose keyframe at [localUs], replacing one within 20 ms and keeping its curve. */
    fun withPoseKey(localUs: Long, state: ModelPose, easing: Easing? = null, target: String = MODEL_TARGET): Scene3DClip {
        require(localUs >= 0L)
        val keys = poseKeys(target)
        val nearby = keys.minByOrNull { abs(it.timeUs - localUs) }?.takeIf { abs(it.timeUs - localUs) <= NEARBY_KEY_US }
        require(nearby != null || keys.size < MAX_SCENE_KEYFRAMES) { "Limite de $MAX_SCENE_KEYFRAMES pontos" }
        val key = ModelKeyframe(nearby?.timeUs ?: localUs, state, easing ?: nearby?.easing ?: Easing.SMOOTH, nearby?.bezier ?: CubicBezier())
        return withPoseTrack(target, poseBase(target), (keys.filterNot { it === nearby } + key).sortedBy { it.timeUs })
    }

    fun withCameraKey(localUs: Long, state: OrbitCamera, easing: Easing? = null, cameraId: String = MAIN_CAMERA): Scene3DClip {
        require(localUs >= 0L)
        val keys = cameraKeys(cameraId)
        val nearby = keys.minByOrNull { abs(it.timeUs - localUs) }?.takeIf { abs(it.timeUs - localUs) <= NEARBY_KEY_US }
        require(nearby != null || keys.size < MAX_SCENE_KEYFRAMES) { "Limite de $MAX_SCENE_KEYFRAMES pontos" }
        val key = OrbitCameraKeyframe(nearby?.timeUs ?: localUs, state, easing ?: nearby?.easing ?: Easing.SMOOTH, nearby?.bezier ?: CubicBezier())
        return withCameraTrack(cameraId, cameraBase(cameraId), (keys.filterNot { it === nearby } + key).sortedBy { it.timeUs })
    }

    /**
     * Writes a recorded take: every key of [cameraId] from [fromUs] to [toUs] is replaced by
     * [recorded] (scene-local, strictly increasing, inside that range); the rest is untouched.
     */
    fun withCameraRecording(cameraId: String, fromUs: Long, toUs: Long, recorded: List<OrbitCameraKeyframe>): Scene3DClip {
        require(fromUs in 0L..toUs) { "Trecho de gravação inválido" }
        require(recorded.all { it.timeUs in fromUs..toUs } && recorded.zipWithNext().all { (a, b) -> a.timeUs < b.timeUs }) { "Gravação fora de ordem" }
        val outside = cameraKeys(cameraId).filter { it.timeUs < fromUs || it.timeUs > toUs }
        require(outside.size + recorded.size <= MAX_SCENE_KEYFRAMES) { "Limite de $MAX_SCENE_KEYFRAMES pontos" }
        return withCameraTrack(cameraId, cameraBase(cameraId), (outside + recorded).sortedBy { it.timeUs })
    }

    /** Moves the layer so it starts at [newStartUs], keeping its length. */
    fun movedTo(newStartUs: Long): Scene3DClip = copy(startUs = newStartUs.coerceAtLeast(0L), endUs = newStartUs.coerceAtLeast(0L) + durationUs)

    /** Changes the length; keyframes after the new end are kept but simply never reached. */
    fun withDuration(newDurationUs: Long): Scene3DClip = copy(endUs = startUs + newDurationUs.coerceAtLeast(MIN_SCENE_DURATION_US))

    /**
     * Timeline edit: the same length moves the layer with its animation; a different length
     * trims it, and the animation stays where it was in the project (like trimming a clip), so
     * moving the start only cuts or reveals the beginning.
     */
    fun retimed(newStartUs: Long, newEndUs: Long): Scene3DClip {
        val start = newStartUs.coerceAtLeast(0L)
        val end = newEndUs.coerceAtLeast(start + MIN_SCENE_DURATION_US)
        if (end - start == durationUs) return movedTo(start)
        val shift = start - startUs
        return shiftedBy(shift).copy(startUs = start, endUs = end)
    }

    /**
     * Timeline edit of a camera cut (scene-local times, kept inside the scene): the same length
     * moves the cut with its keyframes; a different length trims it and the keyframes stay.
     */
    fun withShotTiming(shotId: String, newStartUs: Long, newEndUs: Long): Scene3DClip {
        val shot = requireNotNull(shotById(shotId)) { "Câmera não encontrada" }
        val length = (newEndUs - newStartUs).coerceIn(MIN_SCENE_DURATION_US, durationUs)
        val start = newStartUs.coerceIn(0L, durationUs - length)
        val end = start + length
        val delta = start - shot.startUs
        val keys = if (length == shot.endUs - shot.startUs && delta != 0L) {
            shot.keyframes.map { it.copy(timeUs = (it.timeUs + delta).coerceAtLeast(0L)) }.distinctBy { it.timeUs }
        } else shot.keyframes
        return copy(cameraShots = cameraShots.map { if (it.id == shotId) it.copy(startUs = start, endUs = end, keyframes = keys) else it })
    }

    /** Moves every scene-local time by -[shiftUs]; what falls before 0 is folded into a key at 0. */
    private fun shiftedBy(shiftUs: Long): Scene3DClip {
        if (shiftUs == 0L) return this
        fun poseTrack(target: String): List<ModelKeyframe> {
            val keys = poseKeys(target)
            if (keys.isEmpty()) return keys
            val kept = keys.filter { it.timeUs >= shiftUs }.map { it.copy(timeUs = it.timeUs - shiftUs) }
            val dropped = keys.lastOrNull { it.timeUs < shiftUs } ?: return kept
            // The pose at the new start becomes the first key, so the animation does not jump.
            return listOf(ModelKeyframe(0L, poseAt(target, shiftUs), dropped.easing, dropped.bezier)) + kept.filter { it.timeUs > 0L }
        }
        fun cameraTrack(cameraId: String): List<OrbitCameraKeyframe> {
            val keys = cameraKeys(cameraId)
            if (keys.isEmpty()) return keys
            val kept = keys.filter { it.timeUs >= shiftUs }.map { it.copy(timeUs = it.timeUs - shiftUs) }
            val dropped = keys.lastOrNull { it.timeUs < shiftUs } ?: return kept
            return listOf(OrbitCameraKeyframe(0L, cameraAt(cameraId, shiftUs), dropped.easing, dropped.bezier)) + kept.filter { it.timeUs > 0L }
        }
        return copy(
            poseKeyframes = poseTrack(MODEL_TARGET),
            cameraKeyframes = cameraTrack(MAIN_CAMERA),
            nulls = nulls.map { it.copy(poseKeyframes = poseTrack(it.id)) },
            cameraShots = cameraShots.mapNotNull { shot ->
                val end = shot.endUs - shiftUs
                if (end <= 0L) null
                else shot.copy(startUs = (shot.startUs - shiftUs).coerceAtLeast(0L), endUs = end, keyframes = cameraTrack(shot.id))
            },
        )
    }

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
    /**
     * Writes [move] into the track of [cameraId] from [fromLocalUs] to the end of that camera's
     * range (the scene for the main camera, the shot for a shot).
     */
    fun apply(clip: Scene3DClip, move: SceneCameraMove, fromLocalUs: Long = 0L, cameraId: String = MAIN_CAMERA): Scene3DClip {
        val shot = if (cameraId == MAIN_CAMERA) null else requireNotNull(clip.shotById(cameraId)) { "Câmera não encontrada" }
        val rangeEnd = minOf(clip.durationUs, shot?.endUs ?: clip.durationUs)
        val start = fromLocalUs.coerceIn(shot?.startUs ?: 0L, rangeEnd)
        val end = rangeEnd
        require(end - start >= Scene3DClip.MIN_SCENE_DURATION_US) { "Pouco tempo até o fim desta câmera" }
        val base = clip.cameraAt(cameraId, start)
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
        val existing = clip.cameraKeys(cameraId)
        val kept = existing.filter { it.timeUs < start - NEARBY_KEY_US || it.timeUs > end + NEARBY_KEY_US }
        require(kept.size + 2 <= MAX_SCENE_KEYFRAMES) { "Limite de $MAX_SCENE_KEYFRAMES pontos" }
        val keys = kept + OrbitCameraKeyframe(start, from, easing) + OrbitCameraKeyframe(end, to, Easing.SMOOTH)
        return clip.withCameraTrack(cameraId, clip.cameraBase(cameraId), keys.sortedBy { it.timeUs })
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
