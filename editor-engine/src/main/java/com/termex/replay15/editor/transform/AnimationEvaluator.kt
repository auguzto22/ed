package com.termex.replay15.editor.transform

import com.termex.replay15.editor.domain.AnimationClip
import com.termex.replay15.editor.domain.MotionPreset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Contribuição de um ou mais [AnimationClip] num instante: o zoom MULTIPLICA o que os keyframes
 * manuais já dizem e os deslocamentos/rotação SOMAM. Neutro = [NONE].
 */
data class AnimationDelta(
    val zoom: Float = 1f, val dx: Float = 0f, val dy: Float = 0f, val rotation: Float = 0f,
) {
    val isNeutral: Boolean get() = zoom == 1f && dx == 0f && dy == 0f && rotation == 0f

    operator fun plus(other: AnimationDelta) =
        AnimationDelta(zoom * other.zoom, dx + other.dx, dy + other.dy, rotation + other.rotation)

    companion object { val NONE = AnimationDelta() }
}

/**
 * Matemática única dos presets, usada igual pelo preview e pelo export. O tempo é sempre LOCAL ao
 * alvo (zero no início do clipe/texto/sticker), o mesmo referencial em que [AnimationClip] guarda
 * a janela.
 */
object AnimationEvaluator {
    /** Intensidade 1 leva um Ken Burns a 1.22x, no meio da faixa 1.18–1.25 pedida. */
    const val KEN_BURNS_ZOOM = .22f
    private const val KEN_BURNS_PAN = .02f
    private const val ZOOM_AMOUNT = .2f
    private const val PAN_AMOUNT = .04f
    private const val PUNCH_AMOUNT = .15f

    /**
     * Rampa de entrada/saída nas bordas do balão que caem DENTRO do alvo. Sem ela, um zoom in que
     * termina antes do fim do clipe "pulava" de volta ao neutro num quadro. Na borda que coincide
     * com o começo/fim do alvo não há rampa: ali já existe um corte, então não há salto visível.
     */
    const val EDGE_US = 250_000L

    fun isActive(clip: AnimationClip, localUs: Long) = localUs >= clip.startUs && localUs < clip.endUs

    /**
     * Soma de todas as animações de [targetId] ativas em [localUs]. [targetDurationUs] é a duração
     * do alvo na timeline (decide se a borda final ganha rampa); 0 = desconhecida, sem rampa no fim.
     */
    fun delta(animations: List<AnimationClip>, targetId: String, localUs: Long, targetDurationUs: Long = 0L): AnimationDelta {
        if (animations.isEmpty()) return AnimationDelta.NONE
        var result = AnimationDelta.NONE
        for (clip in animations) {
            if (clip.targetId == targetId && isActive(clip, localUs)) result += delta(clip, localUs, targetDurationUs)
        }
        return result
    }

    fun delta(clip: AnimationClip, localUs: Long, targetDurationUs: Long = 0L): AnimationDelta {
        if (!isActive(clip, localUs)) return AnimationDelta.NONE
        val raw = rawDelta(clip, localUs)
        val weight = edgeWeight(clip, localUs, targetDurationUs)
        return if (weight >= 1f) raw else raw.scaled(weight)
    }

    /** 0 na borda interna, 1 a partir de [EDGE_US] (ou 1/4 do balão, se ele for curto). */
    fun edgeWeight(clip: AnimationClip, localUs: Long, targetDurationUs: Long = 0L): Float {
        val edge = minOf(EDGE_US, clip.durationUs / 4).coerceAtLeast(1L)
        val rampIn = if (clip.startUs > 0L) (localUs - clip.startUs).toFloat() / edge else 1f
        val rampOut = if (clip.endUs < targetDurationUs) (clip.endUs - localUs).toFloat() / edge else 1f
        val t = minOf(rampIn, rampOut).coerceIn(0f, 1f)
        return if (t >= 1f) 1f else com.termex.replay15.editor.domain.Easing.SMOOTH.apply(t)
    }

    private fun AnimationDelta.scaled(weight: Float) = AnimationDelta(
        zoom = 1f + (zoom - 1f) * weight, dx = dx * weight, dy = dy * weight, rotation = rotation * weight)

    private fun rawDelta(clip: AnimationClip, localUs: Long): AnimationDelta {
        val progress = ((localUs - clip.startUs).toDouble() / clip.durationUs).toFloat().coerceIn(0f, 1f)
        val k = clip.intensity
        val e = clip.easing.apply(progress)
        return when (clip.preset) {
            MotionPreset.ZOOM_IN -> AnimationDelta(zoom = 1f + ZOOM_AMOUNT * k * e)
            MotionPreset.ZOOM_OUT -> AnimationDelta(zoom = 1f + ZOOM_AMOUNT * k * (1f - e))
            MotionPreset.PAN_LEFT -> AnimationDelta(dx = PAN_AMOUNT * k * (1f - 2f * e))
            MotionPreset.PAN_RIGHT -> AnimationDelta(dx = -PAN_AMOUNT * k * (1f - 2f * e))
            MotionPreset.PUNCH_IN -> {
                val q = clip.easing.apply((progress / .3f).coerceAtMost(1f))
                AnimationDelta(zoom = 1f + PUNCH_AMOUNT * k * q)
            }
            MotionPreset.SHAKE -> {
                // Envelope em sino: começa e termina parado, então não "salta" nas bordas do balão.
                val envelope = sin(PI * progress).toFloat()
                val seconds = (localUs - clip.startUs) / 1_000_000.0
                AnimationDelta(
                    dx = (.01f * k * envelope * sin(2 * PI * 9 * seconds)).toFloat(),
                    dy = (.008f * k * envelope * sin(2 * PI * 13 * seconds + 1.3)).toFloat(),
                    rotation = (1.2f * k * envelope * sin(2 * PI * 7 * seconds + .7)).toFloat(),
                )
            }
            MotionPreset.KEN_BURNS -> kenBurns(k, e, 1, out = false)
            MotionPreset.KEN_BURNS_LEFT -> kenBurns(k, e, -1, out = false)
            MotionPreset.KEN_BURNS_OUT -> kenBurns(k, e, 1, out = true)
            MotionPreset.KEN_BURNS_OUT_LEFT -> kenBurns(k, e, -1, out = true)
        }
    }

    private fun kenBurns(k: Float, e: Float, direction: Int, out: Boolean): AnimationDelta {
        val amount = if (out) 1f - e else e
        return AnimationDelta(zoom = 1f + KEN_BURNS_ZOOM * k * amount, dx = direction * KEN_BURNS_PAN * k * amount)
    }

    /** Aplica sobre o keyframe de vídeo (zoom multiplica; x/y/rotação somam), respeitando seus limites. */
    fun apply(base: com.termex.replay15.editor.domain.TransformKeyframe, d: AnimationDelta):
        com.termex.replay15.editor.domain.TransformKeyframe {
        if (d.isNeutral) return base
        return base.copy(
            zoom = (base.zoom * d.zoom).coerceIn(.25f, 4f),
            x = (base.x + d.dx).coerceIn(-.5f, .5f),
            y = (base.y + d.dy).coerceIn(-.5f, .5f),
            rotation = wrapDegrees(base.rotation + d.rotation),
        )
    }

    /** Aplica sobre o estado de texto/sticker (escala multiplica; posição/rotação somam). */
    fun apply(base: TransformState, d: AnimationDelta): TransformState {
        if (d.isNeutral) return base
        return base.copy(
            x = base.x + d.dx, y = base.y + d.dy,
            scaleX = (base.scaleX * d.zoom).coerceIn(.05f, 20f),
            scaleY = (base.scaleY * d.zoom).coerceIn(.05f, 20f),
            rotation = wrapDegrees(base.rotation + d.rotation),
        )
    }

    private fun wrapDegrees(value: Float): Float =
        if (abs(value) <= 180f) value else ((value + 180f) % 360f + 360f) % 360f - 180f
}
