package com.termex.replay15.editor.assets

import android.content.Context
import org.json.JSONArray

object BuiltInTransitions {
    @Volatile private var cached: List<TransitionDefinition>? = null

    fun definitions(context: Context): List<TransitionDefinition> {
        return cached ?: synchronized(this) {
            cached ?: run {
                val text = context.assets.open("editor/transitions.json").bufferedReader().use { it.readText() }
                val catalog = JSONArray(text)
                List(catalog.length()) { TransitionDefinition.parse(catalog.getJSONObject(it).toString()) }.also { cached = it }
            }
        }
    }

    fun findById(context: Context, id: String): TransitionDefinition? =
        definitions(context).firstOrNull { it.id == id }

    fun shader(context: Context, shaderFile: String): String =
        context.assets.open("editor/transitions/$shaderFile").bufferedReader().use { it.readText() }
}
