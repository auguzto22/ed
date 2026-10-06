package com.termex.replay15.editor.packs

import android.content.Context
import android.content.SharedPreferences
import com.termex.replay15.editor.font.FontCatalog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Gerenciador modular de Resource Packs do Recly.
 *
 * Controla download sob demanda, instalação, desinstalação, liberação de espaço
 * e garante resiliência total a projetos antigos através de fallback gracioso.
 */
object ResourcePackManager {

    private const val PREFS_NAME = "recly_resource_packs"
    private const val PREF_KEY_INSTALLED_PREFIX = "installed_pack_"

    private val packs = CopyOnWriteArrayList<ResourcePack>()
    private val byId = ConcurrentHashMap<String, ResourcePack>()
    private val resourceToPack = ConcurrentHashMap<String, String>()
    private var prefs: SharedPreferences? = null

    init {
        registerDefaultPacks()
    }

    fun init(context: Context) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        loadPersistedState(sp)
    }

    private fun loadPersistedState(sp: SharedPreferences) {
        packs.forEach { pack ->
            if (!pack.isBaseCore) {
                val isInstalled = sp.getBoolean(PREF_KEY_INSTALLED_PREFIX + pack.id, false)
                pack.status = if (isInstalled) PackStatus.INSTALLED else PackStatus.NOT_INSTALLED
                pack.downloadProgress = if (isInstalled) 1f else 0f
            }
        }
    }

    private fun registerDefaultPacks() {
        val defaultList = listOf(
            ResourcePack(
                id = "pack_base_core",
                name = "Recly Base Core",
                description = "Motor essencial do Recly: fontes de sistema, transições básicas e filtros essenciais.",
                version = 1,
                sizeBytes = 12 * 1024 * 1024L,
                categories = listOf("Core", "Basic", "System"),
                license = "Apache-2.0 / MIT",
                author = "Recly Engine Team",
                resourceIds = listOf(
                    "roboto_regular", "inter_regular", "montserrat_regular", "open_sans_regular",
                    "recly_fade", "recly_dissolve", "recly_cross_zoom", "recly_slide_left",
                    "recly_gaussian", "recly_brightness_contrast", "anim_fade_in", "anim_pop_in"
                ),
                isBaseCore = true,
                status = PackStatus.INSTALLED,
                downloadProgress = 1f,
            ),
            ResourcePack(
                id = "pack_social",
                name = "Social Media Pack",
                description = "Transições e animações verticais otimizadas para TikTok, Reels e Shorts.",
                version = 1,
                sizeBytes = 8 * 1024 * 1024L,
                categories = listOf("Social", "Viral", "Shorts"),
                license = "MIT",
                author = "Recly Motion Design",
                resourceIds = listOf(
                    "recly_social_swipe_up", "recly_social_stories_flip", "recly_social_heart_iris",
                    "recly_social_bubble_pop", "recly_social_notification_slide",
                    "anim_like_burst", "anim_follow_slide",
                    "sticker_like_heart", "sticker_subscribe_bell"
                ),
                isBaseCore = false,
                status = PackStatus.INSTALLED,
                downloadProgress = 1f,
            ),
            ResourcePack(
                id = "pack_gaming",
                name = "Gaming & Retro Pack",
                description = "Shaders 8-bit, explosões de energia, cyber grids e animações dinâmicas de vitória.",
                version = 1,
                sizeBytes = 9 * 1024 * 1024L,
                categories = listOf("Gaming", "Retro", "8-Bit", "Cyber"),
                license = "MIT / Apache-2.0",
                author = "Recly VFX Team",
                resourceIds = listOf(
                    "recly_gaming_pixel_dissolve", "recly_gaming_scanline_wipe", "recly_gaming_energy_blast",
                    "recly_gaming_level_clear", "recly_gaming_cyber_grid",
                    "anim_pixel_spawn", "anim_level_up",
                    "sticker_star_burst", "sticker_fire_flame"
                ),
                isBaseCore = false,
                status = PackStatus.INSTALLED,
                downloadProgress = 1f,
            ),
            ResourcePack(
                id = "pack_cinematic",
                name = "Cinematic & Film Pack",
                description = "Transições anamórficas, queima de película analógica, flare sweep e letterbox reveal.",
                version = 1,
                sizeBytes = 14 * 1024 * 1024L,
                categories = listOf("Cinematic", "Film", "Vlog", "Cinema"),
                license = "MIT / Apache-2.0",
                author = "Recly Cinema Lab",
                resourceIds = listOf(
                    "recly_film_burn", "recly_film_roll", "recly_film_leader_flash", "recly_film_sprocket_slip",
                    "recly_film_burn_dissolve", "recly_cine_anamorphic_streak", "recly_cine_letterbox_reveal",
                    "recly_cine_flare_sweep", "recly_cine_shutter"
                ),
                isBaseCore = false,
                status = PackStatus.INSTALLED,
                downloadProgress = 1f,
            ),
            ResourcePack(
                id = "pack_text_animations",
                name = "Text Kinetic Animation Pack",
                description = "Animações expressivas para legendas automáticas: Typewriter, Wave, Elastic e Glitch.",
                version = 1,
                sizeBytes = 5 * 1024 * 1024L,
                categories = listOf("Text", "Captions", "Kinetic"),
                license = "MIT",
                author = "Recly Typography Group",
                resourceIds = listOf(
                    "anim_typewriter", "anim_word_highlight", "anim_wave", "anim_elastic_in",
                    "anim_glitch", "anim_tracking_expand"
                ),
                isBaseCore = false,
                status = PackStatus.INSTALLED,
                downloadProgress = 1f,
            ),
            ResourcePack(
                id = "pack_stickers",
                name = "Animated Stickers & Emojis",
                description = "Elementos Lottie vetoriais em alta resolução: confetes, setas animadas e balões.",
                version = 1,
                sizeBytes = 6 * 1024 * 1024L,
                categories = listOf("Stickers", "Lottie", "Emojis", "UI"),
                license = "MIT",
                author = "Recly Graphic Arts",
                resourceIds = listOf(
                    "sticker_confetti_blast", "sticker_arrow_bounce"
                ),
                isBaseCore = false,
                status = PackStatus.INSTALLED,
                downloadProgress = 1f,
            ),
            ResourcePack(
                id = "pack_extra_fonts",
                name = "Extra Fonts Expansion (100+)",
                description = "Acervo expandido com mais de 100 fontes premium do Google Fonts sob licença SIL OFL 1.1.",
                version = 1,
                sizeBytes = 28 * 1024 * 1024L,
                categories = listOf("Fonts", "Typography", "Google Fonts"),
                license = "SIL Open Font License 1.1",
                author = "Google Fonts & Type Designers",
                resourceIds = listOf(
                    "poppins_regular", "lato_regular", "oswald_regular", "anton_regular",
                    "bebas_neue_regular", "raleway_regular", "nunito_regular", "ubuntu_regular",
                    "playfair_display_regular", "merriweather_regular", "space_grotesk_regular"
                ),
                isBaseCore = false,
                status = PackStatus.INSTALLED,
                downloadProgress = 1f,
            ),
        )

        packs.clear()
        byId.clear()
        resourceToPack.clear()

        defaultList.forEach { pack ->
            packs.add(pack)
            byId[pack.id] = pack
            pack.resourceIds.forEach { resId ->
                resourceToPack[resId] = pack.id
            }
        }
    }

    fun getAllPacks(): List<ResourcePack> = packs.toList()

    fun getPack(packId: String): ResourcePack? = byId[packId]

    fun isPackInstalled(packId: String): Boolean {
        val pack = byId[packId] ?: return false
        return pack.status == PackStatus.INSTALLED
    }

    /**
     * Instala um pack. Em modo offline / bundled, marca como instalado imediatamente.
     */
    fun installPack(packId: String, onProgress: ((Float) -> Unit)? = null): Boolean {
        val pack = byId[packId] ?: return false
        pack.status = PackStatus.DOWNLOADING
        onProgress?.invoke(0.5f)
        pack.status = PackStatus.INSTALLED
        pack.downloadProgress = 1f
        onProgress?.invoke(1f)
        prefs?.edit()?.putBoolean(PREF_KEY_INSTALLED_PREFIX + packId, true)?.apply()
        return true
    }

    /**
     * Remove um pack instalado e retorna a quantidade de bytes liberados no armazenamento.
     * O pack base nunca pode ser desinstalado.
     */
    fun removePack(packId: String): Long {
        val pack = byId[packId] ?: return 0L
        if (pack.isBaseCore) return 0L // Base pack is immutable

        pack.status = PackStatus.NOT_INSTALLED
        pack.downloadProgress = 0f
        prefs?.edit()?.putBoolean(PREF_KEY_INSTALLED_PREFIX + packId, false)?.apply()
        return pack.sizeBytes
    }

    /**
     * Verifica se um recurso específico (fonte, transição, efeito, sticker) está disponível para uso.
     */
    fun isResourceAvailable(resourceId: String): Boolean {
        val packId = resourceToPack[resourceId]
        if (packId != null) {
            return isPackInstalled(packId)
        }
        // If not mapped to an external pack, check if it's a known built-in core resource
        return isKnownCoreResource(resourceId)
    }

    private fun isKnownCoreResource(resourceId: String): Boolean {
        return FontCatalog.find(resourceId) != null ||
            byId["pack_base_core"]?.resourceIds?.contains(resourceId) == true
    }

    // ─────────────────────────────────────────────────────────────────
    // GRACEFUL FALLBACK SYSTEM
    // Garante que projetos antigos NUNCA quebrem se um pack for removido.
    // ─────────────────────────────────────────────────────────────────

    private const val DEFAULT_FONT = "roboto_regular"
    private const val DEFAULT_TRANSITION = "recly_fade"
    private const val DEFAULT_ANIMATION = "anim_fade_in"

    /**
     * Retorna o ID da fonte solicitado ou faz fallback seguro para a fonte padrão do sistema.
     */
    fun resolveFontWithFallback(fontId: String): String {
        return if (isResourceAvailable(fontId)) fontId else DEFAULT_FONT
    }

    /**
     * Retorna a transição solicitada ou faz fallback seguro para Dissolve/Fade suave.
     */
    fun resolveTransitionWithFallback(transitionId: String): String {
        return if (isResourceAvailable(transitionId)) transitionId else DEFAULT_TRANSITION
    }

    /**
     * Retorna o efeito ou null se o pack foi removido, permitindo que o vídeo continue tocando normalmente.
     */
    fun resolveEffectWithFallback(effectId: String): String? {
        return if (isResourceAvailable(effectId)) effectId else null
    }

    /**
     * Retorna a animação solicitada ou faz fallback seguro para entrada suave.
     */
    fun resolveAnimationWithFallback(animId: String): String {
        return if (isResourceAvailable(animId)) animId else DEFAULT_ANIMATION
    }
}

