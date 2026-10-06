package com.termex.replay15.editor.audio

/**
 * The audio treatments the editor offers on a clip.
 *
 * Every value is a normalized control, not a physical unit, so the UI can drive all of them
 * from one slider and a disabled setting costs nothing at playback time.
 */
data class AudioEnhance(
    /** Suppresses steady background hiss; 0 leaves the noise untouched. */
    val noiseReduction: Float = 0f,
    /** Presence lift plus a high-pass, for dialogue that sounds dull or boomy. */
    val voiceEnhance: Float = 0f,
    /** Evens out level between quiet and loud parts of the take. */
    val compression: Float = 0f,
    /** Pulls the result up to a consistent peak. */
    val normalize: Float = 0f,
) {
    init {
        require(noiseReduction in 0f..1f) { "noiseReduction fora de 0..1: $noiseReduction" }
        require(voiceEnhance in 0f..1f) { "voiceEnhance fora de 0..1: $voiceEnhance" }
        require(compression in 0f..1f) { "compression fora de 0..1: $compression" }
        require(normalize in 0f..1f) { "normalize fora de 0..1: $normalize" }
    }

    val isNeutral: Boolean get() = noiseReduction == 0f && voiceEnhance == 0f &&
        compression == 0f && normalize == 0f

    companion object {
        val NEUTRAL = AudioEnhance()
    }
}

/** True when a clip carries no processing, so the chain can be skipped entirely. */
fun AudioEnhance.requiresProcessing(): Boolean = !isNeutral
