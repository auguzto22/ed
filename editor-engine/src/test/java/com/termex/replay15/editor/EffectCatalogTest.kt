package com.termex.replay15.editor

import com.termex.replay15.editor.assets.*
import java.io.File
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class EffectCatalogTest {

    private fun loadBuiltInCatalog(): List<EffectDefinition> {
        val relative = "src/main/assets/editor/effects.json"
        val file = sequenceOf(File(relative), File("app/$relative"))
            .firstOrNull(File::isFile)
            ?: error("effects.json not found")
        val array = JSONArray(file.readText())
        return List(array.length()) { EffectDefinition.parse(array.getJSONObject(it).toString()) }
    }

    private fun loadShader(name: String): String {
        val clean = name.removeSuffix(".frag")
        val relative = "src/main/assets/editor/effects/$clean.frag"
        return sequenceOf(File(relative), File("app/$relative"))
            .firstOrNull(File::isFile)
            ?.readText()
            ?: error("Shader not found: $name")
    }

    @Test
    fun `catalog contains at least 100 unique valid effects`() {
        val definitions = loadBuiltInCatalog()
        assertTrue("Expected at least 100 effects, found ${definitions.size}", definitions.size >= 100)
        val ids = definitions.map { it.id }
        assertEquals("Duplicate effect IDs found", ids.size, ids.distinct().size)
    }

    @Test
    fun `all 10 categories are represented in the catalog`() {
        val definitions = loadBuiltInCatalog()
        val categories = definitions.map { EffectCatalog.canonicalCategory(it.category) }.toSet()
        val expected = setOf(
            EffectCatalog.CAT_BLUR,
            EffectCatalog.CAT_CAMERA,
            EffectCatalog.CAT_MOTION,
            EffectCatalog.CAT_GLITCH,
            EffectCatalog.CAT_LIGHT,
            EffectCatalog.CAT_FILM,
            EffectCatalog.CAT_DISTORTION,
            EffectCatalog.CAT_TRAIL,
            EffectCatalog.CAT_CREATIVE,
            EffectCatalog.CAT_PARTICLES,
        )
        assertTrue("Missing categories: ${expected - categories}", categories.containsAll(expected))
    }

    @Test
    fun `every effect definition resolves to an existing shader with valid entry point`() {
        val definitions = loadBuiltInCatalog()
        for (def in definitions) {
            val shaderName = def.shaderFile ?: if (def.shader != "shader.frag") def.shader.removeSuffix(".frag") else def.id
            val code = loadShader(shaderName)
            assertTrue("Shader for ${def.id} ($shaderName) must contain reclyEffect", code.contains("vec4 reclyEffect("))
        }
    }

    @Test
    fun `effect parameters have valid finite bounds and defaults`() {
        val definitions = loadBuiltInCatalog()
        for (def in definitions) {
            assertTrue("Too many parameters in ${def.id}", def.parameters.size <= 16)
            for (param in def.parameters) {
                assertTrue("Parameter ${param.id} in ${def.id} min must be < max", param.min < param.max)
                assertTrue("Parameter ${param.id} in ${def.id} default out of bounds", param.default in param.min..param.max)
                assertTrue("Parameter ${param.id} name cannot be blank", param.name.isNotBlank())
            }
        }
    }

    @Test
    fun `search filter finds effects by query and category`() {
        val definitions = loadBuiltInCatalog()
        
        // Search by name
        val glowResults = EffectCatalog.filter(definitions, EffectCatalog.CATEGORY_ALL, "Glow")
        assertTrue(glowResults.any { it.name.contains("Glow", ignoreCase = true) })

        // Filter by category
        val blurResults = EffectCatalog.filter(definitions, EffectCatalog.CAT_BLUR)
        assertTrue(blurResults.all { EffectCatalog.canonicalCategory(it.category) == EffectCatalog.CAT_BLUR })
        assertTrue(blurResults.size >= 10)

        // Filter by favorites
        val favs = setOf("recly_shake", "rain", "motion_trail")
        val favResults = EffectCatalog.filter(definitions, EffectCatalog.CATEGORY_FAVORITES, favoriteIds = favs)
        assertEquals(3, favResults.size)

        // Filter by recents
        val recent = listOf("rain", "smoke", "lens_blur")
        val recentResults = EffectCatalog.filter(definitions, EffectCatalog.CATEGORY_RECENT, recentIds = recent)
        assertEquals(listOf("rain", "smoke", "lens_blur"), recentResults.map { it.id })
    }

    @Test
    fun `canonical category normalizes localized and legacy category names`() {
        assertEquals(EffectCatalog.CAT_BLUR, EffectCatalog.canonicalCategory("desfoque"))
        assertEquals(EffectCatalog.CAT_CAMERA, EffectCatalog.canonicalCategory("câmera"))
        assertEquals(EffectCatalog.CAT_MOTION, EffectCatalog.canonicalCategory("movimento"))
        assertEquals(EffectCatalog.CAT_LIGHT, EffectCatalog.canonicalCategory("Luz"))
        assertEquals(EffectCatalog.CAT_FILM, EffectCatalog.canonicalCategory("Cinema"))
        assertEquals(EffectCatalog.CAT_DISTORTION, EffectCatalog.canonicalCategory("Distorsao"))
        assertEquals(EffectCatalog.CAT_TRAIL, EffectCatalog.canonicalCategory("Temporal"))
        assertEquals(EffectCatalog.CAT_CREATIVE, EffectCatalog.canonicalCategory("Criativo"))
        assertEquals(EffectCatalog.CAT_PARTICLES, EffectCatalog.canonicalCategory("Gaming"))
    }
}
