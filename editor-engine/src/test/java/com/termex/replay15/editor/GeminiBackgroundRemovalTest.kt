package com.termex.replay15.editor

import com.recly.editor.engine.BuildConfig
import com.termex.replay15.editor.ai.GeminiApiKeyConfig
import com.termex.replay15.editor.backgroundremoval.GeminiSegmentationParser
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
import org.junit.Assert.assertFalse
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
    fun geminiSegmentationParserParsesPolygonContours() {
        val json = """
            {
                "box_2d": [100, 100, 900, 900],
                "polygons": [
                    [
                        [100, 100],
                        [100, 900],
                        [900, 900],
                        [900, 100]
                    ]
                ]
            }
        """.trimIndent()

        val result = GeminiSegmentationParser.parseSegmentation(json, 100, 100, 12345L)
        assertNotNull(result)
        assertEquals(100, result!!.width)
        assertEquals(100, result.height)
        assertEquals(12345L, result.timestampUs)
        assertEquals(10000, result.mask.size)

        // Center pixel (50, 50) should be inside polygon (mask confidence > 0.5)
        val centerIdx = 50 * 100 + 50
        assertTrue("Center should be foreground", result.mask[centerIdx] > 0.5f)

        // Top-left corner outside (2, 2) should be background (confidence < 0.5)
        val outsideIdx = 2 * 100 + 2
        assertTrue("Outside should be background", result.mask[outsideIdx] < 0.5f)
    }

    @Test
    fun geminiSegmentationParserParsesBoundingBoxFallback() {
        val json = """
            {
                "box_2d": [200, 200, 800, 800]
            }
        """.trimIndent()

        val result = GeminiSegmentationParser.parseSegmentation(json, 100, 100, 67890L)
        assertNotNull(result)
        assertEquals(100, result!!.width)
        assertEquals(100, result.height)
        assertEquals(67890L, result.timestampUs)

        // Center pixel (50, 50) should be foreground
        val centerIdx = 50 * 100 + 50
        assertTrue("Center should be foreground", result.mask[centerIdx] > 0.5f)

        // A box-derived ellipse is a geometric guess, not a measured silhouette. It must say so
        // so callers that must not invent subject geometry can refuse it.
        assertEquals(SegmentationOrigin.APPROXIMATED_FROM_BOX, result.origin)
        assertFalse(result.isMeasured)
    }

    @Test
    fun polygonContoursAreReportedAsMeasured() {
        val json = """
            {
                "box_2d": [100, 100, 900, 900],
                "polygons": [[[100, 100], [100, 900], [900, 900], [900, 100]]]
            }
        """.trimIndent()

        val result = GeminiSegmentationParser.parseSegmentation(json, 100, 100, 1L)
        assertNotNull(result)
        assertEquals(SegmentationOrigin.MEASURED, result!!.origin)
        assertTrue(result.isMeasured)
    }

    @Test
    fun geminiSegmentationParserRejectsInvalidData() {
        val result = GeminiSegmentationParser.parseSegmentation("not json", 100, 100, 0L)
        assertNull(result)
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
