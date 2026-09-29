package com.termex.replay15.editor.assets

import org.json.JSONObject
import com.termex.replay15.editor.domain.CubicBezier
import com.termex.replay15.editor.domain.Easing
import com.termex.replay15.editor.domain.upperBoundTime
import com.termex.replay15.editor.domain.newId

enum class AssetType { FILTER, LUT, EFFECT, TRANSITION, ANIMATION, TEXT_TEMPLATE, FONT, STICKER, SOUND, TEMPLATE }
enum class EffectCost { LOW, MEDIUM, HIGH }
/**
 * Compositor blend modes. The first five entries are intentionally kept in
 * their original order because projects persist the enum ordinal on disk.
 */
enum class EffectBlendMode {
    NORMAL,
    ADD,
    MULTIPLY,
    SCREEN,
    OVERLAY,
    LIGHTEN,
    DARKEN,
    DIFFERENCE,
    SOFT_LIGHT,
    HARD_LIGHT,
}
enum class EffectMaskShape { NONE, RECTANGLE, ELLIPSE, LINEAR, RADIAL }

enum class TransitionEngine {
    BLEND,
    TRANSFORM,
    MASK_WIPE,
    BLUR,
    WHIP_MOTION,
    LIGHT_FLASH,
    RGB_GLITCH,
    LUMA,
    DISTORTION_UV,
    PERSPECTIVE_3D,
    CREATIVE,
    COMPOSITOR
}

data class TransitionDefinition(
    val id: String,
    val version: Int = 1,
    val name: String,
    val category: String,
    val engine: TransitionEngine,
    val defaultDurationUs: Long = 500_000L,
    val minDurationUs: Long = 100_000L,
    val maxDurationUs: Long = 3_000_000L,
    val parameters: List<EffectParameter> = emptyList(),
    val supportsDirection: Boolean = false,
    val supportsEasing: Boolean = true,
    val gpuCost: EffectCost = EffectCost.LOW,
    val shaderFile: String = "$id.frag",
) {
    companion object {
        fun parse(text: String): TransitionDefinition {
            val json = JSONObject(text)
            val id = json.getString("id")
            val version = json.optInt("version", 1)
            val name = json.getString("name")
            val category = json.getString("category")
            val engine = runCatching { TransitionEngine.valueOf(json.optString("engine", "BLEND")) }.getOrDefault(TransitionEngine.BLEND)
            val defaultDurationUs = json.optLong("defaultDurationUs", 500_000L)
            val minDurationUs = json.optLong("minDurationUs", 100_000L)
            val maxDurationUs = json.optLong("maxDurationUs", 3_000_000L)
            val array = json.optJSONArray("parameters")
            val parameters = if (array != null) List(array.length()) { n ->
                val p = array.getJSONObject(n)
                EffectParameter(p.getString("id"), p.getString("name"), p.getDouble("min").toFloat(), p.getDouble("max").toFloat(), p.getDouble("default").toFloat())
            } else emptyList()
            val supportsDirection = json.optBoolean("supportsDirection", false)
            val supportsEasing = json.optBoolean("supportsEasing", true)
            val gpuCost = runCatching { EffectCost.valueOf(json.optString("gpuCost", "LOW")) }.getOrDefault(EffectCost.LOW)
            val shaderFile = json.optString("shaderFile").takeIf { it.isNotBlank() } ?: "$id.frag"
            return TransitionDefinition(id, version, name, category, engine, defaultDurationUs, minDurationUs, maxDurationUs,
                parameters, supportsDirection, supportsEasing, gpuCost, shaderFile)
        }
    }
}

data class TransitionInstance(
    val id: String = newId(),
    val transitionId: String,
    val leftClipId: String,
    val rightClipId: String,
    val durationUs: Long = 500_000L,
    val parameters: Map<String, Float> = emptyMap(),
    val easing: Easing = Easing.SMOOTH,
    val bezier: CubicBezier = CubicBezier(),
) {
    init {
        require(id.length in 1..80)
        require(transitionId.matches(Regex("[a-z][a-z0-9_]{0,63}")))
        require(leftClipId.isNotBlank() && rightClipId.isNotBlank() && leftClipId != rightClipId)
        require(durationUs in 100_000L..5_000_000L)
        require(parameters.size <= 16 && parameters.values.all { it.isFinite() })
    }
}

data class EffectMask(
    val shape: EffectMaskShape = EffectMaskShape.NONE,
    val x: Float = 0f,
    val y: Float = 0f,
    val size: Float = .75f,
    val aspect: Float = 1f,
    val rotation: Float = 0f,
    val feather: Float = .08f,
    val invert: Boolean = false,
    val opacity: Float = 1f,
) {
    init {
        require(x in -.5f.. .5f && y in -.5f.. .5f && size in .05f..2f)
        require(aspect in .1f..10f && rotation in -180f..180f)
        require(feather in .001f.. .5f && opacity in 0f..1f)
    }
}

enum class EffectEngine {
    BLUR,
    TRANSFORM_MOTION,
    SHAKE,
    DISTORTION_UV,
    RGB_CHANNEL,
    GLOW_BLOOM,
    LIGHT_FLASH,
    FILM_NOISE,
    TEMPORAL_TRAIL,
    COLOR_LUT,
    PARTICLE,
    CREATIVE,
    COMPOSITOR
}

data class EffectParameter(val id: String, val name: String, val min: Float, val max: Float, val default: Float) {
    init { require(id.matches(Regex("[a-z][a-z0-9_]{0,31}")) && name.length in 1..80 && min.isFinite() && max.isFinite() && max > min && default in min..max) }
}
data class EffectDefinition(val id: String, val version: Int, val name: String, val category: String,
    val shader: String, val parameters: List<EffectParameter>, val license: String, val premium: Boolean = false,
    val supportsKeyframes: Boolean = true, val supportsMask: Boolean = true,
    val gpuRequired: Boolean = true, val cost: EffectCost = EffectCost.LOW,
    val compatibility: String = "SDR_HDR_TONEMAPPED",
    val engine: EffectEngine = EffectEngine.COMPOSITOR,
    val thumbnail: String? = null,
    val multiPass: Boolean = false,
    val temporal: Boolean = false,
    val shaderFile: String? = null) {
    companion object {
        fun parse(text: String): EffectDefinition {
            require(text.length <= 32_768)
            val json = JSONObject(text)
            require(json.getInt("engineVersion") == 1 && json.getString("type") == "EFFECT") { "Pacote requer outra versao do editor" }
            val id = json.getString("id"); require(id.matches(Regex("[a-z][a-z0-9_]{0,63}")))
            val version = json.getInt("version"); require(version in 1..100000)
            val name = json.getString("name"); require(name.length in 1..100)
            val category = json.getString("category"); require(category.length in 1..50)
            val license = json.getString("license"); require(license.length in 1..2000)
            val array = json.getJSONArray("parameters"); require(array.length() <= 16)
            val parameters = List(array.length()) { n -> val p = array.getJSONObject(n)
                require(p.getString("type") == "float")
                EffectParameter(p.getString("id"), p.getString("name"), p.getDouble("min").toFloat(), p.getDouble("max").toFloat(), p.getDouble("default").toFloat()) }
            require(parameters.map { it.id }.distinct().size == parameters.size)
            val shader = json.getString("shader")
            require(shader == "shader.frag" || shader.endsWith(".frag"))
            val cost = runCatching { EffectCost.valueOf(json.optString("cost", "LOW")) }.getOrDefault(EffectCost.LOW)
            val compatibility = json.optString("compatibility", "SDR_HDR_TONEMAPPED")
            require(compatibility.length in 1..80)
            val engine = runCatching { EffectEngine.valueOf(json.optString("engine", "COMPOSITOR")) }.getOrDefault(EffectEngine.COMPOSITOR)
            val thumbnail = json.optString("thumbnail").takeIf { it.isNotBlank() }
            val multiPass = json.optBoolean("multiPass", false)
            val temporal = json.optBoolean("temporal", false)
            val shaderFile = json.optString("shaderFile").takeIf { it.isNotBlank() }
                ?: if (shader != "shader.frag") shader.removeSuffix(".frag") else null
            return EffectDefinition(id, version, name, category, shader, parameters, license,
                json.optBoolean("premium", false), json.optBoolean("supportsKeyframes", true),
                json.optBoolean("supportsMask", true), json.optBoolean("gpuRequired", true), cost, compatibility,
                engine, thumbnail, multiPass, temporal, shaderFile)
        }
    }
}
data class OnlineAsset(val id: String, val version: Int, val name: String, val url: String, val bytes: Long, val sha256: String)

data class EffectValueKeyframe(val sourceUs: Long, val value: Float, val easing: Easing = Easing.SMOOTH,
    val bezier: CubicBezier = CubicBezier()) {
    init { require(sourceUs >= 0 && value.isFinite()) }
}

data class EffectInstance(val id: String, val assetId: String, val version: Int = 1,
    val enabled: Boolean = true, val intensity: Float = .7f, val values: Map<String, Float> = emptyMap(),
    val keyframes: Map<String, List<EffectValueKeyframe>> = emptyMap(),
    val startTimeUs: Long? = null, val endTimeUs: Long? = null,
    val mask: EffectMask? = null, val blendMode: EffectBlendMode = EffectBlendMode.NORMAL,
    /** Shared by every node created from one preset. Node values remain untouched when this changes. */
    val groupId: String? = null, val groupIntensity: Float = 1f) {
    init {
        require(id.length in 1..80 && assetId.matches(Regex("[a-z][a-z0-9_]{0,63}")) && version in 1..100000)
        require(intensity in 0f..1f && values.size <= 16 && values.values.all { it.isFinite() })
        require(keyframes.size <= 32 && keyframes.keys.all { it == INTENSITY || it.matches(Regex("[a-z][a-z0-9_]{0,31}")) })
        require(keyframes.values.sumOf { it.size } <= 400)
        require(keyframes.values.all { keys -> keys.size <= 200 && keys.zipWithNext().all { (a, b) -> a.sourceUs < b.sourceUs } })
        require(startTimeUs == null || startTimeUs >= 0)
        require(endTimeUs == null || endTimeUs >= 0)
        require(startTimeUs == null || endTimeUs == null || endTimeUs > startTimeUs)
        require(groupId == null || groupId.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        require(groupIntensity in 0f..1f)
    }

    fun valueAt(property: String, sourceUs: Long, fallback: Float): Float {
        val keys = keyframes[property].orEmpty()
        if (keys.isEmpty()) return fallback
        val upper = keys.upperBoundTime(sourceUs, EffectValueKeyframe::sourceUs)
        if (upper == 0) return keys.first().value
        if (upper == keys.size) return keys.last().value
        val a = keys[upper - 1]; val b = keys[upper]
        val t = a.easing.apply((sourceUs - a.sourceUs).toFloat() / (b.sourceUs - a.sourceUs), a.bezier)
        return a.value + (b.value - a.value) * t
    }

    fun activeAt(sourceUs: Long): Boolean = enabled && sourceUs >= (startTimeUs ?: 0L) && sourceUs < (endTimeUs ?: Long.MAX_VALUE)

    companion object {
        const val INTENSITY = "_intensity"
        const val MASK_X = "mask_x"
        const val MASK_Y = "mask_y"
        const val MASK_SIZE = "mask_size"
        const val MASK_ASPECT = "mask_aspect"
        const val MASK_ROTATION = "mask_rotation"
        const val MASK_FEATHER = "mask_feather"
        const val MASK_OPACITY = "mask_opacity"
    }
}
