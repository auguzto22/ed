package com.termex.replay15.editor.presets

import android.content.Context
import android.content.SharedPreferences
import com.termex.replay15.editor.domain.StudioGrade
import com.termex.replay15.editor.domain.TextAlignment
import com.termex.replay15.editor.domain.TextAnimation
import com.termex.replay15.editor.domain.TextFont
import com.termex.replay15.editor.domain.VideoFilter
import com.termex.replay15.editor.licenses.ResourceCreditsRegistry
import com.termex.replay15.editor.licenses.ResourceLicenseMetadata
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Catalogo central de presets de edicao.
 * Fornece presets embutidos, gerenciamento de favoritos/recents
 * e um sistema de persistencia para presets personalizados.
 */
object PresetCatalog {

    private val builtInPresets = ConcurrentHashMap<String, EditPreset>()
    private val customPresets = ConcurrentHashMap<String, EditPreset>()

    init {
        registerBuiltIns()
    }

    // ── Built-in Presets ────────────────────────────────────────────────────────

    /*
     * Each built-in look is one VideoFilter at full strength. The filters were referenced by raw
     * indices from an older enum order (several out of range, so applying them threw and nothing
     * changed; others pointed at a different look, e.g. "Noir" applied "Drama"), and their own
     * brightness/contrast/saturation were repeated as manual adjustments in the wrong units,
     * doubling contrast/brightness on top of the filter. Manual adjustments now stay neutral.
     */
    private fun registerBuiltIns() {
        builtInPresets.clear()
        builtInPresets.putAll(listOf(
            // ── CINEMATIC ───────────────────────────────────────────────
            createBuiltIn(
                id = "preset_cinema_v1",
                name = "Cinema",
                description = "Visual cinematografico com contraste elevado e tons frios.",
                group = PresetGroup.CINEMATIC,
                tags = listOf("cinema", "profissional", "filme"),
                colorGrade = PresetColorGrade(
                    filterIndex = VideoFilter.CINEMA.ordinal,
                ),
                textStyle = PresetTextStyle(
                    fontId = TextFont.MONTSERRAT.id,
                    color = 0xFFFFFFFF.toInt(),
                    size = .055f,
                    bold = true,
                    alignment = TextAlignment.CENTER,
                    animation = TextAnimation.FADE,
                ),
            ),
            createBuiltIn(
                id = "preset_noir_v1",
                name = "Noir",
                description = "Preto e branco dramatico com alto contraste.",
                group = PresetGroup.CINEMATIC,
                tags = listOf("noir", "pb", "dramatico", "preto", "branco"),
                colorGrade = PresetColorGrade(
                    filterIndex = VideoFilter.NOIR.ordinal,
                ),
            ),
            createBuiltIn(
                id = "preset_drama_v1",
                name = "Drama",
                description = "Contraste intenso e saturação reduzida para impacto emocional.",
                group = PresetGroup.CINEMATIC,
                tags = listOf("drama", "impacto", "cinema"),
                colorGrade = PresetColorGrade(
                    filterIndex = VideoFilter.DRAMA.ordinal,
                ),
            ),

            // ── SOCIAL / REELS ────────────────────────────────────────
            createBuiltIn(
                id = "preset_vibrant_v1",
                name = "Vibrante",
                description = "Cores saturadas e brilhantes para chamar atencao no feed.",
                group = PresetGroup.SOCIAL,
                tags = listOf("vibrante", "cores", "feed", "reels", "social"),
                colorGrade = PresetColorGrade(
                    filterIndex = VideoFilter.VIBRANT.ordinal,
                ),
                textStyle = PresetTextStyle(
                    fontId = TextFont.BEBAS.id,
                    color = 0xFFFFFFFF.toInt(),
                    size = .07f,
                    bold = true,
                    alignment = TextAlignment.CENTER,
                    animation = TextAnimation.POP,
                    backgroundColor = 0xCC000000.toInt(),
                    outlineWidth = 0f,
                    shadow = true,
                ),
            ),
            createBuiltIn(
                id = "preset_clean_v1",
                name = "Clean",
                description = "Visual minimalista com cores neutras e tons suaves.",
                group = PresetGroup.SOCIAL,
                tags = listOf("clean", "minimalista", "suave", "neutro"),
                colorGrade = PresetColorGrade(
                    filterIndex = VideoFilter.CLEAN.ordinal,
                ),
                textStyle = PresetTextStyle(
                    fontId = TextFont.OUTFIT.id,
                    color = 0xFFF5F5F5.toInt(),
                    size = .055f,
                    bold = false,
                    italic = false,
                    alignment = TextAlignment.CENTER,
                    animation = TextAnimation.FADE,
                    letterSpacing = 0.02f,
                ),
            ),
            createBuiltIn(
                id = "preset_golden_hour_v1",
                name = "Golden Hour",
                description = "Tom quente e dourado que simula a hora magica.",
                group = PresetGroup.SOCIAL,
                tags = listOf("golden", "dourado", "quente", "hora magica", "beleza"),
                colorGrade = PresetColorGrade(
                    filterIndex = VideoFilter.GOLDEN.ordinal,
                ),
                textStyle = PresetTextStyle(
                    fontId = TextFont.PLAYFAIR.id,
                    color = 0xFFFFF8E1.toInt(),
                    size = .055f,
                    bold = false,
                    italic = true,
                    alignment = TextAlignment.CENTER,
                    animation = TextAnimation.SLIDE_UP,
                ),
            ),

            // ── VINTAGE ───────────────────────────────────────────────
            createBuiltIn(
                id = "preset_vintage_v1",
                name = "Vintage",
                description = "Efeito retro com reducao de saturacao e tonalidade amarelada.",
                group = PresetGroup.VINTAGE,
                tags = listOf("vintage", "retro", "filme", "velho", "nostalgia"),
                colorGrade = PresetColorGrade(
                    filterIndex = VideoFilter.VINTAGE.ordinal,
                ),
                textStyle = PresetTextStyle(
                    fontId = TextFont.CAVEAT.id,
                    color = 0xFF2D2D2D.toInt(),
                    size = .065f,
                    bold = false,
                    alignment = TextAlignment.CENTER,
                    animation = TextAnimation.FADE,
                    backgroundColor = 0x99F5E6C8.toInt(),
                    outlineColor = 0xFFD4C4A0.toInt(),
                    outlineWidth = 0.002f,
                ),
            ),
            createBuiltIn(
                id = "preset_retro_v1",
                name = "Retro",
                description = "Estetica retrô com cores dessaturadas e vinhetas.",
                group = PresetGroup.VINTAGE,
                tags = listOf("retro", "70s", "nostalgia", "vintage"),
                colorGrade = PresetColorGrade(
                    filterIndex = VideoFilter.RETRO.ordinal,
                ),
            ),
            createBuiltIn(
                id = "preset_film_v1",
                name = "Film",
                description = "Grão de filme e tonalidade levemente verde-azulada.",
                group = PresetGroup.VINTAGE,
                tags = listOf("filme", "pellicula", "cinema classico", "8mm"),
                colorGrade = PresetColorGrade(
                    filterIndex = VideoFilter.DOCUMENTARY.ordinal,
                ),
            ),

            // ── MINIMAL ───────────────────────────────────────────────
            createBuiltIn(
                id = "preset_minimal_v1",
                name = "Minimal",
                description = "Visual puro e minimalista sem filtros.",
                group = PresetGroup.MINIMAL,
                tags = listOf("minimal", "clean", "puro", "branco"),
                colorGrade = PresetColorGrade(
                    filterIndex = VideoFilter.ORIGINAL.ordinal,
                ),
                textStyle = PresetTextStyle(
                    fontId = TextFont.OUTFIT.id,
                    color = 0xFFFFFFFF.toInt(),
                    size = .05f,
                    bold = false,
                    alignment = TextAlignment.CENTER,
                    animation = TextAnimation.NONE,
                    letterSpacing = 0.05f,
                ),
            ),
            createBuiltIn(
                id = "preset_portrait_v1",
                name = "Retrato",
                description = "Ajuste otimizado para close-ups e retratos.",
                group = PresetGroup.MINIMAL,
                tags = listOf("retrato", "rosto", "beleza", "close", "pele"),
                colorGrade = PresetColorGrade(
                    filterIndex = VideoFilter.PORTRAIT.ordinal,
                ),
                textStyle = PresetTextStyle(
                    fontId = TextFont.PLAYFAIR.id,
                    color = 0xFFFFFFFF.toInt(),
                    size = .06f,
                    bold = false,
                    italic = true,
                    alignment = TextAlignment.CENTER,
                    animation = TextAnimation.SLIDE_UP,
                ),
            ),

            // ── GAMING ────────────────────────────────────────────────
            createBuiltIn(
                id = "preset_neon_v1",
                name = "Neon",
                description = "Cores neon vibrantes com alto contraste.",
                group = PresetGroup.GAMING,
                tags = listOf("neon", "game", "gaming", "cyber", "vibrante"),
                colorGrade = PresetColorGrade(
                    filterIndex = VideoFilter.NEON.ordinal,
                ),
                textStyle = PresetTextStyle(
                    fontId = TextFont.BEBAS.id,
                    color = 0xFF00FFFF.toInt(),
                    size = .08f,
                    bold = true,
                    alignment = TextAlignment.CENTER,
                    animation = TextAnimation.GLITCH,
                    shadow = true,
                ),
            ),
            createBuiltIn(
                id = "preset_ice_v1",
                name = "Ice",
                description = "Tons frios e azulados com brilho cristalino.",
                group = PresetGroup.GAMING,
                tags = listOf("ice", "gelo", "frio", "azul", "glacial"),
                colorGrade = PresetColorGrade(
                    filterIndex = VideoFilter.ICE.ordinal,
                ),
                textStyle = PresetTextStyle(
                    fontId = TextFont.SPACE_MONO.id,
                    color = 0xFFB3E5FC.toInt(),
                    size = .055f,
                    bold = false,
                    alignment = TextAlignment.CENTER,
                    animation = TextAnimation.WAVE,
                    letterSpacing = 0.02f,
                ),
            ),
            createBuiltIn(
                id = "preset_night_v1",
                name = "Noite",
                description = "Visual noturno com azuis profundos e sombras accentuadas.",
                group = PresetGroup.GAMING,
                tags = listOf("noite", "dark", "azul", "noturno", "neon"),
                colorGrade = PresetColorGrade(
                    filterIndex = VideoFilter.NIGHT.ordinal,
                ),
            ),

            // ── RECLY BUILT-IN ───────────────────────────────────────
            createBuiltIn(
                id = "preset_warm_v1",
                name = "Warm",
                description = "Tonalidade quente e acolhedora.",
                group = PresetGroup.BUILT_IN,
                tags = listOf("warm", "quente", "laranja", "sol"),
                colorGrade = PresetColorGrade(
                    filterIndex = VideoFilter.WARM.ordinal,
                ),
            ),
            createBuiltIn(
                id = "preset_cool_v1",
                name = "Cool",
                description = "Tonalidade fria e serena.",
                group = PresetGroup.BUILT_IN,
                tags = listOf("cool", "frio", "azul", "sereno"),
                colorGrade = PresetColorGrade(
                    filterIndex = VideoFilter.COOL.ordinal,
                ),
            ),
            createBuiltIn(
                id = "preset_fade_v1",
                name = "Fade",
                description = "Visual desbotado com alta luz e baixa saturacao.",
                group = PresetGroup.BUILT_IN,
                tags = listOf("fade", "desbotado", "suave", "light"),
                colorGrade = PresetColorGrade(
                    filterIndex = VideoFilter.FADE.ordinal,
                ),
            ),
            createBuiltIn(
                id = "preset_teal_orange_v1",
                name = "Teal & Orange",
                description = "Classico de cinema com complementares teal/laranja.",
                group = PresetGroup.BUILT_IN,
                tags = listOf("teal", "orange", "cinema", "complementar", "hollywood"),
                colorGrade = PresetColorGrade(
                    filterIndex = VideoFilter.TEAL_ORANGE.ordinal,
                ),
            ),
            createBuiltIn(
                id = "preset_sunset_v1",
                name = "Sunset",
                description = "Tons dourados e alaranjados de um por do sol intenso.",
                group = PresetGroup.BUILT_IN,
                tags = listOf("sunset", "por do sol", "dourado", "laranja", "golden"),
                colorGrade = PresetColorGrade(
                    filterIndex = VideoFilter.SUNSET.ordinal,
                ),
            ),
        ).associateBy { it.id })

        // Register all built-ins with the attribution registry
        builtInPresets.values.forEach { preset ->
            ResourceCreditsRegistry.register(
                ResourceLicenseMetadata(
                    id = preset.id,
                    name = preset.name,
                    author = "Recly Design Team",
                    source = "Recly Built-in Presets",
                    license = "Proprietary",
                    category = "Preset - ${preset.group.label}",
                    tags = preset.tags + listOf("preset", preset.group.name.lowercase()),
                    notes = preset.description,
                )
            )
        }
    }

    private fun createBuiltIn(
        id: String,
        name: String,
        description: String,
        group: PresetGroup,
        tags: List<String>,
        colorGrade: PresetColorGrade? = null,
        textStyle: PresetTextStyle? = null,
        audioStyle: PresetAudioStyle? = null,
        stickerStyle: PresetStickerStyle? = null,
        transitionStyle: PresetTransitionStyle? = null,
        smartZoom: PresetSmartZoom? = null,
    ): EditPreset = EditPreset(
        id = id,
        name = name,
        description = description,
        group = group,
        type = PresetType.FULL_PROJECT,
        textStyle = textStyle,
        colorGrade = colorGrade,
        audioStyle = audioStyle,
        stickerStyle = stickerStyle,
        transitionStyle = transitionStyle,
        smartZoom = smartZoom,
        isBuiltIn = true,
        isReadOnly = true,
        tags = tags,
    )

    // ── Query ─────────────────────────────────────────────────────────────────

    fun all(): List<EditPreset> = builtInPresets.values.toList() + customPresets.values.toList()

    fun find(id: String): EditPreset? = builtInPresets[id] ?: customPresets[id]

    fun byGroup(group: PresetGroup): List<EditPreset> =
        all().filter { it.group == group }

    fun byType(type: PresetType): List<EditPreset> =
        all().filter { it.type == type || it.type == PresetType.FULL_PROJECT }

    fun search(query: String): List<EditPreset> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return all()
        return all().filter { preset ->
            preset.name.lowercase().contains(q) ||
                preset.description.lowercase().contains(q) ||
                preset.tags.any { it.lowercase().contains(q) } ||
                preset.group.label.lowercase().contains(q)
        }
    }

    fun builtIn(): List<EditPreset> = builtInPresets.values.toList()

    fun custom(): List<EditPreset> = customPresets.values.toList()

    // ── Custom Preset Management ───────────────────────────────────────────────

    fun saveCustomPreset(preset: EditPreset): EditPreset {
        val toSave = preset.copy(
            id = if (preset.id.isBlank()) UUID.randomUUID().toString() else preset.id,
            group = PresetGroup.CUSTOM,
            isBuiltIn = false,
            isReadOnly = false,
        )
        customPresets[toSave.id] = toSave
        return toSave
    }

    fun deleteCustomPreset(id: String): Boolean {
        val preset = customPresets[id] ?: return false
        if (preset.isReadOnly) return false
        return customPresets.remove(id) != null
    }

    fun updateCustomPreset(preset: EditPreset): EditPreset {
        require(!preset.isBuiltIn) { "Cannot update built-in presets" }
        val updated = preset.copy(isReadOnly = false)
        customPresets[updated.id] = updated
        return updated
    }

    // ── Favorites & Recents ────────────────────────────────────────────────────

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("recly_preset_catalog", Context.MODE_PRIVATE)

    fun favorites(context: Context): List<EditPreset> =
        prefs(context).getStringSet(KEY_FAVORITES, emptySet())
            ?.mapNotNull { find(it) } ?: emptyList()

    fun isFavorite(context: Context, id: String): Boolean =
        prefs(context).getStringSet(KEY_FAVORITES, emptySet())?.contains(id) ?: false

    fun toggleFavorite(context: Context, id: String): Boolean {
        val current = prefs(context).getStringSet(KEY_FAVORITES, emptySet())?.toMutableSet() ?: mutableSetOf()
        val isFav = if (id in current) { current.remove(id); false } else { current.add(id); true }
        prefs(context).edit().putStringSet(KEY_FAVORITES, current).apply()
        return isFav
    }

    fun recents(context: Context): List<EditPreset> =
        prefs(context).getString(KEY_RECENTS, "")
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?.mapNotNull { find(it) } ?: emptyList()

    fun markRecent(context: Context, id: String) {
        val list = recents(context).toMutableList()
        list.removeAll { it.id == id }
        list.add(0, find(id) ?: return)
        prefs(context).edit().putString(KEY_RECENTS, list.take(20).joinToString(",") { it.id }).apply()
    }

    // ── Constants ───────────────────────────────────────────────────────────────

    private const val KEY_FAVORITES = "favorites"
    private const val KEY_RECENTS = "recents"
}
