package com.termex.replay15.editor.presets

import com.termex.replay15.editor.audio.AudioEnhance
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.licenses.ResourceCreditsRegistry
import com.termex.replay15.editor.licenses.ResourceLicenseMetadata
import java.util.UUID

/** Tipo de preset — determina quais campos são preenchidos. */
enum class PresetType {
    /** Preset completo de projeto: texto, cor, audio e transicoes juntos. */
    FULL_PROJECT,
    /** Apenas estilo de texto. */
    TEXT_STYLE,
    /** Apenas gradacao de cor (filtro + ajustes manuais). */
    COLOR_GRADE,
    /** Apenas processamento de audio. */
    AUDIO_STYLE,
    /** Estilo de sticker/overlay. */
    STICKER_STYLE,
}

/** Grupo de presets para organizacao na UI. */
enum class PresetGroup(val label: String) {
    BUILT_IN("Recly"),
    CINEMATIC("Cinematografico"),
    SOCIAL("Social / Reels"),
    VINTAGE("Vintage"),
    MINIMAL("Minimalista"),
    GAMING("Gaming"),
    CUSTOM("Meus Presets"),
}

data class PresetColorGrade(
    val filterIndex: Int = 0,
    val filterStrength: Float = 1f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val hue: Float = 0f,
    val lightness: Float = 0f,
    val temperature: Float = 0f,
    val grade: StudioGrade = StudioGrade(),
) {
    companion object {
        val NONE = PresetColorGrade()
    }
}

data class PresetTextStyle(
    val fontId: String = TextFont.MODERNA.id,
    val fontFamily: String = "",
    val fontWeight: Int = 400,
    val color: Int = 0xFFFFFFFF.toInt(),
    val size: Float = .06f,
    val bold: Boolean = true,
    val italic: Boolean = false,
    val alignment: TextAlignment = TextAlignment.CENTER,
    val backgroundColor: Int = 0,
    val outlineColor: Int = 0xFF000000.toInt(),
    val outlineWidth: Float = 0f,
    val shadow: Boolean = false,
    val letterSpacing: Float = 0f,
    val lineSpacing: Float = 1f,
    val animation: TextAnimation = TextAnimation.NONE,
    val enterAnimation: TextAnimation = TextAnimation.NONE,
    val duringAnimation: TextAnimation = TextAnimation.NONE,
    val exitAnimation: TextAnimation = TextAnimation.NONE,
) {
    companion object {
        val DEFAULT = PresetTextStyle()
    }
}

data class PresetAudioStyle(
    val noiseReduction: Float = 0f,
    val voiceEnhance: Float = 0f,
    val compression: Float = 0f,
    val normalize: Float = 0f,
    val fadeInUs: Long = 0L,
    val fadeOutUs: Long = 0L,
    val volume: Float = 1f,
) {
    fun toAudioEnhance() = AudioEnhance(
        noiseReduction = noiseReduction.coerceIn(0f, 1f),
        voiceEnhance = voiceEnhance.coerceIn(0f, 1f),
        compression = compression.coerceIn(0f, 1f),
        normalize = normalize.coerceIn(0f, 1f),
    )

    companion object {
        val DEFAULT = PresetAudioStyle()
        fun fromAudioEnhance(ae: AudioEnhance) = PresetAudioStyle(
            noiseReduction = ae.noiseReduction,
            voiceEnhance = ae.voiceEnhance,
            compression = ae.compression,
            normalize = ae.normalize,
        )
    }
}

data class PresetStickerStyle(
    val animation: TextAnimation = TextAnimation.NONE,
    val opacity: Float = 1f,
    val defaultSize: Float = .25f,
) {
    companion object {
        val DEFAULT = PresetStickerStyle()
    }
}

data class PresetTransitionStyle(
    val transitionId: String? = null,
    val durationUs: Long = 400_000L,
) {
    companion object {
        val NONE = PresetTransitionStyle()
    }
}

/** Estilo de zoom inteligente. */
data class PresetSmartZoom(
    val enabled: Boolean = false,
    val intensity: Float = 0.3f,
    val minScale: Float = 1.0f,
    val maxScale: Float = 1.5f,
    val speechDetection: Boolean = true,
    val motionBoost: Float = 0.2f,
) {
    companion object {
        val DEFAULT = PresetSmartZoom()
    }
}

/**
 * Um preset de edicao salavavel e aplicavel.
 * Suporta aplicacao parcial: apenas os campos nao-nulos sao aplicados ao destino.
 */
data class EditPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val group: PresetGroup = PresetGroup.BUILT_IN,
    val type: PresetType = PresetType.FULL_PROJECT,
    val thumbnail: String = "",
    val version: Int = 1,
    val textStyle: PresetTextStyle? = null,
    val colorGrade: PresetColorGrade? = null,
    val audioStyle: PresetAudioStyle? = null,
    val stickerStyle: PresetStickerStyle? = null,
    val transitionStyle: PresetTransitionStyle? = null,
    val smartZoom: PresetSmartZoom? = null,
    val isBuiltIn: Boolean = false,
    val isReadOnly: Boolean = false,
    val tags: List<String> = emptyList(),
) {
    init {
        require(name.isNotBlank() && name.length <= 80)
        require(description.length <= 300)
        require(version >= 1)
    }

    /** Aplica este preset a um VideoClip. Retorna um novo VideoClip. */
    fun applyTo(clip: VideoClip): VideoClip {
        var result = clip
        colorGrade?.let { grade ->
            result = result.copy(
                filter = grade.filterIndex,
                filterStrength = grade.filterStrength,
                brightness = grade.brightness,
                contrast = grade.contrast,
                saturation = grade.saturation,
                hue = grade.hue,
                lightness = grade.lightness,
                temperature = grade.temperature,
                grade = grade.grade,
            )
        }
        audioStyle?.let { style ->
            result = result.copy(
                enhance = style.toAudioEnhance(),
                audioFadeInUs = style.fadeInUs,
                audioFadeOutUs = style.fadeOutUs,
                volume = style.volume,
            )
        }
        smartZoom?.let { zoom ->
            if (zoom.enabled) {
                result = result.copy(
                    zoom = zoom.intensity.coerceIn(zoom.minScale, zoom.maxScale),
                )
            }
        }
        return result
    }

    /** Aplica este preset a um TextClip. Retorna um novo TextClip. */
    fun applyTo(text: TextClip): TextClip {
        var result = text
        textStyle?.let { style ->
            result = result.copy(
                fontId = style.fontId,
                fontFamily = style.fontFamily,
                fontWeight = style.fontWeight,
                color = style.color,
                size = style.size,
                bold = style.bold,
                italic = style.italic,
                alignment = style.alignment,
                backgroundColor = style.backgroundColor,
                outlineColor = style.outlineColor,
                outlineWidth = style.outlineWidth,
                shadow = style.shadow,
                letterSpacing = style.letterSpacing,
                lineSpacing = style.lineSpacing,
                animation = style.animation,
                enterAnimation = style.enterAnimation,
                duringAnimation = style.duringAnimation,
                exitAnimation = style.exitAnimation,
            )
        }
        return result
    }

    /** Aplica este preset a um StickerClip. Retorna um novo StickerClip. */
    fun applyTo(sticker: StickerClip): StickerClip {
        var result = sticker
        stickerStyle?.let { style ->
            result = result.copy(
                animation = style.animation,
                opacity = style.opacity,
                size = style.defaultSize,
            )
        }
        return result
    }

    /** Aplica este preset a um AudioClip. Retorna um novo AudioClip. */
    fun applyTo(audio: AudioClip): AudioClip {
        var result = audio
        audioStyle?.let { style ->
            result = result.copy(
                enhance = style.toAudioEnhance(),
                fadeInUs = style.fadeInUs,
                fadeOutUs = style.fadeOutUs,
                volume = style.volume,
            )
        }
        return result
    }

    /** Extrai o estilo de um VideoClip como preset. */
    fun extractFrom(clip: VideoClip): EditPreset = copy(
        type = type,
        colorGrade = PresetColorGrade(
            filterIndex = clip.filter,
            filterStrength = clip.filterStrength,
            brightness = clip.brightness,
            contrast = clip.contrast,
            saturation = clip.saturation,
            hue = clip.hue,
            lightness = clip.lightness,
            temperature = clip.temperature,
            grade = clip.grade,
        ),
        audioStyle = PresetAudioStyle.fromAudioEnhance(clip.enhance).copy(
            fadeInUs = clip.audioFadeInUs,
            fadeOutUs = clip.audioFadeOutUs,
            volume = clip.volume,
        ),
    )

    /** Extrai o estilo de um TextClip como preset. */
    fun extractFrom(text: TextClip): EditPreset = copy(
        type = PresetType.TEXT_STYLE,
        textStyle = PresetTextStyle(
            fontId = text.fontId,
            fontFamily = text.fontFamily,
            fontWeight = text.fontWeight,
            color = text.color,
            size = text.size,
            bold = text.bold,
            italic = text.italic,
            alignment = text.alignment,
            backgroundColor = text.backgroundColor,
            outlineColor = text.outlineColor,
            outlineWidth = text.outlineWidth,
            shadow = text.shadow,
            letterSpacing = text.letterSpacing,
            lineSpacing = text.lineSpacing,
            animation = text.animation,
            enterAnimation = text.enterAnimation,
            duringAnimation = text.duringAnimation,
            exitAnimation = text.exitAnimation,
        ),
    )

    /** Extrai o estilo de um StickerClip como preset. */
    fun extractFrom(sticker: StickerClip): EditPreset = copy(
        type = PresetType.STICKER_STYLE,
        stickerStyle = PresetStickerStyle(
            animation = sticker.animation,
            opacity = sticker.opacity,
            defaultSize = sticker.size,
        ),
    )

    /** Extrai o estilo de um AudioClip como preset. */
    fun extractFrom(audio: AudioClip): EditPreset = copy(
        type = PresetType.AUDIO_STYLE,
        audioStyle = PresetAudioStyle.fromAudioEnhance(audio.enhance).copy(
            fadeInUs = audio.fadeInUs,
            fadeOutUs = audio.fadeOutUs,
            volume = audio.volume,
        ),
    )
}
