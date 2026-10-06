package com.termex.replay15.editor.project

import com.termex.replay15.editor.audio.AudioEnhance
import com.termex.replay15.editor.domain.Project
import java.io.DataInputStream
import java.io.DataOutputStream

/** Schema-25 appendix: the per-clip audio treatments, appended after every other section. */
internal object AudioEnhanceCodec {
    fun write(project: Project, out: DataOutputStream) {
        out.writeInt(project.audio.size)
        project.audio.forEach { clip ->
            out.writeFloat(clip.enhance.noiseReduction)
            out.writeFloat(clip.enhance.voiceEnhance)
            out.writeFloat(clip.enhance.compression)
            out.writeFloat(clip.enhance.normalize)
        }
    }

    fun read(project: Project, input: DataInputStream): Project {
        val count = input.readInt().also { require(it in 0..8) { "Projeto incompativel" } }
        require(count == project.audio.size)
        val settings = List(count) {
            // A file edited by a newer build could carry a value outside the normalized range.
            // Clamping keeps the project loadable; rejecting it would lose the whole edit.
            fun amount() = input.readFloat().let { if (it.isNaN()) 0f else it.coerceIn(0f, 1f) }
            AudioEnhance(noiseReduction = amount(), voiceEnhance = amount(),
                compression = amount(), normalize = amount())
        }
        return project.copy(audio = project.audio.mapIndexed { index, clip -> clip.copy(enhance = settings[index]) })
    }
}
