package com.termex.replay15.editor.resources

import com.termex.replay15.editor.ai.AiResourceSelector
import com.termex.replay15.editor.animation.AnimationCatalog
import com.termex.replay15.editor.animation.AnimationCategory
import com.termex.replay15.editor.assets.EffectCatalog
import com.termex.replay15.editor.assets.TransitionCatalog
import com.termex.replay15.editor.domain.TextAnimation
import com.termex.replay15.editor.font.FontCatalog
import com.termex.replay15.editor.font.FontCategory
import com.termex.replay15.editor.licenses.ResourceCreditsRegistry
import com.termex.replay15.editor.licenses.ResourceLicenseMetadata
import com.termex.replay15.editor.packs.ResourcePackManager
import com.termex.replay15.editor.presets.ReclyCompositePreset
import com.termex.replay15.editor.stickers.StickerCatalog
import com.termex.replay15.editor.stickers.StickerCategory
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class ResourceLibraryIntegrationTest {

    @Before
    fun setUp() {
        ResourceCreditsRegistry.clear()
        // Pre-register test credits
        ResourceCreditsRegistry.register(
            ResourceLicenseMetadata(
                id = "test_ofl_font",
                name = "Test OFL Font",
                author = "Type Foundry",
                source = "Google Fonts",
                license = "OFL-1.1",
                licenseUrl = "https://scripts.sil.org/OFL",
                category = "Font"
            )
        )
    }

    @Test
    fun testLicensingAttributionAndExport() {
        assertTrue(ResourceCreditsRegistry.all().isNotEmpty())
        val md = ResourceCreditsRegistry.formatMarkdownCredits()
        assertTrue(md.contains("Créditos e Licenças de Recursos do Recly"))
        assertTrue(md.contains("Test OFL Font"))

        val filtered = ResourceCreditsRegistry.search("OFL-1.1")
        assertTrue(filtered.any { it.id == "test_ofl_font" })
    }

    @Test
    fun testFontCatalogHasOver100FontsWithValidMetadata() {
        val relative = "src/main/assets/fonts/font_catalog.json"
        val file = sequenceOf(File(relative), File("editor-engine/$relative"), File("app/$relative"))
            .firstOrNull(File::isFile)
        assertNotNull("font_catalog.json must exist", file)

        val array = org.json.JSONArray(file!!.readText())
        assertTrue("Expected 100+ remote fonts, found ${array.length()}", array.length() >= 100)

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val id = obj.getString("id")
            val displayName = obj.getString("displayName")
            val license = obj.getString("license")
            assertTrue("Font ID blank", id.isNotBlank())
            assertTrue("DisplayName blank for $id", displayName.isNotBlank())
            assertTrue("License blank for $id", license.isNotBlank())
        }

        // Test search and builtIns
        val poppins = FontCatalog.search("Poppins")
        assertTrue(poppins.any { it.id.contains("poppins") || it.family.contains("Poppins") })

        val outfit = FontCatalog.builtIns.firstOrNull { it.displayName == "Outfit" }
        assertNotNull(outfit)
    }

    @Test
    fun testTransitionsCoverAllCanonicalCategories() {
        val relative = "src/main/assets/editor/transitions.json"
        val file = sequenceOf(File(relative), File("editor-engine/$relative"), File("app/$relative"))
            .firstOrNull(File::isFile)
        assertNotNull("transitions.json should exist", file)

        val array = org.json.JSONArray(file!!.readText())
        assertTrue("Expected 80+ transitions, found ${array.length()}", array.length() >= 80)

        val categories = mutableSetOf<String>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val cat = TransitionCatalog.canonicalCategory(obj.getString("category"))
            categories.add(cat)
        }

        val required = setOf(
            TransitionCatalog.CAT_BASIC,
            TransitionCatalog.CAT_FADE,
            TransitionCatalog.CAT_ZOOM,
            TransitionCatalog.CAT_SLIDE,
            TransitionCatalog.CAT_WIPE,
            TransitionCatalog.CAT_BLUR,
            TransitionCatalog.CAT_DISTORTION,
            TransitionCatalog.CAT_GLITCH,
            TransitionCatalog.CAT_RGB,
            TransitionCatalog.CAT_LIGHT,
            TransitionCatalog.CAT_FILM,
            TransitionCatalog.CAT_CINEMATIC,
            TransitionCatalog.CAT_3D,
            TransitionCatalog.CAT_SOCIAL,
            TransitionCatalog.CAT_GAMING
        )

        for (req in required) {
            assertTrue("Canonical category $req missing from transitions", categories.contains(req))
        }
    }

    @Test
    fun testEffectCatalogAllCanonicalCategories() {
        val categories = EffectCatalog.CANONICAL_CATEGORIES
        val expected = listOf(
            EffectCatalog.CAT_COLOR,
            EffectCatalog.CAT_BLUR,
            EffectCatalog.CAT_DISTORTION,
            EffectCatalog.CAT_LIGHT,
            EffectCatalog.CAT_GLITCH,
            EffectCatalog.CAT_RGB,
            EffectCatalog.CAT_MOTION,
            EffectCatalog.CAT_CAMERA,
            EffectCatalog.CAT_CINEMATIC,
            EffectCatalog.CAT_GAMING,
            EffectCatalog.CAT_RETRO,
            EffectCatalog.CAT_VHS,
            EffectCatalog.CAT_FILM,
            EffectCatalog.CAT_SOCIAL
        )

        for (exp in expected) {
            assertTrue("Effect category $exp missing", categories.contains(exp))
        }
    }

    @Test
    fun testTextAnimationsAndUniversalCatalog() {
        val animations = AnimationCatalog.all()
        assertTrue(animations.isNotEmpty())

        val textAnims = TextAnimation.values()
        val names = textAnims.map { it.name }
        assertTrue(names.contains("TYPEWRITER"))
        assertTrue(names.contains("SCALE"))
        assertTrue(names.contains("GLITCH"))
        assertTrue(names.contains("ELASTIC"))
        assertTrue(names.contains("WAVE"))
        assertTrue(names.contains("TRACKING"))

        val entrance = AnimationCatalog.byCategory(AnimationCategory.ENTRANCE)
        assertTrue(entrance.isNotEmpty())
    }

    @Test
    fun testLottieFilesIntegrity() {
        val lottieDir = sequenceOf(
            File("src/main/assets/editor/lottie"),
            File("editor-engine/src/main/assets/editor/lottie"),
            File("app/src/main/assets/editor/lottie")
        ).firstOrNull(File::isDirectory)

        assertNotNull("Lottie directory must exist", lottieDir)
        val files = lottieDir!!.listFiles { f -> f.extension == "json" }
        assertNotNull(files)
        assertTrue("Expected curated lottie files, found ${files?.size}", (files?.size ?: 0) >= 6)

        for (file in files!!) {
            // A zero-byte or truncated asset parses into nothing and the sticker silently
            // renders blank at runtime, so report it as a broken asset rather than a JSON error.
            val text = file.readText()
            assertTrue("Lottie file ${file.name} is empty", text.isNotBlank())
            val json = JSONObject(text)
            assertTrue(
                "Lottie file ${file.name} missing 'v', 'layers' or asset bounds",
                json.has("v") && json.has("layers") && json.has("w") && json.has("h"),
            )
        }
        // Every sticker the catalog advertises must actually resolve to a shipped file, so a
        // curated entry can never point at a missing or empty animation.
        val shipped = files!!.map { it.name }.toSet()
        val advertised = StickerCatalog.all()
            .filter { it.uri.startsWith("asset:///editor/lottie/") }
            .map { it.uri.substringAfterLast('/') }
        assertTrue("Lottie stickers advertised", advertised.isNotEmpty())
        assertEquals("Lottie stickers without a shipped file", emptySet<String>(), advertised.filterNot { it in shipped }.toSet())
    }

    @Test
    fun testStickerCatalogCuratedItems() {
        val stickers = StickerCatalog.all()
        assertTrue("Sticker catalog empty", stickers.isNotEmpty())

        val social = StickerCatalog.byCategory(StickerCategory.SOCIAL)
        assertTrue("Social stickers missing", social.isNotEmpty())

        val clip = StickerCatalog.createClip(
            stickerId = social.first().id,
            startUs = 0L,
            durationUs = 3_000_000L
        )
        assertNotNull(clip)
        assertEquals(social.first().name, clip.name)
    }

    @Test
    fun testResourcePackSystemAndFallback() {
        val packs = ResourcePackManager.getAllPacks()
        assertTrue(packs.isNotEmpty())
        assertTrue(packs.any { it.id == "pack_base_core" })
        assertTrue(packs.any { it.id == "pack_social" })
        assertTrue(packs.any { it.id == "pack_gaming" })

        // Test fallback resolution
        val font = ResourcePackManager.resolveFontWithFallback("non_existent_custom_font")
        assertNotNull(font)
        assertEquals("roboto_regular", font)

        val trans = ResourcePackManager.resolveTransitionWithFallback("unknown_trans_id")
        assertEquals("recly_fade", trans)

        val eff = ResourcePackManager.resolveEffectWithFallback("non_existent_filter")
        assertNull(eff)
    }

    @Test
    fun testCompositePresets() {
        val presets = ReclyCompositePreset.CURATED_PRESETS
        assertTrue(presets.isNotEmpty())

        val tiktok = presets.first { it.id == "preset_tiktok" }
        assertEquals("poppins", tiktok.fontId)
        assertEquals(TextAnimation.POP, tiktok.textAnimation)
        assertEquals("rgb_split", tiktok.effectId)

        val textClip = tiktok.applyToTextClip(
            text = "Hello Recly",
            startTimeUs = 0L,
            durationUs = 2_000_000L
        )
        assertEquals("poppins", textClip.fontFamily)
        assertEquals(TextAnimation.POP, textClip.animation)
    }

    @Test
    fun testAiResourceSelector() {
        val impactResources = AiResourceSelector.selectResourcesForScene(listOf("impact", "beat"))
        assertTrue(impactResources.transitionId.isNotBlank())
        assertNotNull(impactResources.textAnimation)

        val cinematicResources = AiResourceSelector.selectResourcesForScene(listOf("cinematic", "drone"))
        assertNotNull(cinematicResources.fontId)
    }
}
