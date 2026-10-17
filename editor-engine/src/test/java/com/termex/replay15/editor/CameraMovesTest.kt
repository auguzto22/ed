package com.termex.replay15.editor

import com.termex.replay15.editor.domain.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class CameraMovesTest {
    private val aspect = 16f / 9f
    private val second = 1_000_000L

    private fun spec(move: CameraMove, startUs: Long = 0L, durationUs: Long = 3 * second,
        intensity: Float = 1f, easing: Easing = Easing.SMOOTH, protect: Boolean = true, aspect: Float = this.aspect) =
        CameraMoveSpec(move, startUs, durationUs, intensity, easing, protect, aspect)

    /**
     * Independent coverage check: maps output points back onto the frame plane through the
     * inverse homography of the scene matrix and verifies they all land inside the frame.
     */
    private fun covers(camera: Camera3D, aspect: Float = this.aspect): Boolean {
        val m = Camera3D.SceneMatrix().evaluate(camera.copy(keyframes = emptyList()), aspect)
        // Homography plane (x, y, 1) -> clip (X, Y, W): matrix rows 0, 1, 3 and columns 0, 1, 3.
        val h = doubleArrayOf(
            m[0].toDouble(), m[4].toDouble(), m[12].toDouble(),
            m[1].toDouble(), m[5].toDouble(), m[13].toDouble(),
            m[3].toDouble(), m[7].toDouble(), m[15].toDouble(),
        )
        val det = h[0] * (h[4] * h[8] - h[5] * h[7]) - h[1] * (h[3] * h[8] - h[5] * h[6]) + h[2] * (h[3] * h[7] - h[4] * h[6])
        if (abs(det) < 1e-12) return false
        val inv = doubleArrayOf(
            (h[4] * h[8] - h[5] * h[7]) / det, (h[2] * h[7] - h[1] * h[8]) / det, (h[1] * h[5] - h[2] * h[4]) / det,
            (h[5] * h[6] - h[3] * h[8]) / det, (h[0] * h[8] - h[2] * h[6]) / det, (h[2] * h[3] - h[0] * h[5]) / det,
            (h[3] * h[7] - h[4] * h[6]) / det, (h[1] * h[6] - h[0] * h[7]) / det, (h[0] * h[4] - h[1] * h[3]) / det,
        )
        for (iy in 0..20) for (ix in 0..20) {
            val u = -1.0 + ix / 10.0; val v = -1.0 + iy / 10.0
            val x = inv[0] * u + inv[1] * v + inv[2]
            val y = inv[3] * u + inv[4] * v + inv[5]
            val w = inv[6] * u + inv[7] * v + inv[8]
            if (abs(w) < 1e-12) return false
            val px = x / w; val py = y / w
            // The point must also be in front of the eye.
            val clipW = m[3] * px + m[7] * py + m[15]
            if (clipW <= 0.0) return false
            if (abs(px) > 1.0005 || abs(py) > 1.0005) return false
        }
        return true
    }

    private fun assertCoveredThroughout(camera: Camera3D, fromUs: Long, toUs: Long) {
        for (step in 0..240) {
            val t = fromUs + (toUs - fromUs) * step / 240
            assertTrue("edges visible at t=$t: ${camera.cameraAt(t).copy(keyframes = emptyList())}", covers(camera.cameraAt(t)))
        }
    }

    @Test fun requiredZoomMatchesKnownCases() {
        assertEquals(1f, CameraFraming.requiredZoom(Camera3D(), aspect), 1e-4f)
        // Moving the eye closer only magnifies: never shows edges.
        assertEquals(1f, CameraFraming.requiredZoom(Camera3D(positionZ = -.5f), aspect), 1e-4f)
        // Pulling back by doubling the distance shrinks the frame to half: needs 2x.
        val distance = Camera3D().canonicalDistance(60f)
        assertEquals(2f, CameraFraming.requiredZoom(Camera3D(positionZ = distance), aspect), 1e-3f)
        // Sideways truck of 0.1 (eye 0.2 world units) on a 16:9 plane of half-width 16/9.
        val truck = CameraFraming.requiredZoom(Camera3D(positionX = .1f), aspect)
        assertEquals(aspect / (aspect - .2f), truck, 1e-3f)
        assertTrue(CameraFraming.requiredZoom(Camera3D(rotationY = 89f), aspect).isInfinite())
    }

    @Test fun everyMoveWithEveryEasingProducesValidCoveredTracks() {
        CameraMove.entries.forEach { move ->
            Easing.entries.forEach { easing ->
                val result = CameraMoves.apply(Camera3D(), spec(move, startUs = second, easing = easing))
                val camera = result.camera
                assertTrue("$move/$easing not covered", result.edgesCovered)
                assertTrue(camera.keyframes.size in 2..CameraMoves.MAX_KEYFRAMES)
                assertEquals(second, camera.keyframes.first().timeUs)
                assertEquals(4 * second, camera.keyframes.last().timeUs)
                assertCoveredThroughout(camera, 0L, 5 * second)
                for (t in 0L..5 * second step 50_000L) {
                    val matrix = Camera3D.SceneMatrix().evaluate(camera.cameraAt(t), aspect)
                    assertTrue(matrix.all { it.isFinite() })
                }
            }
        }
    }

    @Test fun strongIntensityAndPortraitAspectStayCovered() {
        listOf(9f / 16f, 1f, 21f / 9f).forEach { ratio ->
            CameraMove.entries.forEach { move ->
                val result = CameraMoves.apply(Camera3D(), spec(move, intensity = CameraMoves.MAX_INTENSITY, aspect = ratio))
                assertTrue("$move @ $ratio", result.edgesCovered)
                for (step in 0..240) {
                    val t = 3 * second * step / 240
                    assertTrue("$move @ $ratio t=$t", covers(result.camera.cameraAt(t), ratio))
                }
            }
        }
    }

    @Test fun movesStartFromCurrentPoseAndGoTheRightWay() {
        val base = Camera3D()
        fun end(move: CameraMove) = CameraMoves.apply(base, spec(move, protect = false)).camera.keyframes.last()
        fun start(move: CameraMove) = CameraMoves.apply(base, spec(move, protect = false)).camera.keyframes.first()
        assertTrue(end(CameraMove.PUSH_IN).positionZ < 0f)
        assertTrue(end(CameraMove.PULL_OUT).positionZ > 0f)
        assertTrue(end(CameraMove.CRASH_ZOOM).fieldOfView < 60f)
        assertTrue(end(CameraMove.TRUCK_LEFT).positionX < 0f)
        assertTrue(end(CameraMove.TRUCK_RIGHT).positionX > 0f)
        assertTrue(end(CameraMove.PEDESTAL_UP).positionY < 0f)
        assertTrue(end(CameraMove.PEDESTAL_DOWN).positionY > 0f)
        assertTrue(end(CameraMove.ORBIT_LEFT).positionX < 0f)
        assertTrue(end(CameraMove.ORBIT_RIGHT).positionX > 0f)
        assertTrue(start(CameraMove.TILT_REVEAL).rotationX > 0f)
        assertEquals(0f, end(CameraMove.TILT_REVEAL).rotationX, 1e-6f)
        assertTrue(end(CameraMove.DUTCH_ANGLE).rotationZ > 0f)
        listOf(CameraMove.PUSH_IN, CameraMove.TRUCK_LEFT, CameraMove.ORBIT_RIGHT, CameraMove.HANDHELD).forEach {
            val first = start(it)
            assertEquals(0f, first.positionX, 1e-6f); assertEquals(0f, first.positionZ, 1e-6f)
            assertEquals(0f, first.rotationY, 1e-6f); assertEquals(60f, first.fieldOfView, 1e-6f)
        }
    }

    @Test fun positiveTiltLooksAboveThePicture() {
        // The centre of the frame moves down the output when the camera looks up.
        val m = Camera3D.SceneMatrix().evaluate(Camera3D(rotationX = 8f), aspect)
        val p = Matrix4.transformPoint(m, 0f, 0f, 0f)
        assertTrue(p[1] / p[3] < 0f)
    }

    @Test fun orbitKeepsTheFrameCentreInTheMiddleOfTheShot() {
        listOf(CameraMove.ORBIT_LEFT, CameraMove.ORBIT_RIGHT).forEach { move ->
            val camera = CameraMoves.apply(Camera3D(), spec(move, protect = false)).camera
            camera.keyframes.forEach { key ->
                val m = Camera3D.SceneMatrix().evaluate(camera.cameraAt(key.timeUs), aspect)
                val p = Matrix4.transformPoint(m, 0f, 0f, 0f)
                assertEquals(0f, p[0] / p[3], 1e-4f)
                assertEquals(0f, p[1] / p[3], 1e-4f)
            }
            val end = camera.keyframes.last()
            assertEquals(if (move == CameraMove.ORBIT_LEFT) -18f else 18f, end.rotationY, 1e-4f)
        }
    }

    @Test fun orbitAroundAnOffsetPoseKeepsItsOwnPivot() {
        val base = Camera3D(positionX = .1f, positionY = -.05f, positionZ = -.3f)
        val camera = CameraMoves.apply(base, spec(CameraMove.ORBIT_RIGHT, protect = false)).camera
        // Pivot: the point of the plane straight ahead of the base eye, in SceneMatrix input space.
        val pivotX = .1f * 2f / aspect; val pivotY = .05f * 2f
        camera.keyframes.forEach { key ->
            val m = Camera3D.SceneMatrix().evaluate(camera.cameraAt(key.timeUs), aspect)
            val p = Matrix4.transformPoint(m, pivotX, pivotY, 0f)
            assertEquals(0f, p[0] / p[3], 1e-4f)
            assertEquals(0f, p[1] / p[3], 1e-4f)
        }
    }

    @Test fun replacesOnlyTheMoveRangeAndChainsFromTheCurrentPose() {
        val first = CameraMoves.apply(Camera3D(), spec(CameraMove.PUSH_IN, startUs = 0L, durationUs = 2 * second)).camera
        val outside = CameraKeyframe(9 * second, positionX = .2f)
        val inside = CameraKeyframe(5 * second, positionX = -.3f)
        val track = first.copy(keyframes = (first.keyframes + inside + outside).sortedBy { it.timeUs })
        val second2 = CameraMoves.apply(track, spec(CameraMove.TRUCK_RIGHT, startUs = 4 * second, durationUs = 2 * second, protect = false)).camera
        assertTrue(second2.keyframes.none { it.timeUs == 5 * second })
        assertTrue(second2.keyframes.any { it.timeUs == 9 * second && it.positionX == .2f })
        assertTrue(second2.keyframes.any { it.timeUs == 0L })
        // The new move starts from the pose the track had at its start time.
        val startPose = track.cameraAt(4 * second)
        val newStart = second2.keyframes.first { it.timeUs == 4 * second }
        assertEquals(startPose.positionX, newStart.positionX, 1e-6f)
        assertEquals(startPose.positionZ, newStart.positionZ, 1e-6f)
        assertEquals(startPose.positionX + .06f, second2.keyframes.first { it.timeUs == 6 * second }.positionX, 1e-6f)
    }

    @Test fun pushInNeverPassesTheNearLimit() {
        val limit = .3f - Camera3D().canonicalDistance(60f)
        // Already closer than the limit: the move holds the distance instead of pulling back.
        val beyond = CameraMoves.apply(Camera3D(positionZ = -1.5f), spec(CameraMove.PUSH_IN, intensity = 2f)).camera
        assertEquals(-1.5f, beyond.keyframes.last().positionZ, 1e-6f)
        val near = CameraMoves.apply(Camera3D(positionZ = -1.2f), spec(CameraMove.PUSH_IN, intensity = 2f)).camera
        assertEquals(limit, near.keyframes.last().positionZ, 1e-5f)
    }

    @Test fun handheldIsDeterministicStartsAndEndsOnTheBasePose() {
        val base = Camera3D(positionX = .05f, rotationZ = 3f)
        val a = CameraMoves.apply(base, spec(CameraMove.HANDHELD, protect = false).copy(seed = 7)).camera
        val b = CameraMoves.apply(base, spec(CameraMove.HANDHELD, protect = false).copy(seed = 7)).camera
        val c = CameraMoves.apply(base, spec(CameraMove.HANDHELD, protect = false).copy(seed = 8)).camera
        assertEquals(a, b)
        assertNotEquals(a, c)
        listOf(a.keyframes.first(), a.keyframes.last()).forEach {
            assertEquals(.05f, it.positionX, 1e-6f); assertEquals(3f, it.rotationZ, 1e-6f)
        }
        val maxRoll = a.keyframes.maxOf { abs(it.rotationZ - 3f) }
        assertTrue(maxRoll > .2f && maxRoll <= .9f + 1e-4f)
    }

    @Test fun longHandheldFitsTheKeyframeLimit() {
        val existing = List(150) { CameraKeyframe(40 * second + it * 100_000L) }
        val camera = CameraMoves.apply(Camera3D(keyframes = existing),
            spec(CameraMove.HANDHELD, durationUs = CameraMoves.MAX_DURATION_US)).camera
        assertEquals(CameraMoves.MAX_KEYFRAMES, camera.keyframes.size)
    }

    @Test fun refusesWhenTheTrackIsFull() {
        val existing = List(199) { CameraKeyframe(10 * second + it * 100_000L) }
        val error = runCatching { CameraMoves.apply(Camera3D(keyframes = existing), spec(CameraMove.PUSH_IN)) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test fun extremeAngleReportsUncoveredInsteadOfOverZooming() {
        val result = CameraMoves.apply(Camera3D(rotationY = 89f), spec(CameraMove.PUSH_IN))
        assertFalse(result.edgesCovered)
        assertEquals(60f, result.camera.keyframes.first().fieldOfView, 1e-6f)
    }

    @Test fun protectWholeCameraCoversStaticPoseAndTracks() {
        val static = CameraFraming.protect(Camera3D(positionX = .1f, rotationZ = 5f), aspect)!!
        assertTrue(covers(static))
        assertTrue(static.fieldOfView < 60f)
        val track = Camera3D(keyframes = listOf(CameraKeyframe(0L), CameraKeyframe(2 * second, positionZ = .5f, rotationZ = -4f)))
        val protected = CameraFraming.protect(track, aspect)!!
        assertCoveredThroughout(protected, 0L, 2 * second)
        assertNull(CameraFraming.protect(Camera3D(rotationY = 89f), aspect))
        assertEquals(Camera3D(), CameraFraming.protect(Camera3D(), aspect))
    }

    @Test fun specRejectsInvalidInput() {
        assertTrue(runCatching { spec(CameraMove.PUSH_IN, durationUs = 100_000L) }.isFailure)
        assertTrue(runCatching { spec(CameraMove.PUSH_IN, intensity = 5f) }.isFailure)
        assertTrue(runCatching { spec(CameraMove.PUSH_IN, aspect = 0f) }.isFailure)
        assertTrue(runCatching { spec(CameraMove.PUSH_IN, startUs = -1L) }.isFailure)
    }
}
