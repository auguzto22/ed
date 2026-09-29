package com.termex.replay15.editor.assets

import android.content.Context
import org.json.JSONArray

object BuiltInEffects {
    fun definitions(context: Context): List<EffectDefinition> {
        val catalog = JSONArray(context.assets.open("editor/effects.json").bufferedReader().use { it.readText() })
        return List(catalog.length()) { EffectDefinition.parse(catalog.getJSONObject(it).toString()) }
    }
    fun shader(context: Context, id: String): String {
        val cleanName = id.removeSuffix(".frag")
        return context.assets.open("editor/effects/$cleanName.frag").bufferedReader().use { it.readText() }
    }
}
