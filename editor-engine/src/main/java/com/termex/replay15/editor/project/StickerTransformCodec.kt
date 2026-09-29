package com.termex.replay15.editor.project

import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.transform.TransformKeyframe
import com.termex.replay15.editor.transform.TransformState
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Serializes sticker (overlay image) transform keyframes and scale appended in schema 20.
 */
internal object StickerTransformCodec {

    fun write(project: Project, out: DataOutputStream) {
        out.writeInt(project.stickers.size)
        project.stickers.forEach { sticker ->
            out.writeUTF(sticker.id)
            out.writeFloat(sticker.scale)
            out.writeInt(sticker.transformKeyframes.size)
            sticker.transformKeyframes.forEach { k ->
                out.writeLong(k.timeUs)
                with(k.transform) {
                    out.writeFloat(x)
                    out.writeFloat(y)
                    out.writeFloat(scaleX)
                    out.writeFloat(scaleY)
                    out.writeFloat(rotation)
                    out.writeFloat(opacity)
                }
                out.writeInt(k.easing.ordinal)
                with(k.bezier) {
                    out.writeFloat(x1)
                    out.writeFloat(y1)
                    out.writeFloat(x2)
                    out.writeFloat(y2)
                }
            }
        }
    }

    fun read(project: Project, input: DataInputStream, schema: Int = PROJECT_SCHEMA): Project {
        val count = input.readInt().also { require(it in 0..24) { "Projeto incompativel" } }
        val scales = mutableMapOf<String, Float>()
        val keyframeMap = buildMap<String, List<TransformKeyframe>>(count) {
            repeat(count) {
                val stickerId = input.readUTF()
                val scale = input.readFloat().coerceIn(0.05f, 20f)
                scales[stickerId] = scale
                val keyCount = input.readInt().also { require(it in 0..200) { "Projeto incompativel" } }
                val keys = List(keyCount) {
                    val timeUs = input.readLong()
                    val x = input.readFloat()
                    val y = input.readFloat()
                    val scaleX = input.readFloat()
                    val scaleY = input.readFloat()
                    val rotation = input.readFloat()
                    val opacity = input.readFloat()
                    val easingOrdinal = input.readInt().also { require(it in Easing.entries.indices) }
                    val bx1 = input.readFloat()
                    val by1 = input.readFloat()
                    val bx2 = input.readFloat()
                    val by2 = input.readFloat()
                    TransformKeyframe(
                        timeUs = timeUs,
                        transform = TransformState(x, y, scaleX, scaleY, rotation, opacity),
                        easing = Easing.entries[easingOrdinal],
                        bezier = CubicBezier(bx1, by1, bx2, by2),
                    )
                }
                put(stickerId, keys)
            }
        }

        return project.copy(
            stickers = project.stickers.map { sticker ->
                val keys = keyframeMap[sticker.id] ?: emptyList()
                val scale = scales[sticker.id] ?: sticker.scale
                sticker.copy(transformKeyframes = keys, scale = scale)
            }
        )
    }
}
