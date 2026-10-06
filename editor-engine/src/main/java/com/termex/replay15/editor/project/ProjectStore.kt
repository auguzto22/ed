package com.termex.replay15.editor.project

import com.termex.replay15.editor.domain.*
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Versioned, bounded metadata only. Media bytes never enter project snapshots. */
object ProjectCodec {
    fun write(project: Project, stream: OutputStream) {
        DataOutputStream(BufferedOutputStream(stream)).use { o ->
            o.writeInt(0x52313545); o.writeInt(PROJECT_SCHEMA)
            o.writeUTF(project.id); o.writeUTF(project.name); o.writeFloat(project.aspect)
            o.writeInt(project.export.shortSide); o.writeInt(project.export.fps); o.writeInt(project.export.bitrate)
            o.writeInt(project.videos.size)
            project.videos.forEach { v ->
                o.writeUTF(v.id); o.writeUTF(v.uri); o.writeUTF(v.name); o.writeLong(v.sourceUs)
                o.writeInt(v.width); o.writeInt(v.height); o.writeFloat(v.fps); o.writeBoolean(v.image)
                o.writeLong(v.inUs); o.writeLong(v.outUs); o.writeFloat(v.speed); o.writeFloat(v.volume)
                o.writeInt(v.rotation); o.writeBoolean(v.flip)
                o.writeFloat(v.crop.left); o.writeFloat(v.crop.top); o.writeFloat(v.crop.right); o.writeFloat(v.crop.bottom)
                o.writeInt(v.filter)
                o.writeFloat(v.brightness); o.writeFloat(v.contrast); o.writeFloat(v.saturation)
                o.writeFloat(v.hue); o.writeFloat(v.lightness); o.writeFloat(v.temperature)
                o.writeFloat(v.filterStrength)
                o.writeFloat(v.zoom); o.writeFloat(v.offsetX); o.writeFloat(v.offsetY)
                o.writeFloat(v.fineRotation); o.writeFloat(v.opacity); o.writeFloat(v.blur)
                o.writeInt(v.motion.ordinal); o.writeInt(v.transitionIn.ordinal); o.writeInt(v.transitionOut.ordinal)
                o.writeLong(v.transitionDurationUs); o.writeLong(v.audioFadeInUs); o.writeLong(v.audioFadeOutUs)
            }
            o.writeInt(project.audio.size)
            project.audio.forEach { a ->
                o.writeUTF(a.id); o.writeUTF(a.uri); o.writeUTF(a.name); o.writeLong(a.sourceUs)
                o.writeLong(a.startUs); o.writeLong(a.inUs); o.writeLong(a.outUs); o.writeFloat(a.volume)
                o.writeLong(a.fadeInUs); o.writeLong(a.fadeOutUs)
            }
            o.writeInt(project.texts.size)
            project.texts.forEach { t ->
                o.writeUTF(t.id); o.writeUTF(t.text); o.writeLong(t.startUs); o.writeLong(t.endUs)
                o.writeFloat(t.x); o.writeFloat(t.y); o.writeFloat(t.size); o.writeInt(t.color); o.writeBoolean(t.bold)
                // Legacy ordinal preserves schema <= 12 decoding. The stable ID is written by FontCodec.
                o.writeInt(TextFont.entries.indexOfFirst { it.id == t.fontId }.coerceAtLeast(0))
                o.writeBoolean(t.italic); o.writeBoolean(t.underline); o.writeInt(t.alignment.ordinal)
                o.writeFloat(t.opacity); o.writeFloat(t.letterSpacing); o.writeFloat(t.lineSpacing)
                o.writeFloat(t.boxWidth); o.writeFloat(t.rotation); o.writeInt(t.backgroundColor)
                o.writeInt(t.outlineColor); o.writeFloat(t.outlineWidth); o.writeBoolean(t.shadow); o.writeInt(t.animation.ordinal)
            }
            o.writeInt(project.stickers.size)
            project.stickers.forEach { s ->
                o.writeUTF(s.id); o.writeUTF(s.uri); o.writeUTF(s.name); o.writeLong(s.startUs); o.writeLong(s.endUs)
                o.writeFloat(s.x); o.writeFloat(s.y); o.writeFloat(s.size); o.writeFloat(s.rotation); o.writeFloat(s.opacity)
                o.writeBoolean(s.flip); o.writeInt(s.animation.ordinal)
            }
            o.writeInt(project.export.audioBitrate)
            project.videos.forEach { v ->
                o.writeInt(v.keyframes.size)
                v.keyframes.forEach { k ->
                    o.writeLong(k.sourceUs); o.writeFloat(k.zoom); o.writeFloat(k.x); o.writeFloat(k.y)
                    o.writeFloat(k.rotation); o.writeFloat(k.opacity); o.writeInt(k.easing.ordinal)
                }
                with(v.grade) {
                    o.writeFloat(exposure); o.writeFloat(shadows); o.writeFloat(highlights)
                    o.writeFloat(vignette); o.writeFloat(grain); o.writeFloat(sharpen)
                    curve.forEach(o::writeFloat)
                    o.writeInt(mask.ordinal); o.writeFloat(maskSize); o.writeFloat(feather); o.writeBoolean(invertMask)
                    o.writeBoolean(chromaEnabled); o.writeInt(chromaColor); o.writeFloat(chromaTolerance)
                    o.writeUTF(lutPath); o.writeFloat(lutStrength)
                }
            }
            o.writeInt(project.markers.size)
            project.markers.forEach { m -> o.writeUTF(m.id); o.writeLong(m.timeUs); o.writeUTF(m.name); o.writeInt(m.color) }
            o.writeInt(project.backgroundColor); o.writeInt(project.canvasFill.ordinal)
            TrackCodec.write(project, o)
            EffectCodec.write(project, o)
            MotionCodec.write(project, o)
            EffectAnimationCodec.write(project, o)
            AdjustmentCodec.write(project, o)
            FontCodec.write(project, o)
            TransitionCodec.write(project, o)
            Motion3DCodec.write(project, o)
            TextTransformCodec.write(project, o)
            CaptionSettingsCodec.write(project, o)
            StickerTransformCodec.write(project, o)
            BackgroundRemovalCodec.write(project, o)
            LayerMaskCodec.write(project, o)
            TrackingCodec.write(project, o)
            AudioEnhanceCodec.write(project, o)
            VideoAudioEnhanceCodec.write(project, o)
            CompoundCodec.write(project, o)
            MediaSourceCodec.write(project, o)
        }
    }
    fun read(stream: InputStream, nested: Boolean = false): Project = DataInputStream(BufferedInputStream(stream)).use { i ->
        require(i.readInt() == 0x52313545) { "Projeto incompativel" }
        val schema = i.readInt().also { require(it in 1..PROJECT_SCHEMA) { "Projeto incompativel" } }
        val id = i.readUTF(); val name = i.readUTF(); val aspect = i.readFloat()
        val export = ExportSettings(i.readInt(), i.readInt(), i.readInt())
        fun count(max: Int): Int = i.readInt().also { require(it in 0..max) }
        val videos = List(count(200)) {
            val base = VideoClip(i.readUTF(), i.readUTF(), i.readUTF(), i.readLong(), i.readInt(), i.readInt(),
                i.readFloat(), i.readBoolean(), i.readLong(), i.readLong(), i.readFloat(), i.readFloat(),
                i.readInt(), i.readBoolean(), CropRect(i.readFloat(), i.readFloat(), i.readFloat(), i.readFloat()), i.readInt())
            val adjusted = if (schema < 3) base else base.copy(
                brightness = i.readFloat(), contrast = i.readFloat(), saturation = i.readFloat(),
                hue = i.readFloat(), lightness = i.readFloat(), temperature = i.readFloat(),
            )
            val filtered = if (schema < 4) adjusted else adjusted.copy(filterStrength = i.readFloat())
            if (schema < 5) filtered else filtered.copy(
                zoom = i.readFloat(), offsetX = i.readFloat(), offsetY = i.readFloat(),
                fineRotation = i.readFloat(), opacity = i.readFloat(), blur = i.readFloat(),
                motion = ClipMotion.entries[i.readInt().also { require(it in ClipMotion.entries.indices) }],
                transitionIn = ClipTransition.entries[i.readInt().also { require(it in ClipTransition.entries.indices) }],
                transitionOut = ClipTransition.entries[i.readInt().also { require(it in ClipTransition.entries.indices) }],
                transitionDurationUs = i.readLong(), audioFadeInUs = i.readLong(), audioFadeOutUs = i.readLong(),
            )
        }
        val audio = List(count(if (schema < 3) 1 else 8)) {
            val base = AudioClip(i.readUTF(), i.readUTF(), i.readUTF(), i.readLong(), i.readLong(), i.readLong(), i.readLong(), i.readFloat())
            if (schema < 5) base else base.copy(fadeInUs = i.readLong(), fadeOutUs = i.readLong())
        }
        val texts = List(count(if (schema < 6) 32 else MAX_TEXTS)) {
            val base = TextClip(
                id = i.readUTF(), text = i.readUTF(), startUs = i.readLong(), endUs = i.readLong(),
                x = i.readFloat(), y = i.readFloat(), size = i.readFloat(), color = i.readInt(), bold = i.readBoolean(),
            )
            if (schema == 1) base else base.copy(
                fontId = TextFont.entries[i.readInt().also { require(it in TextFont.entries.indices) }].id,
                italic = i.readBoolean(), underline = i.readBoolean(),
                alignment = TextAlignment.entries[i.readInt().also { require(it in TextAlignment.entries.indices) }],
                opacity = i.readFloat(), letterSpacing = i.readFloat(), lineSpacing = i.readFloat(),
                boxWidth = i.readFloat(), rotation = i.readFloat(), backgroundColor = i.readInt(),
                outlineColor = i.readInt(), outlineWidth = i.readFloat(), shadow = i.readBoolean(),
                animation = TextAnimation.entries[i.readInt().also { require(it in TextAnimation.entries.indices) }],
            )
        }
        val stickers = if (schema < 5) emptyList() else List(count(24)) {
            StickerClip(
                id = i.readUTF(), uri = i.readUTF(), name = i.readUTF(), startUs = i.readLong(), endUs = i.readLong(),
                x = i.readFloat(), y = i.readFloat(), size = i.readFloat(), rotation = i.readFloat(), opacity = i.readFloat(),
                flip = i.readBoolean(), animation = TextAnimation.entries[i.readInt().also { require(it in TextAnimation.entries.indices) }],
            )
        }
        val finalExport = if (schema < 5) export else export.copy(audioBitrate = i.readInt())
        fun ordinal(size: Int) = i.readInt().also { require(it in 0 until size) }
        val studioVideos = if (schema < 6) videos else videos.map { v ->
            val keys = List(count(200)) { TransformKeyframe(i.readLong(), i.readFloat(), i.readFloat(), i.readFloat(),
                i.readFloat(), i.readFloat(), Easing.entries[ordinal(Easing.entries.size)]) }
            val grade = StudioGrade(exposure = i.readFloat(), shadows = i.readFloat(), highlights = i.readFloat(),
                vignette = i.readFloat(), grain = i.readFloat(), sharpen = i.readFloat(), curve = List(5) { i.readFloat() },
                mask = MaskShape.entries[ordinal(MaskShape.entries.size)], maskSize = i.readFloat(), feather = i.readFloat(),
                invertMask = i.readBoolean(), chromaEnabled = i.readBoolean(), chromaColor = i.readInt(), chromaTolerance = i.readFloat(),
                lutPath = i.readUTF(), lutStrength = i.readFloat())
            v.copy(keyframes = keys, grade = grade)
        }
        val markers = if (schema < 6) emptyList() else List(count(500)) { TimelineMarker(i.readUTF(), i.readLong(), i.readUTF(), i.readInt()) }
        val background = if (schema < 6) 0xFF000000.toInt() else i.readInt()
        val fill = if (schema < 6) CanvasFill.FIT else CanvasFill.entries[ordinal(CanvasFill.entries.size)]
        val project = Project(id = id, name = name, videos = studioVideos, audio = audio, texts = texts, aspect = aspect,
            export = finalExport, stickers = stickers, markers = markers, backgroundColor = background, canvasFill = fill)
        val tracked = if (schema < 7) project else TrackCodec.read(project, i, nested)
        val effected = if (schema < 8) tracked else EffectCodec.read(tracked, i, schema)
        val motion = if (schema < 9) effected else MotionCodec.read(effected, i)
        val animated = if (schema < 10) motion else EffectAnimationCodec.read(motion, i)
        val adjusted = if (schema < 11) animated else AdjustmentCodec.read(animated, i, schema)
        val fonted = if (schema < 13) adjusted else FontCodec.read(adjusted, i, schema)
        val transitioned = if (schema < 14) fonted else TransitionCodec.read(fonted, i)
        val withMotion3D = if (schema < 16) transitioned else Motion3DCodec.read(transitioned, i)
        val transformed = if (schema < 17) withMotion3D else TextTransformCodec.read(withMotion3D, i, schema)
        val captioned = if (schema < 19) transformed else CaptionSettingsCodec.read(transformed, i, schema)
        val stickerTransformed = if (schema < 20) captioned else StickerTransformCodec.read(captioned, i, schema)
        val backgroundRemoved = if (schema < 22) stickerTransformed else BackgroundRemovalCodec.read(stickerTransformed, i)
        val masked = if (schema < 23) backgroundRemoved else LayerMaskCodec.read(backgroundRemoved, i, schema)
        val motionTracked = if (schema < 24) masked else TrackingCodec.read(masked, i)
        val audioEnhanced = if (schema < 25) motionTracked else AudioEnhanceCodec.read(motionTracked, i)
        val videoEnhanced = if (schema < 26) audioEnhanced else VideoAudioEnhanceCodec.read(audioEnhanced, i)
        val compounded = if (schema < 27) videoEnhanced else CompoundCodec.read(videoEnhanced, i)
        if (schema < 28) compounded else MediaSourceCodec.read(compounded, i)
    }
}

class ProjectStore(private val directory: File) {
    init { check(directory.exists() || directory.mkdirs()) }
    fun file(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        return File(directory, "$id.r15")
    }
    @Synchronized fun save(project: Project) {
        val target = file(project.id)
        val temporary = File(directory, "${project.id}.tmp")
        try {
            ProjectCodec.write(project, temporary.outputStream())
            RandomAccessFile(temporary, "rw").use { it.fd.sync() }
            preserveLegacy(target)
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    private fun preserveLegacy(target: File) {
        if (!target.isFile) return
        val version = DataInputStream(target.inputStream()).use { input ->
            if (input.readInt() != 0x52313545) return
            input.readInt()
        }
        if (version !in 1 until PROJECT_SCHEMA) return
        val backup = File(directory, "${target.name}.pre-v3.bak")
        if (backup.exists()) return
        val temporary = File.createTempFile("migration-", ".tmp", directory)
        try {
            target.inputStream().use { source -> temporary.outputStream().use { source.copyTo(it) } }
            RandomAccessFile(temporary, "rw").use { it.fd.sync() }
            Files.move(temporary.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
    }
    fun load(id: String): Project = ProjectCodec.read(file(id).inputStream())
    fun list(): List<Project> = directory.listFiles()?.filter { it.extension == "r15" }
        ?.sortedByDescending { it.lastModified() }?.mapNotNull { runCatching { ProjectCodec.read(it.inputStream()) }.getOrNull() } ?: emptyList()
    @Synchronized fun delete(id: String) {
        val target = file(id)
        target.delete()
        File(directory, "${target.name}.pre-v3.bak").delete()
    }
}
