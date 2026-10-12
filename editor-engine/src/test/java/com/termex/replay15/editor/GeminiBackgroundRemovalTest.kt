package com.termex.replay15.editor

import com.recly.editor.engine.BuildConfig
import com.termex.replay15.editor.ai.GeminiApiKeyConfig
import com.termex.replay15.editor.backgroundremoval.GeminiSegmentationParser
import com.termex.replay15.editor.backgroundremoval.GeminiSegmentationRequest
import com.termex.replay15.editor.domain.BackgroundMode
import com.termex.replay15.editor.domain.BackgroundRemovalEffect
import com.termex.replay15.editor.domain.BackgroundRemovalProvider
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.SECOND
import com.termex.replay15.editor.domain.SegmentationQuality
import com.termex.replay15.editor.domain.StickerClip
import com.termex.replay15.editor.domain.VideoClip
import com.termex.replay15.editor.project.ProjectCodec
import com.termex.replay15.editor.backgroundremoval.SegmentationOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class GeminiBackgroundRemovalTest {

    @Test
    fun geminiApiKeyConfigProvidesConfiguredKey() {
        val key = GeminiApiKeyConfig.getApiKey()
        assertEquals(BuildConfig.GEMINI_API_KEY.takeIf { it.isNotBlank() } ?: GeminiApiKeyConfig.DEFAULT_KEY, key)
    }

    @Test
    fun parsesDocumentedFormatWithXyPolygonPoints() {
        // Wide, short subject: x spans 100..900, y spans 400..600. Read as [y, x] it would be tall.
        val json = """
            {"boxes": [{"box_2d": [400, 100, 600, 900], "label": "skate",
              "mask": [[100, 400], [900, 400], [900, 600], [100, 600]]}]}
        """.trimIndent()

        val result = GeminiSegmentationParser.parseSegmentation(json, 100, 100, 12345L)
        assertNotNull(result)
        assertEquals(12345L, result!!.timestampUs)
        assertEquals(SegmentationOrigin.MEASURED, result.origin)
        assertTrue("Centro é o objeto", result.mask[50 * 100 + 50] > 0.5f)
        assertTrue("Borda esquerda no meio é objeto", result.mask[50 * 100 + 15] > 0.5f)
        assertTrue("Topo no meio é fundo", result.mask[15 * 100 + 50] < 0.5f)
        assertTrue("Canto é fundo", result.mask[2 * 100 + 2] < 0.5f)
    }

    @Test
    fun unionOfSeveralSubjectsIsKeptAndRestIsRemoved() {
        val json = """
            [{"box_2d": [0, 0, 400, 400], "label": "a", "mask": [[0, 0], [400, 0], [400, 400], [0, 400]]},
             {"box_2d": [600, 600, 1000, 1000], "label": "b", "mask": [[600, 600], [1000, 600], [1000, 1000], [600, 1000]]}]
        """.trimIndent()
        val subjects = GeminiSegmentationParser.parseSubjects(json)
        assertEquals(2, subjects.size)
        val mask = GeminiSegmentationParser.rasterize(subjects, 10, 10)
        assertEquals(1f, mask[1 * 10 + 1], 0f)
        assertEquals(1f, mask[8 * 10 + 8], 0f)
        assertEquals(0f, mask[5 * 10 + 5], 0f)
    }

    @Test
    fun boxRelativePolygonIsMappedIntoTheBox() {
        // Points span the whole 0..1000 range but the box is the right half: box-relative.
        val json = """
            {"boxes": [{"box_2d": [0, 500, 1000, 1000], "label": "x",
              "mask": [[0, 0], [1000, 0], [1000, 1000], [0, 1000]]}]}
        """.trimIndent()
        val subject = GeminiSegmentationParser.parseSubjects(json).single()
        assertTrue(subject.polygon.all { it.first >= 0.5f - 1e-4f })
    }

    @Test
    fun boundingBoxWithoutContourIsNotACutout() {
        // No invented ellipse: a box alone must never be treated as a removed background.
        val json = """{"boxes": [{"box_2d": [200, 200, 800, 800], "label": "x", "mask": []}]}"""
        assertTrue(GeminiSegmentationParser.parseSubjects(json).isEmpty())
        assertNull(GeminiSegmentationParser.parseSegmentation(json, 100, 100, 0L))
    }

    @Test
    fun geminiSegmentationParserRejectsInvalidData() {
        assertNull(GeminiSegmentationParser.parseSegmentation("not json", 100, 100, 0L))
    }

    @Test
    fun requestUsesDocumentedInteractionsShape() {
        val payload = GeminiSegmentationRequest.build("AAAA")
        val input = payload.getJSONArray("input")
        assertEquals("text", input.getJSONObject(0).getString("type"))
        assertEquals("image", input.getJSONObject(1).getString("type"))
        assertEquals("AAAA", input.getJSONObject(1).getString("data"))
        assertEquals("application/json", payload.getJSONObject("response_format").getString("mime_type"))
        assertEquals("minimal", payload.getJSONObject("generation_config").getString("thinking_level"))
    }

    @Test
    fun outputTextIsReadFromInteractionResponse() {
        val response = org.json.JSONObject().put("output_text", "[]")
        assertEquals("[]", GeminiSegmentationRequest.outputText(response))
    }

    @Test
    fun roundTripWithGeminiProviderPreserved() {
        val settings = BackgroundRemovalEffect(
            enabled = true,
            mode = BackgroundMode.REMOVE,
            threshold = 0.6f,
            feather = 0.15f,
            edgeSmoothing = 0.4f,
            quality = SegmentationQuality.HIGH,
            provider = BackgroundRemovalProvider.GEMINI,
        )
        val video = VideoClip(
            uri = "file:///clip.mp4",
            name = "Test",
            sourceUs = SECOND,
            width = 1280,
            height = 720,
            backgroundRemoval = settings,
        )
        val sticker = StickerClip(
            uri = "file:///sticker.png",
            name = "Overlay",
            startUs = 0L,
            endUs = SECOND,
            backgroundRemoval = settings.copy(provider = BackgroundRemovalProvider.AUTO),
        )

        val project = Project(videos = listOf(video), stickers = listOf(sticker))
        val output = ByteArrayOutputStream()
        ProjectCodec.write(project, output)

        val restored = ProjectCodec.read(output.toByteArray().inputStream())
        assertEquals(BackgroundRemovalProvider.GEMINI, restored.videos.single().backgroundRemoval.provider)
        assertEquals(BackgroundRemovalProvider.AUTO, restored.stickers.single().backgroundRemoval.provider)
        assertEquals(project, restored)
    }
}
