package com.termex.replay15.editor

import com.termex.replay15.editor.animation.AnimationRepository
import com.termex.replay15.editor.assets.AssetRepository
import com.termex.replay15.editor.assets.EffectDefinition
import com.termex.replay15.editor.stickers.StickerCatalog
import java.io.File
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

/** Guards the generated library (tools/library): size targets plus the runtime rules each asset must satisfy. */
class LibraryAssetsTest {
    private fun assets(relative: String): File =
        sequenceOf(File("src/main/assets/editor/$relative"), File("editor-engine/src/main/assets/editor/$relative"))
            .firstOrNull { it.exists() } ?: error("missing assets/editor/$relative")

    @Test
    fun `animation library has 300 plus presets that all parse`() {
        val files = assets("animations").listFiles { f -> f.extension == "json" }!!
        val presets = files.map { AnimationRepository.parse(it.readText()) }
        assertTrue("Expected 300+ animations, found ${presets.size}", presets.size >= 300)
        assertEquals("Duplicate animation ids", presets.size, presets.map { it.id }.distinct().size)
    }

    @Test
    fun `effect library has 300 new effects whose shaders satisfy the runtime shader rules`() {
        val array = JSONArray(assets("effects.json").readText())
        val definitions = List(array.length()) { EffectDefinition.parse(array.getJSONObject(it).toString()) }
        val library = definitions.filter { it.shader.startsWith("recly_lib_") }
        assertTrue("Expected 300+ library effects, found ${library.size}", library.size >= 300)
        for (shader in library.map { it.shader }.distinct()) {
            val source = assets("effects/$shader").readText()
            AssetRepository.validateShader(source)
        }
    }

    @Test
    fun `sticker library has 300 plus lottie stickers backed by shipped files`() {
        val library = StickerCatalog.all().filter { it.uri.contains("/lottie/lib/") }
        assertTrue("Expected 300+ stickers, found ${library.size}", library.size >= 300)
        for (sticker in library) {
            val file = assets("lottie/lib/${sticker.uri.substringAfterLast('/')}")
            val json = org.json.JSONObject(file.readText())
            assertTrue("${sticker.id} has layers", json.getJSONArray("layers").length() > 0)
        }
    }
}
