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
