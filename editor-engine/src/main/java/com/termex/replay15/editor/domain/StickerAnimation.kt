package com.termex.replay15.editor.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Em que parte do sticker a animação age (as três abas do painel Animações, como no CapCut). */
enum class StickerAnimationKind(val label: String) {
    IN("Entrada"),
    OUT("Saída"),
    LOOP("Loop"),
}

/**
 * Animações de sticker no jeito do CapCut: uma de entrada, uma de saída e uma em loop.
 *
 * O nome do enum é o identificador salvo no projeto (StickerAnimationCodec grava o nome, não a
 * posição), então dá para acrescentar animações novas em qualquer lugar da lista.
 */
enum class StickerAnimationType(val label: String, val kind: StickerAnimationKind?) {
    NONE("Nenhum", null),

    IN_FADE("Aparecer", StickerAnimationKind.IN),
    IN_POP("Pop", StickerAnimationKind.IN),
    IN_ZOOM_IN("Aumentar zoom", StickerAnimationKind.IN),
    IN_ZOOM_OUT("Diminuir zoom", StickerAnimationKind.IN),
    IN_SLIDE_LEFT("Deslizar para a esquerda", StickerAnimationKind.IN),
    IN_SLIDE_RIGHT("Deslizar para a direita", StickerAnimationKind.IN),
    IN_SLIDE_UP("Deslizar para cima", StickerAnimationKind.IN),
    IN_SLIDE_DOWN("Deslizar para baixo", StickerAnimationKind.IN),
    IN_SPIN("Girar", StickerAnimationKind.IN),
    IN_SPIRAL("Espiral", StickerAnimationKind.IN),
    IN_BOUNCE("Quicar", StickerAnimationKind.IN),
    IN_SWING("Balançar", StickerAnimationKind.IN),
    IN_FOLD("Dobrar", StickerAnimationKind.IN),

    OUT_FADE("Desaparecer", StickerAnimationKind.OUT),
    OUT_POP("Pop", StickerAnimationKind.OUT),
    OUT_ZOOM_IN("Aumentar zoom", StickerAnimationKind.OUT),
    OUT_ZOOM_OUT("Diminuir zoom", StickerAnimationKind.OUT),
    OUT_SLIDE_LEFT("Deslizar para a esquerda", StickerAnimationKind.OUT),
    OUT_SLIDE_RIGHT("Deslizar para a direita", StickerAnimationKind.OUT),
    OUT_SLIDE_UP("Deslizar para cima", StickerAnimationKind.OUT),
    OUT_SLIDE_DOWN("Deslizar para baixo", StickerAnimationKind.OUT),
    OUT_SPIN("Girar", StickerAnimationKind.OUT),
    OUT_SPIRAL("Espiral", StickerAnimationKind.OUT),
    OUT_SINK("Afundar", StickerAnimationKind.OUT),
    OUT_FOLD("Dobrar", StickerAnimationKind.OUT),

    LOOP_PULSE("Pulsar", StickerAnimationKind.LOOP),
    LOOP_FLOAT("Flutuar", StickerAnimationKind.LOOP),
    LOOP_SWING("Balançar", StickerAnimationKind.LOOP),
    LOOP_SHAKE("Tremer", StickerAnimationKind.LOOP),
    LOOP_SPIN("Girar", StickerAnimationKind.LOOP),
    LOOP_BLINK("Piscar", StickerAnimationKind.LOOP),
    LOOP_BREATHE("Respirar", StickerAnimationKind.LOOP),
    LOOP_JELLY("Gelatina", StickerAnimationKind.LOOP),
    LOOP_HEARTBEAT("Batimento", StickerAnimationKind.LOOP),
    LOOP_WIGGLE("Mexer", StickerAnimationKind.LOOP),
    ;

    companion object {
        fun of(kind: StickerAnimationKind): List<StickerAnimationType> = entries.filter { it.kind == kind }

        /** Nome salvo -> tipo; nomes desconhecidos (de versões futuras) viram [NONE]. */
        fun fromId(id: String): StickerAnimationType = entries.firstOrNull { it.name == id } ?: NONE
    }
}

const val MIN_STICKER_ANIMATION_US = 100_000L
const val MAX_STICKER_ANIMATION_US = 5_000_000L
const val DEFAULT_STICKER_ANIMATION_US = 500_000L
const val MIN_STICKER_LOOP_US = 200_000L
const val MAX_STICKER_LOOP_US = 5_000_000L
const val DEFAULT_STICKER_LOOP_US = 1_000_000L

/**
 * Animações escolhidas para um sticker. Como no CapCut, entrada e saída podem ser usadas juntas;
 * o loop é exclusivo (escolher um loop tira a entrada e a saída, e vice-versa — ver [with]).
 */
data class StickerAnimations(
    val enter: StickerAnimationType = StickerAnimationType.NONE,
    val enterUs: Long = DEFAULT_STICKER_ANIMATION_US,
    val exit: StickerAnimationType = StickerAnimationType.NONE,
    val exitUs: Long = DEFAULT_STICKER_ANIMATION_US,
    val loop: StickerAnimationType = StickerAnimationType.NONE,
    val loopUs: Long = DEFAULT_STICKER_LOOP_US,
) {
    init {
        require(enter == StickerAnimationType.NONE || enter.kind == StickerAnimationKind.IN)
        require(exit == StickerAnimationType.NONE || exit.kind == StickerAnimationKind.OUT)
        require(loop == StickerAnimationType.NONE || loop.kind == StickerAnimationKind.LOOP)
        require(enterUs in MIN_STICKER_ANIMATION_US..MAX_STICKER_ANIMATION_US)
        require(exitUs in MIN_STICKER_ANIMATION_US..MAX_STICKER_ANIMATION_US)
        require(loopUs in MIN_STICKER_LOOP_US..MAX_STICKER_LOOP_US)
    }

    val isNone: Boolean
        get() = enter == StickerAnimationType.NONE && exit == StickerAnimationType.NONE && loop == StickerAnimationType.NONE

    fun selected(kind: StickerAnimationKind): StickerAnimationType = when (kind) {
        StickerAnimationKind.IN -> enter
        StickerAnimationKind.OUT -> exit
        StickerAnimationKind.LOOP -> loop
    }

    fun durationUs(kind: StickerAnimationKind): Long = when (kind) {
        StickerAnimationKind.IN -> enterUs
        StickerAnimationKind.OUT -> exitUs
        StickerAnimationKind.LOOP -> loopUs
    }

    /** Escolhe [type] na aba dele. Loop limpa entrada/saída; entrada ou saída limpam o loop. */
    fun with(type: StickerAnimationType): StickerAnimations = when (type.kind) {
        StickerAnimationKind.IN -> copy(enter = type, loop = StickerAnimationType.NONE)
        StickerAnimationKind.OUT -> copy(exit = type, loop = StickerAnimationType.NONE)
        StickerAnimationKind.LOOP -> copy(loop = type, enter = StickerAnimationType.NONE, exit = StickerAnimationType.NONE)
        null -> this
    }

    companion object {
        /**
         * Converte a animação única de projetos antigos (campo `animation`) para entrada + saída.
         * A antiga sempre entrava em 0,35 s e saía em 0,25 s com fade.
         */
        fun fromLegacy(current: StickerAnimations, legacy: TextAnimation): StickerAnimations {
            if (!current.isNone || legacy == TextAnimation.NONE) return current
            val enter = when (legacy) {
                TextAnimation.POP -> StickerAnimationType.IN_POP
                TextAnimation.SLIDE_UP -> StickerAnimationType.IN_SLIDE_UP
                TextAnimation.SLIDE_LEFT -> StickerAnimationType.IN_SLIDE_LEFT
                else -> StickerAnimationType.IN_FADE
            }
            return StickerAnimations(enter = enter, enterUs = 350_000L, exit = StickerAnimationType.OUT_FADE, exitUs = 250_000L)
        }
    }

    /** "Nenhum" numa aba. */
    fun cleared(kind: StickerAnimationKind): StickerAnimations = when (kind) {
        StickerAnimationKind.IN -> copy(enter = StickerAnimationType.NONE)
        StickerAnimationKind.OUT -> copy(exit = StickerAnimationType.NONE)
        StickerAnimationKind.LOOP -> copy(loop = StickerAnimationType.NONE)
    }

    fun withDuration(kind: StickerAnimationKind, us: Long): StickerAnimations = when (kind) {
        StickerAnimationKind.IN -> copy(enterUs = us.coerceIn(MIN_STICKER_ANIMATION_US, MAX_STICKER_ANIMATION_US))
        StickerAnimationKind.OUT -> copy(exitUs = us.coerceIn(MIN_STICKER_ANIMATION_US, MAX_STICKER_ANIMATION_US))
        StickerAnimationKind.LOOP -> copy(loopUs = us.coerceIn(MIN_STICKER_LOOP_US, MAX_STICKER_LOOP_US))
    }
}

/**
 * Estado de movimento de um sticker num instante, somado ao transform da camada.
 * Deslocamentos são frações da largura/altura do quadro; rotação em graus.
 */
data class StickerMotionState(
    val alpha: Float = 1f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val rotation: Float = 0f,
) {
    companion object { val IDENTITY = StickerMotionState() }
}

/**
 * Avaliador único das animações de sticker. A prévia (GpuPreviewRenderer) e a exportação
 * (TextCanvasOverlay) usam este mesmo código, então o que se vê é o que sai no vídeo.
 */
object StickerMotion {
    private const val SLIDE = .3f

    /** True quando o sticker precisa ser redesenhado a cada quadro por causa da animação. */
    fun isAnimated(sticker: StickerClip): Boolean =
        !sticker.animations.isNone || sticker.animation != TextAnimation.NONE

    fun evaluate(sticker: StickerClip, timeUs: Long): StickerMotionState =
        evaluate(sticker.animations, sticker.animation, sticker.startUs, sticker.endUs, timeUs)

    fun evaluate(
        animations: StickerAnimations,
        legacy: TextAnimation,
        startUs: Long,
        endUs: Long,
        timeUs: Long,
    ): StickerMotionState {
        val durationUs = (endUs - startUs).coerceAtLeast(1L)
        val localUs = (timeUs - startUs).coerceIn(0L, durationUs)
        if (animations.isNone) return legacy(legacy, localUs, durationUs)

        var alpha = 1f; var sx = 1f; var sy = 1f; var dx = 0f; var dy = 0f; var rot = 0f

        // Entrada e saída dividem o tempo do sticker quando juntas não cabem nele.
        val hasIn = animations.enter != StickerAnimationType.NONE
        val hasOut = animations.exit != StickerAnimationType.NONE
        val wantedIn = if (hasIn) animations.enterUs else 0L
        val wantedOut = if (hasOut) animations.exitUs else 0L
        val fit = if (wantedIn + wantedOut > durationUs) durationUs.toFloat() / (wantedIn + wantedOut) else 1f
        val inUs = (wantedIn * fit).coerceAtLeast(1f)
        val outUs = (wantedOut * fit).coerceAtLeast(1f)

        if (hasIn) {
            val t = (localUs / inUs).coerceIn(0f, 1f)
            if (t < 1f) {
                val e = easeOutCubic(t)
                when (animations.enter) {
                    StickerAnimationType.IN_FADE -> alpha *= e
                    StickerAnimationType.IN_POP -> { val s = .3f + .7f * backOut(t); sx *= s; sy *= s; alpha *= min(1f, t * 3f) }
                    StickerAnimationType.IN_ZOOM_IN -> { val s = .2f + .8f * e; sx *= s; sy *= s; alpha *= e }
                    StickerAnimationType.IN_ZOOM_OUT -> { val s = 1f + 1.2f * (1f - e); sx *= s; sy *= s; alpha *= e }
                    StickerAnimationType.IN_SLIDE_LEFT -> { dx += SLIDE * (1f - e); alpha *= e }
                    StickerAnimationType.IN_SLIDE_RIGHT -> { dx -= SLIDE * (1f - e); alpha *= e }
                    StickerAnimationType.IN_SLIDE_UP -> { dy += SLIDE * (1f - e); alpha *= e }
                    StickerAnimationType.IN_SLIDE_DOWN -> { dy -= SLIDE * (1f - e); alpha *= e }
                    StickerAnimationType.IN_SPIN -> { rot -= 180f * (1f - e); val s = .5f + .5f * e; sx *= s; sy *= s; alpha *= e }
                    StickerAnimationType.IN_SPIRAL -> { rot -= 360f * (1f - e); sx *= e; sy *= e; alpha *= min(1f, t * 2f) }
                    StickerAnimationType.IN_BOUNCE -> { dy -= .25f * (1f - bounceOut(t)); alpha *= min(1f, t * 4f) }
                    StickerAnimationType.IN_SWING -> { rot += 35f * (1f - t) * sin(t * PI.toFloat() * 3f); alpha *= min(1f, t * 3f) }
                    StickerAnimationType.IN_FOLD -> { sx *= e; alpha *= min(1f, t * 2f) }
                    else -> Unit
                }
            }
        }

        if (hasOut) {
            val remaining = (durationUs - localUs).toFloat()
            val q = (1f - remaining / outUs).coerceIn(0f, 1f) // 0 = normal, 1 = saiu
            if (q > 0f) {
                val e = easeInCubic(q)
                when (animations.exit) {
                    StickerAnimationType.OUT_FADE -> alpha *= 1f - e
                    StickerAnimationType.OUT_POP -> { val s = 1f + .25f * sin(q * PI.toFloat()) - .8f * e; sx *= s.coerceAtLeast(.05f); sy *= s.coerceAtLeast(.05f); alpha *= 1f - e }
                    StickerAnimationType.OUT_ZOOM_IN -> { val s = 1f + 1.2f * e; sx *= s; sy *= s; alpha *= 1f - e }
                    StickerAnimationType.OUT_ZOOM_OUT -> { val s = (1f - .8f * e).coerceAtLeast(.05f); sx *= s; sy *= s; alpha *= 1f - e }
                    StickerAnimationType.OUT_SLIDE_LEFT -> { dx -= SLIDE * e; alpha *= 1f - e }
                    StickerAnimationType.OUT_SLIDE_RIGHT -> { dx += SLIDE * e; alpha *= 1f - e }
                    StickerAnimationType.OUT_SLIDE_UP -> { dy -= SLIDE * e; alpha *= 1f - e }
                    StickerAnimationType.OUT_SLIDE_DOWN -> { dy += SLIDE * e; alpha *= 1f - e }
                    StickerAnimationType.OUT_SPIN -> { rot += 180f * e; val s = (1f - .5f * e); sx *= s; sy *= s; alpha *= 1f - e }
                    StickerAnimationType.OUT_SPIRAL -> { rot += 360f * e; val s = (1f - e).coerceAtLeast(.05f); sx *= s; sy *= s; alpha *= 1f - q * q }
                    StickerAnimationType.OUT_SINK -> { dy += .25f * e; val s = 1f - .3f * e; sx *= s; sy *= s; alpha *= 1f - e }
                    StickerAnimationType.OUT_FOLD -> { sx *= (1f - e).coerceAtLeast(.02f); alpha *= 1f - q * q }
                    else -> Unit
                }
            }
        }

        if (animations.loop != StickerAnimationType.NONE) {
            val period = animations.loopUs.toFloat()
            val phase = ((localUs % animations.loopUs) / period)
            val wave = sin(phase * 2f * PI.toFloat())
            when (animations.loop) {
                StickerAnimationType.LOOP_PULSE -> { val s = 1f + .12f * wave; sx *= s; sy *= s }
                StickerAnimationType.LOOP_FLOAT -> dy += .025f * wave
                StickerAnimationType.LOOP_SWING -> rot += 15f * wave
                StickerAnimationType.LOOP_SHAKE -> { dx += .012f * sin(phase * 2f * PI.toFloat() * 6f); rot += 4f * sin(phase * 2f * PI.toFloat() * 4f) }
                StickerAnimationType.LOOP_SPIN -> rot += 360f * phase
                StickerAnimationType.LOOP_BLINK -> alpha *= if (phase < .5f) 1f else .15f + .85f * abs(phase - .75f) * 4f
                StickerAnimationType.LOOP_BREATHE -> { val s = 1f + .06f * wave; sx *= s; sy *= s; alpha *= .85f + .15f * (wave + 1f) / 2f }
                StickerAnimationType.LOOP_JELLY -> { sx *= 1f + .1f * wave; sy *= 1f - .1f * wave }
                StickerAnimationType.LOOP_HEARTBEAT -> { val s = 1f + .15f * heartbeat(phase); sx *= s; sy *= s }
                StickerAnimationType.LOOP_WIGGLE -> { rot += 8f * sin(phase * 2f * PI.toFloat() * 2f); dy += .01f * sin(phase * 2f * PI.toFloat() * 4f) }
                else -> Unit
            }
        }

        return StickerMotionState(alpha.coerceIn(0f, 1f), sx, sy, dx, dy, rot)
    }

    /** O comportamento antigo (campo `animation` de projetos salvos antes das três abas). */
    private fun legacy(animation: TextAnimation, localUs: Long, durationUs: Long): StickerMotionState {
        if (animation == TextAnimation.NONE) return StickerMotionState.IDENTITY
        val progressIn = (localUs / 350_000f).coerceIn(0f, 1f)
        val progressOut = ((durationUs - localUs) / 250_000f).coerceIn(0f, 1f)
        val progress = min(progressIn, progressOut)
        val scale = if (animation == TextAnimation.POP) .72f + .28f * legacyOvershoot(progress) else 1f
        // Os deslocamentos antigos eram em pixels (9% da largura / 7% da altura).
        return StickerMotionState(
            alpha = progress,
            scaleX = scale, scaleY = scale,
            offsetX = if (animation == TextAnimation.SLIDE_LEFT) (1f - progress) * .09f else 0f,
            offsetY = if (animation == TextAnimation.SLIDE_UP) (1f - progress) * .07f else 0f,
        )
    }

    private fun legacyOvershoot(value: Float): Float {
        val shifted = value - 1f
        return shifted * shifted * (2.2f * shifted + 2.2f) + 1f
    }

    private fun easeOutCubic(t: Float): Float = 1f - (1f - t).pow(3)
    private fun easeInCubic(t: Float): Float = t * t * t

    private fun backOut(t: Float): Float {
        val c1 = 1.70158f; val c3 = c1 + 1f
        val u = t - 1f
        return 1f + c3 * u * u * u + c1 * u * u
    }

    private fun bounceOut(t: Float): Float {
        val n = 7.5625f; val d = 2.75f
        return when {
            t < 1f / d -> n * t * t
            t < 2f / d -> { val u = t - 1.5f / d; n * u * u + .75f }
            t < 2.5f / d -> { val u = t - 2.25f / d; n * u * u + .9375f }
            else -> { val u = t - 2.625f / d; n * u * u + .984375f }
        }
    }

    /** Duas batidas curtas e uma pausa, como um coração. */
    private fun heartbeat(phase: Float): Float {
        fun beat(center: Float): Float { val d = (phase - center) / .06f; return kotlin.math.exp(-d * d) }
        return beat(.12f) + .7f * beat(.32f)
    }
}
