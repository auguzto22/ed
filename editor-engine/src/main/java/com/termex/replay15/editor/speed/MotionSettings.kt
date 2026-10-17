package com.termex.replay15.editor.speed

/**
 * How a slowed clip fills the frames its source does not have.
 *
 * These are three different techniques, not three strengths of one technique:
 *
 * - [ECONOMY] repeats the nearest original frame. Zero cost and zero artifacts, but a 30 fps
 *   clip at 0.25× shows each frame for four output frames, so motion steps.
 * - [BLEND] cross-fades the two neighbouring frames by their distance in time. Steps disappear,
 *   but anything that moves more than its own size between frames shows up twice (ghosting),
 *   because a cross-fade shows both moments at once. Detail is never invented.
 * - [OPTICAL_FLOW] estimates a motion vector for every pixel between the two frames (both
 *   directions), moves both frames to the in-between instant and blends them where they agree.
 *   Where they do not (occlusions, very fast motion, cuts) it falls back pixel by pixel to the
 *   nearest frame, so failures look like the economy mode rather than like smears.
 *
 * [AUTO] picks per device and per situation through [MotionQualityPolicy].
 */
enum class SlowMotionMode(val label: String, val description: String) {
    AUTO("Automático", "Escolhe o melhor método para este aparelho e este clipe."),
    ECONOMY("Econômico", "Repete os quadros originais. Leve, mas o movimento anda em degraus na câmera lenta."),
    BLEND("Mistura de quadros", "Funde os quadros vizinhos. Suaviza os degraus, mas movimentos rápidos podem duplicar."),
    OPTICAL_FLOW("Optical flow", "Calcula o movimento de cada pixel e cria quadros novos. O mais fluido e o mais pesado."),
}

/**
 * Where motion blur gets its motion from.
 *
 * [MOTION_VECTORS] uses the per-pixel optical flow of the video content (true motion blur:
 * only what moves is smeared, along its own direction). [LAYER_ANIMATION] uses the layer's own
 * position keyframes, so the whole layer is smeared uniformly — correct for an animated layer,
 * blind to motion inside the video. [AUTO] prefers vectors and falls back to layer animation
 * when the device cannot estimate flow.
 */
enum class MotionBlurSource(val label: String) {
    AUTO("Automático"),
    MOTION_VECTORS("Movimento do vídeo (vetores)"),
    LAYER_ANIMATION("Animação da camada (keyframes)"),
}

enum class MotionBlurQuality(val label: String, val samples: Int) {
    LOW("Rápida", 6),
    STANDARD("Padrão", 12),
    HIGH("Alta", 24),
}

/**
 * Motion blur of one clip.
 *
 * [shutterAngle] is the photographic control: the fraction of each output frame interval the
 * virtual shutter stays open is `shutterAngle / 360`. 180° is the cinema norm, 90° freezes
 * action, 360° gives the longest smear without gaps. [intensity] scales the result (1 = the
 * physical amount).
 */
data class MotionBlurConfig(
    val enabled: Boolean = false,
    val shutterAngle: Float = 180f,
    val intensity: Float = 1f,
    val quality: MotionBlurQuality = MotionBlurQuality.STANDARD,
    val source: MotionBlurSource = MotionBlurSource.AUTO,
) {
    init {
        require(shutterAngle.isFinite() && shutterAngle in MIN_SHUTTER..MAX_SHUTTER)
        require(intensity.isFinite() && intensity in 0f..2f)
    }

    /** Fraction of the frame interval the shutter is open, or 0 when blur is off. */
    val exposureFraction: Float get() = if (enabled && intensity > 0f) shutterAngle / 360f else 0f

    companion object {
        const val MIN_SHUTTER = 1f
        const val MAX_SHUTTER = 360f
        val SHUTTER_PRESETS = listOf(90f, 180f, 270f, 360f)
    }
}

/** Export-wide trade-off between render time and motion quality. */
enum class MotionExportQuality(val label: String, val description: String) {
    ECONOMY("Econômica", "Mistura de quadros e blur leve. Exporta mais rápido."),
    HIGH("Alta qualidade", "Optical flow em resolução maior e blur com mais amostras."),
}
