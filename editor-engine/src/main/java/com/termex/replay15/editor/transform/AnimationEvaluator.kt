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

    fun isActive(clip: AnimationClip, localUs: Long) = localUs >= clip.startUs && localUs < clip.endUs

    /** Soma de todas as animações de [targetId] ativas em [localUs]. */
    fun delta(animations: List<AnimationClip>, targetId: String, localUs: Long): AnimationDelta {
        if (animations.isEmpty()) return AnimationDelta.NONE
        var result = AnimationDelta.NONE
        for (clip in animations) {
            if (clip.targetId == targetId && isActive(clip, localUs)) result += delta(clip, localUs)
        }
        return result
    }

    fun delta(clip: AnimationClip, localUs: Long): AnimationDelta {
        if (!isActive(clip, localUs)) return AnimationDelta.NONE
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
