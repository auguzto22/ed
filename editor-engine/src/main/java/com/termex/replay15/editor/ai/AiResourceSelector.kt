package com.termex.replay15.editor.ai

import com.termex.replay15.editor.domain.TextAnimation
import com.termex.replay15.editor.font.FontCatalog
import com.termex.replay15.editor.resources.ResourceManager
import com.termex.replay15.editor.resources.ResourceType

/**
 * Sugestão estruturada de recursos gerada pela IA do Recly.
 */
data class AiResourceSuggestion(
    val transitionId: String,
    val fontId: String,
    val textAnimation: TextAnimation,
    val effectId: String?,
    val stickerId: String?,
    val confidence: Float,
    val explanation: String,
)

/**
 * Seletor Inteligente de Recursos para o AI Editor do Recly.
 *
 * Mapeia contexto de cena, pacing e mood para recursos reais e verificados do catálogo,
 * garantindo consistência sem alucinação de IDs inexistentes.
 */
object AiResourceSelector {

    private const val FALLBACK_TRANSITION = "recly_fade"
    private const val FALLBACK_FONT = "roboto_regular"

    /**
     * Seleciona o conjunto ótimo de transição, fonte, animação e sticker para uma cena analisada.
     */
    fun selectResourcesForScene(sceneTags: List<String>): AiResourceSuggestion {
        val normalized = sceneTags.map { it.trim().lowercase() }

        return when {
            normalized.any { it in listOf("impact", "impacto", "drop", "beat", "climax", "action") } -> {
                AiResourceSuggestion(
                    transitionId = validateTransition("recly_cross_zoom"),
                    fontId = validateFont("anton_regular"),
                    textAnimation = TextAnimation.POP,
                    effectId = validateEffect("recly_camera_shake"),
                    stickerId = "sticker_fire_flame",
                    confidence = 0.94f,
                    explanation = "Momento de impacto: Zoom rápido com tipografia bold e animação Pop vibrante.",
                )
            }
            normalized.any { it in listOf("cinematic", "cinematografico", "cinema", "drone", "landscape", "slow") } -> {
                AiResourceSuggestion(
                    transitionId = validateTransition("recly_cine_anamorphic_streak"),
                    fontId = validateFont("playfair_display_regular"),
                    textAnimation = TextAnimation.FADE,
                    effectId = validateEffect("recly_film_burn"),
                    stickerId = null,
                    confidence = 0.91f,
                    explanation = "Cena cinematográfica: Flare anamórfico suave com fonte serif elegante.",
                )
            }
            normalized.any { it in listOf("gaming", "gameplay", "kill", "victory", "level", "arcade") } -> {
                AiResourceSuggestion(
                    transitionId = validateTransition("recly_gaming_pixel_dissolve"),
                    fontId = validateFont("space_grotesk_regular"),
                    textAnimation = TextAnimation.BOUNCE,
                    effectId = validateEffect("recly_glitch_block"),
                    stickerId = "sticker_star_burst",
                    confidence = 0.92f,
                    explanation = "Cena gamer: Dissolve 8-bit com elementos pixel e estrela de conquista.",
                )
            }
            normalized.any { it in listOf("social", "tiktok", "reels", "shorts", "viral", "vlog") } -> {
                AiResourceSuggestion(
                    transitionId = validateTransition("recly_social_swipe_up"),
                    fontId = validateFont("poppins_regular"),
                    textAnimation = TextAnimation.POP,
                    effectId = validateEffect("recly_rgb_split"),
                    stickerId = "sticker_like_heart",
                    confidence = 0.95f,
                    explanation = "Conteúdo para redes sociais: Swipe vertical dinâmico com call-to-action de like.",
                )
            }
            normalized.any { it in listOf("celebration", "festa", "parabens", "win", "congrats") } -> {
                AiResourceSuggestion(
                    transitionId = validateTransition("recly_cross_zoom"),
                    fontId = validateFont("montserrat_regular"),
                    textAnimation = TextAnimation.WAVE,
                    effectId = null,
                    stickerId = "sticker_confetti_blast",
                    confidence = 0.89f,
                    explanation = "Celebração: Animação de confetes em Lottie com efeito wave nas legendas.",
                )
            }
            normalized.any { it in listOf("retro", "vintage", "vhs", "80s", "90s", "nostalgia") } -> {
                AiResourceSuggestion(
                    transitionId = validateTransition("recly_film_roll"),
                    fontId = validateFont("space_mono_regular"),
                    textAnimation = TextAnimation.TYPEWRITER,
                    effectId = validateEffect("recly_vhs"),
                    stickerId = null,
                    confidence = 0.90f,
                    explanation = "Estética retrô: Rolo de película analógica com legenda estilo máquina de escrever.",
                )
            }
            else -> {
                AiResourceSuggestion(
                    transitionId = validateTransition("recly_fade"),
                    fontId = validateFont("inter_regular"),
                    textAnimation = TextAnimation.SLIDE_UP,
                    effectId = null,
                    stickerId = null,
                    confidence = 0.85f,
                    explanation = "Corte padrão: Transição suave com tipografia limpa e moderna.",
                )
            }
        }
    }

    private fun validateTransition(id: String): String {
        val resolved = ResourceManager.resolveWithFallback(ResourceType.TRANSITION, id) ?: id
        return if (ResourceManager.transitions.find(resolved) != null) resolved else FALLBACK_TRANSITION
    }

    private fun validateFont(id: String): String {
        val resolved = ResourceManager.resolveWithFallback(ResourceType.FONT, id) ?: id
        return if (FontCatalog.find(resolved) != null) resolved else FALLBACK_FONT
    }

    private fun validateEffect(id: String): String? {
        return ResourceManager.resolveWithFallback(ResourceType.EFFECT, id)
    }
}

