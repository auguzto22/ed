package com.termex.replay15.editor.speed

import com.termex.replay15.editor.domain.VideoClip

/**
 * O que o renderizador da prévia deve fazer com uma camada de vídeo neste quadro.
 *
 * [exposureSourceUs] é quanto tempo **da mídia** o obturador virtual fica aberto neste instante
 * (fração do obturador × intervalo do quadro de saída × velocidade × intensidade). Dividido pela
 * distância entre os dois quadros do par, vira a escala do blur por vetores.
 */
data class LayerMotion(
    val synthesis: SynthesisMethod,
    val blurSamples: Int,
    val exposureSourceUs: Float,
    val flowShortSide: Int,
    val flowLevels: Int,
    val flowIterations: Int,
    /** Controle de fantasmas da mistura de quadros (0 = mistura pura). */
    val ghostControl: Float,
) {
    val needsFlow: Boolean get() = synthesis == SynthesisMethod.OPTICAL_FLOW || blurSamples > 0
    val needsPair: Boolean get() = synthesis != SynthesisMethod.REPEAT || blurSamples > 0
}

/**
 * Resolve o plano de movimento das camadas da prévia (thread principal).
 *
 * O plano em si vem do motor (`policy.rs`) e é guardado por clipe e situação; o que muda a cada
 * quadro (a velocidade instantânea da curva) é calculado na hora.
 */
class PreviewMotionPlanner(private val device: () -> DeviceMotionProfile) {
    private data class Key(
        val clipId: String, val speed: Float, val curve: List<com.termex.replay15.editor.domain.SpeedPoint>,
        val inUs: Long, val outUs: Long, val mode: SlowMotionMode, val blur: MotionBlurConfig, val fps: Float,
        val width: Int, val height: Int, val animated: Boolean, val context: MotionContext, val device: DeviceMotionProfile,
        val outputFps: Int,
    )

    private val cache = object : LinkedHashMap<Key, MotionPlan>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, MotionPlan>?): Boolean = size > 64
    }

    /** Último aviso de cada clipe (para a interface explicar uma troca de técnica). */
    val notices = HashMap<String, String>()

    fun plan(clip: VideoClip, context: MotionContext, outputFps: Int): MotionPlan {
        val profile = device()
        val key = Key(clip.id, clip.speed, clip.speedCurve, clip.inUs, clip.outUs, clip.slowMotion, clip.motionBlur, clip.fps,
            clip.width, clip.height, clip.keyframes.size >= 2, context, profile, outputFps)
        return cache.getOrPut(key) { MotionPolicy.plan(clip, context, profile, outputFps.toFloat()) }.also { plan ->
            if (plan.notice != null) notices[clip.id] = plan.notice else notices.remove(clip.id)
        }
    }

    /** `null` quando a camada passa direto (tempo real e sem blur por vetores). */
    fun layerMotion(clip: VideoClip, clipTimelineUs: Long, context: MotionContext, outputFps: Int): LayerMotion? {
        if (clip.image || clip.isNullObject) return null
        val plan = plan(clip, context, outputFps)
        val vectorBlur = plan.blur == BlurMethod.MOTION_VECTORS
        if (plan.synthesis == SynthesisMethod.REPEAT && !vectorBlur) return null
        val speed = clip.timeMap.curveSpeedAtTimeline(clipTimelineUs)
        val exposure = if (vectorBlur) plan.exposureFraction * 1_000_000f / outputFps * speed * plan.blurIntensity else 0f
        return LayerMotion(
            synthesis = plan.synthesis,
            blurSamples = if (vectorBlur) plan.blurSamples else 0,
            exposureSourceUs = exposure,
            flowShortSide = plan.flowShortSide,
            flowLevels = plan.flowLevels,
            flowIterations = plan.flowIterations,
            ghostControl = GHOST_CONTROL,
        )
    }

    fun clear() = cache.clear()

    companion object {
        /** Supressão de fantasmas padrão da mistura: forte o bastante para não duplicar objetos. */
        const val GHOST_CONTROL = .6f
    }
}
