package com.termex.replay15.editor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShaderContractTest {
    private fun shader(name: String): String {
        val relative = "src/main/assets/editor/effects/$name.frag"
        return sequenceOf(File(relative), File("app/$relative"))
            .firstOrNull(File::isFile)
            ?.readText()
            ?: error("Shader fixture not found: $name")
    }

    @Test
    fun `gaussian preserves constant rgba energy`() {
        val values = Regex("\\*\\s*(0\\.\\d+)")
            .findAll(shader("recly_gaussian"))
            .map { it.groupValues[1].toDouble() }
            .toList()
        assertEquals(listOf(0.206185, 0.110457, 0.110457, 0.087997, 0.087997), values)
        val total = values[0] + 2 * values[1] + 2 * values[2] + 2 * values[3] + 2 * values[4]
        assertEquals(1.0, total, 0.00001)
    }

    @Test
    fun `ripple measures radial distance in aspect corrected visual space`() {
        val source = shader("recly_ripple")
        assertTrue(source.contains("uResolution.x / uResolution.y"))
        assertTrue(source.contains("length(delta)"))
    }

    @Test
    fun `rgb split owns its edge policy and glow has a soft threshold`() {
        val rgb = shader("recly_rgb")
        assertTrue(rgb.contains("clamp(uv+d,0.0,1.0)"))
        assertTrue(rgb.contains("clamp(uv-d,0.0,1.0)"))
        assertTrue(shader("recly_glow").contains("smoothstep"))
    }

    @Test
    fun `studio lut strength is a realtime shader uniform`() {
        val relative = "src/main/res/raw/studio_fragment.glsl"
        val source = sequenceOf(File(relative), File("app/$relative"))
            .first(File::isFile)
            .readText()
        assertTrue(source.contains("uniform float uLutStrength"))
        assertTrue(source.contains("mix(rgb, lookup(rgb), uLutStrength)"))
    }

    @Test
    fun `effect footer applies nondestructive mask and blend after reusable shader`() {
        val relative = "src/main/res/raw/effect_footer.glsl"
        val source = sequenceOf(File(relative), File("app/$relative")).first(File::isFile).readText()
        assertTrue(source.contains("reclyEffect(vUv)"))
        assertTrue(source.contains("uEffectMask"))
        assertTrue(source.contains("uBlendMode"))
        assertTrue(source.contains("uIntensity * maskAmount"))
    }

    @Test
    fun `professional minimum shader set is packaged`() {
        val names = listOf("recly_zoom_blur", "recly_bloom", "recly_halation", "recly_chromatic",
            "recly_glitch", "recly_punch_zoom", "recly_film_grain", "recly_flicker",
            "recly_fisheye", "recly_mosaic", "recly_sharpen", "recly_crt",
            "recly_blur_engine", "recly_motion_engine", "recly_glitch_engine", "recly_light_engine",
            "recly_film_engine", "recly_distortion_engine", "recly_temporal_engine",
            "recly_creative_engine", "recly_particle_engine")
        names.forEach { assertTrue("Missing $it", shader(it).contains("vec4 reclyEffect(")) }
    }

    @Test
    fun `distortion engine normalizes aspect ratio`() {
        val source = shader("recly_distortion_engine")
        assertTrue(source.contains("uResolution.x / uResolution.y"))
    }

    @Test
    fun `temporal engine utilizes history samplers`() {
        val source = shader("recly_temporal_engine")
        assertTrue(source.contains("uHistory0"))
        assertTrue(source.contains("uHistory1"))
        assertTrue(source.contains("uHistory2"))
    }
}
