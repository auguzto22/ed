package com.termex.replay15.editor.assets

import android.content.Context

object EffectCatalog {
    const val CATEGORY_RECENT = "Recentes"
    const val CATEGORY_FAVORITES = "Favoritos"
    const val CATEGORY_ALL = "Todos"

    const val CAT_BLUR = "Blur"
    const val CAT_CAMERA = "Camera"
    const val CAT_MOTION = "Motion"
    const val CAT_GLITCH = "Glitch"
    const val CAT_LIGHT = "Light"
    const val CAT_FILM = "Film"
    const val CAT_DISTORTION = "Distortion"
    const val CAT_TRAIL = "Trail"
    const val CAT_CREATIVE = "Creative"
    const val CAT_PARTICLES = "Particles"

    val CANONICAL_CATEGORIES: List<String> = listOf(
        CAT_BLUR,
        CAT_CAMERA,
        CAT_MOTION,
        CAT_GLITCH,
        CAT_LIGHT,
        CAT_FILM,
        CAT_DISTORTION,
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
        "blur", "desfoque" -> CAT_BLUR
        "camera", "shake", "câmera" -> CAT_CAMERA
        "motion", "movimento", "zoom" -> CAT_MOTION
        "glitch", "digital" -> CAT_GLITCH
        "light", "luz", "iluminação" -> CAT_LIGHT
        "film", "filme", "retro", "cinema" -> CAT_FILM
        "distortion", "distorção", "distorsao", "lens" -> CAT_DISTORTION
        "trail", "temporal", "eco", "rastro" -> CAT_TRAIL
        "creative", "criativo", "stylize", "estilizar", "utility" -> CAT_CREATIVE
        "particles", "partículas", "particulas", "vfx", "gaming" -> CAT_PARTICLES
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
}
