package com.termex.replay15.editor.project

import com.termex.replay15.editor.domain.*
import java.io.DataInputStream
import java.io.DataOutputStream

/** Appended in schema 31: real 3D layers (glTF model, pose and camera tracks, environment). */
internal object Scene3DCodec {
    private const val VERSION = 1

    fun write(project: Project, out: DataOutputStream) {
        out.writeInt(VERSION)
        out.writeInt(project.scenes3D.size)
        project.scenes3D.forEach { scene ->
            out.writeUTF(scene.id); out.writeUTF(scene.name); out.writeUTF(scene.modelPath)
            out.writeLong(scene.startUs); out.writeLong(scene.endUs)
            writePose(out, scene.pose)
            out.writeInt(scene.poseKeyframes.size)
            scene.poseKeyframes.forEach { key ->
                out.writeLong(key.timeUs); writePose(out, key.pose); writeCurve(out, key.easing, key.bezier)
            }
            writeCamera(out, scene.camera)
            out.writeInt(scene.cameraKeyframes.size)
            scene.cameraKeyframes.forEach { key ->
                out.writeLong(key.timeUs); writeCamera(out, key.camera); writeCurve(out, key.easing, key.bezier)
            }
            with(scene.environment) {
                out.writeInt(preset.ordinal); out.writeUTF(environmentPath)
                out.writeFloat(lightIntensity); out.writeFloat(lightRotation); out.writeBoolean(showBackground)
                out.writeFloat(exposure); out.writeInt(toneMapping.ordinal); out.writeFloat(bloom)
                out.writeBoolean(floor); out.writeFloat(floorReflection); out.writeBoolean(shadows)
            }
            out.writeBoolean(scene.playAnimation); out.writeFloat(scene.animationSpeed)
        }
    }

    fun read(project: Project, input: DataInputStream): Project {
        val version = input.readInt()
        require(version in 1..VERSION) { "Cena 3D de uma versão mais nova do app" }
        val count = input.readInt().also { require(it in 0..MAX_SCENES_3D) }
        val scenes = List(count) {
            val id = input.readUTF(); val name = input.readUTF(); val modelPath = input.readUTF()
            val startUs = input.readLong(); val endUs = input.readLong()
            val pose = readPose(input)
            val poseKeys = List(input.readInt().also { require(it in 0..200) }) {
                val time = input.readLong(); val value = readPose(input); val (easing, bezier) = readCurve(input)
                ModelKeyframe(time, value, easing, bezier)
            }
            val camera = readCamera(input)
            val cameraKeys = List(input.readInt().also { require(it in 0..200) }) {
                val time = input.readLong(); val value = readCamera(input); val (easing, bezier) = readCurve(input)
                OrbitCameraKeyframe(time, value, easing, bezier)
            }
            val environment = Environment3D(
                preset = StudioPreset.entries[input.readInt().coerceIn(StudioPreset.entries.indices)],
                environmentPath = input.readUTF(),
                lightIntensity = input.readFloat(), lightRotation = input.readFloat(), showBackground = input.readBoolean(),
                exposure = input.readFloat(),
                toneMapping = ToneMapping.entries[input.readInt().coerceIn(ToneMapping.entries.indices)],
                bloom = input.readFloat(), floor = input.readBoolean(), floorReflection = input.readFloat(),
                shadows = input.readBoolean(),
            )
            val play = input.readBoolean(); val speed = input.readFloat()
            Scene3DClip(id, name, modelPath, startUs, endUs, pose, poseKeys, camera, cameraKeys, environment, play, speed)
        }
        return project.copy(scenes3D = scenes)
    }

    private fun writePose(out: DataOutputStream, pose: ModelPose) = with(pose) {
        out.writeFloat(positionX); out.writeFloat(positionY); out.writeFloat(positionZ)
        out.writeFloat(rotationX); out.writeFloat(rotationY); out.writeFloat(rotationZ); out.writeFloat(scale)
    }

    private fun readPose(input: DataInputStream) = ModelPose(
        input.readFloat(), input.readFloat(), input.readFloat(),
        input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat(),
    )

    private fun writeCamera(out: DataOutputStream, camera: OrbitCamera) = with(camera) {
        out.writeFloat(yaw); out.writeFloat(pitch); out.writeFloat(distance)
        out.writeFloat(targetX); out.writeFloat(targetY); out.writeFloat(targetZ)
        out.writeFloat(focalLength); out.writeFloat(roll)
    }

    private fun readCamera(input: DataInputStream) = OrbitCamera(
        input.readFloat(), input.readFloat(), input.readFloat(),
        input.readFloat(), input.readFloat(), input.readFloat(),
        input.readFloat(), input.readFloat(),
    )

    private fun writeCurve(out: DataOutputStream, easing: Easing, bezier: CubicBezier) {
        out.writeInt(easing.ordinal)
        out.writeFloat(bezier.x1); out.writeFloat(bezier.y1); out.writeFloat(bezier.x2); out.writeFloat(bezier.y2)
    }

    private fun readCurve(input: DataInputStream): Pair<Easing, CubicBezier> {
        val easing = Easing.entries[input.readInt().coerceIn(Easing.entries.indices)]
        return easing to CubicBezier(input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat())
    }
}
