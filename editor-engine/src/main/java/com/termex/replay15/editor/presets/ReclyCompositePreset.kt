package com.termex.replay15.editor.presets

import android.graphics.Color
import com.termex.replay15.editor.domain.TextAnimation
import com.termex.replay15.editor.domain.TextClip
import com.termex.replay15.editor.licenses.ResourceCreditsRegistry
import com.termex.replay15.editor.licenses.ResourceLicenseMetadata

/**
 * Composite preset combining Font + Text Animation + Effect + Transition + Color styling + Scale.
 * Allows users and the AI engine to apply a complete aesthetic theme in one tap.
 */
data class ReclyCompositePreset(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val fontId: String,
    val textAnimation: TextAnimation,
    val effectId: String? = null,
    val transitionId: String = "cross_dissolve",
    val textColor: Int = Color.WHITE,
    val outlineColor: Int = Color.BLACK,
    val outlineWidth: Float = 0.025f,
    val scale: Float = 1.0f,
    val tags: List<String> = emptyList(),
) {
    init {
        require(outlineWidth in 0f..0.04f) { "outlineWidth must be in 0f..0.04f" }
    }

    /**
     * Applies this preset to create a fully styled and animated [TextClip].
     */
    fun applyToTextClip(
        text: String,
        startTimeUs: Long,
        durationUs: Long,
        x: Float = 0.5f,
        y: Float = 0.5f,
    ): TextClip {
        return TextClip(
            text = text,
            startUs = startTimeUs,
            endUs = startTimeUs + durationUs,
            fontFamily = fontId,
            color = textColor,
            outlineColor = outlineColor,
            outlineWidth = outlineWidth,
            animation = textAnimation,
            x = x,
            y = y,
            size = 0.08f * scale,
        )
    }

    companion object {
        val CURATED_PRESETS: List<ReclyCompositePreset> = listOf(
            ReclyCompositePreset(
                id = "preset_tiktok",
                name = "Meu Estilo TikTok",
                description = "Tipografia vibrante com animação Pop e aberração cromática RGB Split",
                category = "Social",
                fontId = "poppins",
                textAnimation = TextAnimation.POP,
                effectId = "rgb_split",
                transitionId = "smooth_zoom",
                textColor = Color.parseColor("#FFE600"),
                outlineColor = Color.BLACK,
                outlineWidth = 0.035f,
                scale = 1.15f,
                tags = listOf("social", "tiktok", "reels", "dynamic", "pop"),
            ),
            ReclyCompositePreset(
                id = "preset_cinematic_vlog",
                name = "Cinematic Vlog",
                description = "Serif clássico com fade suave e vazamento de luz / film burn",
                category = "Cinematic",
                fontId = "playfair_display",
                textAnimation = TextAnimation.FADE,
                effectId = "film_burn",
                transitionId = "anamorphic_streak",
                textColor = Color.WHITE,
                outlineColor = Color.parseColor("#33000000"),
                outlineWidth = 0.015f,
                scale = 0.95f,
                tags = listOf("cinematic", "vlog", "documentary", "travel"),
            ),
            ReclyCompositePreset(
                id = "preset_gaming_highlight",
                name = "Gaming Highlight",
                description = "Tipografia ultra pesada com Glitch, vibração e scanlines retrô arcade",
                category = "Gaming",
                fontId = "anton",
                textAnimation = TextAnimation.GLITCH,
                effectId = "rgb_glitch",
                transitionId = "scanline_wipe",
                textColor = Color.parseColor("#00FF66"),
                outlineColor = Color.BLACK,
                outlineWidth = 0.038f,
                scale = 1.2f,
                tags = listOf("gaming", "twitch", "esports", "fast", "glitch"),
            ),
            ReclyCompositePreset(
                id = "preset_minimal_clean",
                name = "Minimal Clean",
                description = "Modern Sans neutro, elegante e legível para tutoriais e negócios",
                category = "Basic",
                fontId = "inter",
                textAnimation = TextAnimation.SLIDE_UP,
                effectId = null,
                transitionId = "fade",
                textColor = Color.WHITE,
                outlineColor = Color.parseColor("#222222"),
                outlineWidth = 0.02f,
                scale = 1.0f,
                tags = listOf("clean", "minimal", "tutorial", "business"),
            ),
            ReclyCompositePreset(
                id = "preset_retro_vhs",
                name = "Retro VHS 80s",
                description = "Estética anos 80 com máquina de escrever e fita analógica",
                category = "Retro",
                fontId = "bebas_neue",
                textAnimation = TextAnimation.TYPEWRITER,
                effectId = "vhs_glitch",
                transitionId = "film_roll",
                textColor = Color.parseColor("#00FFFF"),
                outlineColor = Color.parseColor("#FF0055"),
                outlineWidth = 0.028f,
                scale = 1.05f,
                tags = listOf("retro", "vhs", "80s", "synthwave"),
            ),
        )

        init {
            CURATED_PRESETS.forEach { preset ->
                ResourceCreditsRegistry.register(
                    ResourceLicenseMetadata(
                        id = preset.id,
                        name = preset.name,
                        author = "Recly Creative Labs",
                        source = "Recly Built-in Preset Library",
                        license = "MIT",
                        licenseUrl = "https://opensource.org/licenses/MIT",
                        category = "Preset",
                        tags = preset.tags,
                    )
                )
            }
        }

        fun find(id: String): ReclyCompositePreset? =
            CURATED_PRESETS.firstOrNull { it.id == id }

        fun all(): List<ReclyCompositePreset> = CURATED_PRESETS
    }
}

