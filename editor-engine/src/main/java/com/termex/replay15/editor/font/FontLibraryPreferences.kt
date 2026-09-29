package com.termex.replay15.editor.font

import android.content.Context

class FontLibraryPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("editor-font-library", Context.MODE_PRIVATE)

    fun favorites(): Set<String> = preferences.getStringSet(FAVORITES, emptySet()).orEmpty()

    fun toggleFavorite(id: String) {
        val changed = favorites().toMutableSet()
        if (!changed.add(id)) changed.remove(id)
        preferences.edit().putStringSet(FAVORITES, changed).apply()
    }

    fun recent(): List<String> = preferences.getString(RECENT, "").orEmpty().split(',').filter(String::isNotBlank)

    fun markRecent(id: String) {
        val changed = (listOf(id) + recent().filterNot { it == id }).take(MAX_RECENT)
        preferences.edit().putString(RECENT, changed.joinToString(",")).apply()
    }

    private companion object {
        const val FAVORITES = "favorites"
        const val RECENT = "recent"
        const val MAX_RECENT = 12
    }
}
