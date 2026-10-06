package com.termex.replay15.editor.project

import com.termex.replay15.editor.domain.*
import java.io.DataInputStream
import java.io.DataOutputStream

/** Compound groups are metadata only: ids and a name, no media, no nested project payload. */
internal object CompoundCodec {
    fun write(project: Project, out: DataOutputStream) {
        out.writeInt(project.compounds.size)
        project.compounds.forEach { compound ->
            out.writeUTF(compound.id)
            out.writeUTF(compound.name)
            out.writeInt(compound.color)
            out.writeInt(compound.childIds.size)
            compound.childIds.forEach(out::writeUTF)
        }
    }

    fun read(project: Project, input: DataInputStream): Project {
        if (input.available() <= 0) return project
        val count = input.readInt().also { require(it in 0..MAX_COMPOUNDS) { "Projeto incompativel" } }
        val compounds = List(count) {
            val id = input.readUTF()
            val name = input.readUTF()
            val color = input.readInt()
            val childCount = input.readInt().also { require(it in 2..MAX_COMPOUND_CHILDREN) { "Projeto incompativel" } }
            val children = List(childCount) { input.readUTF() }
            CompoundClip(id, name, children, color)
        }
        // A project saved by an older build may hold a group whose clip was removed since; drop
        // those instead of refusing to open the file.
        val staged = project.copy(compounds = emptyList())
        return staged.copy(compounds = CompoundEditing.sanitize(staged.copy(compounds = compounds)).compounds)
    }
}
