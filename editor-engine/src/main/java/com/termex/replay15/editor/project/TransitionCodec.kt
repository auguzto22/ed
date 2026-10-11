package com.termex.replay15.editor.project

import com.termex.replay15.editor.assets.TransitionInstance
import com.termex.replay15.editor.domain.*
import java.io.DataInputStream
import java.io.DataOutputStream

internal object TransitionCodec {
    fun write(project: Project, out: DataOutputStream) {
        out.writeInt(project.transitions.size)
        project.transitions.forEach { t ->
            out.writeUTF(t.id)
            out.writeUTF(t.transitionId)
            out.writeUTF(t.leftClipId)
            out.writeUTF(t.rightClipId)
            out.writeLong(t.durationUs)
            out.writeInt(t.parameters.size)
            t.parameters.forEach { (key, value) ->
                out.writeUTF(key)
                out.writeFloat(value)
            }
            out.writeInt(t.easing.ordinal)
            out.writeFloat(t.bezier.x1)
            out.writeFloat(t.bezier.y1)
            out.writeFloat(t.bezier.x2)
            out.writeFloat(t.bezier.y2)
        }
    }

    fun read(project: Project, input: DataInputStream): Project {
        if (input.available() <= 0) return project
        val count = input.readInt().also { require(it in 0..200) }
        val transitions = List(count) {
            val id = input.readUTF()
            val transitionId = input.readUTF()
            val leftClipId = input.readUTF()
            val rightClipId = input.readUTF()
            val durationUs = input.readLong()
            val paramCount = input.readInt().also { require(it in 0..16) }
            val params = linkedMapOf<String, Float>()
            repeat(paramCount) {
                val key = input.readUTF()
                val value = input.readFloat()
                params[key] = value
            }
            val easingOrdinal = input.readInt().also { require(it in Easing.entries.indices) }
            val easing = Easing.entries[easingOrdinal]
            val bezier = CubicBezier(input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat())
            // Schema < 29 had no alignment: every window started at the cut. TransitionAlignmentCodec
            // overwrites this for newer files, so old projects keep exactly the timing they were made with.
            TransitionInstance(id, transitionId, leftClipId, rightClipId, durationUs, params, easing, bezier,
                TransitionAlignment.START_AT_CUT)
        }
        return project.copy(transitions = transitions)
    }
}

/**
 * Schema-29 appendix: where each transition sits around its cut. Written as a trailing section so
 * files from earlier builds keep loading through the schema gate (and load as START_AT_CUT).
 */
internal object TransitionAlignmentCodec {
    fun write(project: Project, out: DataOutputStream) {
        out.writeInt(project.transitions.size)
        project.transitions.forEach { out.writeInt(it.alignment.ordinal) }
    }

    fun read(project: Project, input: DataInputStream): Project {
        if (input.available() <= 0) return project
        val count = input.readInt().also { require(it in 0..200) { "Projeto incompativel" } }
        require(count == project.transitions.size) { "Projeto incompativel" }
        val alignments = List(count) {
            TransitionAlignment.entries[input.readInt().also { require(it in TransitionAlignment.entries.indices) { "Projeto incompativel" } }]
        }
        return project.copy(transitions = project.transitions.mapIndexed { index, transition ->
            transition.copy(alignment = alignments[index])
        })
    }
}
