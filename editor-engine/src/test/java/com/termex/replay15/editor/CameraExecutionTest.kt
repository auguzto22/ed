package com.termex.replay15.editor

import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.core.EditorChangeClassifier
import com.termex.replay15.editor.preview.engine.ActiveClipResolver
import com.termex.replay15.editor.preview.engine.RenderStateEvaluator
import com.termex.replay15.editor.render.RenderPlan
import org.junit.Assert.*
import org.junit.Test

class CameraExecutionTest {
    private val scene = Camera3D.SceneMatrix()
    private fun projected(camera: Camera3D, x: Float = .5f, y: Float = .3f): FloatArray {
        val p = Matrix4.transformPoint(scene.evaluate(camera, 16f / 9f), x, y, 0f)
        return floatArrayOf(p[0] / p[3], p[1] / p[3])
    }

    @Test fun fovChangesFramingInsteadOfCancellingItWithEyeDistance() {
        assertEquals(.5f, projected(Camera3D())[0], .0001f)
        assertTrue(projected(Camera3D(fieldOfView = 30f))[0] > .9f)
        assertTrue(projected(Camera3D(fieldOfView = 100f))[0] < .3f)
        assertTrue(projected(Camera3D(positionZ = 2f))[0] < .3f)
        assertTrue(projected(Camera3D(positionX = .2f))[0] < .5f)
        assertTrue(projected(Camera3D(positionY = .2f))[1] > .3f)
    }

    @Test fun pitchYawAndRollChangeProjectedVertices() {
        val base = projected(Camera3D())
        listOf(Camera3D(rotationX = 45f), Camera3D(rotationY = 45f), Camera3D(rotationZ = 45f)).forEach {
            val p = projected(it)
            assertTrue(kotlin.math.abs(p[0] - base[0]) + kotlin.math.abs(p[1] - base[1]) > .1f)
        }
        val yaw = scene.evaluate(Camera3D(rotationY = 45f), 1f)
        val left = Matrix4.transformPoint(yaw, -1f, 0f, 0f)
        val right = Matrix4.transformPoint(yaw, 1f, 0f, 0f)
        assertTrue("Perspective must vary homogeneous w across the quad", kotlin.math.abs(left[3] - right[3]) > 1f)
    }

    @Test fun viewIsInverseOfCameraWorldIncludingCombinedEulerRotation() {
        val camera = Camera3D(positionX = .2f, positionY = -.1f, positionZ = 1f,
            rotationX = 20f, rotationY = -30f, rotationZ = 40f)
        val world = Matrix4.createIdentity()
        Matrix4.translate(world, .4f, .2f, camera.canonicalDistance(60f) + 1f)
        Matrix4.rotateEuler(world, 20f, -30f, 40f)
        val product = FloatArray(16)
        Matrix4.multiply(product, camera.toViewMatrix(), world)
        assertArrayEquals(Matrix4.createIdentity(), product, .00001f)
    }

    @Test fun extremeDepthRemainsInFrontOfThePlaneAndInvalidMatrixFallsBack() {
        val view = Camera3D(positionZ = -1000f).toViewMatrix()
        assertEquals(-.25f, view[14], .00001f)
        assertTrue(scene.evaluate(Camera3D(positionX = Float.MAX_VALUE), 1f).all { it.isFinite() })
        assertArrayEquals(Matrix4.createIdentity(), scene.evaluate(Camera3D(positionX = 1f), Float.NaN), 0f)
    }

    @Test fun trackUsesShortestAngularPathAndGlobalProjectTimeAcrossClips() {
        val camera = Camera3D(keyframes = listOf(
            CameraKeyframe(0, rotationZ = 170f, positionX = 0f, easing = Easing.LINEAR),
            CameraKeyframe(6 * SECOND, rotationZ = -170f, positionX = 1f)))
        assertEquals(180f, camera.cameraAt(3 * SECOND).rotationZ, .0001f)
        assertEquals(170f, camera.cameraAt(0).rotationZ, .0001f)
        assertEquals(-170f, camera.cameraAt(8 * SECOND).rotationZ, .0001f)
        val clip = VideoClip(uri = "file:///fixture.mp4", name = "fixture", width = 320, height = 180, sourceUs = 8 * SECOND,
            inUs = SECOND, outUs = 5 * SECOND, speed = 2f)
        val project = Project(videos = listOf(clip, clip.copy(id = "second"), clip.copy(id = "third")), camera = camera)
        val snapshot = ActiveClipResolver(project).resolve(3 * SECOND)
        val render = RenderStateEvaluator.evaluate(snapshot, project, 1, 1, 320, 180)
        assertEquals(.5f, render.camera.positionX, .0001f)
        assertEquals(180f, render.camera.rotationZ, .0001f)
        assertEquals(3 * SECOND, render.layers.single().sourceTimeUs)
    }

    @Test fun upsertReplacesNearbyPointPreservesEasingAndSorts() {
        val camera = Camera3D(keyframes = listOf(CameraKeyframe(SECOND, easing = Easing.LINEAR), CameraKeyframe(3 * SECOND)))
        val updated = camera.upsert(SECOND + 20_000, Camera3D(rotationY = 45f))
        assertEquals(2, updated.keyframes.size)
        assertEquals(SECOND + 20_000, updated.keyframes.first().timeUs)
        assertEquals(Easing.LINEAR, updated.keyframes.first().easing)
        assertEquals(45f, updated.keyframes.first().rotationY, 0f)
        val inserted = updated.upsert(0, Camera3D())
        assertEquals(listOf(0L, SECOND + 20_000, 3 * SECOND), inserted.keyframes.map { it.timeUs })
        assertEquals(EditorChangeClassifier.ChangeKind.RENDER_ONLY,
            EditorChangeClassifier.classify(Project(camera = camera), Project(camera = updated)))
    }

    @Test fun sparseExportSequencesRetainTimelinePlacementAndSourceMapping() {
        val clip = VideoClip(uri = "file:///fixture.mp4", name = "fixture", width = 320, height = 180, sourceUs = 10 * SECOND,
            inUs = SECOND, outUs = 5 * SECOND, speed = 2f)
        val clips = listOf(TimedVideoClip(0, clip), TimedVideoClip(4_070_000, clip.copy(id = "later")))
        // A transition places alternating main clips on separate Media3 inputs.
        // The second input must begin with 4.07 seconds of gap before its first frame.
        assertEquals(4_070_000L, RenderPlan.gapBefore(0L, clips[1]))
        var sequenceEnd = 0L
        clips.forEach { placed ->
            sequenceEnd += RenderPlan.gapBefore(sequenceEnd, placed)
            assertEquals(placed.startUs, sequenceEnd)
            sequenceEnd += placed.clip.durationUs
        }
        val layer = com.termex.replay15.editor.render.RenderLayer("test", clips)
        for (time in 4_070_000L..6_050_000L step 33_333L) {
            val active = layer.activeAt(time)!!
            assertEquals("later", active.clip.id)
            val source = RenderPlan.sourceTime(active.clip, active.startUs, time)
            assertTrue(source >= clip.inUs && source < clip.outUs)
        }
        assertEquals(6_070_000L, sequenceEnd)
    }

    @Test fun writeSharedMatricesForHostGpuRegression() {
        val cameras = linkedMapOf("default" to Camera3D(), "fov" to Camera3D(fieldOfView = 100f),
            "x" to Camera3D(positionX = .2f), "y" to Camera3D(positionY = .2f), "z" to Camera3D(positionZ = 1f),
            "pitch" to Camera3D(rotationX = 45f), "yaw" to Camera3D(rotationY = 45f), "roll" to Camera3D(rotationZ = 45f))
        val track = Camera3D(keyframes = listOf(CameraKeyframe(0), CameraKeyframe(6 * SECOND, rotationY = 25f, fieldOfView = 90f)))
        for (time in 0L..6 * SECOND step SECOND / 2) cameras["time$time"] = track.cameraAt(time)
        val file = java.io.File("build/camera-gpu-matrices.txt")
        requireNotNull(file.parentFile).mkdirs()
        file.writeText(cameras.entries.joinToString("\n") { (name, camera) -> name + " " + scene.evaluate(camera, 16f / 9f).joinToString(" ") })
    }
}
