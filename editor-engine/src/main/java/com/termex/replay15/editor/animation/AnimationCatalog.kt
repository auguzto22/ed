package com.termex.replay15.editor.animation

import android.content.Context
import android.content.SharedPreferences
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.licenses.ResourceCreditsRegistry
import com.termex.replay15.editor.licenses.ResourceLicenseMetadata
import java.util.concurrent.ConcurrentHashMap

/**
 * Reusable animation definition adhering to Recly's universal animation architecture.
 * Supports application to: Video clips, PIP layers, Images, Stickers, Text captions, and UI elements.
 */
data class AnimationDefinition(
    val id: String,
    val name: String,
    val category: AnimationCategory,
    val durationUs: Long = 500_000L,
    val preview: String = "animated",
    val parameters: Map<String, Float> = emptyMap(),
    val source: String = "Recly Open Motion (MIT)",
    val license: String = "MIT",
    val points: List<AnimationPoint> = emptyList(),
    val textAnimation: TextAnimation? = null,
    val description: String = "",
) {
    init {
        require(id.matches(Regex("[a-z0-9_]{1,80}"))) { "Invalid animation id: $id" }
        require(name.isNotBlank())
        require(durationUs in 50_000L..10_000_000L)
    }

    /**
     * Convert this definition to an [AnimationPreset] for compilation into timeline keyframes.
     */
    fun toPreset(): AnimationPreset = AnimationPreset(
        id = id,
        version = 1,
        name = name,
        category = category,
        points = if (points.isNotEmpty()) points else defaultPointsFor(category),
        license = license,
    )

    companion object {
        private fun defaultPointsFor(cat: AnimationCategory): List<AnimationPoint> = when (cat) {
            AnimationCategory.OUT, AnimationCategory.EXIT -> listOf(
                AnimationPoint(0f, 1f, 0f, 0f, 0f, 1f),
                AnimationPoint(1f, 0.7f, 0f, 0f, 0f, 0f, Easing.SMOOTH)
            )
            AnimationCategory.LOOP -> listOf(
                AnimationPoint(0f, 1f, 0f, 0f, 0f, 1f),
                AnimationPoint(0.5f, 1.05f, 0f, -0.02f, 0f, 1f, Easing.SMOOTH),
                AnimationPoint(1f, 1f, 0f, 0f, 0f, 1f, Easing.SMOOTH)
            )
            else -> listOf(
                AnimationPoint(0f, 0.7f, 0f, 0.1f, 0f, 0f, Easing.SPRING),
                AnimationPoint(1f, 1f, 0f, 0f, 0f, 1f)
            )
        }
    }
}

/**
 * Central catalog of all reusable motion and text animations in Recly.
 */
object AnimationCatalog {
    private val definitions = ArrayList<AnimationDefinition>()
    private val byId = ConcurrentHashMap<String, AnimationDefinition>()

    init {
        registerBuiltIns()
    }

    private fun registerBuiltIns() {
        val list = listOf(
            // ── 1. ENTRANCE ──────────────────────────────────────────
            AnimationDefinition(
                id = "anim_pop_in", name = "Pop In", category = AnimationCategory.ENTRANCE,
                durationUs = 400_000L, textAnimation = TextAnimation.POP,
                points = listOf(
                    AnimationPoint(0f, 0.3f, 0f, 0f, 0f, 0f, Easing.SPRING),
                    AnimationPoint(1f, 1f, 0f, 0f, 0f, 1f)
                ),
                description = "Entrada rápida com leve efeito elástico no final."
            ),
            AnimationDefinition(
                id = "anim_bounce_in", name = "Bounce In", category = AnimationCategory.ENTRANCE,
                durationUs = 600_000L, textAnimation = TextAnimation.BOUNCE,
                points = listOf(
                    AnimationPoint(0f, 1f, 0f, 0.35f, 0f, 0f, Easing.BOUNCE),
                    AnimationPoint(1f, 1f, 0f, 0f, 0f, 1f)
                ),
                description = "Quique vertical dinâmico."
            ),
            AnimationDefinition(
                id = "anim_fade_in", name = "Fade In", category = AnimationCategory.ENTRANCE,
                durationUs = 500_000L, textAnimation = TextAnimation.FADE,
                points = listOf(
                    AnimationPoint(0f, 1f, 0f, 0f, 0f, 0f, Easing.SMOOTH),
                    AnimationPoint(1f, 1f, 0f, 0f, 0f, 1f)
                ),
                description = "Transição suave de opacidade."
            ),
            AnimationDefinition(
                id = "anim_slide_up", name = "Slide Up", category = AnimationCategory.ENTRANCE,
                durationUs = 450_000L, textAnimation = TextAnimation.SLIDE_UP,
                points = listOf(
                    AnimationPoint(0f, 1f, 0f, 0.25f, 0f, 0f, Easing.EASE_OUT),
                    AnimationPoint(1f, 1f, 0f, 0f, 0f, 1f)
                ),
                description = "Deslize ascendente com desaceleração."
            ),
            AnimationDefinition(
                id = "anim_zoom_in", name = "Zoom In", category = AnimationCategory.ENTRANCE,
                durationUs = 500_000L, textAnimation = TextAnimation.ZOOM,
                points = listOf(
                    AnimationPoint(0f, 0.2f, 0f, 0f, 0f, 0f, Easing.EASE_OUT),
                    AnimationPoint(1f, 1f, 0f, 0f, 0f, 1f)
                ),
                description = "Aproximação rápida a partir do centro."
            ),
            AnimationDefinition(
                id = "anim_elastic_in", name = "Elastic In", category = AnimationCategory.ENTRANCE,
                durationUs = 650_000L, textAnimation = TextAnimation.ELASTIC,
                points = listOf(
                    AnimationPoint(0f, 0.1f, 0f, 0f, 0f, 0f, Easing.SPRING),
                    AnimationPoint(0.7f, 1.15f, 0f, 0f, 0f, 1f, Easing.SMOOTH),
                    AnimationPoint(1f, 1f, 0f, 0f, 0f, 1f)
                ),
                description = "Entrada elástica com recuo suave."
            ),

            // ── 2. EXIT ──────────────────────────────────────────────
            AnimationDefinition(
                id = "anim_fade_out", name = "Fade Out", category = AnimationCategory.EXIT,
                durationUs = 400_000L, textAnimation = TextAnimation.FADE_OUT,
                points = listOf(
                    AnimationPoint(0f, 1f, 0f, 0f, 0f, 1f, Easing.SMOOTH),
                    AnimationPoint(1f, 1f, 0f, 0f, 0f, 0f)
                ),
                description = "Desaparecimento gradual."
            ),
            AnimationDefinition(
                id = "anim_slide_out", name = "Slide Out", category = AnimationCategory.EXIT,
                durationUs = 400_000L, textAnimation = TextAnimation.SLIDE_DOWN,
                points = listOf(
                    AnimationPoint(0f, 1f, 0f, 0f, 0f, 1f, Easing.EASE_IN),
                    AnimationPoint(1f, 1f, 0f, 0.3f, 0f, 0f)
                ),
                description = "Deslize de saída para baixo."
            ),
            AnimationDefinition(
                id = "anim_zoom_away", name = "Zoom Away", category = AnimationCategory.EXIT,
                durationUs = 450_000L, textAnimation = TextAnimation.ZOOM_OUT,
                points = listOf(
                    AnimationPoint(0f, 1f, 0f, 0f, 0f, 1f, Easing.EASE_IN),
                    AnimationPoint(1f, 0.1f, 0f, 0f, 0f, 0f)
                ),
                description = "Afastamento rápido em direção ao fundo."
            ),

            // ── 3. EMPHASIS ──────────────────────────────────────────
            AnimationDefinition(
                id = "anim_pulse", name = "Pulse", category = AnimationCategory.EMPHASIS,
                durationUs = 500_000L, textAnimation = TextAnimation.PULSE,
                points = listOf(
                    AnimationPoint(0f, 1f, 0f, 0f, 0f, 1f, Easing.SMOOTH),
                    AnimationPoint(0.5f, 1.12f, 0f, 0f, 0f, 1f, Easing.SMOOTH),
                    AnimationPoint(1f, 1f, 0f, 0f, 0f, 1f)
                ),
                description = "Pulso de destaque único."
            ),
            AnimationDefinition(
                id = "anim_heartbeat", name = "Heartbeat", category = AnimationCategory.EMPHASIS,
                durationUs = 600_000L,
                points = listOf(
                    AnimationPoint(0f, 1f, 0f, 0f, 0f, 1f, Easing.SMOOTH),
                    AnimationPoint(0.25f, 1.15f, 0f, 0f, 0f, 1f, Easing.SMOOTH),
                    AnimationPoint(0.45f, 1f, 0f, 0f, 0f, 1f, Easing.SMOOTH),
                    AnimationPoint(0.7f, 1.1f, 0f, 0f, 0f, 1f, Easing.SMOOTH),
                    AnimationPoint(1f, 1f, 0f, 0f, 0f, 1f)
                ),
                description = "Batimento duplo de impacto."
            ),
            AnimationDefinition(
                id = "anim_wiggle", name = "Wiggle", category = AnimationCategory.EMPHASIS,
                durationUs = 500_000L,
                points = listOf(
                    AnimationPoint(0f, 1f, 0f, 0f, 0f, 1f, Easing.SMOOTH),
                    AnimationPoint(0.25f, 1f, 0f, 0f, -8f, 1f, Easing.SMOOTH),
                    AnimationPoint(0.75f, 1f, 0f, 0f, 8f, 1f, Easing.SMOOTH),
                    AnimationPoint(1f, 1f, 0f, 0f, 0f, 1f)
                ),
                description = "Gingado angular para chamar atenção."
            ),

            // ── 4. LOOP ──────────────────────────────────────────────
            AnimationDefinition(
                id = "anim_float", name = "Float Loop", category = AnimationCategory.LOOP,
                durationUs = 1_500_000L, textAnimation = TextAnimation.FLOAT,
                points = listOf(
                    AnimationPoint(0f, 1f, 0f, 0f, 0f, 1f, Easing.SMOOTH),
                    AnimationPoint(0.5f, 1f, 0f, -0.025f, 0f, 1f, Easing.SMOOTH),
                    AnimationPoint(1f, 1f, 0f, 0f, 0f, 1f)
                ),
                description = "Flutuação vertical contínua."
            ),
            AnimationDefinition(
                id = "anim_breathe", name = "Breathe Loop", category = AnimationCategory.LOOP,
                durationUs = 2_000_000L,
                points = listOf(
                    AnimationPoint(0f, 1f, 0f, 0f, 0f, 1f, Easing.SMOOTH),
                    AnimationPoint(0.5f, 1.04f, 0f, 0f, 0f, 1f, Easing.SMOOTH),
                    AnimationPoint(1f, 1f, 0f, 0f, 0f, 1f)
                ),
                description = "Respiração suave de escala contínua."
            ),
            AnimationDefinition(
                id = "anim_shake", name = "Shake Loop", category = AnimationCategory.LOOP,
                durationUs = 400_000L, textAnimation = TextAnimation.SHAKE,
                points = listOf(
                    AnimationPoint(0f, 1f, 0f, 0f, 0f, 1f, Easing.LINEAR),
                    AnimationPoint(0.25f, 1f, -0.015f, 0f, -2f, 1f, Easing.LINEAR),
                    AnimationPoint(0.75f, 1f, 0.015f, 0f, 2f, 1f, Easing.LINEAR),
                    AnimationPoint(1f, 1f, 0f, 0f, 0f, 1f)
                ),
                description = "Tremor rítmico de alta frequência."
            ),

            // ── 5. TEXT ──────────────────────────────────────────────
            AnimationDefinition(
                id = "anim_typewriter", name = "Typewriter", category = AnimationCategory.TEXT,
                durationUs = 1_200_000L, textAnimation = TextAnimation.TYPEWRITER,
                description = "Texto revelado caractere a caractere no tempo certo."
            ),
            AnimationDefinition(
                id = "anim_word_pop", name = "Word Pop", category = AnimationCategory.TEXT,
                durationUs = 400_000L, textAnimation = TextAnimation.WORD_POP,
                description = "Destaque e animação palavra por palavra sincronizado."
            ),
            AnimationDefinition(
                id = "anim_text_wave", name = "Text Wave", category = AnimationCategory.TEXT,
                durationUs = 600_000L, textAnimation = TextAnimation.WAVE,
                description = "Ondulação fluida das letras."
            ),
            AnimationDefinition(
                id = "anim_text_glitch", name = "Text Glitch", category = AnimationCategory.TEXT,
                durationUs = 450_000L, textAnimation = TextAnimation.GLITCH,
                description = "Interferência digital e deslocamento horizontal."
            ),
            AnimationDefinition(
                id = "anim_text_tracking", name = "Text Tracking", category = AnimationCategory.TEXT,
                durationUs = 600_000L, textAnimation = TextAnimation.TRACKING,
                description = "Expansão de espaçamento entre caracteres."
            ),

            // ── 6. SOCIAL ────────────────────────────────────────────
            AnimationDefinition(
                id = "anim_like_burst", name = "Like Burst", category = AnimationCategory.SOCIAL,
                durationUs = 550_000L,
                points = listOf(
                    AnimationPoint(0f, 0.2f, 0f, 0f, 0f, 0f, Easing.SPRING),
                    AnimationPoint(0.6f, 1.25f, 0f, 0f, 0f, 1f, Easing.SMOOTH),
                    AnimationPoint(1f, 1f, 0f, 0f, 0f, 1f)
                ),
                description = "Animação de coração/like estourando na tela."
            ),
            AnimationDefinition(
                id = "anim_follow_slide", name = "Follow Slide", category = AnimationCategory.SOCIAL,
                durationUs = 500_000L,
                points = listOf(
                    AnimationPoint(0f, 1f, 0.4f, 0f, 0f, 0f, Easing.EASE_OUT),
                    AnimationPoint(1f, 1f, 0f, 0f, 0f, 1f)
                ),
                description = "Botão de inscrição/seguir entrando pela lateral."
            ),

            // ── 7. GAMING ────────────────────────────────────────────
            AnimationDefinition(
                id = "anim_pixel_spawn", name = "Pixel Spawn", category = AnimationCategory.GAMING,
                durationUs = 400_000L,
                points = listOf(
                    AnimationPoint(0f, 0.1f, 0f, 0f, 0f, 0f, Easing.HOLD),
                    AnimationPoint(0.33f, 0.5f, 0f, 0f, 0f, 0.8f, Easing.HOLD),
                    AnimationPoint(0.66f, 0.8f, 0f, 0f, 0f, 0.9f, Easing.HOLD),
                    AnimationPoint(1f, 1f, 0f, 0f, 0f, 1f)
                ),
                description = "Aparição em passos escalonados no estilo 8-bit."
            ),
            AnimationDefinition(
                id = "anim_level_up", name = "Level Up Jump", category = AnimationCategory.GAMING,
                durationUs = 600_000L,
                points = listOf(
                    AnimationPoint(0f, 0.8f, 0f, 0.1f, 0f, 0f, Easing.EASE_OUT),
                    AnimationPoint(0.5f, 1.2f, 0f, -0.08f, 0f, 1f, Easing.EASE_IN),
                    AnimationPoint(1f, 1f, 0f, 0f, 0f, 1f)
                ),
                description = "Salto triunfal estilo arcade."
            ),

            // ── 8. CELEBRATION ───────────────────────────────────────
            AnimationDefinition(
                id = "anim_confetti_pop", name = "Confetti Pop", category = AnimationCategory.CELEBRATION,
                durationUs = 600_000L,
                points = listOf(
                    AnimationPoint(0f, 0.1f, 0f, 0f, -20f, 0f, Easing.SPRING),
                    AnimationPoint(0.7f, 1.15f, 0f, 0f, 5f, 1f, Easing.SMOOTH),
                    AnimationPoint(1f, 1f, 0f, 0f, 0f, 1f)
                ),
                description = "Explosão festiva com rotação e overshoot."
            ),

            // ── 9. MOTION & KINETIC ──────────────────────────────────
            AnimationDefinition(
                id = "anim_whip_pan_left", name = "Whip Pan Left", category = AnimationCategory.MOTION,
                durationUs = 400_000L,
                points = listOf(
                    AnimationPoint(0f, 1.1f, 0.6f, 0f, 0f, 0.5f, Easing.EASE_OUT),
                    AnimationPoint(1f, 1f, 0f, 0f, 0f, 1f)
                ),
                description = "Movimento cinético rápido com zoom dinâmico."
            ),
            AnimationDefinition(
                id = "anim_kinetic_spin", name = "Kinetic Spin", category = AnimationCategory.MOTION,
                durationUs = 500_000L,
                points = listOf(
                    AnimationPoint(0f, 0.4f, 0f, 0f, -180f, 0f, Easing.EASE_OUT),
                    AnimationPoint(1f, 1f, 0f, 0f, 0f, 1f)
                ),
                description = "Giro de 180 graus com rápida expansão."
            )
        )

        definitions.clear()
        definitions.addAll(list)
        byId.clear()
        list.forEach { anim ->
            byId[anim.id] = anim
            ResourceCreditsRegistry.register(
                ResourceLicenseMetadata(
                    id = anim.id,
                    name = anim.name,
                    author = "Recly Motion Design Team",
                    source = anim.source,
                    license = anim.license,
                    category = "Animation",
                    tags = listOf(anim.category.name, anim.category.label),
                    notes = anim.description,
                )
            )
        }
    }

    fun all(): List<AnimationDefinition> = definitions.toList()

    fun find(id: String): AnimationDefinition? = byId[id]

    fun byCategory(category: AnimationCategory): List<AnimationDefinition> =
        definitions.filter { it.category == category }

    fun search(query: String): List<AnimationDefinition> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all()
        return definitions.filter {
            it.name.lowercase().contains(q) ||
                it.category.name.lowercase().contains(q) ||
                it.category.label.lowercase().contains(q) ||
                it.description.lowercase().contains(q)
        }
    }

    // ── Application Helpers ─────────────────────────────────────────────────────

    /** Apply animation to VideoClip using keyframes. */
    fun applyTo(clip: VideoClip, animId: String, durationUs: Long): VideoClip {
        val def = find(animId) ?: return clip
        return def.toPreset().applyTo(clip, durationUs)
    }

    /** Apply animation to TextClip. */
    fun applyTo(text: TextClip, animId: String): TextClip {
        val def = find(animId) ?: return text
        val anim = def.textAnimation ?: TextAnimation.POP
        return when (def.category) {
            AnimationCategory.EXIT, AnimationCategory.OUT -> text.copy(exitAnimation = anim)
            AnimationCategory.LOOP, AnimationCategory.EMPHASIS -> text.copy(duringAnimation = anim)
            else -> text.copy(enterAnimation = anim, animation = anim)
        }
    }

    /** Apply animation to StickerClip. */
    fun applyTo(sticker: StickerClip, animId: String): StickerClip {
        val def = find(animId) ?: return sticker
        val anim = def.textAnimation ?: TextAnimation.POP
        return sticker.copy(animation = anim)
    }

    // ── Favorites & Recents ─────────────────────────────────────────────────────

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("recly_animations_prefs", Context.MODE_PRIVATE)

    fun favorites(context: Context): Set<String> =
        prefs(context).getStringSet("favorites", emptySet()) ?: emptySet()

    fun toggleFavorite(context: Context, id: String): Boolean {
        val current = favorites(context).toMutableSet()
        val isFav = if (id in current) { current.remove(id); false } else { current.add(id); true }
        prefs(context).edit().putStringSet("favorites", current).apply()
        return isFav
    }

    fun recents(context: Context): List<String> =
        prefs(context).getString("recents", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

    fun markRecent(context: Context, id: String) {
        val list = recents(context).toMutableList()
        list.remove(id)
        list.add(0, id)
        prefs(context).edit().putString("recents", list.take(20).joinToString(",")).apply()
    }
}

