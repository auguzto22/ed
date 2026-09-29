package com.termex.replay15.editor

import com.termex.replay15.editor.assets.EffectPreset
import com.termex.replay15.editor.assets.EffectPresetNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EffectPresetTest {
    @Test fun preservesOrderedMultiPassPresetNodes() {
        val preset = EffectPreset(
            "cyber", "Cyber", "Gaming", listOf(
                EffectPresetNode("recly_rgb", intensity = .7f, values = mapOf("distance" to 8f)),
                EffectPresetNode("recly_glow", version = 2, intensity = .4f),
            ),
        )
        assertEquals("cyber", preset.id)
        assertEquals(listOf("recly_rgb", "recly_glow"), preset.nodes.map { it.assetId })
        assertEquals(8f, preset.nodes.first().values["distance"])
        assertEquals(2, preset.nodes.last().version)
    }

    @Test fun rejectsInvalidPresetData() {
        assertThrows(IllegalArgumentException::class.java) {
            EffectPreset("Invalid ID", "X", "X", listOf(EffectPresetNode("recly_rgb")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EffectPresetNode("recly_rgb", intensity = 2f)
        }
    }
}
