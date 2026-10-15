package com.termex.replay15.editor

import com.termex.replay15.editor.assets.BuiltInEffectPresets
import com.termex.replay15.editor.assets.EffectDefinition
import com.termex.replay15.editor.assets.EffectPreset
import java.io.File
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

/** The shipped preset library must only combine effects and parameters that really exist. */
class BuiltInEffectPresetsTest {
    private fun asset(name: String): String {
        val relative = "src/main/assets/editor/$name"
        return (sequenceOf(File(relative), File("editor-engine/$relative")).firstOrNull(File::isFile)
            ?: error("$name not found")).readText()
    }

    private val presets: List<EffectPreset> by lazy { BuiltInEffectPresets.parse(asset("effect_presets.json")) }
    private val catalog: Map<String, EffectDefinition> by lazy {
        val array = JSONArray(asset("effects.json"))
        List(array.length()) { EffectDefinition.parse(array.getJSONObject(it).toString()) }.associateBy { it.id }
    }

    @Test fun libraryHasAtLeastOneHundredPresetsWithUniqueIds() {
        assertTrue("only ${presets.size} presets", presets.size >= 100)
        assertEquals(presets.size, presets.map { it.id }.distinct().size)
    }

    @Test fun everyNodePointsAtARealEffectAndParameterInRange() {
        presets.forEach { preset ->
            preset.nodes.forEach { node ->
                val definition = catalog[node.assetId]
                assertNotNull("${preset.id}: unknown effect ${node.assetId}", definition)
                if (definition == null) return@forEach
                node.values.forEach { (key, value) ->
                    val parameter = definition.parameters.firstOrNull { it.id == key }
                    assertNotNull("${preset.id}: ${node.assetId} has no parameter $key", parameter)
                    assertTrue("${preset.id}: $key=$value outside ${parameter!!.min}..${parameter.max}", value in parameter.min..parameter.max)
                }
            }
        }
    }

    @Test fun headshotAndFriendsAreShortTimedHits() {
        val headshot = presets.first { it.id == "headshot" }
        assertTrue(headshot.isTimed)
        val critical = presets.first { it.id == "headshot_critico" }
        assertEquals(300, critical.durationMs)
        assertTrue(critical.isTimed)
    }

    @Test fun lookPresetsCoverTheWholeClip() {
        val blockbuster = presets.first { it.id == "blockbuster" }
        assertNull(blockbuster.durationMs)
        assertFalse(blockbuster.isTimed)
    }

    @Test fun presetsFitTheEffectStackLimit() {
        // A clip accepts 12 effects: a single preset must never fill the stack on its own.
        assertTrue(presets.all { it.nodes.size <= 6 })
    }

    @Test fun categoriesAreNamedForTheGallery() {
        val categories = presets.map { it.category }.distinct()
        assertTrue(categories.size >= 8)
        assertTrue(categories.containsAll(listOf("Gaming", "Cinema", "Glitch", "Neon", "Social")))
    }
}
