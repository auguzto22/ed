package com.termex.replay15.editor.project

import com.termex.replay15.editor.domain.*
import java.io.DataInputStream
import java.io.DataOutputStream

internal object AdjustmentCodec {
    fun write(project: Project, output: DataOutputStream) {
        output.writeInt(project.adjustmentClips.size)
        project.adjustmentClips.forEach { clip ->
            output.writeUTF(clip.id); output.writeLong(clip.startUs); output.writeLong(clip.endUs)
            output.writeUTF(clip.name); output.writeBoolean(clip.enabled)
            EffectCodec.writeList(clip.effects, output)
            EffectAnimationCodec.writeList(clip.effects, output)
        }
    }

    fun read(project: Project, input: DataInputStream, schema: Int): Project {
        val count = input.readInt().also { require(it in 0..8) }
        return project.copy(adjustmentClips = List(count) {
            val id = input.readUTF(); val start = input.readLong(); val end = input.readLong()
            val name = input.readUTF(); val enabled = input.readBoolean()
            val effects = EffectCodec.readList(input, schema)
            AdjustmentClip(id, start, end, name, enabled, EffectAnimationCodec.readList(effects, input))
        })
    }
}
