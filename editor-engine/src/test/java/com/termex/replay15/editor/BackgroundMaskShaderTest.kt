package com.termex.replay15.editor

import com.termex.replay15.editor.backgroundremoval.BackgroundMaskShader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundMaskShaderTest {
    @Test fun edgeScaleCountsMaskTexelsPerFinalPixel() {
        // 2000x2286 photo, 1344x1536 mask, fitted into a 720x822 canvas: ~2.1 mask texels per output pixel.
        assertEquals(1536f / 822f, BackgroundMaskShader.edgeScale(1344, 1536, 2000, 2286, 720, 822), .02f)
        // Upscaled or unknown sizes never shrink the edge below one texel.
        assertEquals(1f, BackgroundMaskShader.edgeScale(512, 512, 512, 512, 1080, 1080), 0f)
        assertEquals(1f, BackgroundMaskShader.edgeScale(0, 0, 512, 512, 1080, 1080), 0f)
        // Filling crops instead of fitting, so less downscaling happens.
        assertTrue(BackgroundMaskShader.edgeScale(1536, 1024, 1536, 1024, 720, 1280, fill = true) <
            BackgroundMaskShader.edgeScale(1536, 1024, 1536, 1024, 720, 1280, fill = false))
    }

    @Test fun shaderDeclaresCoverageAndUsesEdgeScale() {
        val source = BackgroundMaskShader.functions("uTexture", "uMaskTexture")
        assertTrue(source.contains("reclyCoverage") && source.contains("uEdgeScale"))
    }
}
