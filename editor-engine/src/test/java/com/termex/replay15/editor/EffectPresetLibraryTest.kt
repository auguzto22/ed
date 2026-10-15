package com.termex.replay15.editor

import com.termex.replay15.editor.assets.BuiltInEffectPresets
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A biblioteca de presets (tools/library/gen_effect_presets.py) contra o catálogo de efeitos. */
class EffectPresetLibraryTest {
    private fun asset(relative: String): String =
        sequenceOf(File("src/main/assets/editor/$relative"), File("editor-engine/src/main/assets/editor/$relative"))
            .first { it.isFile }.readText()

    private val presets by lazy { BuiltInEffectPresets.parse(asset("effect_presets.json")) }

    @Test fun libraryHasAboutAThousandUniquePresets() {
        assertTrue("só ${presets.size} presets", presets.size >= 1000)
        assertEquals("ids repetidos", presets.size, presets.map { it.id }.distinct().size)
        assertEquals("nomes repetidos", presets.size, presets.map { it.name }.distinct().size)
        assertTrue(BuiltInEffectPresets.categories(presets).size >= 10)
    }

    @Test fun everyNodeUsesAnExistingEffectWithValuesInRange() {
        val catalog = JSONArray(asset("effects.json"))
        val ranges = HashMap<String, Map<String, ClosedFloatingPointRange<Double>>>()
        for (i in 0 until catalog.length()) {
            val effect = catalog.getJSONObject(i)
            val params = effect.getJSONArray("parameters")
            ranges[effect.getString("id")] = (0 until params.length()).associate { j ->
                val p = params.getJSONObject(j)
                p.getString("id") to p.getDouble("min")..p.getDouble("max")
            }
        }
        for (preset in presets) for (node in preset.nodes) {
            val params = ranges[node.assetId]
            assertTrue("${preset.id}: efeito ${node.assetId} não existe", params != null)
            assertTrue("${preset.id}: valores demais", node.values.size <= 16)
            node.values.forEach { (key, value) ->
                val range = params!![key]
                assertTrue("${preset.id}/${node.assetId}: parâmetro $key não existe", range != null)
                // Tolerância: o valor vira Float no app (0.05 vira 0.0500000007).
                val v = value.toDouble()
                assertTrue("${preset.id}/${node.assetId}.$key=$value fora de $range", v >= range!!.start - 1e-4 && v <= range.endInclusive + 1e-4)
            }
        }
    }
}
