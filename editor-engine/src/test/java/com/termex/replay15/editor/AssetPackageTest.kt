package com.termex.replay15.editor

import com.termex.replay15.editor.assets.*
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.project.ProjectCodec
import java.io.*
import java.nio.file.Files
import java.util.zip.*
import org.junit.Assert.*
import org.junit.Test

class AssetPackageTest {
    private fun archive(dir: File, name: String, data: ByteArray): File = File(dir, "test.zip").also { file ->
        ZipOutputStream(file.outputStream()).use { zip -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
    }
    @Test fun zipTraversalExecutablesAndOversizedShadersAreRejected() {
        val dir = Files.createTempDirectory("recly-assets").toFile()
        try {
            for ((name, size) in listOf("../outside" to 1, "engine.dex" to 1, "shader.frag" to 70_000)) {
                val destination = Files.createTempDirectory(dir.toPath(), "extract-").toFile()
                val file = archive(dir, name, ByteArray(size))
                assertThrows(IllegalArgumentException::class.java) { AssetValidator.extract(file, destination) }
            }
            assertFalse(File(dir, "outside").exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun checksumDetectsAlteredDownload() {
        val dir = Files.createTempDirectory("recly-checksum").toFile()
        try {
            val file = File(dir, "asset"); file.writeText("abc")
            val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            AssetValidator.verify(file, 3, sha)
            file.writeText("abd")
            assertThrows(IllegalArgumentException::class.java) { AssetValidator.verify(file, 3, sha) }
        } finally { dir.deleteRecursively() }
    }
    @Test fun effectOrderValuesAndBypassSurviveProjectSerialization() {
        val animated = mapOf("radius" to listOf(
            EffectValueKeyframe(0, 2f, Easing.BEZIER, CubicBezier(.1f, -.2f, .7f, 1.4f)),
            EffectValueKeyframe(SECOND, 18f, Easing.SPRING)),
            EffectInstance.INTENSITY to listOf(EffectValueKeyframe(0, 0f), EffectValueKeyframe(SECOND, 1f)))
        val fx = listOf(EffectInstance("one", "recly_glow", values = mapOf("radius" to 12f), keyframes = animated), EffectInstance("two", "recly_rgb", enabled = false))
        val clip = VideoClip(uri = "file:///video", name = "video", sourceUs = SECOND, width = 1280, height = 720, effects = fx)
        val layer = VideoTrack(clips = listOf(TimedVideoClip(SECOND, clip.copy(id = newId()))))
        val p = Project(videos = listOf(clip), videoTracks = listOf(layer))
        val output = ByteArrayOutputStream(); ProjectCodec.write(p, output)
        assertEquals(p, ProjectCodec.read(output.toByteArray().inputStream()))
    }

    @Test fun effectParameterInterpolationUsesEasingAndStaticFallback() {
        val effect = EffectInstance("one", "recly_glow", intensity = .7f, values = mapOf("radius" to 12f), keyframes = mapOf(
            "radius" to listOf(EffectValueKeyframe(0, 0f, Easing.EASE_IN), EffectValueKeyframe(SECOND, 20f))))
        assertEquals(12f, effect.valueAt("threshold", SECOND / 2, 12f), 0f)
        assertEquals(5f, effect.valueAt("radius", SECOND / 2, 12f), .001f)
        assertEquals(0f, effect.valueAt("radius", -1, 12f), 0f)
        assertEquals(20f, effect.valueAt("radius", 2 * SECOND, 12f), 0f)
    }

    @Test fun effectRangeAndMaskAnimationRemainNondestructiveMetadata() {
        val effect = EffectInstance("one", "recly_glow", startTimeUs = SECOND, endTimeUs = 2 * SECOND,
            mask = EffectMask(EffectMaskShape.RECTANGLE, feather = .2f),
            blendMode = EffectBlendMode.OVERLAY,
            keyframes = mapOf(EffectInstance.MASK_X to listOf(
                EffectValueKeyframe(SECOND, -.2f), EffectValueKeyframe(2 * SECOND, .2f))))
        assertFalse(effect.activeAt(SECOND - 1))
        assertTrue(effect.activeAt(SECOND))
        assertFalse(effect.activeAt(2 * SECOND))
        assertEquals(0f, effect.valueAt(EffectInstance.MASK_X, SECOND + SECOND / 2, 0f), .001f)
        assertEquals(EffectMaskShape.RECTANGLE, effect.mask?.shape)
    }

    @Test fun invalidEffectAnimationsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { EffectValueKeyframe(0, Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { EffectInstance("one", "recly_glow", keyframes = mapOf(
            "radius" to listOf(EffectValueKeyframe(2, 1f), EffectValueKeyframe(1, 2f)))) }
        assertThrows(IllegalArgumentException::class.java) { EffectInstance("one", "recly_glow", keyframes = mapOf(
            "bad-name" to listOf(EffectValueKeyframe(0, 1f)))) }
        assertThrows(IllegalArgumentException::class.java) {
            VideoClip(uri = "file:///video", name = "v", sourceUs = SECOND, width = 1, height = 1,
                effects = listOf(EffectInstance("one", "recly_glow", keyframes = mapOf("radius" to listOf(EffectValueKeyframe(2 * SECOND, 1f))))))
        }
    }
}
