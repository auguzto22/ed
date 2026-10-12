package com.termex.replay15.editor.project

import com.termex.replay15.editor.domain.Project
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Schema-29 appendix: os efeitos por palavra das legendas animadas (caixa atrás da palavra,
 * revelar palavra a palavra, caixa alta, "pulo" e paleta de cores).
 *
 * Fica numa seção no fim do arquivo, como as outras adições tardias, para que projetos
 * salvos por versões anteriores continuem abrindo pelo controle de schema (campos ficam no padrão).
 */
internal object CaptionWordFxCodec {
    fun write(project: Project, out: DataOutputStream) {
        out.writeInt(project.texts.size)
        project.texts.forEach { text ->
            val style = text.wordStyle
            out.writeUTF(text.id)
            out.writeInt(style.highlightColor)
            out.writeBoolean(style.reveal)
            out.writeBoolean(style.uppercase)
            out.writeBoolean(style.pop)
            out.writeInt(style.activePalette.size)
            style.activePalette.forEach(out::writeInt)
        }
    }

    fun read(project: Project, input: DataInputStream): Project {
        val count = input.readInt()
        require(count == project.texts.size) { "Projeto incompativel" }
        val entries = HashMap<String, (com.termex.replay15.editor.domain.SubtitleWordStyle) -> com.termex.replay15.editor.domain.SubtitleWordStyle>()
        repeat(count) {
            val id = input.readUTF()
            val highlight = input.readInt()
            val reveal = input.readBoolean()
            val uppercase = input.readBoolean()
            val pop = input.readBoolean()
            val palette = List(input.readInt().also { require(it in 0..8) }) { input.readInt() }
            require(id !in entries)
            entries[id] = { style -> style.copy(highlightColor = highlight, reveal = reveal, uppercase = uppercase, pop = pop, activePalette = palette) }
        }
        require(project.texts.all { it.id in entries })
        return project.copy(texts = project.texts.map { text ->
            text.copy(wordStyle = requireNotNull(entries[text.id])(text.wordStyle))
        })
    }
}
