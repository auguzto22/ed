package com.termex.replay15.editor.project

import com.termex.replay15.editor.domain.BackgroundMode
import com.termex.replay15.editor.domain.BackgroundRemovalEffect
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.SegmentationQuality
import java.io.DataInputStream
import java.io.DataOutputStream

/** Schema-22 appendix for background-removal settings. Masks are runtime-only and never serialized. */
internal object BackgroundRemovalCodec {
    fun write(project: Project, out: DataOutputStream) {
        out.writeInt(project.videos.size)
        project.videos.forEach { writeEffect(it.backgroundRemoval, out) }
        out.writeInt(project.stickers.size)
        project.stickers.forEach { writeEffect(it.backgroundRemoval, out) }
    }

    fun read(project: Project, input: DataInputStream): Project {
        fun count(max: Int) = input.readInt().also { require(it in 0..max) { "Projeto incompativel" } }
        val videoCount = count(200)
        require(videoCount == project.videos.size) { "Projeto incompativel" }
        val videos = project.videos.map { it.copy(backgroundRemoval = readEffect(input)) }
        val stickerCount = count(24)
        require(stickerCount == project.stickers.size) { "Projeto incompativel" }
        val stickers = project.stickers.map { it.copy(backgroundRemoval = readEffect(input)) }
        return project.copy(videos = videos, stickers = stickers)
    }

    private fun writeEffect(effect: BackgroundRemovalEffect, out: DataOutputStream) {
        out.writeBoolean(effect.enabled)
        out.writeInt(effect.mode.ordinal)
        out.writeFloat(effect.threshold)
        out.writeFloat(effect.feather)
        out.writeFloat(effect.edgeSmoothing)
        out.writeInt(effect.quality.ordinal)
        out.writeBoolean(effect.backgroundUri != null)
        if (effect.backgroundUri != null) out.writeUTF(effect.backgroundUri)
        out.writeInt(effect.backgroundColor)
    }

    private fun readEffect(input: DataInputStream): BackgroundRemovalEffect {
        val enabled = input.readBoolean()
        val mode = BackgroundMode.entries[input.readInt().also { require(it in BackgroundMode.entries.indices) }]
        val threshold = input.readFloat()
        val feather = input.readFloat()
        val edgeSmoothing = input.readFloat()
        val quality = SegmentationQuality.entries[input.readInt().also { require(it in SegmentationQuality.entries.indices) }]
        val backgroundUri = if (input.readBoolean()) input.readUTF() else null
        return BackgroundRemovalEffect(enabled, mode, threshold, feather, edgeSmoothing, quality, backgroundUri, input.readInt())
    }
}
