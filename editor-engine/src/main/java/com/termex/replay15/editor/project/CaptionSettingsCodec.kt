package com.termex.replay15.editor.project

import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.SubtitleWordCue
import com.termex.replay15.editor.domain.SubtitleWordStyle
import com.termex.replay15.editor.domain.TextAnimation
import java.io.DataInputStream
import java.io.DataOutputStream

internal object CaptionSettingsCodec {
    fun write(project: Project, output: DataOutputStream) {
        output.writeUTF(project.captionGlobalFontId)
        output.writeUTF(project.captionLanguage)
        output.writeInt(project.captionVocabulary.size)
        project.captionVocabulary.sorted().forEach(output::writeUTF)
        output.writeInt(project.texts.size)
        project.texts.forEach {
            output.writeUTF(it.id)
            output.writeBoolean(it.isCaption)
            output.writeBoolean(it.captionFontOverride != null)
            it.captionFontOverride?.let(output::writeUTF)
            output.writeInt(it.enterAnimation.ordinal)
            output.writeInt(it.duringAnimation.ordinal)
            output.writeInt(it.exitAnimation.ordinal)
            with(it.wordStyle) {
                output.writeInt(normalColor); output.writeInt(spokenColor)
                output.writeInt(activeColor); output.writeInt(futureColor)
                output.writeFloat(activeScale); output.writeBoolean(boldCurrentWord)
            }
            output.writeInt(it.wordCues.size)
            it.wordCues.forEach { cue -> output.writeUTF(cue.text); output.writeLong(cue.startUs); output.writeLong(cue.endUs) }
        }
    }

    fun read(project: Project, input: DataInputStream, schema: Int = 21): Project {
        val font = input.readUTF().also { require(it.matches(Regex("[a-z0-9_]{1,80}"))) }
        val language = input.readUTF().also { require(it.length <= 24) }
        val vocabulary = List(input.readInt().also { require(it in 0..500) }) {
            input.readUTF().also { require(it.length in 1..80) }
        }.toSet()
        val entries = buildMap {
            repeat(input.readInt().also { require(it == project.texts.size) }) {
                val id = input.readUTF()
                val isCaption = input.readBoolean()
                val override = if (input.readBoolean()) input.readUTF().also {
                    require(it.matches(Regex("[a-z0-9_]{1,80}")))
                } else null
                require(id !in this)
                val animations = if (schema >= 21) {
                    Triple(
                        TextAnimation.entries[input.readInt().also { require(it in TextAnimation.entries.indices) }],
                        TextAnimation.entries[input.readInt().also { require(it in TextAnimation.entries.indices) }],
                        TextAnimation.entries[input.readInt().also { require(it in TextAnimation.entries.indices) }],
                    )
                } else Triple(TextAnimation.NONE, TextAnimation.NONE, TextAnimation.NONE)
                val wordStyle = if (schema >= 21) SubtitleWordStyle(
                    normalColor = input.readInt(), spokenColor = input.readInt(),
                    activeColor = input.readInt(), futureColor = input.readInt(),
                    activeScale = input.readFloat(), boldCurrentWord = input.readBoolean(),
                ) else SubtitleWordStyle()
                val cues = if (schema >= 21) List(input.readInt().also { require(it in 0..300) }) {
                    SubtitleWordCue(input.readUTF(), input.readLong(), input.readLong())
                } else emptyList()
                put(id, CaptionSettings(isCaption, override, animations, wordStyle, cues))
            }
        }
        require(project.texts.all { it.id in entries })
        return project.copy(captionGlobalFontId = font, captionLanguage = language,
            captionVocabulary = vocabulary, texts = project.texts.map { text ->
                val entry = requireNotNull(entries[text.id])
                text.copy(isCaption = entry.isCaption, captionFontOverride = entry.override,
                    enterAnimation = entry.animations.first, duringAnimation = entry.animations.second,
                    exitAnimation = entry.animations.third, wordStyle = entry.wordStyle, wordCues = entry.wordCues)
            })
    }

    private data class CaptionSettings(
        val isCaption: Boolean,
        val override: String?,
        val animations: Triple<TextAnimation, TextAnimation, TextAnimation>,
        val wordStyle: SubtitleWordStyle,
        val wordCues: List<SubtitleWordCue>,
    )
}
