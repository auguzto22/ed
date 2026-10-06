package com.termex.replay15.editor

import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.assets.*
import com.termex.replay15.editor.audio.AudioEnhance
import com.termex.replay15.editor.project.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files

class ProjectPersistenceTest {
    private fun sample() = Project(name = "My replay", videos = listOf(VideoClip(uri = "content://video/1", name = "Clip",
        sourceUs = 20 * SECOND, width = 1080, height = 2220, inUs = SECOND, outUs = 19 * SECOND,
        speed = .75f, volume = 1.25f, rotation = 270, flip = true, crop = CropRect(.1f, .2f, .8f, .9f), filter = 4,
        brightness = .12f, contrast = -.2f, saturation = 35f, hue = -20f, lightness = 8f, temperature = .4f,
        filterStrength = .65f, zoom = 1.4f, offsetX = -.2f, offsetY = .15f, fineRotation = 7f,
        opacity = .75f, blur = 3.5f, motion = ClipMotion.PAN_LEFT,
        transitionIn = ClipTransition.FADE_BLACK, transitionOut = ClipTransition.FADE_WHITE,
        enhance = AudioEnhance(noiseReduction = .1f, voiceEnhance = .5f, compression = .75f, normalize = .25f),
        transitionDurationUs = 650_000, audioFadeInUs = 400_000, audioFadeOutUs = 900_000)),
        audio = listOf(AudioClip(uri = "content://audio/2", name = "Music", sourceUs = 30 * SECOND,
            startUs = SECOND, inUs = 2 * SECOND, outUs = 10 * SECOND, volume = .3f,
            fadeInUs = SECOND, fadeOutUs = 2 * SECOND),
            AudioClip(uri = "content://audio/3", name = "Voice", sourceUs = 12 * SECOND,
                startUs = 3 * SECOND, outUs = 8 * SECOND, volume = 1.2f)),
        texts = listOf(TextClip(text = "Replay\n15 seconds", startUs = 3 * SECOND, endUs = 7 * SECOND,
            x = .2f, y = .7f, size = .1f, color = 0xFFFFFF00.toInt(), bold = false,
            fontId = TextFont.CONDENSADA.id, italic = true, underline = true, alignment = TextAlignment.RIGHT,
            opacity = .8f, letterSpacing = .12f, lineSpacing = 1.3f, boxWidth = .7f, rotation = -12f,
            backgroundColor = 0x99000000.toInt(), outlineColor = 0xFFFF00FF.toInt(), outlineWidth = .012f,
            shadow = true, animation = TextAnimation.POP)),
        aspect = 9f / 16, export = ExportSettings(1080, 60, 20_000_000, 320_000),
        stickers = listOf(StickerClip(uri = "content://image/sticker", name = "Logo.webp",
            startUs = SECOND, endUs = 6 * SECOND, x = .8f, y = .2f, size = .18f,
            rotation = 15f, opacity = .7f, flip = true, animation = TextAnimation.SLIDE_UP)))

    @Test fun everyEditablePropertyRoundTrips() {
        val project = sample(); val bytes = ByteArrayOutputStream()
        ProjectCodec.write(project, bytes)
        assertEquals(project, ProjectCodec.read(ByteArrayInputStream(bytes.toByteArray())))
        assertTrue(bytes.size() < 2048)
    }

    @Test fun theAudioTreatmentsSurviveASaveAndReload() {
        val treated = sample().let { base ->
            base.copy(audio = base.audio.mapIndexed { index, clip ->
                clip.copy(enhance = AudioEnhance(
                    noiseReduction = .1f * (index + 1), voiceEnhance = .5f,
                    compression = .75f, normalize = .25f))
            })
        }
        val bytes = ByteArrayOutputStream(); ProjectCodec.write(treated, bytes)
        val restored = ProjectCodec.read(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(treated.audio.map { it.enhance }, restored.audio.map { it.enhance })
    }

    @Test fun aClipWithNoTreatmentComesBackNeutral() {
        val bytes = ByteArrayOutputStream(); ProjectCodec.write(sample(), bytes)
        val restored = ProjectCodec.read(ByteArrayInputStream(bytes.toByteArray()))
        assertTrue(restored.audio.all { it.enhance.isNeutral })
    }

    @Test fun embeddedVideoAudioTreatmentsSurviveASaveAndReload() {
        val base = sample()
        val trackedClip = base.videos.single().copy(id = "upper-video",
            enhance = AudioEnhance(noiseReduction = .3f, voiceEnhance = .6f, compression = .2f, normalize = .9f))
        val project = base.copy(videoTracks = listOf(VideoTrack("upper", listOf(TimedVideoClip(2 * SECOND, trackedClip)))))
        val bytes = ByteArrayOutputStream(); ProjectCodec.write(project, bytes)
        val restored = ProjectCodec.read(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(project.videos.map { it.enhance }, restored.videos.map { it.enhance })
        assertEquals(project.videoTracks.flatMap { track -> track.clips.map { it.clip.enhance } },
            restored.videoTracks.flatMap { track -> track.clips.map { it.clip.enhance } })
    }
    @Test fun audioVolumeAutomationAndClipMimeTypeSurviveASaveAndReload() {
        // Both fields were fully modelled and editable but reached no codec, so saving
        // silently reverted volume automation and blanked the MIME type.
        val base = sample()
        val project = base.copy(
            videos = base.videos.map { it.copy(mimeType = "video/mp4") },
            audio = base.audio.mapIndexed { index, clip -> clip.copy(volumeKeyframes = listOf(
                VolumeKeyframe(0L, .2f),
                VolumeKeyframe(2 * SECOND, 1.4f),
                VolumeKeyframe(4 * SECOND, .05f + index),
            )) },
        )
        val bytes = ByteArrayOutputStream(); ProjectCodec.write(project, bytes)
        val restored = ProjectCodec.read(bytes.toByteArray().inputStream())
        assertEquals(project.videos.map { it.mimeType }, restored.videos.map { it.mimeType })
        assertEquals(project.audio.map { it.volumeKeyframes }, restored.audio.map { it.volumeKeyframes })
    }

    @Test fun aProjectWithoutVolumeAutomationStillLoads() {
        val bytes = ByteArrayOutputStream(); ProjectCodec.write(sample(), bytes)
        val restored = ProjectCodec.read(bytes.toByteArray().inputStream())
        assertTrue(restored.audio.all { it.volumeKeyframes.isEmpty() })
    }

    @Test fun stableFontIdRoundTripsWithoutPersistingAnAssetPath() {
        val project = sample().copy(texts = sample().texts.map { it.copy(fontId = "poppins_semibold") })
        val bytes = ByteArrayOutputStream(); ProjectCodec.write(project, bytes)
        val encoded = bytes.toByteArray()
        assertEquals("poppins_semibold", ProjectCodec.read(encoded.inputStream()).texts.single().fontId)
        assertFalse(String(encoded, Charsets.ISO_8859_1).contains("poppins_semibold.ttf"))
    }
    @Test fun reclyOriginalFontIdSurvivesRoundTrip() {
        val project = sample().copy(texts = sample().texts.map { it.copy(fontId = "recly_gothic_regular") })
        val bytes = ByteArrayOutputStream(); ProjectCodec.write(project, bytes)
        assertEquals("recly_gothic_regular", ProjectCodec.read(bytes.toByteArray().inputStream()).texts.single().fontId)
    }
    @Test fun effectMaskRangeBlendAndAnimationRoundTrip() {
        val effect = EffectInstance("fx-1", "recly_bloom", intensity = .6f,
            values = mapOf("radius" to 9f),
            keyframes = mapOf(EffectInstance.INTENSITY to listOf(EffectValueKeyframe(SECOND, .2f))),
            startTimeUs = SECOND, endTimeUs = 5 * SECOND,
            mask = EffectMask(EffectMaskShape.ELLIPSE, x = .1f, y = -.1f, size = .6f,
                aspect = 1.4f, rotation = 12f, feather = .12f, invert = true, opacity = .8f),
            blendMode = EffectBlendMode.SCREEN, groupId = "film-preset-1", groupIntensity = .55f)
        val project = sample().copy(
            videos = listOf(sample().videos.single().copy(effects = listOf(effect))),
            adjustmentClips = listOf(AdjustmentClip(startUs = 2 * SECOND, endUs = 4 * SECOND,
                name = "Film Look", effects = listOf(effect.copy(id = "adjustment-fx")))),
        )
        val bytes = ByteArrayOutputStream(); ProjectCodec.write(project, bytes)
        assertEquals(project, ProjectCodec.read(bytes.toByteArray().inputStream()))
    }
    @Test fun truncatedAndUnknownSchemaAreRejected() {
        val bytes = ByteArrayOutputStream(); ProjectCodec.write(sample(), bytes)
        assertThrows(EOFException::class.java) { ProjectCodec.read(ByteArrayInputStream(bytes.toByteArray().copyOf(40))) }
        val corrupted = bytes.toByteArray(); corrupted[7] = 99
        assertThrows(IllegalArgumentException::class.java) { ProjectCodec.read(ByteArrayInputStream(corrupted)) }
    }
    @Test fun previousSchemaSixVideoAudioAndTextMigrateWithoutLosingEdits() {
        val original = sample()
        val output = ByteArrayOutputStream(); ProjectCodec.write(original, output)
        val motion = ByteArrayOutputStream().also { MotionCodec.write(original, DataOutputStream(it)) }
        // Every section written after MotionCodec is a later schema and must be removed to
        // reproduce the original schema-6 envelope. Measure them by actually encoding them
        // rather than hardcoding a byte count, which silently rots each time a codec is added.
        val laterSections = ByteArrayOutputStream()
        DataOutputStream(laterSections).run {
            EffectAnimationCodec.write(original, this)
            AdjustmentCodec.write(original, this)
            FontCodec.write(original, this)
            TransitionCodec.write(original, this)
            Motion3DCodec.write(original, this)
            TextTransformCodec.write(original, this)
            CaptionSettingsCodec.write(original, this)
            StickerTransformCodec.write(original, this)
            BackgroundRemovalCodec.write(original, this)
            LayerMaskCodec.write(original, this)
            TrackingCodec.write(original, this)
            AudioEnhanceCodec.write(original, this)
            VideoAudioEnhanceCodec.write(original, this)
            CompoundCodec.write(original, this)
            MediaSourceCodec.write(original, this)
            flush()
        }
        // The schema-6 base ends after the marker/background/canvas tail and the MotionCodec section.
        val legacyTail = 4 /* backgroundColor + canvasFill */ + 4 /* markers count */ +
            laterSections.size() + motion.size()
        val legacy = output.toByteArray().copyOf(output.size() - legacyTail)
        java.nio.ByteBuffer.wrap(legacy).putInt(4, 6)
        val migrated = ProjectCodec.read(legacy.inputStream())
        // Schema 6 predates per-video audio treatment (added in 26), so that field migrates
        // to neutral rather than being preserved. Every field the format did know about must
        // come back byte-identical.
        val expected = original.copy(videos = original.videos.map { it.copy(enhance = AudioEnhance.NEUTRAL) })
        assertEquals(expected, migrated)
        val saved = ByteArrayOutputStream(); ProjectCodec.write(migrated, saved)
        assertEquals(PROJECT_SCHEMA, java.nio.ByteBuffer.wrap(saved.toByteArray()).getInt(4))
        assertEquals(migrated, ProjectCodec.read(saved.toByteArray().inputStream()))
        val directory = Files.createTempDirectory("r15-migration-test").toFile()
        try {
            val store = ProjectStore(directory)
            store.file(original.id).writeBytes(legacy)
            store.save(migrated)
            val backup = File(directory, "${original.id}.r15.pre-v3.bak")
            assertArrayEquals(legacy, backup.readBytes())
            store.save(migrated.copy(name = "Edited in V3"))
            assertArrayEquals(legacy, backup.readBytes())
            assertEquals("Edited in V3", store.load(original.id).name)
        } finally { directory.deleteRecursively() }
    }
    @Test fun atomicOverwriteListingAndDeletionNeverTouchSource() {
        val dir = Files.createTempDirectory("r15-project-test").toFile()
        try {
            val source = File(dir, "source.mp4"); source.writeBytes(byteArrayOf(1, 2, 3))
            val store = ProjectStore(File(dir, "projects")); val project = sample()
            store.save(project); assertEquals(project, store.load(project.id))
            val updated = project.copy(name = "Updated"); store.save(updated)
            File(dir, "projects/broken.r15").writeBytes(byteArrayOf(9))
            assertEquals(listOf(updated), store.list())
            store.delete(project.id); assertTrue(store.list().isEmpty())
            assertArrayEquals(byteArrayOf(1, 2, 3), source.readBytes())
        } finally { dir.deleteRecursively() }
    }
    @Test fun failedSavePreservesLastGoodProject() {
        val dir = Files.createTempDirectory("r15-failure-test").toFile()
        try {
            val store = ProjectStore(dir); val project = sample(); store.save(project)
            val invalid = project.copy(videos = project.videos.map { it.copy(uri = "x".repeat(70_000)) })
            assertThrows(UTFDataFormatException::class.java) { store.save(invalid) }
            assertEquals(project, store.load(project.id))
            assertFalse(File(dir, "${project.id}.tmp").exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun stickerScaleAndKeyframesRoundTripInSchema20() {
        val sticker = StickerClip(
            uri = "content://image/sticker",
            name = "overlay.png",
            startUs = SECOND,
            endUs = 5 * SECOND,
            x = 0.3f,
            y = 0.4f,
            size = 0.2f,
            scale = 1.5f,
            transformKeyframes = listOf(
                com.termex.replay15.editor.transform.TransformKeyframe(
                    timeUs = SECOND,
                    transform = com.termex.replay15.editor.transform.TransformState(x = 0.1f, y = 0.2f, scaleX = 1f, scaleY = 1f, rotation = 0f, opacity = 1f)
                ),
                com.termex.replay15.editor.transform.TransformKeyframe(
                    timeUs = 3 * SECOND,
                    transform = com.termex.replay15.editor.transform.TransformState(x = 0.8f, y = 0.7f, scaleX = 2.5f, scaleY = 2.5f, rotation = 45f, opacity = 0.8f),
                    easing = Easing.EASE_IN_OUT
                )
            )
        )
        val project = sample().copy(stickers = listOf(sticker))
        val bytes = ByteArrayOutputStream()
        ProjectCodec.write(project, bytes)
        val read = ProjectCodec.read(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(project, read)
        assertEquals(1.5f, read.stickers.single().scale, 0.001f)
        assertEquals(2, read.stickers.single().transformKeyframes.size)
        assertEquals(45f, read.stickers.single().transformKeyframes[1].transform.rotation, 0.001f)
    }

    @Test fun projectIdsCannotTraverseDirectories() {
        val dir = Files.createTempDirectory("r15-path-test").toFile()
        try { assertThrows(IllegalArgumentException::class.java) { ProjectStore(dir).file("../../other") } }
        finally { dir.deleteRecursively() }
    }
}
