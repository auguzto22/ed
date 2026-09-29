package com.termex.replay15.editor

import com.termex.replay15.editor.backgroundremoval.SegmentationMaskCache
import com.termex.replay15.editor.backgroundremoval.SegmentationResult
import com.termex.replay15.editor.domain.BackgroundMode
import com.termex.replay15.editor.domain.BackgroundRemovalEffect
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.SegmentationQuality
import com.termex.replay15.editor.domain.SECOND
import com.termex.replay15.editor.domain.StickerClip
import com.termex.replay15.editor.domain.VideoClip
import com.termex.replay15.editor.project.ProjectCodec
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BackgroundRemovalTest {
    @Test
    fun settingsRoundTripForVideoAndSticker() {
        val settings = BackgroundRemovalEffect(
            enabled = true,
            mode = BackgroundMode.REMOVE,
            threshold = .56f,
            feather = .12f,
            edgeSmoothing = .7f,
            quality = SegmentationQuality.HIGH,
        )
        val video = VideoClip(uri = "file:///video.mp4", name = "Video", sourceUs = SECOND, width = 1920, height = 1080,
            backgroundRemoval = settings)
        val sticker = StickerClip(uri = "file:///photo.png", name = "Photo", startUs = 0, endUs = SECOND,
            backgroundRemoval = settings)
        val project = Project(videos = listOf(video), stickers = listOf(sticker))
        val encoded = ByteArrayOutputStream().also { ProjectCodec.write(project, it) }

        assertEquals(project, ProjectCodec.read(encoded.toByteArray().inputStream()))
    }

    @Test
    fun cacheIsBoundedAndReturnsNearestMask() {
        val cache = SegmentationMaskCache(maxEntries = 2)
        val first = SegmentationResult(2, 1, 100, floatArrayOf(0f, 1f))
        val second = SegmentationResult(2, 1, 200, floatArrayOf(.2f, .8f))
        val third = SegmentationResult(2, 1, 300, floatArrayOf(.4f, .6f))
        cache.put(first); cache.put(second); cache.put(third)

        assertEquals(2, cache.size())
        assertSame(second, cache.nearest(210))
    }
}
