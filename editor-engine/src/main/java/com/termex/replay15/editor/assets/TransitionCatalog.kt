package com.termex.replay15.editor.assets

import android.content.Context
import android.content.SharedPreferences

object TransitionCatalog {
    const val CATEGORY_ALL = "Todos"
    const val CATEGORY_RECENT = "Recentes"
    const val CATEGORY_FAVORITES = "Favoritos"

    const val CAT_BASIC = "Basic"
    const val CAT_SLIDE = "Slide"
    const val CAT_WIPE = "Wipe"
    const val CAT_ZOOM = "Zoom"
    const val CAT_MOTION = "Motion"
    const val CAT_BLUR = "Blur"
    const val CAT_LIGHT = "Light"
    const val CAT_GLITCH = "Glitch"
    const val CAT_LUMA = "Luma"
    const val CAT_DISTORTION = "Distortion"
    const val CAT_3D = "3D"
    const val CAT_CREATIVE = "Creative"

    val CANONICAL_CATEGORIES = listOf(
        CAT_BASIC,
        CAT_SLIDE,
        CAT_WIPE,
        CAT_ZOOM,
        CAT_MOTION,
        CAT_BLUR,
        CAT_LIGHT,
        CAT_GLITCH,
        CAT_LUMA,
        CAT_DISTORTION,
        CAT_3D,
        CAT_CREATIVE,
    )

    val UI_CATEGORIES = listOf(
        CATEGORY_ALL,
        CATEGORY_RECENT,
        CATEGORY_FAVORITES,
    ) + CANONICAL_CATEGORIES

    fun canonicalCategory(category: String): String = when (category.trim().lowercase()) {
        "basic", "basico", "básico" -> CAT_BASIC
        "slide", "push", "deslizar" -> CAT_SLIDE
        "wipe", "mascara", "cortina" -> CAT_WIPE
        "zoom", "escala" -> CAT_ZOOM
        "motion", "movimento", "whip" -> CAT_MOTION
        "blur", "desfoque" -> CAT_BLUR
        "light", "luz", "flash" -> CAT_LIGHT
        "glitch", "digital" -> CAT_GLITCH
        "luma", "luminancia", "luminância" -> CAT_LUMA
        "distortion", "distorcao", "distorção" -> CAT_DISTORTION
        "3d", "perspectiva", "perspective" -> CAT_3D
        "creative", "criativo" -> CAT_CREATIVE
        else -> category.replaceFirstChar { it.uppercase() }
    }

    fun findById(context: Context, id: String): TransitionDefinition? =
        BuiltInTransitions.findById(context, id)

    fun filter(
        definitions: List<TransitionDefinition>,
        category: String,
        query: String = "",
        recentIds: List<String> = emptyList(),
        favoriteIds: Set<String> = emptySet(),
    ): List<TransitionDefinition> {
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
            filtered.sortedWith(compareBy<TransitionDefinition> { canonicalCategory(it.category) }.thenBy { it.name })
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("recly_transitions_prefs", Context.MODE_PRIVATE)

    fun getFavorites(context: Context): Set<String> =
        prefs(context).getStringSet("favorites", emptySet()) ?: emptySet()

    fun toggleFavorite(context: Context, id: String): Boolean {
        val current = getFavorites(context).toMutableSet()
        val isFav = if (id in current) { current.remove(id); false } else { current.add(id); true }
        prefs(context).edit().putStringSet("favorites", current).apply()
        return isFav
    }

    fun isFavorite(context: Context, id: String): Boolean =
        id in getFavorites(context)

    fun getRecents(context: Context): List<String> =
        prefs(context).getString("recents", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

    fun recordUsed(context: Context, id: String) {
        val list = getRecents(context).toMutableList()
        list.remove(id)
        list.add(0, id)
        val trimmed = list.take(20)
        prefs(context).edit().putString("recents", trimmed.joinToString(",")).apply()
    }
}
