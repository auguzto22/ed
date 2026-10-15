package com.termex.replay15.editor.assets

import android.content.Context
import org.json.JSONArray

data class EffectPresetNode(
    val assetId: String,
    val version: Int = 1,
    val intensity: Float = .7f,
    val values: Map<String, Float> = emptyMap(),
) {
    init {
        require(assetId.matches(Regex("[a-z][a-z0-9_]{0,63}")) && version > 0)
        require(intensity in 0f..1f && values.values.all { it.isFinite() })
    }
}

data class EffectPreset(val id: String, val name: String, val category: String, val nodes: List<EffectPresetNode>) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9_]{0,63}")))
        require(name.isNotBlank() && category.isNotBlank() && nodes.isNotEmpty() && nodes.size <= 8)
    }
}

object BuiltInEffectPresets {
    fun definitions(context: Context): List<EffectPreset> =
        parse(context.assets.open("editor/effect_presets.json").bufferedReader().use { it.readText() })

    /** The library ships ~300 presets (about 90 KB); the caps leave room to grow without accepting arbitrary input. */
    const val MAX_PRESETS = 500
    const val MAX_TEXT_LENGTH = 524_288

    // Gallery tabs keep the historical order first; categories added later follow, then anything unknown.
    private val PREFERRED_CATEGORIES = listOf(
        "Cinema", "Film", "Gaming", "Retro", "Distorsao",
        "Cor", "Glitch", "Luz", "Movimento", "Camera", "Blur", "Criativo", "Particulas", "Rastro",
    )

    fun orderedCategories(presets: List<EffectPreset>): List<String> {
        val present = presets.map { it.category }.distinct()
        val known = PREFERRED_CATEGORIES.filter { wanted -> present.any { it.equals(wanted, ignoreCase = true) } }
        return known + present.filter { category -> PREFERRED_CATEGORIES.none { it.equals(category, ignoreCase = true) } }
    }

    fun parse(text: String): List<EffectPreset> {
        require(text.length in 2..MAX_TEXT_LENGTH)
        val root = JSONArray(text)
        require(root.length() in 1..MAX_PRESETS)
        return List(root.length()) { index ->
            val json = root.getJSONObject(index)
            require(json.getInt("schemaVersion") == 1)
            val nodesJson = json.getJSONArray("nodes")
            val nodes = List(nodesJson.length()) { nodeIndex ->
                val node = nodesJson.getJSONObject(nodeIndex)
                val valuesJson = node.optJSONObject("values")
                val values = valuesJson?.let { objectValues ->
                    objectValues.keys().asSequence().associateWith { key -> objectValues.getDouble(key).toFloat() }
                }.orEmpty()
                EffectPresetNode(
                    assetId = node.getString("assetId"),
                    version = node.optInt("version", 1),
                    intensity = node.optDouble("intensity", .7).toFloat(),
                    values = values,
                )
            }
            EffectPreset(json.getString("id"), json.getString("name"), json.getString("category"), nodes)
        }
    }
}
