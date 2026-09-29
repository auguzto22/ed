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

    fun parse(text: String): List<EffectPreset> {
        require(text.length in 2..131_072)
        val root = JSONArray(text)
        require(root.length() in 1..100)
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
