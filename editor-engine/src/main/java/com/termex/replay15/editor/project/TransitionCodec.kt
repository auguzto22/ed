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
            TransitionInstance(id, transitionId, leftClipId, rightClipId, durationUs, params, easing, bezier)
        }
        return project.copy(transitions = transitions)
    }
}
