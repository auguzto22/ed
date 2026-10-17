package com.termex.replay15.editor

import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.project.ProjectCodec
import com.termex.replay15.editor.scene3d.GizmoMath
import com.termex.replay15.editor.scene3d.RealCameraMapper
import com.termex.replay15.editor.scene3d.SceneProjection
import com.termex.replay15.editor.scene3d.Vec
import com.termex.replay15.editor.ui.Scene3DTools
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class Scene3DProTest {
    private val second = 1_000_000L

    private fun scene(end: Long = 6 * second) =
        Scene3DClip(id = "scene-1", name = "Carro", modelPath = "/data/models/car.glb", startUs = 0L, endUs = end)

    @After fun resetAutoKey() { Scene3DTools.autoKey = true }

    private fun position(m: FloatArray) = floatArrayOf(m[12], m[13], m[14])
    private fun assertVec(expected: FloatArray, actual: FloatArray, tolerance: Float = 1e-3f) =
        expected.indices.forEach { assertEquals("component $it", expected[it], actual[it], tolerance) }

    // ---- hierarchy --------------------------------------------------------------------------
    @Test fun aNullMovesAndTurnsWhatIsAttachedToIt() {
        val rig = Null3D(id = "rig", name = "Rig", pose = ModelPose(positionX = 2f))
        val attached = scene().copy(nulls = listOf(rig), modelParentId = "rig", pose = ModelPose(positionX = 1f))
        assertVec(floatArrayOf(3f, 0f, 0f), position(attached.objectMatrix(MODEL_TARGET, 0L)))
        val turned = attached.copy(nulls = listOf(rig.copy(pose = ModelPose(rotationY = 90f))))
        assertVec(floatArrayOf(0f, 0f, -1f), position(turned.objectMatrix(MODEL_TARGET, 0L)))
        val free = turned.withParent(MODEL_TARGET, null)
        assertVec(floatArrayOf(1f, 0f, 0f), position(free.objectMatrix(MODEL_TARGET, 0L)))
    }

    @Test fun nullsAnimateWithTheirOwnKeyframes() {
        val rig = Null3D(id = "rig", name = "Rig")
        val clip = scene().copy(nulls = listOf(rig), modelParentId = "rig")
            .withPoseKey(0L, ModelPose(), target = "rig")
            .withPoseKey(2 * second, ModelPose(positionX = 4f), Easing.LINEAR, target = "rig")
        assertVec(floatArrayOf(2f, 0f, 0f), position(clip.objectMatrix(MODEL_TARGET, second)))
        assertTrue(clip.isAnimated.not() || clip.nulls.single().poseKeyframes.size == 2)
        assertEquals(0, clip.poseKeyframes.size)
    }

    @Test fun hierarchiesCannotLoopOrPointToMissingNulls() {
        val a = Null3D(id = "a", name = "A"); val b = Null3D(id = "b", name = "B", parentId = "a")
        val base = scene().copy(nulls = listOf(a, b))
        assertEquals(listOf("a", "b"), base.eligibleParents(MODEL_TARGET).map { it.id })
        assertEquals(emptyList<String>(), base.eligibleParents("a").map { it.id }) // b descends from a, a is itself
        assertEquals(listOf("a"), base.eligibleParents("b").map { it.id })
        assertTrue(runCatching { base.withParent("a", "b") }.isFailure)
        assertTrue(runCatching { base.copy(modelParentId = "ghost") }.isFailure)
        assertTrue(runCatching { Null3D(id = "x", name = "X", parentId = "x") }.isFailure)
        assertTrue(runCatching { scene().copy(nulls = listOf(a, a)) }.isFailure)
    }

    @Test fun removingANullHandsItsChildrenToItsParent() {
        val a = Null3D(id = "a", name = "A"); val b = Null3D(id = "b", name = "B", parentId = "a")
        val clip = scene().copy(nulls = listOf(a, b), modelParentId = "b", cameraParentId = "b")
            .copy(cameraShots = listOf(CameraShot(id = "s1", name = "S", startUs = 0L, endUs = second, parentId = "b")))
        val removed = Scene3DTools.removeNull(clip, "b")
        assertEquals(listOf("a"), removed.nulls.map { it.id })
        assertEquals("a", removed.modelParentId); assertEquals("a", removed.cameraParentId)
        assertEquals("a", removed.cameraShots.single().parentId)
        assertNull(Scene3DTools.removeNull(clip, "a").nulls.single().parentId)
    }

    // ---- cameras ----------------------------------------------------------------------------
    @Test fun theLaterShotWinsAndMainCameraFillsTheGaps() {
        val s1 = CameraShot(id = "s1", name = "A", startUs = second, endUs = 4 * second, camera = OrbitCamera(yaw = 10f))
        val s2 = CameraShot(id = "s2", name = "B", startUs = 2 * second, endUs = 3 * second, camera = OrbitCamera(yaw = 20f))
        val clip = scene().copy(cameraShots = listOf(s1, s2), camera = OrbitCamera(yaw = 5f))
        assertEquals(MAIN_CAMERA, clip.activeCameraId(0L))
        assertEquals("s1", clip.activeCameraId(second))
        assertEquals("s2", clip.activeCameraId(2 * second))
        assertEquals("s1", clip.activeCameraId(3 * second))
        assertEquals(MAIN_CAMERA, clip.activeCameraId(4 * second))
        assertEquals(20f, clip.cameraAt("s2", 0L).yaw, 0f)
        assertEquals(clip.cameraAt("s2", 2 * second).basis().eye[0], clip.worldCamera(2 * second).eye[0], 1e-4f)
        assertTrue(runCatching { CameraShot(id = "x", name = "x", startUs = 5L, endUs = 5L) }.isFailure)
    }

    @Test fun aCameraAttachedToANullFollowsIt() {
        val rig = Null3D(id = "rig", name = "Rig", pose = ModelPose(positionX = 3f))
        val free = scene().copy(nulls = listOf(rig))
        val attached = free.withParent(MAIN_CAMERA, "rig")
        assertVec(free.worldCamera(0L).eye.let { floatArrayOf(it[0] + 3f, it[1], it[2]) }, attached.worldCamera(0L).eye)
        assertVec(floatArrayOf(3f, free.camera.targetY, 0f), attached.worldCamera(0L).target)
    }

    @Test fun movesTargetTheChosenCameraOnly() {
        val shot = CameraShot(id = "s1", name = "A", startUs = second, endUs = 4 * second)
        val clip = scene().copy(cameraShots = listOf(shot))
        val moved = SceneCameraMoves.apply(clip, SceneCameraMove.TURNTABLE, second, "s1")
        assertTrue(moved.cameraKeyframes.isEmpty())
        val keys = moved.shotById("s1")!!.keyframes
        assertEquals(listOf(second, 4 * second), keys.map { it.timeUs })
        assertEquals(shot.camera.yaw + 360f, keys.last().camera.yaw, 1e-3f)
        assertTrue(runCatching { SceneCameraMoves.apply(clip, SceneCameraMove.PUSH_IN, 0L, "ghost") }.isFailure)
    }

    @Test fun fromBasisRebuildsTheOrbitCamera() {
        val random = Random(7)
        repeat(200) {
            val camera = OrbitCamera(yaw = random.nextFloat() * 700f - 350f, pitch = random.nextFloat() * 160f - 80f,
                distance = .5f + random.nextFloat() * 20f, targetX = random.nextFloat() * 4f - 2f, targetY = random.nextFloat() * 4f - 2f,
                targetZ = random.nextFloat() * 4f - 2f, focalLength = 35f + random.nextFloat() * 100f, roll = random.nextFloat() * 120f - 60f)
            val basis = camera.basis()
            val forward = floatArrayOf(basis.target[0] - basis.eye[0], basis.target[1] - basis.eye[1], basis.target[2] - basis.eye[2])
            val back = OrbitCamera.fromBasis(basis.eye, forward, basis.up, camera)
            assertEquals(camera.yaw, back.yaw, 1e-2f); assertEquals(camera.pitch, back.pitch, 1e-2f)
            assertEquals(camera.roll, back.roll, 5e-2f)
            assertEquals(camera.targetX, back.targetX, 1e-3f); assertEquals(camera.targetY, back.targetY, 1e-3f)
            assertEquals(camera.targetZ, back.targetZ, 1e-3f)
            assertEquals(camera.focalLength, back.focalLength, 0f)
        }
        // Pitch past the orbit range is clamped, not rejected.
        val steep = OrbitCamera.fromBasis(floatArrayOf(0f, 5f, 0.01f), floatArrayOf(0f, -1f, 0f), floatArrayOf(0f, 0f, -1f), OrbitCamera())
        assertEquals(OrbitCamera.MAX_PITCH, steep.pitch, 0f)
    }

    // ---- timeline edits ---------------------------------------------------------------------
    @Test fun movingKeepsTheAnimationAndTrimmingKeepsItInPlace() {
        val clip = scene(6 * second).copy(startUs = 2 * second, endUs = 8 * second)
            .withPoseKey(0L, ModelPose(), Easing.LINEAR).withPoseKey(4 * second, ModelPose(positionX = 4f), Easing.LINEAR)
        val moved = clip.retimed(3 * second, 9 * second)
        assertEquals(clip.poseKeyframes, moved.poseKeyframes)
        assertEquals(3 * second, moved.startUs)
        // Cutting the first second off: what was at project time 3 s (local 1 s) is now local 0.
        val trimmed = clip.retimed(3 * second, 8 * second)
        assertEquals(5 * second, trimmed.durationUs)
        assertEquals(1f, trimmed.poseAt(0L).positionX, 1e-4f)
        assertEquals(clip.poseAt(second).positionX, trimmed.poseAt(0L).positionX, 1e-4f)
        assertEquals(clip.poseAt(4 * second).positionX, trimmed.poseAt(3 * second).positionX, 1e-4f)
        // Pulling the start earlier shifts the animation later (local times grow).
        val extended = clip.retimed(second, 8 * second)
        assertEquals(clip.poseAt(0L).positionX, extended.poseAt(second).positionX, 1e-4f)
    }

    @Test fun shotTimingStaysInsideTheScene() {
        val shot = CameraShot(id = "s1", name = "A", startUs = second, endUs = 3 * second)
        val clip = scene().copy(cameraShots = listOf(shot)).withCameraKey(2 * second, OrbitCamera(yaw = 9f), cameraId = "s1")
        val moved = clip.withShotTiming("s1", 2 * second, 4 * second)
        assertEquals(3 * second, moved.shotById("s1")!!.keyframes.single().timeUs)
        val clamped = clip.withShotTiming("s1", 5 * second, 7 * second)
        assertEquals(4 * second, clamped.shotById("s1")!!.startUs); assertEquals(6 * second, clamped.shotById("s1")!!.endUs)
        val trimmed = clip.withShotTiming("s1", second, 2 * second + 500_000L)
        assertEquals(2 * second, trimmed.shotById("s1")!!.keyframes.single().timeUs)
    }

    // ---- Auto-Key on nulls and shots --------------------------------------------------------
    @Test fun autoKeyWritesToTheChosenObjectAndCamera() {
        val rig = Null3D(id = "rig", name = "Rig")
        val clip = scene().copy(nulls = listOf(rig), cameraShots = listOf(CameraShot(id = "s1", name = "A", startUs = 0L, endUs = 3 * second)))
        val posed = Scene3DTools.applyPose(clip, 2 * second, ModelPose(), ModelPose(positionY = 1f), "rig")
        assertEquals(listOf(0L, 2 * second), posed.nullById("rig")!!.poseKeyframes.map { it.timeUs })
        assertTrue(posed.poseKeyframes.isEmpty())
        val shot = Scene3DTools.applyCamera(clip, second, OrbitCamera(), OrbitCamera(yaw = 99f), "s1")
        assertEquals(99f, shot.shotById("s1")!!.keyframes.last().camera.yaw, 0f)
        assertTrue(shot.cameraKeyframes.isEmpty())
        Scene3DTools.autoKey = false
        val flat = Scene3DTools.applyPose(clip, 0L, ModelPose(), ModelPose(positionX = 2f), "rig")
        assertEquals(2f, flat.nullById("rig")!!.pose.positionX, 0f)
        assertTrue(flat.nullById("rig")!!.poseKeyframes.isEmpty())
    }

    // ---- file format ------------------------------------------------------------------------
    @Test fun nullsShotsAndParentsSurviveTheProjectFile() {
        val a = Null3D(id = "a", name = "Rig", pose = ModelPose(1f, 2f, 3f, 4f, 5f, 6f, 1.5f),
            poseKeyframes = listOf(ModelKeyframe(0L, ModelPose()), ModelKeyframe(second, ModelPose(positionX = 1f), Easing.BOUNCE)))
        val b = Null3D(id = "b", name = "Filho", parentId = "a")
        val shot = CameraShot(id = "s1", name = "Close", startUs = second, endUs = 3 * second, camera = OrbitCamera(yaw = 44f, focalLength = 85f),
            keyframes = listOf(OrbitCameraKeyframe(second, OrbitCamera(), Easing.LINEAR), OrbitCameraKeyframe(2 * second, OrbitCamera(distance = 2f))),
            parentId = "b")
        val clip = scene().copy(nulls = listOf(a, b), modelParentId = "a", cameraParentId = "b", cameraShots = listOf(shot))
        val project = Project(videos = listOf(VideoClip(uri = "content://v", name = "v", sourceUs = 10 * second, width = 1080, height = 1920)),
            scenes3D = listOf(clip))
        val bytes = ByteArrayOutputStream().also { ProjectCodec.write(project, it) }.toByteArray()
        assertEquals(project, ProjectCodec.read(bytes.inputStream()))
    }

    // ---- gizmo math -------------------------------------------------------------------------
    private fun projection() = SceneProjection(scene(), 0L, 9f / 16f, Camera3D())

    @Test fun dragAlongAnAxisReturnsTheWorldDistance() {
        val projection = projection()
        val origin = floatArrayOf(0.3f, 0.5f, -0.2f)
        for (axis in listOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f))) {
            val from = projection.project(origin)!!
            val to = projection.project(Vec.add(origin, Vec.scale(axis, .8f)))!!
            val moved = GizmoMath.axisDrag(projection, origin, axis, from, to)!!
            assertEquals(.8f, moved, 2e-3f)
        }
    }

    @Test fun planeDragLandsOnTheTargetPoint() {
        val projection = projection()
        val origin = floatArrayOf(0f, .6f, 0f)
        val target = floatArrayOf(.4f, .9f, .3f)
        // A point on the plane facing the camera through the origin.
        val normal = Vec.normalize(Vec.sub(projection.eye, origin))
        val inPlane = Vec.sub(target, Vec.scale(normal, Vec.dot(Vec.sub(target, origin), normal)))
        val delta = GizmoMath.planeDrag(projection, origin, projection.project(origin)!!, projection.project(inPlane)!!)!!
        assertVec(Vec.sub(inPlane, origin), delta, 3e-3f)
    }

    @Test fun rotationDragMeasuresTheAngleAroundTheAxis() {
        val projection = projection()
        val origin = floatArrayOf(0f, .6f, 0f)
        val axis = floatArrayOf(0f, 1f, 0f)
        fun onRing(degrees: Float): FloatArray {
            val r = Math.toRadians(degrees.toDouble())
            return projection.project(Vec.add(origin, floatArrayOf((.7 * Math.cos(r)).toFloat(), 0f, (-.7 * Math.sin(r)).toFloat())))!!
        }
        val turned = GizmoMath.rotationDrag(projection, origin, axis, onRing(10f), onRing(55f))!!
        assertEquals(45f, turned, .5f) // counter-clockwise seen from +Y
        assertEquals(-45f, GizmoMath.rotationDrag(projection, origin, axis, onRing(55f), onRing(10f))!!, .5f)
    }

    @Test fun eulerAnglesRoundTripAndStayContinuous() {
        val random = Random(11)
        repeat(500) {
            val angles = floatArrayOf(random.nextFloat() * 340f - 170f, random.nextFloat() * 170f - 85f, random.nextFloat() * 340f - 170f)
            val r = GizmoMath.rotation(angles[0], angles[1], angles[2])
            val back = GizmoMath.euler(r, angles)
            val again = GizmoMath.rotation(back[0], back[1], back[2])
            r.indices.forEach { assertEquals(r[it], again[it], 2e-4f) }
            angles.indices.forEach { assertEquals(angles[it], back[it], .05f) }
        }
        // Gimbal lock (pitch exactly ±90°) still rebuilds the same rotation.
        for (y in listOf(90f, -90f)) {
            val r = GizmoMath.rotation(20f, y, 35f)
            val back = GizmoMath.euler(r, floatArrayOf(20f, y, 35f))
            val again = GizmoMath.rotation(back[0], back[1], back[2])
            r.indices.forEach { assertEquals(r[it], again[it], 2e-3f) }
        }
    }

    @Test fun turningAndMovingUnderARotatedParentHappensInTheSpaceTheUserSees() {
        val parent = ModelPose(rotationY = 90f, positionX = 5f).toMatrix()
        val pose = ModelPose(positionX = 1f)
        // World +X is the parent's local +Z (it is turned 90° around Y).
        val moved = GizmoMath.translate(pose, parent, floatArrayOf(2f, 0f, 0f))
        val world = FloatArray(16).also { Matrix4.multiply(it, parent, moved.toMatrix()) }
        val before = FloatArray(16).also { Matrix4.multiply(it, parent, pose.toMatrix()) }
        assertEquals(before[12] + 2f, world[12], 1e-3f); assertEquals(before[13], world[13], 1e-3f); assertEquals(before[14], world[14], 1e-3f)
        // Turning 30° around world Y changes only the world yaw.
        val turned = GizmoMath.rotate(ModelPose(rotationX = 10f), parent, floatArrayOf(0f, 1f, 0f), 30f)
        val worldRotation = GizmoMath.multiply3(GizmoMath.orthonormal(GizmoMath.linear(parent)), GizmoMath.rotation(turned.rotationX, turned.rotationY, turned.rotationZ))
        val expected = GizmoMath.multiply3(GizmoMath.axisAngle(floatArrayOf(0f, 1f, 0f), 30f),
            GizmoMath.multiply3(GizmoMath.orthonormal(GizmoMath.linear(parent)), GizmoMath.rotation(10f, 0f, 0f)))
        worldRotation.indices.forEach { assertEquals(expected[it], worldRotation[it], 2e-4f) }
    }

    @Test fun projectionAndRayAreInverse() {
        val projection = projection()
        val point = floatArrayOf(.5f, 1f, -.4f)
        val ndc = projection.project(point)!!
        val ray = projection.ray(ndc[0], ndc[1])!!
        val t = Vec.dot(Vec.sub(point, ray.first), ray.second)
        assertVec(point, Vec.add(ray.first, Vec.scale(ray.second, t)), 2e-3f)
        assertNull(projection.project(Vec.add(projection.eye, Vec.scale(Vec.sub(projection.eye, floatArrayOf(0f, .6f, 0f)), 3f))))
    }

    // ---- Real Camera ------------------------------------------------------------------------
    private fun phone(x: Float = 0f, y: Float = 1f, z: Float = 0f, turnY: Float = 0f) = ModelPose(positionX = x, positionY = y, positionZ = z, rotationY = turnY).toMatrix()
    private val flat = OrbitCamera(yaw = 0f, pitch = 0f, distance = 5f, targetX = 0f, targetY = 0f, targetZ = 0f)

    @Test fun movingThePhoneForwardMovesTheCameraForward() {
        val mapper = RealCameraMapper(flat)
        assertNull(mapper.map(phone()))
        mapper.reset(phone())
        val same = mapper.map(phone())!!
        assertEquals(flat.yaw, same.yaw, 1e-3f); assertEquals(0f, same.targetZ, 1e-3f)
        val ahead = mapper.map(phone(z = -1f))!!
        assertEquals(-1f, ahead.targetZ, 1e-3f); assertEquals(0f, ahead.yaw, 1e-3f); assertEquals(5f, ahead.distance, 0f)
        assertEquals(-.5f, mapper.map(phone(z = -1f), multiplier = .5f)!!.targetZ, 1e-3f)
        assertEquals(1f, mapper.map(phone(z = -1f), invert = true)!!.targetZ, 1e-3f)
        val side = mapper.map(phone(x = 2f))!!
        assertEquals(2f, side.targetX, 1e-3f)
    }

    @Test fun turningThePhoneLeftTurnsTheCameraLeft() {
        val mapper = RealCameraMapper(flat)
        mapper.reset(phone())
        val left = mapper.map(phone(turnY = 90f))!!
        assertEquals(90f, left.yaw, 1e-2f)
        assertEquals(-90f, mapper.map(phone(turnY = 90f), invert = true)!!.yaw, 1e-2f)
        // The start pose of the phone does not matter, only the change since reset.
        val turnedStart = RealCameraMapper(flat)
        turnedStart.reset(phone(x = 3f, z = 2f, turnY = 40f))
        assertEquals(90f, turnedStart.map(phone(x = 3f, z = 2f, turnY = 130f))!!.yaw, 1e-2f)
    }

    @Test fun motionIsAppliedInTheStartingCamerasOwnAxes() {
        val mapper = RealCameraMapper(OrbitCamera(yaw = 90f, pitch = 0f, distance = 5f, targetX = 0f, targetY = 0f, targetZ = 0f))
        mapper.reset(phone())
        val ahead = mapper.map(phone(z = -1f))!!
        assertEquals(-1f, ahead.targetX, 1e-3f); assertEquals(0f, ahead.targetZ, 1e-3f); assertEquals(90f, ahead.yaw, 1e-3f)
        assertNull(mapper.map(FloatArray(16) { Float.NaN }))
    }

    @Test fun aTakeReplacesOnlyItsRange() {
        val old = scene().withCameraKey(0L, OrbitCamera(yaw = 1f)).withCameraKey(2 * second, OrbitCamera(yaw = 2f)).withCameraKey(5 * second, OrbitCamera(yaw = 3f))
        val take = listOf(1, 2, 3).map { OrbitCameraKeyframe(it * second, OrbitCamera(yaw = 10f * it), Easing.LINEAR) }
        val result = old.withCameraRecording(MAIN_CAMERA, second, 3 * second, take)
        assertEquals(listOf(0L, second, 2 * second, 3 * second, 5 * second), result.cameraKeyframes.map { it.timeUs })
        assertEquals(20f, result.cameraKeyframes[2].camera.yaw, 0f)
        assertTrue(runCatching { old.withCameraRecording(MAIN_CAMERA, 3 * second, second, emptyList()) }.isFailure)
        assertTrue(runCatching { old.withCameraRecording(MAIN_CAMERA, 0L, second, listOf(take[1])) }.isFailure)
    }
}
