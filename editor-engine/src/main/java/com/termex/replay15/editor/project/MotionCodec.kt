package com.termex.replay15.editor.project

import com.termex.replay15.editor.domain.*
import java.io.DataInputStream
import java.io.DataOutputStream

/** Appended in schema 9; nested video tracks use the same project envelope. */
internal object MotionCodec {
    fun write(project: Project, out: DataOutputStream) {
        project.videos.forEach { clip ->
            out.writeBoolean(clip.preservePitch)
            out.writeInt(clip.speedCurve.size)
            clip.speedCurve.forEach { out.writeLong(it.sourceUs); out.writeFloat(it.speed) }
            clip.keyframes.forEach { key ->
                with(key.bezier) { out.writeFloat(x1); out.writeFloat(y1); out.writeFloat(x2); out.writeFloat(y2) }
            }
            with(clip.grade) {
                listOf(maskX, maskY, maskRotation, maskAspect, maskOpacity, chromaSmoothness, chromaSpill, chromaEdge).forEach(out::writeFloat)
                hsl.forEach { out.writeFloat(it.hue); out.writeFloat(it.saturation); out.writeFloat(it.luminance) }
                channelCurves.forEach { channel -> channel.forEach(out::writeFloat) }
            }
        }
    }
    fun read(project: Project, input: DataInputStream): Project = project.copy(videos = project.videos.map { clip ->
        val pitch = input.readBoolean()
        val size = input.readInt().also { require(it in 0..32) }
        val curve = List(size) { SpeedPoint(input.readLong(), input.readFloat()) }
        val keys = clip.keyframes.map { it.copy(bezier = CubicBezier(input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat())) }
        val grade = clip.grade.copy(maskX = input.readFloat(), maskY = input.readFloat(), maskRotation = input.readFloat(),
            maskAspect = input.readFloat(), maskOpacity = input.readFloat(), chromaSmoothness = input.readFloat(), chromaSpill = input.readFloat(),
            chromaEdge = input.readFloat(), hsl = List(8) { HslAdjustment(input.readFloat(), input.readFloat(), input.readFloat()) },
            channelCurves = List(3) { List(5) { input.readFloat() } })
        clip.copy(preservePitch = pitch, speedCurve = curve, keyframes = keys, grade = grade)
    })
}
