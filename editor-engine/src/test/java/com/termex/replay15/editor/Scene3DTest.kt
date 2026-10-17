package com.termex.replay15.editor

import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.project.ProjectCodec
import com.termex.replay15.editor.ui.Scene3DTools
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class Scene3DTest {
    private val second = 1_000_000L

    private fun scene(id: String = "scene-1", start: Long = 0L, end: Long = 4 * second) =
        Scene3DClip(id = id, name = "Carro", modelPath = "/data/models/car.glb", startUs = start, endUs = end)

    private fun video(durationUs: Long = 10 * second) =
        VideoClip(uri = "content://video/1", name = "Clip", sourceUs = durationUs, width = 1080, height = 1920)

    @After fun resetAutoKey() { Scene3DTools.autoKey = true }

    @Test fun poseInterpolatesLinearlyAndScaleGeometrically() {
        val clip = scene().copy(poseKeyframes = listOf(
            ModelKeyframe(0L, ModelPose(positionX = 0f, rotationY = 0f, scale = 1f), Easing.LINEAR),
            ModelKeyframe(2 * second, ModelPose(positionX = 2f, rotationY = 720f, scale = 4f), Easing.LINEAR),
        ))
        val mid = clip.poseAt(second)
        assertEquals(1f, mid.positionX, 1e-5f)
        assertEquals(360f, mid.rotationY, 1e-3f) // not wrapped: a 720° spin really spins twice
        assertEquals(2f, mid.scale, 1e-4f)       // geometric: halfway between 1x and 4x is 2x
        assertEquals(ModelPose(positionX = 2f, rotationY = 720f, scale = 4f), clip.poseAt(10 * second))
        assertEquals(clip.poseKeyframes.first().pose, clip.poseAt(0L))
    }

    @Test fun overshootingCurvesNeverProduceInvalidValues() {
        listOf(Easing.ELASTIC, Easing.BACK, Easing.SPRING, Easing.BOUNCE).forEach { easing ->
            val clip = scene().copy(
                poseKeyframes = listOf(
                    ModelKeyframe(0L, ModelPose(positionX = -50f, scale = .01f), easing),
                    ModelKeyframe(second, ModelPose(positionX = 50f, scale = 100f), easing),
                ),
                cameraKeyframes = listOf(
                    OrbitCameraKeyframe(0L, OrbitCamera(pitch = OrbitCamera.MIN_PITCH, distance = OrbitCamera.MIN_DISTANCE,
                        focalLength = OrbitCamera.MIN_FOCAL, roll = -180f, targetY = -50f), easing),
                    OrbitCameraKeyframe(second, OrbitCamera(pitch = OrbitCamera.MAX_PITCH, distance = OrbitCamera.MAX_DISTANCE,
                        focalLength = OrbitCamera.MAX_FOCAL, roll = 180f, targetY = 50f), easing),
                ),
            )
            for (t in 0L..second step 5_000L) {
                clip.poseAt(t); val camera = clip.cameraAt(t)
                val basis = camera.basis()
                (basis.eye + basis.target + basis.up).forEach { assertTrue("$easing t=$t", it.isFinite()) }
            }
        }
    }

    @Test fun orbitCameraBasisMatchesItsDefinition() {
        val front = OrbitCamera(yaw = 0f, pitch = 0f, distance = 5f, targetX = 1f, targetY = 2f, targetZ = 3f).basis()
        assertArrayEquals(floatArrayOf(1f, 2f, 8f), front.eye, 1e-5f)
        assertArrayEquals(floatArrayOf(0f, 1f, 0f), front.up, 1e-5f)
        val side = OrbitCamera(yaw = 90f, pitch = 0f, distance = 5f, targetY = 0f).basis()
        assertEquals(5f, side.eye[0], 1e-4f); assertEquals(0f, side.eye[2], 1e-4f)
        val high = OrbitCamera(yaw = 0f, pitch = 30f, distance = 4f, targetY = 0f).basis()
        assertEquals(2f, high.eye[1], 1e-4f) // 4 * sin 30°
        // Up stays perpendicular to the view direction and has unit length.
        listOf(high, OrbitCamera(pitch = OrbitCamera.MAX_PITCH).basis(), OrbitCamera(roll = 37f).basis()).forEach { b ->
            val f = floatArrayOf(b.target[0] - b.eye[0], b.target[1] - b.eye[1], b.target[2] - b.eye[2])
            assertEquals(0f, f[0] * b.up[0] + f[1] * b.up[1] + f[2] * b.up[2], 1e-4f)
            assertEquals(1f, sqrt(b.up[0] * b.up[0] + b.up[1] * b.up[1] + b.up[2] * b.up[2]), 1e-4f)
        }
        // Rolling 90° turns "up" into the camera's right side (+x when looking down -z).
        val rolled = OrbitCamera(yaw = 0f, pitch = 0f, roll = 90f).basis()
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), rolled.up, 1e-4f)
    }

    @Test fun keyframeUpsertReplacesNearbyPointsAndKeepsTheirCurve() {
        val first = scene().withPoseKey(second, ModelPose(positionX = 1f), Easing.BOUNCE)
        val replaced = first.withPoseKey(second + 15_000L, ModelPose(positionX = 2f))
        assertEquals(1, replaced.poseKeyframes.size)
        assertEquals(second, replaced.poseKeyframes.single().timeUs)
        assertEquals(Easing.BOUNCE, replaced.poseKeyframes.single().easing)
        assertEquals(2f, replaced.poseKeyframes.single().pose.positionX, 0f)
        val sorted = replaced.withPoseKey(0L, ModelPose()).withPoseKey(3 * second, ModelPose())
        assertEquals(listOf(0L, second, 3 * second), sorted.poseKeyframes.map { it.timeUs })
        var full = scene()
        repeat(200) { full = full.withCameraKey(it * 25_000L, OrbitCamera()) }
        assertTrue(runCatching { full.withCameraKey(9 * second, OrbitCamera()) }.isFailure)
        assertEquals(200, full.withCameraKey(25_000L, OrbitCamera(yaw = 5f)).cameraKeyframes.size)
    }

    @Test fun timingHelpers() {
        val clip = scene(start = second, end = 3 * second)
        assertTrue(clip.isActiveAt(second)); assertFalse(clip.isActiveAt(3 * second)); assertFalse(clip.isActiveAt(0L))
        assertEquals(0L, clip.localTime(0L)); assertEquals(2 * second, clip.localTime(9 * second))
        val moved = clip.movedTo(5 * second)
        assertEquals(5 * second, moved.startUs); assertEquals(7 * second, moved.endUs)
        assertEquals(Scene3DClip.MIN_SCENE_DURATION_US, clip.withDuration(1L).durationUs)
        assertFalse(clip.copy(playAnimation = false).isAnimated)
        assertTrue(clip.isAnimated)
        assertEquals(1.5f, clip.copy(animationSpeed = 1.5f).animationSeconds(second), 1e-6f)
        assertEquals(0f, clip.copy(playAnimation = false).animationSeconds(second), 0f)
    }

    @Test fun readyMadeMovesWriteValidTracks() {
        val base = scene(end = 6 * second).copy(camera = OrbitCamera(yaw = 20f, distance = 6f, focalLength = 40f))
        SceneCameraMove.entries.forEach { move ->
            listOf(0L, 2 * second).forEach { from ->
                val result = SceneCameraMoves.apply(base, move, from)
                assertEquals("$move", listOf(from, 6 * second), result.cameraKeyframes.map { it.timeUs })
                for (t in 0L..6 * second step 100_000L) result.cameraAt(t).basis()
            }
        }
        val turntable = SceneCameraMoves.apply(base, SceneCameraMove.TURNTABLE)
        assertEquals(380f, turntable.cameraKeyframes.last().camera.yaw, 1e-4f)
        assertEquals(Easing.LINEAR, turntable.cameraKeyframes.first().easing)
        assertEquals(200f, turntable.cameraAt(3 * second).yaw, 1e-3f)
        // Dolly zoom: focal length grows with distance, so the subject keeps its size on screen.
        val dolly = SceneCameraMoves.apply(base, SceneCameraMove.DOLLY_ZOOM).cameraKeyframes.last().camera
        assertEquals(40f / 6f, dolly.focalLength / dolly.distance, 1e-3f)
        // Keyframes before the start are kept; the move continues from the pose there.
        val keyed = base.withCameraKey(0L, OrbitCamera(yaw = 0f)).withCameraKey(second, OrbitCamera(yaw = 50f))
        val chained = SceneCameraMoves.apply(keyed, SceneCameraMove.ORBIT_HALF, 3 * second)
        assertEquals(listOf(0L, second, 3 * second, 6 * second), chained.cameraKeyframes.map { it.timeUs })
        assertEquals(230f, chained.cameraKeyframes.last().camera.yaw, 1e-4f)
        assertTrue(runCatching { SceneCameraMoves.apply(base, SceneCameraMove.PUSH_IN, 6 * second - 100_000L) }.isFailure)
    }

    @Test fun projectFilesRoundTripTheThreeDLayers() {
        val keyed = scene(start = second, end = 5 * second)
            .copy(
                pose = ModelPose(1f, .5f, -2f, 10f, 45f, -5f, 1.5f),
                camera = OrbitCamera(yaw = -30f, pitch = 20f, distance = 7.5f, targetY = 1f, focalLength = 85f, roll = 3f),
                environment = Environment3D(StudioPreset.SKY, "/data/env/sky.hdr", 1.7f, -90f, false, 1.5f,
                    ToneMapping.AGX, .4f, false, .8f, false),
                playAnimation = false, animationSpeed = 2.5f,
            )
            .withPoseKey(0L, ModelPose(), Easing.ELASTIC)
            .withPoseKey(2 * second, ModelPose(positionZ = 3f, scale = 2f))
            .withCameraKey(second, OrbitCamera(yaw = 400f), Easing.BEZIER)
        val project = Project(videos = listOf(video()), scenes3D = listOf(keyed, scene(id = "scene-2")))
        val bytes = ByteArrayOutputStream().also { ProjectCodec.write(project, it) }.toByteArray()
        val loaded = ProjectCodec.read(bytes.inputStream())
        assertEquals(project, loaded)
        assertEquals(project.scenes3D, loaded.scenes3D)
    }

    @Test fun schema30FilesStillOpenWithoutThreeDLayers() {
        val project = Project(videos = listOf(video()))
        val bytes = ByteArrayOutputStream().also { ProjectCodec.write(project, it) }.toByteArray()
        // Schema 31 only appended the 3D section, so a schema 30 file is these bytes minus the tail.
        java.nio.ByteBuffer.wrap(bytes).putInt(4, 30)
        val legacy = ProjectCodec.read(bytes.copyOf(bytes.size - 8).inputStream())
        assertEquals(project, legacy)
        assertTrue(legacy.scenes3D.isEmpty())
    }

    @Test fun projectValidatesItsThreeDLayers() {
        assertTrue(runCatching { Project(scenes3D = List(MAX_SCENES_3D + 1) { scene(id = "s$it") }) }.isFailure)
        assertTrue(runCatching { Project(scenes3D = listOf(scene(id = "same"), scene(id = "same"))) }.isFailure)
        assertTrue(runCatching { scene(start = 2 * second, end = second) }.isFailure)
        assertTrue(runCatching { Environment3D(lightIntensity = 9f) }.isFailure)
        assertTrue(runCatching { ModelPose(scale = 0f) }.isFailure)
        assertTrue(runCatching { OrbitCamera(pitch = 90f) }.isFailure)
    }

    @Test fun autoKeySeedsTheStartAndWritesAtThePlayhead() {
        Scene3DTools.autoKey = true
        val base = scene().copy(pose = ModelPose(positionX = .5f))
        val moved = Scene3DTools.applyPose(base, 2 * second, base.pose, base.pose.copy(positionX = 2f))
        assertEquals(listOf(0L, 2 * second), moved.poseKeyframes.map { it.timeUs })
        assertEquals(.5f, moved.poseAt(0L).positionX, 0f)
        assertEquals(2f, moved.poseAt(2 * second).positionX, 0f)
        // At the very start there is nothing to seed.
        val atStart = Scene3DTools.applyPose(base, 0L, base.pose, base.pose.copy(positionX = 1f))
        assertEquals(listOf(0L), atStart.poseKeyframes.map { it.timeUs })
        val camera = Scene3DTools.applyCamera(base, second, base.camera, base.camera.copy(yaw = 90f))
        assertEquals(listOf(0L, second), camera.cameraKeyframes.map { it.timeUs })
    }

    @Test fun withoutAutoKeyAnEditMovesTheWholeAnimation() {
        Scene3DTools.autoKey = false
        val plain = scene()
        assertEquals(ModelPose(positionY = 1f), Scene3DTools.applyPose(plain, second, ModelPose(), ModelPose(positionY = 1f)).pose)
        val keyed = plain.withPoseKey(0L, ModelPose(positionX = 0f, scale = 1f)).withPoseKey(2 * second, ModelPose(positionX = 2f, scale = 2f))
        val before = keyed.poseAt(second)
        val shifted = Scene3DTools.applyPose(keyed, second, before, before.copy(positionX = before.positionX + 1f, scale = before.scale * 2f))
        assertEquals(listOf(1f, 3f), shifted.poseKeyframes.map { it.pose.positionX })
        assertEquals(listOf(2f, 4f), shifted.poseKeyframes.map { it.pose.scale })
        val cam = plain.withCameraKey(0L, OrbitCamera(yaw = 0f, distance = 4f)).withCameraKey(second, OrbitCamera(yaw = 90f, distance = 8f))
        val camBefore = cam.cameraAt(0L)
        val camShifted = Scene3DTools.applyCamera(cam, 0L, camBefore, camBefore.copy(yaw = 10f, distance = 2f))
        assertEquals(listOf(10f, 100f), camShifted.cameraKeyframes.map { it.camera.yaw })
        assertEquals(listOf(2f, 4f), camShifted.cameraKeyframes.map { it.camera.distance })
    }

    private operator fun FloatArray.plus(other: FloatArray) = FloatArray(size + other.size) { if (it < size) this[it] else other[it - size] }
}
