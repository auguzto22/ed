package com.termex.replay15.editor

import com.termex.replay15.editor.assets.EffectBlendMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BlendModeTest {
    @Test
    fun `blend mode ordinals preserve existing projects and expose professional modes`() {
        assertEquals(0, EffectBlendMode.NORMAL.ordinal)
        assertEquals(1, EffectBlendMode.ADD.ordinal)
        assertEquals(2, EffectBlendMode.MULTIPLY.ordinal)
        assertEquals(3, EffectBlendMode.SCREEN.ordinal)
        assertEquals(4, EffectBlendMode.OVERLAY.ordinal)
        assertEquals(listOf("LIGHTEN", "DARKEN", "DIFFERENCE", "SOFT_LIGHT", "HARD_LIGHT"),
            EffectBlendMode.entries.drop(5).map { it.name })
    }

    @Test
    fun `shared effect footer implements every blend mode`() {
        val source = File("src/main/res/raw/effect_footer.glsl").readText()
        listOf("max(original.rgb, effected.rgb)", "min(original.rgb, effected.rgb)",
            "abs(original.rgb - effected.rgb)", "softHighlight", "step(0.5, effected.rgb)")
            .forEach { token -> assertTrue("Missing $token", source.contains(token)) }
    }
}
