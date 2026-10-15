package com.termex.replay15.editor.project

import com.termex.replay15.editor.domain.AnimationClip
import com.termex.replay15.editor.domain.MotionPreset
import com.termex.replay15.editor.domain.Easing
import com.termex.replay15.editor.domain.MAX_ANIMATION_CLIPS
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.withSanitizedAnimations
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Schema-31 appendix: os balões de animação ([AnimationClip]). Seção no fim do arquivo; projetos
 * anteriores ao schema 31 simplesmente não a têm e abrem sem animações, idênticos ao que eram.
 *
 * O preset vai pelo id textual (estável); o easing pelo ordinal, como no resto do formato.
 */
internal object AnimationClipCodec {
    fun write(project: Project, out: DataOutputStream) {
        out.writeInt(project.animationClips.size)
        project.animationClips.forEach { clip ->
            out.writeUTF(clip.id)
            out.writeUTF(clip.targetId)
            out.writeLong(clip.startUs)
            out.writeLong(clip.endUs)
            out.writeUTF(clip.preset.id)
            out.writeFloat(clip.intensity)
            out.writeInt(clip.easing.ordinal)
        }
    }

    fun read(project: Project, input: DataInputStream): Project {
        val count = input.readInt()
        require(count in 0..MAX_ANIMATION_CLIPS) { "Projeto incompativel" }
        val easings = Easing.entries
        val clips = List(count) {
            val id = input.readUTF()
            val target = input.readUTF()
            val start = input.readLong()
            val end = input.readLong()
            // Preset desconhecido (arquivo de versão futura): cai no zoom em vez de falhar.
            val preset = MotionPreset.fromId(input.readUTF()) ?: MotionPreset.ZOOM_IN
            val intensity = input.readFloat()
            val easing = input.readInt().also { require(it in easings.indices) { "Projeto incompativel" } }
            AnimationClip(id, target, start, end, preset, intensity, easings[easing])
        }
        return project.copy(animationClips = clips).withSanitizedAnimations()
    }
}
