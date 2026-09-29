package com.termex.replay15.editor.project

import com.termex.replay15.editor.assets.EffectInstance
import com.termex.replay15.editor.domain.*
import java.io.*

internal object EffectCodec {
    fun write(project: Project, out: DataOutputStream) {
        project.videos.forEach { clip ->
            writeList(clip.effects, out)
        }
    }
    fun writeList(effects: List<EffectInstance>, out: DataOutputStream) {
            out.writeInt(effects.size)
            effects.forEach { effect ->
                out.writeUTF(effect.id); out.writeUTF(effect.assetId); out.writeInt(effect.version)
                out.writeBoolean(effect.enabled); out.writeFloat(effect.intensity); out.writeInt(effect.values.size)
                effect.values.forEach { (key, value) -> out.writeUTF(key); out.writeFloat(value) }
                out.writeLong(effect.startTimeUs ?: -1L); out.writeLong(effect.endTimeUs ?: -1L)
                out.writeInt(effect.blendMode.ordinal)
                out.writeBoolean(effect.mask != null)
                effect.mask?.let { mask ->
                    out.writeInt(mask.shape.ordinal); out.writeFloat(mask.x); out.writeFloat(mask.y)
                    out.writeFloat(mask.size); out.writeFloat(mask.aspect); out.writeFloat(mask.rotation)
                    out.writeFloat(mask.feather); out.writeBoolean(mask.invert); out.writeFloat(mask.opacity)
                }
                out.writeBoolean(effect.groupId != null)
                effect.groupId?.let(out::writeUTF)
                out.writeFloat(effect.groupIntensity)
            }
    }
    fun read(project: Project, input: DataInputStream, schema: Int): Project {
        return project.copy(videos = project.videos.map { clip ->
            clip.copy(effects = readList(input, schema))
        })
    }
    fun readList(input: DataInputStream, schema: Int): List<EffectInstance> {
        fun count(max: Int) = input.readInt().also { require(it in 0..max) }
        return List(count(12)) {
                val id = input.readUTF(); val assetId = input.readUTF(); val version = input.readInt()
                val enabled = input.readBoolean(); val intensity = input.readFloat()
                val values = linkedMapOf<String, Float>()
                repeat(count(16)) { val key = input.readUTF(); require(key.length <= 32 && key !in values); values[key] = input.readFloat() }
                if (schema < 11) EffectInstance(id, assetId, version, enabled, intensity, values) else {
                    val start = input.readLong().takeIf { it >= 0 }
                    val end = input.readLong().takeIf { it >= 0 }
                    val blend = com.termex.replay15.editor.assets.EffectBlendMode.entries[
                        input.readInt().also { require(it in com.termex.replay15.editor.assets.EffectBlendMode.entries.indices) }]
                    val mask = if (!input.readBoolean()) null else com.termex.replay15.editor.assets.EffectMask(
                        shape = com.termex.replay15.editor.assets.EffectMaskShape.entries[
                            input.readInt().also { require(it in com.termex.replay15.editor.assets.EffectMaskShape.entries.indices) }],
                        x = input.readFloat(), y = input.readFloat(), size = input.readFloat(),
                        aspect = input.readFloat(), rotation = input.readFloat(), feather = input.readFloat(),
                        invert = input.readBoolean(), opacity = input.readFloat(),
                    )
                    val groupId = if (schema >= 12 && input.readBoolean()) input.readUTF().also {
                        require(it.matches(Regex("[a-zA-Z0-9-]{1,80}")))
                    } else null
                    val groupIntensity = if (schema >= 12) input.readFloat() else 1f
                    EffectInstance(id, assetId, version, enabled, intensity, values,
                        startTimeUs = start, endTimeUs = end, mask = mask, blendMode = blend,
                        groupId = groupId, groupIntensity = groupIntensity)
                }
            }
    }
}
