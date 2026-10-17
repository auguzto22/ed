package com.termex.replay15.editor.motion

import com.termex.replay15.editor.assets.EffectInstance
import com.termex.replay15.editor.assets.EffectValueKeyframe
import com.termex.replay15.editor.domain.Easing
import com.termex.replay15.editor.domain.VideoClip
import com.termex.replay15.editor.speed.MotionBlurConfig
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Motion blur pela **animação da camada** (keyframes de posição): o método alternativo,
 * identificado como tal na interface, para quando não há optical flow (aparelho sem `highp`,
 * ou o usuário escolheu esta fonte). Borra a camada inteira na direção em que ela anda — não
 * enxerga movimento dentro do vídeo; isso é o blur por vetores do motor em Rust.
 *
 * O efeito não é salvo no projeto: prévia e exportação o geram a partir do clipe a cada vez, então
 * mudar velocidade, obturador ou keyframes atualiza o resultado sem passos extras. A velocidade
 * do clipe entra no cálculo: uma camada que anda 100 px por segundo **da mídia** anda 50 px por
 * segundo na tela a 0,5×, e o rastro cai pela metade.
 */
object MotionBlurEngine {

    const val ASSET_ID = "recly_motion_blur_vector"

    // EffectInstance aceita até 400 chaves no total; duas curvas de até 150.
    private const val MAX_CURVE_POINTS = 150
    /** Abaixo disto (px por segundo na tela) a camada é considerada parada. */
    private const val MIN_SPEED_PX_PER_SECOND = 12f
    private const val MAX_TRAVEL_PX = 80f
    /** O shader encurta o rastro por `0,25 + 0,15·samples`; com samples = 1 o fator é 0,4. */
    private const val SHADER_TRAIL_FACTOR = .4f

    fun layerEffect(clip: VideoClip, config: MotionBlurConfig, outputFps: Int): EffectInstance? {
        if (!config.enabled || config.intensity <= 0f) return null
        val keyframes = clip.keyframes.sortedBy { it.sourceUs }
        if (keyframes.size < 2) return null
        require(outputFps > 0)
        val exposureSeconds = config.exposureFraction / outputFps
        if (exposureSeconds <= 0f) return null

        val dxCurve = ArrayList<EffectValueKeyframe>()
        val dyCurve = ArrayList<EffectValueKeyframe>()
        for (index in 0 until keyframes.size - 1) {
            val from = keyframes[index]
            val to = keyframes[index + 1]
            val sourceSeconds = (to.sourceUs - from.sourceUs) / 1_000_000f
            if (sourceSeconds <= 0f) continue
            // Segundos de tela que esse trecho da mídia dura, pela curva de velocidade.
            val speed = clip.timeMap.curveSpeedAtSource((from.sourceUs + to.sourceUs) / 2)
            val screenSeconds = sourceSeconds / speed
            // x e y são deslocamentos normalizados; o renderizador os escala por 2.
            val velocityX = (to.x - from.x) * 2f * clip.width / screenSeconds
            val velocityY = (to.y - from.y) * 2f * clip.height / screenSeconds
            if (hypot(velocityX, velocityY) < MIN_SPEED_PX_PER_SECOND) continue
            val travelX = min(abs(velocityX) * exposureSeconds * config.intensity, MAX_TRAVEL_PX) / SHADER_TRAIL_FACTOR
            val travelY = min(abs(velocityY) * exposureSeconds * config.intensity, MAX_TRAVEL_PX) / SHADER_TRAIL_FACTOR
            dxCurve += EffectValueKeyframe(from.sourceUs, if (velocityX < 0f) -travelX else travelX, Easing.LINEAR)
            dyCurve += EffectValueKeyframe(from.sourceUs, if (velocityY < 0f) -travelY else travelY, Easing.LINEAR)
            if (dxCurve.size >= MAX_CURVE_POINTS) break
        }
        if (dxCurve.isEmpty()) return null
        return EffectInstance(
            id = "layer-motion-blur",
            assetId = ASSET_ID,
            intensity = 1f,
            values = mapOf("amount" to 1f, "samples" to 1f),
            keyframes = mapOf("dx" to dxCurve.distinctBy { it.sourceUs }, "dy" to dyCurve.distinctBy { it.sourceUs }),
        )
    }
}
