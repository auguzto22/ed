package com.termex.replay15.editor.project

import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.ReverseAudio
import com.termex.replay15.editor.domain.VolumeKeyframe
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Schema-28 appendix: the per-clip fields that the base video/audio blocks predate.
 *
 * Both were fully modelled and edited in the UI but reached no codec, so saving a project
 * silently discarded them: audio volume automation reverted to the flat clip gain, and every
 * video clip lost the MIME type that [com.termex.replay15.editor.media.AdaptiveProxyManager]
 * and [com.termex.replay15.editor.core.EditorChangeClassifier] both read back.
 *
 * Written as a trailing section rather than inserted into the base blocks, so files saved by
 * earlier builds keep loading unchanged through the schema gate.
 */
internal object MediaSourceCodec {
    fun write(project: Project, out: DataOutputStream) {
        // Order-sensitive: the count must equal the base blocks so a mismatch is caught early.
        out.writeInt(project.videos.size)
        project.videos.forEach {
            out.writeBoolean(it.reverse)
            out.writeInt(it.reverseAudio.ordinal)
            out.writeUTF(it.mimeType)
        }

        out.writeInt(project.audio.size)
        project.audio.forEach { clip ->
            out.writeInt(clip.volumeKeyframes.size)
            clip.volumeKeyframes.forEach { key ->
                out.writeLong(key.timeUs)
                out.writeFloat(key.volume)
            }
        }
    }

    fun read(project: Project, input: DataInputStream): Project {
        if (input.available() <= 0) return project

        val videoCount = input.readInt().also { require(it in 0..200) { "Projeto incompativel" } }
        require(videoCount == project.videos.size)
        val videoFields = List(videoCount) {
            Triple(input.readBoolean(), ReverseAudio.entries[input.readInt().also {
                require(it in ReverseAudio.entries.indices) { "Projeto incompativel" }
            }], input.readUTF())
        }

        val audioCount = input.readInt().also { require(it in 0..8) { "Projeto incompativel" } }
        require(audioCount == project.audio.size)
        val keys = List(audioCount) {
            val size = input.readInt().also { require(it in 0..200) { "Projeto incompativel" } }
            List(size) { VolumeKeyframe(input.readLong(), input.readFloat()) }
        }

        return project.copy(
            videos = project.videos.mapIndexed { index, clip ->
                val (reverse, reverseAudio, mimeType) = videoFields[index]
                clip.copy(reverse = reverse, reverseAudio = reverseAudio, mimeType = mimeType)
            },
            audio = project.audio.mapIndexed { index, clip -> clip.copy(volumeKeyframes = keys[index]) },
        )
    }
}