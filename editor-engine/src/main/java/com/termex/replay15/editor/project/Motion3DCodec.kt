package com.termex.replay15.editor.project

import com.termex.replay15.editor.domain.*
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Appended in schema 16: Camera3D, 2.5D layer properties, parenting, Null Objects,
 * and 3D keyframe extensions (Z, rotationX, rotationY, scaleZ, anchor).
 */
internal object Motion3DCodec {

    fun write(project: Project, out: DataOutputStream) {
        // 1. Camera3D
        with(project.camera) {
            out.writeFloat(positionX); out.writeFloat(positionY); out.writeFloat(positionZ)
            out.writeFloat(rotationX); out.writeFloat(rotationY); out.writeFloat(rotationZ)
            out.writeFloat(fieldOfView); out.writeFloat(focusDistance); out.writeFloat(depthOfFieldAmount)
            out.writeFloat(nearPlane); out.writeFloat(farPlane)
            out.writeInt(keyframes.size)
            keyframes.forEach { k ->
                out.writeLong(k.timeUs)
                out.writeFloat(k.positionX); out.writeFloat(k.positionY); out.writeFloat(k.positionZ)
                out.writeFloat(k.rotationX); out.writeFloat(k.rotationY); out.writeFloat(k.rotationZ)
                out.writeFloat(k.fieldOfView); out.writeFloat(k.focusDistance); out.writeFloat(k.depthOfFieldAmount)
                out.writeInt(k.easing.ordinal)
                with(k.bezier) { out.writeFloat(x1); out.writeFloat(y1); out.writeFloat(x2); out.writeFloat(y2) }
            }
        }

        // Helper to write clip 3D metadata
        fun writeClip3D(v: VideoClip) {
            out.writeBoolean(v.is3D)
            out.writeBoolean(v.isNullObject)
            val parent = v.parentId
            out.writeBoolean(parent != null)
            if (parent != null) out.writeUTF(parent)
            with(v.transform3D) {
                out.writeFloat(positionX); out.writeFloat(positionY); out.writeFloat(positionZ)
                out.writeFloat(rotationX); out.writeFloat(rotationY); out.writeFloat(rotationZ)
                out.writeFloat(scaleX); out.writeFloat(scaleY); out.writeFloat(scaleZ)
                out.writeFloat(anchorX); out.writeFloat(anchorY); out.writeFloat(anchorZ)
                out.writeFloat(opacity)
            }
            // 3D extensions for each transform keyframe
            v.keyframes.forEach { k ->
                out.writeFloat(k.z)
                out.writeFloat(k.rotationX)
                out.writeFloat(k.rotationY)
                out.writeFloat(k.scaleZ)
                out.writeFloat(k.anchorX)
                out.writeFloat(k.anchorY)
                out.writeFloat(k.anchorZ)
            }
        }

        // 2. Videos 3D metadata
        project.videos.forEach(::writeClip3D)
    }

    fun read(project: Project, input: DataInputStream): Project {
        // 1. Camera3D
        val camPosX = input.readFloat(); val camPosY = input.readFloat(); val camPosZ = input.readFloat()
        val camRotX = input.readFloat(); val camRotY = input.readFloat(); val camRotZ = input.readFloat()
        val camFov = input.readFloat(); val camFocus = input.readFloat(); val camDof = input.readFloat()
        val camNear = input.readFloat(); val camFar = input.readFloat()
        val keyCount = input.readInt().also { require(it in 0..200) }
        val camKeys = List(keyCount) {
            CameraKeyframe(
                timeUs = input.readLong(),
                positionX = input.readFloat(), positionY = input.readFloat(), positionZ = input.readFloat(),
                rotationX = input.readFloat(), rotationY = input.readFloat(), rotationZ = input.readFloat(),
                fieldOfView = input.readFloat(), focusDistance = input.readFloat(), depthOfFieldAmount = input.readFloat(),
                easing = Easing.entries[input.readInt().coerceIn(Easing.entries.indices)],
                bezier = CubicBezier(input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat()),
            )
        }
        val camera = Camera3D(
            positionX = camPosX, positionY = camPosY, positionZ = camPosZ,
            rotationX = camRotX, rotationY = camRotY, rotationZ = camRotZ,
            fieldOfView = camFov, focusDistance = camFocus, depthOfFieldAmount = camDof,
            nearPlane = camNear, farPlane = camFar, keyframes = camKeys,
        )

        // Helper to read clip 3D metadata
        fun readClip3D(v: VideoClip): VideoClip {
            val is3D = input.readBoolean()
            val isNull = input.readBoolean()
            val hasParent = input.readBoolean()
            val parentId = if (hasParent) input.readUTF() else null
            val t3D = Transform3D(
                positionX = input.readFloat(), positionY = input.readFloat(), positionZ = input.readFloat(),
                rotationX = input.readFloat(), rotationY = input.readFloat(), rotationZ = input.readFloat(),
                scaleX = input.readFloat(), scaleY = input.readFloat(), scaleZ = input.readFloat(),
                anchorX = input.readFloat(), anchorY = input.readFloat(), anchorZ = input.readFloat(),
                opacity = input.readFloat(),
            )
            val updatedKeys = v.keyframes.map { k ->
                k.copy(
                    z = input.readFloat(),
                    rotationX = input.readFloat(),
                    rotationY = input.readFloat(),
                    scaleZ = input.readFloat(),
                    anchorX = input.readFloat(),
                    anchorY = input.readFloat(),
                    anchorZ = input.readFloat(),
                )
            }
            return v.copy(
                is3D = is3D,
                isNullObject = isNull,
                parentId = parentId,
                transform3D = t3D,
                keyframes = updatedKeys,
            )
        }

        return project.copy(
            camera = camera,
            videos = project.videos.map(::readClip3D),
        )
    }
}
