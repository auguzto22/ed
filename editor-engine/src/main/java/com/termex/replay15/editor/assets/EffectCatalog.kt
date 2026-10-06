package com.termex.replay15.editor.assets

import com.termex.replay15.editor.licenses.ResourceCreditsRegistry
import com.termex.replay15.editor.licenses.ResourceLicenseMetadata

object EffectCatalog {
    const val CATEGORY_RECENT = "Recentes"
    const val CATEGORY_FAVORITES = "Favoritos"
    const val CATEGORY_ALL = "Todos"

    const val CAT_COLOR = "Color"
    const val CAT_BLUR = "Blur"
    const val CAT_DISTORTION = "Distortion"
    const val CAT_LIGHT = "Light"
    const val CAT_GLITCH = "Glitch"
    const val CAT_RGB = "RGB"
    const val CAT_MOTION = "Motion"
    const val CAT_CAMERA = "Camera"
    const val CAT_CINEMATIC = "Cinematic"
    const val CAT_GAMING = "Gaming"
    const val CAT_RETRO = "Retro"
    const val CAT_VHS = "VHS"
    const val CAT_FILM = "Film"
    const val CAT_SOCIAL = "Social"
    const val CAT_TRAIL = "Trail"
    const val CAT_CREATIVE = "Creative"
    const val CAT_PARTICLES = "Particles"

    val CANONICAL_CATEGORIES: List<String> = listOf(
        CAT_COLOR,
        CAT_BLUR,
        CAT_DISTORTION,
        CAT_LIGHT,
        CAT_GLITCH,
        CAT_RGB,
        CAT_MOTION,
        CAT_CAMERA,
        CAT_CINEMATIC,
        CAT_GAMING,
        CAT_RETRO,
        CAT_VHS,
        CAT_FILM,
        CAT_SOCIAL,
        CAT_TRAIL,
        CAT_CREATIVE,
        CAT_PARTICLES,
    )

    val UI_CATEGORIES: List<String> = listOf(
        CATEGORY_ALL,
        CATEGORY_RECENT,
        CATEGORY_FAVORITES,
    ) + CANONICAL_CATEGORIES

    fun canonicalCategory(category: String): String = when (category.trim().lowercase()) {
        "color", "cor", "cores", "tonalidade" -> CAT_COLOR
        "blur", "desfoque" -> CAT_BLUR
        "distortion", "distorção", "distorsao", "lens", "lente" -> CAT_DISTORTION
        "light", "luz", "iluminação", "iluminacao", "flare", "glow" -> CAT_LIGHT
        "glitch", "digital" -> CAT_GLITCH
        "rgb", "chromatic", "chromatic aberration", "split" -> CAT_RGB
        "motion", "movimento", "zoom", "speed" -> CAT_MOTION
        "camera", "shake", "câmera", "trepidação", "trepidacao" -> CAT_CAMERA
        "cinematic", "anamorphic" -> CAT_CINEMATIC
        "film", "filme", "pelicula", "burn", "retro", "cinema" -> CAT_FILM
        "social", "reels", "tiktok", "stories" -> CAT_SOCIAL
        "trail", "temporal", "eco", "rastro" -> CAT_TRAIL
        "creative", "criativo", "stylize", "estilizar", "utility" -> CAT_CREATIVE
        "particles", "partículas", "particulas", "vfx", "gaming" -> CAT_PARTICLES
        "vhs", "tape", "fita", "cassette" -> CAT_VHS
        else -> category.replaceFirstChar { it.uppercase() }
    }

    fun filter(
        definitions: List<EffectDefinition>,
        category: String,
        query: String = "",
        recentIds: List<String> = emptyList(),
        favoriteIds: Set<String> = emptySet(),
    ): List<EffectDefinition> {
        val trimmedQuery = query.trim()
        val filtered = definitions.filter { def ->
            val matchesCategory = when (category) {
                CATEGORY_ALL -> true
                CATEGORY_RECENT -> def.id in recentIds
                CATEGORY_FAVORITES -> def.id in favoriteIds
                else -> canonicalCategory(def.category).equals(category, ignoreCase = true)
            }
            if (!matchesCategory) return@filter false
            if (trimmedQuery.isEmpty()) return@filter true
            def.name.contains(trimmedQuery, ignoreCase = true) ||
                def.id.contains(trimmedQuery, ignoreCase = true) ||
                def.category.contains(trimmedQuery, ignoreCase = true) ||
                canonicalCategory(def.category).contains(trimmedQuery, ignoreCase = true)
        }
        return if (category == CATEGORY_RECENT) {
            filtered.sortedBy { recentIds.indexOf(it.id) }
        } else {
            filtered.sortedWith(compareBy<EffectDefinition> { canonicalCategory(it.category) }.thenBy { it.name })
        }
    }

    /**
     * Registers loaded effect definitions with the central attribution registry.
     */
    fun registerCredits(definitions: List<EffectDefinition>) {
        definitions.forEach { def ->
            ResourceCreditsRegistry.register(
                ResourceLicenseMetadata(
                    id = def.id,
                    name = def.name,
                    author = "Recly Shader Core & Open VFX Contributors",
                    source = "Recly Built-in GLSL Pipeline",
                    license = def.license.ifBlank { "Apache-2.0" },
                    licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
                    sourceUrl = "https://github.com/recly/recly-engine",
                    category = "Effect - ${canonicalCategory(def.category)}",
                    tags = listOf(def.category, def.engine.name, "shader", "effect"),
                )
            )
        }
    }
}
