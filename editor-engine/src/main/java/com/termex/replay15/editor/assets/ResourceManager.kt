package com.termex.replay15.editor.assets

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import com.termex.replay15.editor.design.DesignTokens
import com.termex.replay15.editor.domain.TextFont
import com.termex.replay15.editor.licenses.ResourceCreditsRegistry
import com.termex.replay15.editor.licenses.ResourceLicenseMetadata
import com.termex.replay15.editor.font.FontCatalog
import com.termex.replay15.editor.animation.AnimationCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified resource orchestration system for Recly.
 *
 * Manages all resource types in one place:
 * - Fonts (system + downloadable)
 * - Transitions (built-in + pack)
 * - Effects (built-in + pack)
 * - Animations (built-in + Lottie + pack)
 * - Stickers / overlays
 * - Presets
 * - Packs (bundled + downloadable)
 *
 * Acts as a single source of truth for:
 * - Catalog queries (search, filter, favorites, recents)
 * - Pack installation/removal
 * - Missing resource fallback
 * - Attribution registration
 *
 * Architecture:
 *
 * ResourceManager ──► FontCatalog
 *                 ──► TransitionCatalog
 *                 ──► EffectCatalog
 *                 ──► AnimationCatalog
 *                 ──► StickerCatalog (future)
 *                 ──► PresetManager (future)
 *                 ──► PackManager
 *                 ──► CacheManager
 */
object ResourceManager {

    private const val PREFS_NAME = "recly_resource_manager"
    private const val KEY_INSTALLED_PACKS = "installed_packs"
    private const val KEY_ACTIVE_PACKS = "active_packs"
    private const val KEY_DISABLED_RESOURCES = "disabled_resources"

    private lateinit var prefs: SharedPreferences

    // ── Initialization ───────────────────────────────────────────────────────

    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        registerBuiltinAttributions(context)
    }

    private fun registerBuiltinAttributions(context: Context) {
        // Transitions (from BuiltInTransitions → already registered via BuiltInTransitions.init)
        TransitionCatalog.all(context).forEach { trans ->
            ResourceCreditsRegistry.register(
                ResourceLicenseMetadata(
                    id = trans.id,
                    name = trans.name,
                    author = "GL Transitions / Recly Community",
                    source = "GL Transitions (MIT)",
                    license = "MIT",
                    licenseUrl = "https://opensource.org/licenses/MIT",
                    sourceUrl = "https://gl-transitions.com",
                    version = "${trans.version}.0",
                    category = "Transition - ${TransitionCatalog.canonicalCategory(trans.category)}",
                    tags = listOf(trans.category, "transition", "shader"),
                )
            )
        }

        // Effects (from BuiltInEffects → register here)
        EffectCatalog.registerCredits(
            EffectCatalog.filter(
                BuiltInEffects.definitions(context),
                EffectCatalog.CATEGORY_ALL
            )
        )

        // Fonts — register system font attributions
        FontCatalog.builtIns.forEach { font ->
            ResourceCreditsRegistry.register(
                ResourceLicenseMetadata(
                    id = font.id,
                    name = font.displayName,
                    author = "Google Fonts / Android Open Source",
                    source = "Android System Fonts",
                    license = "Apache-2.0",
                    licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
                    sourceUrl = "https://fonts.google.com",
                    version = "1.0",
                    category = "Font - ${font.categories.firstOrNull()?.name ?: "Sans"}",
                    tags = listOf(font.categories.firstOrNull()?.name ?: "Sans", "font", "system"),
                )
            )
        }
    }

    // ── Pack Management ──────────────────────────────────────────────────────

    fun getInstalledPackIds(): Set<String> =
        prefs.getStringSet(KEY_INSTALLED_PACKS, emptySet()) ?: emptySet()

    fun getActivePackIds(): Set<String> =
        prefs.getStringSet(KEY_ACTIVE_PACKS, emptySet()) ?: emptySet()

    fun installPack(packId: String) {
        val installed = getInstalledPackIds().toMutableSet()
        val active = getActivePackIds().toMutableSet()
        installed.add(packId)
        active.add(packId)
        prefs.edit()
            .putStringSet(KEY_INSTALLED_PACKS, installed)
            .putStringSet(KEY_ACTIVE_PACKS, active)
            .apply()
    }

    fun uninstallPack(packId: String) {
        val installed = getInstalledPackIds().toMutableSet()
        val active = getActivePackIds().toMutableSet()
        installed.remove(packId)
        active.remove(packId)
        prefs.edit()
            .putStringSet(KEY_INSTALLED_PACKS, installed)
            .putStringSet(KEY_ACTIVE_PACKS, active)
            .apply()
    }

    fun setPackActive(packId: String, active: Boolean) {
        val current = getActivePackIds().toMutableSet()
        if (active) current.add(packId) else current.remove(packId)
        prefs.edit().putStringSet(KEY_ACTIVE_PACKS, current).apply()
    }

    fun isPackInstalled(packId: String): Boolean =
        packId in getInstalledPackIds()

    fun isPackActive(packId: String): Boolean =
        packId in getActivePackIds()

    // ── Resource Availability ────────────────────────────────────────────────

    /**
     * Returns whether a specific resource ID is currently available
     * (belongs to an installed and active pack, or is built-in).
     */
    fun isResourceAvailable(resourceId: String): Boolean {
        // Built-in resources are always available
        if (isBuiltinResource(resourceId)) return true
        // Pack resources require the pack to be installed
        return isInActivePack(resourceId)
    }

    private fun isBuiltinResource(resourceId: String): Boolean {
        return BuiltInTransitions.findById(resourceId) != null ||
                BuiltInEffects.definitions(android.app.Application()).any { it.id == resourceId } ||
                FontCatalog.builtIns.any { it.id == resourceId }
    }

    private fun isInActivePack(resourceId: String): Boolean {
        val activePacks = getActivePackIds()
        if (activePacks.isEmpty()) return false
        return PackRegistry.getPackResources(resourceId) in activePacks
    }

    /**
     * Returns a fallback resource when the requested one is unavailable.
     * For transitions: returns the first built-in transition.
     * For effects: returns the first built-in effect.
     * For fonts: returns system default.
     */
    fun getFallbackResource(type: ResourceType, context: Context): Any? = when (type) {
        ResourceType.TRANSITION -> TransitionCatalog.all(context).firstOrNull()
        ResourceType.EFFECT -> BuiltInEffects.definitions(context).firstOrNull()
        ResourceType.FONT -> FontCatalog.builtIns.firstOrNull()
        ResourceType.ANIMATION -> AnimationCatalog.all().firstOrNull()
        ResourceType.STICKER -> null
        ResourceType.PRESET -> null
        ResourceType.TEXT_STYLE -> null
        ResourceType.EFFECT_CHAIN -> null
    }

    /**
     * Describes why a resource is unavailable — used for user-facing messages.
     */
    fun getUnavailabilityReason(resourceId: String, type: ResourceType): String {
        val packId = PackRegistry.getPackResources(resourceId)
        if (packId != null && !isPackInstalled(packId)) {
            val pack = PackRegistry.getPack(packId)
            return "Este recurso faz parte do ${pack?.name ?: "pack"} que não está instalado."
        }
        if (packId != null && !isPackActive(packId)) {
            return "Este recurso pertence a um pack desativado."
        }
        return "Recurso não disponível."
    }

    // ── Unified Search ────────────────────────────────────────────────────────

    /**
     * Searches across all resource types.
     * Returns grouped results with resource type labels.
     */
    suspend fun searchAll(context: Context, query: String): Map<ResourceType, List<ResourceSummary>> =
        withContext(Dispatchers.IO) {
            val q = query.trim().lowercase()
            if (q.isBlank()) return@withContext emptyMap()

            val results = mutableMapOf<ResourceType, MutableList<ResourceSummary>>()

            // Transitions
            TransitionCatalog.search(context, q).take(10).forEach {
                if (!results.containsKey(ResourceType.TRANSITION)) results[ResourceType.TRANSITION] = mutableListOf()
                results[ResourceType.TRANSITION]!!.add(ResourceSummary(it.id, it.name, it.category, ResourceType.TRANSITION))
            }

            // Effects
            EffectCatalog.filter(
                BuiltInEffects.definitions(context),
                EffectCatalog.CATEGORY_ALL,
                query
            ).take(10).forEach {
                if (!results.containsKey(ResourceType.EFFECT)) results[ResourceType.EFFECT] = mutableListOf()
                results[ResourceType.EFFECT]!!.add(ResourceSummary(it.id, it.name, it.category, ResourceType.EFFECT))
            }

            // Fonts
            FontCatalog.search(query).take(10).forEach {
                if (!results.containsKey(ResourceType.FONT)) results[ResourceType.FONT] = mutableListOf()
                results[ResourceType.FONT]!!.add(ResourceSummary(it.id, it.displayName, it.categories.firstOrNull()?.name ?: "Sans", ResourceType.FONT))
            }

            // Animations
            AnimationCatalog.search(q).take(10).forEach {
                if (!results.containsKey(ResourceType.ANIMATION)) results[ResourceType.ANIMATION] = mutableListOf()
                results[ResourceType.ANIMATION]!!.add(ResourceSummary(it.id, it.name, it.category.name, ResourceType.ANIMATION))
            }

            results
        }

    // ── Pack Catalog ─────────────────────────────────────────────────────────

    /**
     * Returns all packs (bundled + available for download).
     */
    fun getAllPacks(): List<PackDefinition> = PackRegistry.allPacks()

    /**
     * Returns only installed packs.
     */
    fun getInstalledPacks(): List<PackDefinition> =
        getAllPacks().filter { isPackInstalled(it.id) }

    /**
     * Returns pack info for a resource ID.
     */
    fun getPackForResource(resourceId: String): PackDefinition? {
        val packId = PackRegistry.getPackResources(resourceId) ?: return null
        return PackRegistry.getPack(packId)
    }

    // ── Cache Management ─────────────────────────────────────────────────────

    /**
     * Clears transient caches (thumbnails, previews, analysis results).
     * Preserves user data (favorites, recents, installed packs).
     */
    fun clearTransientCache(context: Context) {
        // Clear analysis cache
        File(context.cacheDir, "analysis").deleteRecursively()
        // Clear preview cache
        File(context.cacheDir, "previews").deleteRecursively()
        // Clear thumbnail cache
        File(context.cacheDir, "thumbnails").deleteRecursively()
    }

    /**
     * Returns total size of cached and installed resources.
     */
    fun getCacheSize(context: Context): Long {
        val cacheDir = context.cacheDir
        return cacheDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }

    /**
     * Estimates the size of an installed pack.
     */
    fun estimatePackSize(packId: String): Long {
        return PackRegistry.getPack(packId)?.estimatedSize ?: 0L
    }
}

// ── Supporting Types ─────────────────────────────────────────────────────────

enum class ResourceType {
    TRANSITION,
    EFFECT,
    FONT,
    ANIMATION,
    STICKER,
    PRESET,
    TEXT_STYLE,
    EFFECT_CHAIN,
}

data class ResourceSummary(
    val id: String,
    val name: String,
    val category: String,
    val type: ResourceType,
    val packId: String? = null,
)

/**
 * Defines a downloadable or bundled resource pack.
 */
data class PackDefinition(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val estimatedSize: Long,
    val resourceCount: Int,
    val categories: List<String>,
    val resourceTypes: List<ResourceType>,
    val isBundled: Boolean,
    val isInstalled: Boolean,
    val isActive: Boolean,
    val sourceUrl: String = "",
    val license: String = "Proprietary",
    val attribution: String = "",
) {
    val sizeLabel: String
        get() = when {
            estimatedSize < 1024 -> "$estimatedSize B"
            estimatedSize < 1024 * 1024 -> "${estimatedSize / 1024} KB"
            else -> "${estimatedSize / (1024 * 1024)} MB"
        }
}

// ── Pack Registry ────────────────────────────────────────────────────────────

/**
 * Registry of all known packs (bundled and downloadable).
 */
object PackRegistry {
    private val packs = ConcurrentHashMap<String, PackDefinition>()

    init {
        registerBundledPacks()
    }

    private fun registerBundledPacks() {
        // Built-in resources are considered part of the "Core" pack
        packs["recly_core"] = PackDefinition(
            id = "recly_core",
            name = "Recly Core",
            description = "Efeitos, transições e fontes essenciais do Recly.",
            version = "1.0",
            estimatedSize = 0L,
            resourceCount = -1, // Dynamic
            categories = listOf("Basic", "Fade", "Cinematic", "Color"),
            resourceTypes = listOf(ResourceType.TRANSITION, ResourceType.EFFECT, ResourceType.FONT),
            isBundled = true,
            isInstalled = true,
            isActive = true,
        )

        // Future packs would be registered here:
        // packs["recly_social_pack"] = PackDefinition(...)
        // packs["recly_gaming_pack"] = PackDefinition(...)
    }

    fun register(pack: PackDefinition) {
        packs[pack.id] = pack
    }

    fun getPack(id: String): PackDefinition? = packs[id]

    fun allPacks(): List<PackDefinition> = packs.values.toList()

    fun getPackResources(resourceId: String): String? {
        // In a full implementation, this would query a resource→pack mapping table.
        // For built-in resources, the packId is "recly_core".
        return null
    }
}
