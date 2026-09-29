package com.termex.replay15.editor.project

import com.termex.replay15.editor.domain.Project
import java.io.DataInputStream
import java.io.DataOutputStream

/** Stable font identities added after the legacy text payload to keep old project readers migratable. */
internal object FontCodec {
    private data class FontEntry(
        val fontId: String,
        val fontFamily: String = "",
        val fontWeight: Int = 400,
        val fontStyle: String = "normal",
        val fontVersion: String = "",
    )

    fun write(project: Project, output: DataOutputStream) {
        output.writeInt(project.texts.size)
        project.texts.forEach { text ->
            output.writeUTF(text.id)
            output.writeUTF(text.fontId)
            output.writeUTF(text.fontFamily)
            output.writeInt(text.fontWeight)
            output.writeUTF(text.fontStyle)
            output.writeUTF(text.fontVersion)
        }
    }

    fun read(project: Project, input: DataInputStream, schema: Int = com.termex.replay15.editor.domain.PROJECT_SCHEMA): Project {
        val count = input.readInt().also { require(it == project.texts.size) { "Projeto incompativel" } }
        val entries = buildMap(count) {
            repeat(count) {
                val textId = input.readUTF()
                val fontId = input.readUTF()
                require(textId !in this && fontId.matches(Regex("[a-z0-9_]{1,80}"))) { "Projeto incompativel" }
                val entry = if (schema >= 15) {
                    val family = input.readUTF()
                    val weight = input.readInt()
                    val style = input.readUTF()
                    val version = input.readUTF()
                    FontEntry(fontId, family, weight, style, version)
                } else {
                    FontEntry(fontId)
                }
                put(textId, entry)
            }
        }
        require(project.texts.all { it.id in entries }) { "Projeto incompativel" }
        return project.copy(texts = project.texts.map { text ->
            val entry = requireNotNull(entries[text.id])
            text.copy(
                fontId = entry.fontId,
                fontFamily = entry.fontFamily.ifBlank { text.fontFamily },
                fontWeight = if (entry.fontWeight in 1..1000) entry.fontWeight else text.fontWeight,
                fontStyle = entry.fontStyle.ifBlank { text.fontStyle },
                fontVersion = entry.fontVersion.ifBlank { text.fontVersion },
            )
        })
    }
}
