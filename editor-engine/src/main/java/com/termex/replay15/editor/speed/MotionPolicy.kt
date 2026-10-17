package com.termex.replay15.editor.speed

import com.termex.replay15.editor.domain.VideoClip

/** A técnica que de fato renderiza o quadro (depois de resolver o automático). */
enum class SynthesisMethod { REPEAT, BLEND, OPTICAL_FLOW }

/** A técnica que de fato produz o motion blur. */
enum class BlurMethod { NONE, MOTION_VECTORS, LAYER_ANIMATION }

/** O que está sendo renderizado (ordinais iguais ao `policy::Context` do Rust). */
enum class MotionContext { SCRUB, PLAYBACK, PAUSED, EXPORT_ECONOMY, EXPORT_HIGH }

/**
 * O que o aparelho aguenta agora. [thermalStatus] segue `PowerManager.THERMAL_STATUS_*`;
 * [previewTier] é o nível do `PreviewPerformanceController` (0 melhor … 5).
 */
data class DeviceMotionProfile(
    val memoryClassMb: Int,
    val lowRamDevice: Boolean,
    val highpFragmentShaders: Boolean,
    val maxTextureSize: Int,
    val thermalStatus: Int = 0,
    val previewTier: Int = 0,
) {
    companion object {
        /** Celular intermediário; usado antes de sondar o aparelho e nos testes. */
        val DEFAULT = DeviceMotionProfile(memoryClassMb = 256, lowRamDevice = false, highpFragmentShaders = true, maxTextureSize = 4096)
    }
}

/** Plano resolvido pelo motor para um clipe num contexto. [notice] explica uma troca ao usuário. */
data class MotionPlan(
    val synthesis: SynthesisMethod,
    val blur: BlurMethod,
    val flowShortSide: Int,
    val flowLevels: Int,
    val flowIterations: Int,
    val blurSamples: Int,
    val exposureFraction: Float,
    val blurIntensity: Float,
    val notice: String?,
) {
    val needsFlow: Boolean get() = synthesis == SynthesisMethod.OPTICAL_FLOW || blur == BlurMethod.MOTION_VECTORS
    val isPassThrough: Boolean get() = synthesis == SynthesisMethod.REPEAT && blur == BlurMethod.NONE
}

/** Ponte para a política de qualidade do motor (`policy.rs`). */
object MotionPolicy {
    fun plan(clip: VideoClip, context: MotionContext, device: DeviceMotionProfile, outputFps: Float): MotionPlan {
        val range = clip.timeMap.speedRange()
        val blur = clip.motionBlur
        val ints = intArrayOf(
            clip.slowMotion.ordinal, if (blur.enabled) 1 else 0, blur.source.ordinal, blur.quality.samples, context.ordinal,
            device.memoryClassMb, if (device.lowRamDevice) 1 else 0, if (device.highpFragmentShaders) 1 else 0,
            device.maxTextureSize, device.thermalStatus, device.previewTier, clip.width, clip.height,
            if (clip.keyframes.size >= 2) 1 else 0, if (clip.image) 1 else 0,
        )
        val floats = floatArrayOf(clip.fps, range.start, range.endInclusive, blur.shutterAngle, blur.intensity, outputFps)
        val r = NativeSpeed.motionPlan(ints, floats)
        val notice = r[8].toInt()
        return MotionPlan(
            synthesis = SynthesisMethod.entries[r[0].toInt()], blur = BlurMethod.entries[r[1].toInt()],
            flowShortSide = r[2].toInt(), flowLevels = r[3].toInt(), flowIterations = r[4].toInt(), blurSamples = r[5].toInt(),
            exposureFraction = r[6], blurIntensity = r[7], notice = if (notice == 0) null else NativeSpeed.noticeText(notice),
        )
    }
}
