package com.termex.replay15.editor.stickers

import com.termex.replay15.editor.domain.StickerClip
import com.termex.replay15.editor.domain.TextAnimation
import com.termex.replay15.editor.licenses.ResourceCreditsRegistry
import com.termex.replay15.editor.licenses.ResourceLicenseMetadata
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Categorias de stickers e elementos gráficos animados.
 */
enum class StickerCategory(val label: String) {
    SHAPES("Formas"),
    ARROWS("Setas"),
    EMOJIS("Emojis"),
    CALLOUTS("Balões"),
    SOCIAL("Social"),
    GAMING("Gaming"),
    SUBSCRIBE("Inscrição"),
    LIKE_SHARE("Curtir & Compartilhar"),
    CELEBRATION("Celebração"),
    BADGES("Selos"),
}

/**
 * Definição imutável de sticker ou elemento animado da biblioteca Recly.
 */
data class StickerDef(
    val id: String,
    val name: String,
    val category: StickerCategory,
    val uri: String,
    val isLottie: Boolean = true,
    val defaultDurationUs: Long = 2_500_000L,
    val defaultSize: Float = 0.25f,
    val author: String = "Recly Open Motion Community",
    val license: String = "MIT",
    val licenseUrl: String = "https://opensource.org/licenses/MIT",
    val sourceUrl: String = "https://github.com/airbnb/lottie-android",
    val tags: List<String> = emptyList(),
)

/**
 * Catálogo central de stickers, formas, setas, emojis e elementos animados.
 */
object StickerCatalog {

    private val definitions = CopyOnWriteArrayList<StickerDef>()
    private val byId = ConcurrentHashMap<String, StickerDef>()
    private val recentIds = CopyOnWriteArrayList<String>()
    private val favoriteIds = ConcurrentHashMap.newKeySet<String>()

    init {
        registerBuiltIns()
    }

    private fun registerBuiltIns() {
        val list = listOf(
            // ── 1. SOCIAL & LIKE/SHARE ──────────────────────────────
            StickerDef(
                id = "sticker_social_share",
                name = "Compartilhar",
                category = StickerCategory.SOCIAL,
                uri = "asset:///editor/lottie/like_heart.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.28f,
                tags = listOf("social", "share", "compartilhar", "viral"),
            ),
            StickerDef(
                id = "sticker_like_heart",
                name = "Coração Pop",
                category = StickerCategory.LIKE_SHARE,
                uri = "asset:///editor/lottie/like_heart.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.28f,
                tags = listOf("like", "heart", "social", "pop", "curtir", "amor"),
            ),
            StickerDef(
                id = "sticker_subscribe_bell",
                name = "Sino de Notificação",
                category = StickerCategory.SUBSCRIBE,
                uri = "asset:///editor/lottie/subscribe_bell.json",
                isLottie = true,
                defaultDurationUs = 2_500_000L,
                defaultSize = 0.26f,
                tags = listOf("subscribe", "bell", "inscreva-se", "sino", "notificacao", "alerta"),
            ),

            // ── 2. CELEBRATION ──────────────────────────────────────
            StickerDef(
                id = "sticker_confetti_blast",
                name = "Explosão de Confete",
                category = StickerCategory.CELEBRATION,
                uri = "asset:///editor/lottie/confetti_blast.json",
                isLottie = true,
                defaultDurationUs = 2_200_000L,
                defaultSize = 0.35f,
                tags = listOf("confetti", "party", "festa", "parabens", "celebracao", "explosao"),
            ),

            // ── 3. GAMING ───────────────────────────────────────────
            StickerDef(
                id = "sticker_star_burst",
                name = "Estrela Brilhante",
                category = StickerCategory.GAMING,
                uri = "asset:///editor/lottie/star_burst.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.25f,
                tags = listOf("star", "estrela", "game", "gaming", "vitoria", "pontos", "brilho"),
            ),
            StickerDef(
                id = "sticker_fire_flame",
                name = "Chama / Fogo",
                category = StickerCategory.GAMING,
                uri = "asset:///editor/lottie/fire_flame.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.28f,
                tags = listOf("fire", "fogo", "flame", "hype", "quente", "viral", "game"),
            ),

            // ── 4. ARROWS & POINTERS ─────────────────────────────────
            StickerDef(
                id = "sticker_arrow_bounce",
                name = "Seta Indicadora",
                category = StickerCategory.ARROWS,
                uri = "asset:///editor/lottie/arrow_bounce.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.22f,
                tags = listOf("arrow", "seta", "apontar", "clique", "indicador", "baixo"),
            ),

            // ── 5. ELEMENTOS RECLY (vetoriais, gerados por gen/lottie_gen.py) ──────
            StickerDef(
                id = "sticker_el_circle_pop",
                name = "Círculo Pop",
                category = StickerCategory.SHAPES,
                uri = "asset:///editor/lottie/circle_pop.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.24f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("circle", "circulo", "pop"),
            ),
            StickerDef(
                id = "sticker_el_ring_burst",
                name = "Anel Explosivo",
                category = StickerCategory.SHAPES,
                uri = "asset:///editor/lottie/ring_burst.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.24f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("anel", "burst", "explosivo", "ring"),
            ),
            StickerDef(
                id = "sticker_el_square_spin",
                name = "Quadrado Girando",
                category = StickerCategory.SHAPES,
                uri = "asset:///editor/lottie/square_spin.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.24f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("girando", "quadrado", "spin", "square"),
            ),
            StickerDef(
                id = "sticker_el_triangle_bounce",
                name = "Triângulo Saltando",
                category = StickerCategory.SHAPES,
                uri = "asset:///editor/lottie/triangle_bounce.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.24f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("bounce", "saltando", "triangle", "triangulo"),
            ),
            StickerDef(
                id = "sticker_el_heart_beat",
                name = "Coração Batendo",
                category = StickerCategory.SHAPES,
                uri = "asset:///editor/lottie/heart_beat.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.24f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("batendo", "beat", "coracao", "heart"),
            ),
            StickerDef(
                id = "sticker_el_sparkle",
                name = "Brilho",
                category = StickerCategory.SHAPES,
                uri = "asset:///editor/lottie/sparkle.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.24f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("brilho", "sparkle"),
            ),
            StickerDef(
                id = "sticker_el_underline_draw",
                name = "Sublinhado",
                category = StickerCategory.SHAPES,
                uri = "asset:///editor/lottie/underline_draw.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.24f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("draw", "sublinhado", "underline"),
            ),
            StickerDef(
                id = "sticker_el_circle_draw",
                name = "Círculo Destaque",
                category = StickerCategory.SHAPES,
                uri = "asset:///editor/lottie/circle_draw.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.24f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("circle", "circulo", "destaque", "draw"),
            ),
            StickerDef(
                id = "sticker_el_check_draw",
                name = "Check Animado",
                category = StickerCategory.SHAPES,
                uri = "asset:///editor/lottie/check_draw.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.24f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("animado", "check", "draw"),
            ),
            StickerDef(
                id = "sticker_el_cross_x",
                name = "X Vermelho",
                category = StickerCategory.SHAPES,
                uri = "asset:///editor/lottie/cross_x.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.24f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("cross", "vermelho"),
            ),
            StickerDef(
                id = "sticker_el_dots_loading",
                name = "Três Pontos",
                category = StickerCategory.SHAPES,
                uri = "asset:///editor/lottie/dots_loading.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.24f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("dots", "loading", "pontos", "tres"),
            ),
            StickerDef(
                id = "sticker_el_arrow_left",
                name = "Seta Esquerda",
                category = StickerCategory.ARROWS,
                uri = "asset:///editor/lottie/arrow_left.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.22f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("arrow", "esquerda", "left", "seta"),
            ),
            StickerDef(
                id = "sticker_el_arrow_right",
                name = "Seta Direita",
                category = StickerCategory.ARROWS,
                uri = "asset:///editor/lottie/arrow_right.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.22f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("arrow", "direita", "right", "seta"),
            ),
            StickerDef(
                id = "sticker_el_arrow_up",
                name = "Seta para Cima",
                category = StickerCategory.ARROWS,
                uri = "asset:///editor/lottie/arrow_up.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.22f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("arrow", "cima", "para", "seta", "up"),
            ),
            StickerDef(
                id = "sticker_el_arrow_curved",
                name = "Seta Curva",
                category = StickerCategory.ARROWS,
                uri = "asset:///editor/lottie/arrow_curved.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.22f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("arrow", "curva", "curved", "seta"),
            ),
            StickerDef(
                id = "sticker_el_chevrons",
                name = "Chevrons",
                category = StickerCategory.ARROWS,
                uri = "asset:///editor/lottie/chevrons.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.22f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("chevrons"),
            ),
            StickerDef(
                id = "sticker_el_speech_bubble",
                name = "Balão de Fala",
                category = StickerCategory.CALLOUTS,
                uri = "asset:///editor/lottie/speech_bubble.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.3f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("balao", "bubble", "de", "fala", "speech"),
            ),
            StickerDef(
                id = "sticker_el_thought_bubble",
                name = "Balão de Pensamento",
                category = StickerCategory.CALLOUTS,
                uri = "asset:///editor/lottie/thought_bubble.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.3f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("balao", "bubble", "de", "pensamento", "thought"),
            ),
            StickerDef(
                id = "sticker_el_alert_badge",
                name = "Alerta",
                category = StickerCategory.CALLOUTS,
                uri = "asset:///editor/lottie/alert_badge.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.3f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("alert", "alerta", "badge"),
            ),
            StickerDef(
                id = "sticker_el_star_rating",
                name = "Cinco Estrelas",
                category = StickerCategory.BADGES,
                uri = "asset:///editor/lottie/star_rating.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.26f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("cinco", "estrelas", "rating", "star"),
            ),
            StickerDef(
                id = "sticker_el_new_burst",
                name = "Selo Novo",
                category = StickerCategory.BADGES,
                uri = "asset:///editor/lottie/new_burst.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.26f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("burst", "new", "novo", "selo"),
            ),
            StickerDef(
                id = "sticker_el_verified",
                name = "Verificado",
                category = StickerCategory.BADGES,
                uri = "asset:///editor/lottie/verified.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.26f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("verificado", "verified"),
            ),
            StickerDef(
                id = "sticker_el_price_tag",
                name = "Etiqueta",
                category = StickerCategory.BADGES,
                uri = "asset:///editor/lottie/price_tag.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.26f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("etiqueta", "price", "tag"),
            ),
            StickerDef(
                id = "sticker_el_fireworks",
                name = "Fogos",
                category = StickerCategory.CELEBRATION,
                uri = "asset:///editor/lottie/fireworks.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.34f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("fireworks", "fogos"),
            ),
            StickerDef(
                id = "sticker_el_sparkles_cluster",
                name = "Brilhos",
                category = StickerCategory.CELEBRATION,
                uri = "asset:///editor/lottie/sparkles_cluster.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.34f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("brilhos", "cluster", "sparkles"),
            ),
            StickerDef(
                id = "sticker_el_balloon",
                name = "Balão",
                category = StickerCategory.CELEBRATION,
                uri = "asset:///editor/lottie/balloon.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.34f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("balao", "balloon"),
            ),
            StickerDef(
                id = "sticker_el_face_smile",
                name = "Sorriso",
                category = StickerCategory.EMOJIS,
                uri = "asset:///editor/lottie/face_smile.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.24f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("face", "smile", "sorriso"),
            ),
            StickerDef(
                id = "sticker_el_face_wow",
                name = "Uau",
                category = StickerCategory.EMOJIS,
                uri = "asset:///editor/lottie/face_wow.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.24f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("face", "uau", "wow"),
            ),
            StickerDef(
                id = "sticker_el_face_wink",
                name = "Piscadinha",
                category = StickerCategory.EMOJIS,
                uri = "asset:///editor/lottie/face_wink.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.24f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("face", "piscadinha", "wink"),
            ),
            StickerDef(
                id = "sticker_el_tap_here",
                name = "Toque Aqui",
                category = StickerCategory.SOCIAL,
                uri = "asset:///editor/lottie/tap_here.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.24f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("aqui", "here", "tap", "toque"),
            ),
            StickerDef(
                id = "sticker_el_play_button",
                name = "Botão Play",
                category = StickerCategory.SOCIAL,
                uri = "asset:///editor/lottie/play_button.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.24f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("botao", "button", "play"),
            ),
            StickerDef(
                id = "sticker_el_comment_bubble",
                name = "Comentário",
                category = StickerCategory.SOCIAL,
                uri = "asset:///editor/lottie/comment_bubble.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.24f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("bubble", "comentario", "comment"),
            ),
            StickerDef(
                id = "sticker_el_lightning",
                name = "Raio",
                category = StickerCategory.GAMING,
                uri = "asset:///editor/lottie/lightning.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.26f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("lightning", "raio"),
            ),
            StickerDef(
                id = "sticker_el_coin_spin",
                name = "Moeda Girando",
                category = StickerCategory.GAMING,
                uri = "asset:///editor/lottie/coin_spin.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.26f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("coin", "girando", "moeda", "spin"),
            ),
            StickerDef(
                id = "sticker_el_explosion",
                name = "Explosão",
                category = StickerCategory.GAMING,
                uri = "asset:///editor/lottie/explosion.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.26f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("explosao", "explosion"),
            ),
            StickerDef(
                id = "sticker_el_level_up",
                name = "Subiu de Nível",
                category = StickerCategory.GAMING,
                uri = "asset:///editor/lottie/level_up.json",
                isLottie = true,
                defaultDurationUs = 2_000_000L,
                defaultSize = 0.26f,
                author = "Recly",
                license = "Recly original",
                licenseUrl = "",
                sourceUrl = "",
                tags = listOf("de", "level", "nivel", "subiu", "up"),
            ),
        )

        definitions.clear()
        byId.clear()
        list.forEach { def ->
            definitions.add(def)
            byId[def.id] = def
            ResourceCreditsRegistry.register(
                ResourceLicenseMetadata(
                    id = def.id,
                    name = def.name,
                    author = def.author,
                    source = "Recly Open Motion Elements",
                    license = def.license,
                    licenseUrl = def.licenseUrl,
                    sourceUrl = def.sourceUrl,
                    category = "Sticker - ${def.category.label}",
                    tags = def.tags,
                )
            )
        }
    }

    fun all(): List<StickerDef> = definitions.toList()

    fun find(id: String): StickerDef? = byId[id]

    fun byCategory(category: StickerCategory): List<StickerDef> =
        definitions.filter { it.category == category }

    fun search(query: String): List<StickerDef> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all()
        return definitions.filter {
            it.name.lowercase().contains(q) ||
                it.category.label.lowercase().contains(q) ||
                it.tags.any { tag -> tag.lowercase().contains(q) }
        }
    }

    fun favorites(): List<StickerDef> =
        favoriteIds.mapNotNull { byId[it] }

    fun setFavorite(id: String, favorite: Boolean) {
        if (favorite) favoriteIds.add(id) else favoriteIds.remove(id)
    }

    fun isFavorite(id: String): Boolean = favoriteIds.contains(id)

    fun recents(): List<StickerDef> =
        recentIds.mapNotNull { byId[it] }

    fun recordRecent(id: String) {
        recentIds.remove(id)
        recentIds.add(0, id)
        while (recentIds.size > 20) recentIds.removeAt(recentIds.size - 1)
    }

    /**
     * Instancia um [StickerClip] pronto para ser inserido na timeline do Recly.
     */
    fun createClip(
        stickerId: String,
        startUs: Long,
        x: Float = 0.5f,
        y: Float = 0.5f,
        durationUs: Long? = null,
    ): StickerClip {
        val def = find(stickerId)
        val clipDuration = durationUs ?: def?.defaultDurationUs ?: 2_500_000L
        val uri = def?.uri ?: "asset:///editor/lottie/like_heart.json"
        val name = def?.name ?: "Sticker"
        val size = def?.defaultSize ?: 0.25f

        recordRecent(stickerId)
        return StickerClip(
            uri = uri,
            name = name,
            startUs = startUs,
            endUs = startUs + clipDuration,
            x = x,
            y = y,
            size = size,
            animation = TextAnimation.NONE,
        )
    }
}
