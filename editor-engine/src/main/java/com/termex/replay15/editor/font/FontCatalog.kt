package com.termex.replay15.editor.font

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

enum class FontSource { BUILT_IN, RECLY_ORIGINAL, USER_IMPORTED, DOWNLOADED }

enum class FontCategory(val label: String) {
    SANS("Sans"), SERIF("Serif"), DISPLAY("Display"), GAMING("Gaming"), PIXEL("Pixel"),
    SCRIPT("Script"), GOTHIC("Gothic"), MONO("Mono"), RECLY_ORIGINALS("Recly Originals"),
    HANDWRITING("Escrita"),
}

data class FontAsset(
    val id: String,
    val displayName: String,
    val family: String,
    val weight: Int = 400,
    val italic: Boolean = false,
    /** Asset-relative path. Persistent project data stores [id], never this path. */
    val file: String = "",
    val source: FontSource,
    val license: String,
    val categories: Set<FontCategory>,
    val isOriginalRecly: Boolean = false,
    val supportsPortuguese: Boolean = true,
    val missingPortugueseCharacters: String = "",
) {
    init {
        require(id.matches(Regex("[a-z0-9_]{1,80}")))
        require(displayName.isNotBlank() && family.isNotBlank() && weight in 1..1000)
        require(supportsPortuguese || missingPortugueseCharacters.isNotEmpty())
    }
}

/** Immutable catalog. Dynamic imported/downloaded fonts can be layered by FontRepository later. */
object FontCatalog {
    const val DEFAULT_ID = "system_sans"
    private const val TAG = "FontCatalog"

    val builtIns: List<FontAsset> = listOf(
        system(DEFAULT_ID, "Moderna", "sans-serif", 400, FontCategory.SANS),
        system("system_serif", "Elegante", "serif", 400, FontCategory.SERIF),
        system("system_sans_black", "Impacto", "sans-serif-black", 900, FontCategory.DISPLAY),
        system("system_sans_condensed", "Condensada", "sans-serif-condensed", 400, FontCategory.SANS, FontCategory.DISPLAY),
        system("system_sans_light", "Leve", "sans-serif-light", 300, FontCategory.SANS),
        system("system_monospace", "Máquina", "monospace", 400, FontCategory.MONO),
        system("system_serif_monospace", "Editorial", "serif-monospace", 400, FontCategory.SERIF),
        system("system_cursive", "Manuscrita", "cursive", 400, FontCategory.SCRIPT),
        system("system_casual", "Casual", "casual", 400, FontCategory.SCRIPT),

        bundled("outfit_regular", "Outfit", "Outfit", 400, "outfit.ttf", "SIL OFL 1.1", FontCategory.SANS),
        bundled("montserrat_regular", "Montserrat", "Montserrat", 400, "montserrat.ttf", "SIL OFL 1.1", FontCategory.SANS),
        bundled("montserrat_semibold", "Montserrat SemiBold", "Montserrat", 600, "montserrat.ttf", "SIL OFL 1.1", FontCategory.SANS, FontCategory.DISPLAY),
        bundled("bebas_neue_regular", "Bebas Neue", "Bebas Neue", 400, "bebas.ttf", "SIL OFL 1.1", FontCategory.DISPLAY),
        bundled("playfair_display_regular", "Playfair Display", "Playfair Display", 400, "playfair.ttf", "SIL OFL 1.1", FontCategory.SERIF),
        bundled("caveat_regular", "Caveat", "Caveat", 400, "caveat.ttf", "SIL OFL 1.1", FontCategory.SCRIPT),
        bundled("space_mono_regular", "Space Mono", "Space Mono", 400, "spacemono.ttf", "SIL OFL 1.1", FontCategory.MONO, FontCategory.GAMING),

        bundled("oswald_light", "Oswald Light", "Oswald", 300, "oswald.ttf", "SIL OFL 1.1", FontCategory.SANS, FontCategory.DISPLAY),
        bundled("raleway_regular", "Raleway", "Raleway", 400, "raleway.ttf", "SIL OFL 1.1", FontCategory.SANS),
        bundled("anton_regular", "Anton", "Anton", 400, "anton.ttf", "SIL OFL 1.1", FontCategory.DISPLAY, FontCategory.GAMING),
        bundled("poppins_regular", "Poppins", "Poppins", 400, "poppins_regular.ttf", "SIL OFL 1.1", FontCategory.SANS),
        bundled("poppins_semibold", "Poppins SemiBold", "Poppins", 600, "poppins_semibold.ttf", "SIL OFL 1.1", FontCategory.SANS, FontCategory.DISPLAY),
        bundled("im_fell_dw_pica_regular", "IM FELL DW Pica", "IM FELL DW Pica", 400, "im_fell_dw_pica.ttf", "SIL OFL 1.1", FontCategory.SERIF, FontCategory.GOTHIC),

        original("recly_sans_regular", "Recly Sans", "Recly Sans", 400, "ReclySans-Regular.ttf", FontCategory.SANS),
        original("recly_wide_bold", "Recly Wide", "Recly Wide", 700, "ReclyWide-Bold.ttf", FontCategory.SANS, FontCategory.DISPLAY, FontCategory.GAMING),
        original("recly_poster_bold", "Recly Poster", "Recly Poster", 700, "ReclyPoster-Bold.ttf", FontCategory.DISPLAY),
        original("recly_pixel_regular", "Recly Pixel", "Recly Pixel", 400, "ReclyPixel-Regular.ttf", FontCategory.PIXEL, FontCategory.GAMING, FontCategory.MONO),
        original("recly_arcade_regular", "Recly Arcade", "Recly Arcade", 400, "ReclyArcade-Regular.ttf", FontCategory.DISPLAY, FontCategory.GAMING, FontCategory.PIXEL),
        original("recly_mono_regular", "Recly Mono", "Recly Mono", 400, "ReclyMono-Regular.ttf", FontCategory.MONO, FontCategory.SANS, FontCategory.GAMING),
        original("recly_signature_regular", "Recly Signature", "Recly Signature", 400, "ReclySignature-Regular.ttf", FontCategory.SCRIPT, FontCategory.DISPLAY),
        original("recly_brush_regular", "Recly Brush", "Recly Brush", 400, "ReclyBrush-Regular.ttf", FontCategory.SCRIPT, FontCategory.DISPLAY),
        original("recly_gothic_regular", "Recly Gothic", "Recly Gothic", 400, "ReclyGothic-Regular.ttf", FontCategory.GOTHIC, FontCategory.DISPLAY),
        original("recly_editorial_regular", "Recly Editorial", "Recly Editorial", 400, "ReclyEditorial-Regular.ttf", FontCategory.SERIF, FontCategory.DISPLAY),
        original("recly_urban_regular", "Recly Urban", "Recly Urban", 400, "ReclyUrban-Regular.ttf", FontCategory.DISPLAY, FontCategory.SCRIPT),
    )

    private val byId = builtIns.associateBy(FontAsset::id).toMutableMap()

    // ── Remote catalog ──────────────────────────────────────────────────────────

    /** Parsed [FontMetadata] entries from `font_catalog.json`. Populated by [loadCatalog]. */
    @Volatile
    private var remoteFonts: List<FontMetadata> = emptyList()

    /** Tags associated with built-in fonts for unified filtering. */
    private val builtInTags: Map<String, List<String>> = mapOf(
        "outfit_regular" to listOf(FontTag.MODERN, FontTag.MINIMAL),
        "montserrat_regular" to listOf(FontTag.MODERN, FontTag.MINIMAL),
        "montserrat_semibold" to listOf(FontTag.MODERN, FontTag.BOLD),
        "bebas_neue_regular" to listOf(FontTag.BOLD, FontTag.TRENDING),
        "playfair_display_regular" to listOf(FontTag.ELEGANT),
        "caveat_regular" to listOf(FontTag.HANDWRITING),
        "space_mono_regular" to listOf(FontTag.GAMING, FontTag.RETRO),
        "oswald_light" to listOf(FontTag.MINIMAL),
        "anton_regular" to listOf(FontTag.BOLD, FontTag.GAMING),
        "poppins_regular" to listOf(FontTag.MODERN, FontTag.TRENDING),
        "poppins_semibold" to listOf(FontTag.MODERN, FontTag.BOLD, FontTag.TRENDING),
    )

    /**
     * Load the remote font catalog from `assets/fonts/font_catalog.json`.
     *
     * Call once during app initialization. Safe to call multiple times; subsequent calls
     * replace the previous catalog. Built-in fonts always take precedence in case of ID
     * collision.
     */
    fun loadCatalog(context: Context) {
        try {
            val json = context.assets.open("fonts/font_catalog.json").bufferedReader().readText()
            val array = JSONArray(json)
            val parsed = mutableListOf<FontMetadata>()
            val fontCache = TypefaceCache.fontCache(context)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.getString("id")
                if (id in byId) continue // built-in wins
                val license = obj.optString("license", "")
                if (!FontLicenseRegistry.isApproved(license)) continue

                val meta = FontMetadata(
                    id = id,
                    family = obj.getString("family"),
                    displayName = obj.getString("displayName"),
                    weights = obj.optJSONArray("weights")?.let { w -> List(w.length()) { w.getInt(it) } } ?: listOf(400),
                    styles = obj.optJSONArray("styles")?.let { s -> List(s.length()) { s.getString(it) } } ?: listOf("normal"),
                    variable = obj.optBoolean("variable", false),
                    subsets = obj.optJSONArray("subsets")?.let { s -> List(s.length()) { s.getString(it) } } ?: listOf("latin"),
                    category = obj.optString("category", "SANS").let { cat ->
                        runCatching { FontCategory.valueOf(cat) }.getOrDefault(FontCategory.SANS)
                    },
                    tags = obj.optJSONArray("tags")?.let { t -> List(t.length()) { t.getString(it) } } ?: emptyList(),
                    source = obj.optString("source", "google-fonts"),
                    license = license,
                    licenseUrl = obj.optString("licenseUrl", ""),
                    remoteUrl = obj.optString("remoteUrl", ""),
                    version = obj.optString("version", ""),
                    sha256 = obj.optString("sha256", ""),
                    fileSize = obj.optLong("fileSize", 0),
                )
                meta.downloaded = fontCache.isDownloaded(id)
                if (meta.downloaded) {
                    meta.localPath = fontCache.diskFile(id)?.absolutePath ?: ""
                }
                parsed.add(meta)

                // Register in the unified ID map so find/requireOrDefault work for downloaded fonts.
                if (meta.downloaded) {
                    byId[id] = meta.toFontAsset()
                }
            }
            remoteFonts = parsed
            Log.i(TAG, "Loaded ${parsed.size} remote fonts from catalog")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load font catalog", e)
        }
    }

    /** All remote/downloadable fonts. */
    fun remotes(): List<FontMetadata> = remoteFonts

    /** Find a remote font by ID. */
    fun findRemote(id: String): FontMetadata? = remoteFonts.firstOrNull { it.id == id }

    /** All fonts (built-in + downloaded remotes), unified as [FontAsset]. */
    fun all(): List<FontAsset> = builtIns + remoteFonts.map { it.toFontAsset() }

    /** Register a downloaded remote font so it resolves via [find]/[requireOrDefault]. */
    fun registerDownloaded(metadata: FontMetadata, localPath: String) {
        metadata.downloaded = true
        metadata.localPath = localPath
        byId[metadata.id] = metadata.toFontAsset().copy(file = localPath)
    }

    /** Tags for a given font ID (works for built-in and remote fonts). */
    fun tagsFor(id: String): List<String> =
        builtInTags[id] ?: findRemote(id)?.tags ?: emptyList()

    // ── Lookup ──────────────────────────────────────────────────────────────────

    fun find(id: String): FontAsset? = byId[id]
    fun requireOrDefault(id: String): FontAsset = byId[id] ?: requireNotNull(byId[DEFAULT_ID])

    // ── Filtering ───────────────────────────────────────────────────────────────

    /** Filter [all] by category, optional tag, and optional search query. */
    fun filter(
        category: FontCategory? = null,
        tag: String? = null,
        query: String = "",
        downloadedOnly: Boolean = false,
    ): List<FontAsset> {
        var result = all()
        if (category != null) result = result.filter { category in it.categories }
        if (tag != null) result = result.filter { tag in tagsFor(it.id) }
        if (query.isNotBlank()) result = result.filter {
            it.displayName.contains(query, ignoreCase = true) || it.family.contains(query, ignoreCase = true)
        }
        if (downloadedOnly) result = result.filter {
            it.source != FontSource.DOWNLOADED || findRemote(it.id)?.downloaded == true
        }
        return result
    }

    // ── Factory helpers ─────────────────────────────────────────────────────────

    private fun system(id: String, name: String, family: String, weight: Int, vararg categories: FontCategory) = FontAsset(
        id, name, family, weight, source = FontSource.BUILT_IN, license = "Android system font; not redistributed by Recly",
        categories = categories.toSet(),
    )

    private fun bundled(
        id: String, name: String, family: String, weight: Int, file: String, license: String,
        vararg categories: FontCategory,
    ) = FontAsset(id, name, family, weight, file = file, source = FontSource.BUILT_IN,
        license = license, categories = categories.toSet())

    private fun original(
        id: String, name: String, family: String, weight: Int, file: String,
        vararg categories: FontCategory,
    ) = FontAsset(
        id = id,
        displayName = name,
        family = family,
        weight = weight,
        file = file,
        source = FontSource.RECLY_ORIGINAL,
        license = "Recly Original Font License 1.0; Copyright 2026 Recly",
        categories = categories.toSet() + FontCategory.RECLY_ORIGINALS,
        isOriginalRecly = true,
    )
}

