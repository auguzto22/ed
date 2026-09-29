package com.termex.replay15.editor.project

import com.termex.replay15.editor.domain.CubicBezier
import com.termex.replay15.editor.domain.Easing
import com.termex.replay15.editor.domain.MaskKeyframe
import com.termex.replay15.editor.domain.MaskPathPoint
import com.termex.replay15.editor.domain.MaskState
import com.termex.replay15.editor.domain.MaskType
import com.termex.replay15.editor.domain.Project
import java.io.DataInputStream
import java.io.DataOutputStream

/** Schema-23 appendix. Nested video tracks receive the same appendix in their own envelope. */
internal object LayerMaskCodec {
    fun write(project: Project, out: DataOutputStream) {
        out.writeInt(project.videos.size)
        project.videos.forEach { writeMask(it.mask, out) }
        out.writeInt(project.texts.size)
        project.texts.forEach { writeMask(it.mask, out) }
        out.writeInt(project.stickers.size)
        project.stickers.forEach { writeMask(it.mask, out) }
    }

    fun read(project: Project, input: DataInputStream, schema: Int): Project {
        fun count(expected: Int, maximum: Int): Int = input.readInt().also {
            require(it in 0..maximum && it == expected) { "Projeto incompativel" }
        }
        count(project.videos.size, 200)
        val videos = project.videos.map { it.copy(mask = readMask(input, schema >= 24)) }
        count(project.texts.size, com.termex.replay15.editor.domain.MAX_TEXTS)
        val texts = project.texts.map { it.copy(mask = readMask(input, schema >= 24)) }
        count(project.stickers.size, 24)
        val stickers = project.stickers.map { it.copy(mask = readMask(input, schema >= 24)) }
        return project.copy(videos = videos, texts = texts, stickers = stickers)
    }

    private fun writeMask(mask: MaskState?, out: DataOutputStream) {
        out.writeBoolean(mask != null)
        if (mask == null) return
        out.writeInt(mask.type.ordinal)
        out.writeFloat(mask.centerX)
        out.writeFloat(mask.centerY)
        out.writeFloat(mask.width)
        out.writeFloat(mask.height)
        out.writeFloat(mask.rotation)
        out.writeFloat(mask.feather)
        out.writeFloat(mask.expansion)
        out.writeFloat(mask.opacity)
        out.writeBoolean(mask.inverted)
        out.writeBoolean(mask.trackingTrackId != null)
        mask.trackingTrackId?.let(out::writeUTF)
        out.writeInt(mask.customPath.size)
        mask.customPath.forEach { point ->
            out.writeFloat(point.x)
            out.writeFloat(point.y)
        }
        out.writeInt(mask.keyframes.size)
        mask.keyframes.forEach { keyframe ->
            out.writeLong(keyframe.timeUs)
            out.writeFloat(keyframe.centerX)
            out.writeFloat(keyframe.centerY)
            out.writeFloat(keyframe.width)
            out.writeFloat(keyframe.height)
            out.writeFloat(keyframe.rotation)
            out.writeFloat(keyframe.feather)
            out.writeFloat(keyframe.expansion)
            out.writeInt(keyframe.easing.ordinal)
            with(keyframe.bezier) {
                out.writeFloat(x1)
                out.writeFloat(y1)
                out.writeFloat(x2)
                out.writeFloat(y2)
            }
        }
    }

    private fun readMask(input: DataInputStream, hasTrackingId: Boolean): MaskState? {
        if (!input.readBoolean()) return null
        val type = MaskType.entries[input.readInt().also { require(it in MaskType.entries.indices) }]
        val centerX = input.readFloat()
        val centerY = input.readFloat()
        val width = input.readFloat()
        val height = input.readFloat()
        val rotation = input.readFloat()
        val feather = input.readFloat()
        val expansion = input.readFloat()
        val opacity = input.readFloat()
        val inverted = input.readBoolean()
        val trackingTrackId = if (hasTrackingId && input.readBoolean()) input.readUTF() else null
        val pointCount = input.readInt().also { require(it in 0..MaskState.MAX_MASK_PATH_POINTS) }
        val path = List(pointCount) { MaskPathPoint(input.readFloat(), input.readFloat()) }
        val keyframeCount = input.readInt().also { require(it in 0..MaskState.MAX_MASK_KEYFRAMES) }
        val keyframes = List(keyframeCount) {
            MaskKeyframe(
                timeUs = input.readLong(),
                centerX = input.readFloat(),
                centerY = input.readFloat(),
                width = input.readFloat(),
                height = input.readFloat(),
                rotation = input.readFloat(),
                feather = input.readFloat(),
                expansion = input.readFloat(),
                easing = Easing.entries[input.readInt().also { require(it in Easing.entries.indices) }],
                bezier = CubicBezier(input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat()),
            )
        }
        return MaskState(type, centerX, centerY, width, height, rotation, feather, expansion,
            opacity, inverted, path, keyframes, trackingTrackId)
    }
}
