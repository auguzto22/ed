package com.termex.replay15.editor.project

import com.termex.replay15.editor.assets.EffectValueKeyframe
import com.termex.replay15.editor.domain.*
import java.io.DataInputStream
import java.io.DataOutputStream

internal object EffectAnimationCodec {
    fun write(project: Project, out: DataOutputStream) {
        project.videos.forEach { clip -> writeList(clip.effects, out) }
    }
    fun writeList(effects: List<com.termex.replay15.editor.assets.EffectInstance>, out: DataOutputStream) {
        effects.forEach { effect ->
            out.writeInt(effect.keyframes.size)
            effect.keyframes.forEach { (property, keys) ->
                out.writeUTF(property); out.writeInt(keys.size)
                keys.forEach { key ->
                    out.writeLong(key.sourceUs); out.writeFloat(key.value); out.writeInt(key.easing.ordinal)
                    with(key.bezier) { out.writeFloat(x1); out.writeFloat(y1); out.writeFloat(x2); out.writeFloat(y2) }
                }
            }
        }
    }
    fun read(project: Project, input: DataInputStream): Project = project.copy(videos = project.videos.map { clip ->
        clip.copy(effects = readList(clip.effects, input))
    })
    fun readList(effects: List<com.termex.replay15.editor.assets.EffectInstance>, input: DataInputStream) = effects.map { effect ->
            val propertyCount = input.readInt().also { require(it in 0..32) }
            val animated = linkedMapOf<String, List<EffectValueKeyframe>>()
            repeat(propertyCount) {
                val property = input.readUTF(); require(property !in animated && property.length <= 32)
                val count = input.readInt().also { require(it in 0..200) }
                animated[property] = List(count) {
                    val source = input.readLong(); val value = input.readFloat()
                    val easing = Easing.entries[input.readInt().also { require(it in Easing.entries.indices) }]
                    EffectValueKeyframe(source, value, easing, CubicBezier(input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat()))
                }
            }
            effect.copy(keyframes = animated)
        }
}
