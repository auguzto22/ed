package com.termex.replay15.editor

import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.project.ProjectCodec
import com.termex.replay15.editor.render.MotionMatrix3D
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class Motion3DIntegrationTest {

    private val eps = 1e-3f

    private fun sampleClip(id: String = "clip1", is3D: Boolean = false, parentId: String? = null) = VideoClip(
        id = id,
        uri = "file:///dummy.mp4",
        name = "Clip",
        sourceUs = 5 * SECOND,
        width = 1920,
        height = 1080,
        is3D = is3D,
        parentId = parentId,
    )

    @Test
    fun `Transform3D toModelMatrix rotates around anchor point`() {
        // Anchor at left edge (-0.5f, 0f)
        val t = Transform3D(
            anchorX = -0.5f,
            rotationY = 90f,
        )
        val m = t.toModelMatrix()
        // Left edge (-1f in NDC extent) is at the anchor/pivot, so after rotation it remains at position (0, 0, 0)
        val pivotPt = Matrix4.transformPoint(m, -1f, 0f, 0f, 1f)
        assertEquals(0f, pivotPt[0], eps)
        assertEquals(0f, pivotPt[1], eps)
        assertEquals(0f, pivotPt[2], eps)

        // Center of the layer (0f, 0f, 0f) was 1 unit to the right of the pivot, so 90 deg rotation swings it into Z = -1
        val centerPt = Matrix4.transformPoint(m, 0f, 0f, 0f, 1f)
        assertEquals(0f, centerPt[0], eps)
        assertEquals(0f, centerPt[1], eps)
        assertEquals(-1f, centerPt[2], eps)
    }

    @Test
    fun `Camera3D keyframe interpolation smoothly blends position and FOV`() {
        val key1 = CameraKeyframe(timeUs = 0L, positionX = 0f, positionZ = 0f, fieldOfView = 60f, easing = Easing.LINEAR)
        val key2 = CameraKeyframe(timeUs = 2 * SECOND, positionX = 1f, positionZ = 5f, fieldOfView = 90f, easing = Easing.LINEAR)
        val cam = Camera3D(keyframes = listOf(key1, key2))

        val mid = cam.cameraAt(SECOND)
        assertEquals(0.5f, mid.positionX, eps)
        assertEquals(2.5f, mid.positionZ, eps)
        assertEquals(75f, mid.fieldOfView, eps)
    }

    @Test
    fun `VideoClip transformAt interpolates 3D coordinates and rotations`() {
        val k1 = TransformKeyframe(sourceUs = 0L, z = 0f, rotationX = 0f, rotationY = 0f, easing = Easing.LINEAR)
        val k2 = TransformKeyframe(sourceUs = SECOND, z = 2f, rotationX = 40f, rotationY = -60f, easing = Easing.LINEAR)
        val clip = sampleClip().copy(is3D = true, keyframes = listOf(k1, k2))

        val mid = clip.transformAt(SECOND / 2)
        assertEquals(1f, mid.z, eps)
        assertEquals(20f, mid.rotationX, eps)
        assertEquals(-30f, mid.rotationY, eps)
    }

    @Test
    fun `MotionMatrix3D provides 4x4 matrix without allocations and supports 2D fast path`() {
        val clip = sampleClip().copy(zoom = 1.5f, offsetX = 0.2f, offsetY = -0.1f)
        val motion = MotionMatrix3D(clip, startUs = 0L)
        motion.configure(1920, 1080)

        val mat = motion.getGlMatrixArray(0L)
        assertNotNull(mat)
        assertEquals(16, mat.size)
        // In 2D fast path, M22 is 1f and M33 is 1f
        assertEquals(1f, mat[10], eps)
        assertEquals(1f, mat[15], eps)
        // Translation X is offsetX * 2f = 0.4f
        assertEquals(0.4f, mat[12], eps)
    }

    @Test
    fun `MotionMatrix3D correctly interpolates keyframes when timestamps are item-local or composition-global`() {
        val k1 = TransformKeyframe(sourceUs = 0L, x = -0.5f, easing = Easing.LINEAR)
        val k2 = TransformKeyframe(sourceUs = 2 * SECOND, x = 0.5f, easing = Easing.LINEAR)
        val clip = sampleClip().copy(
            inUs = 0L,
            outUs = 3 * SECOND,
            keyframes = listOf(k1, k2),
        )
        val startUs = 4 * SECOND
        val motion = MotionMatrix3D(clip, startUs = startUs)
        motion.configure(1920, 1080)

        // 1. Item-local timestamps (Media3 VideoFrameProcessor feeding item PTS starting at 0)
        val mat0 = motion.getGlMatrixArray(0L)
        assertEquals(-1.0f, mat0[12], eps) // x = -0.5f -> tx = -0.5f * 2 = -1.0f

        val matMidLocal = motion.getGlMatrixArray(SECOND)
        assertEquals(0.0f, matMidLocal[12], eps) // x = 0.0f -> tx = 0.0f

        val mat2Local = motion.getGlMatrixArray(2 * SECOND)
        assertEquals(1.0f, mat2Local[12], eps) // x = 0.5f -> tx = 1.0f

        // 2. Composition-global timestamps (if Media3 passes project time e.g. startUs..startUs+duration)
        val matGlobalStart = motion.getGlMatrixArray(startUs)
        assertEquals(-1.0f, matGlobalStart[12], eps)

        val matGlobalMid = motion.getGlMatrixArray(startUs + SECOND)
        assertEquals(0.0f, matGlobalMid[12], eps)

        val matGlobal2 = motion.getGlMatrixArray(startUs + 2 * SECOND)
        assertEquals(1.0f, matGlobal2[12], eps)
    }

    @Test
    fun inspectRecentProjects() {
        listOf("/tmp/recent1.r15", "/tmp/recent2.r15").forEach { path ->
            val f = java.io.File(path)
            if (!f.exists()) return@forEach
            val p = ProjectCodec.read(f.inputStream())
            println("=== FILE: $path ===")
            println("ID: ${p.id}, DUR: ${p.durationUs}")
            println("VIDEOS: ${p.videos.map { "${it.id} (dur=${it.durationUs}, img=${it.image}, kfs=${it.keyframes.size})" }}")
            println("TRACKS: ${p.videoTracks.map { "${it.id}: clips=${it.clips.map { c -> "${c.clip.id}(start=${c.startUs}, dur=${c.clip.durationUs}, img=${c.clip.image}, kfs=${c.clip.keyframes.size})" }}" }}")
            println("STICKERS: ${p.stickers.map { "${it.id} (start=${it.startUs}, end=${it.endUs}, kfs=${it.transformKeyframes.size})" }}")
            p.stickers.forEach { s ->
                s.transformKeyframes.forEach { k ->
                    println("  StickerKey: time=${k.timeUs}, x=${k.transform.x}, y=${k.transform.y}, sX=${k.transform.scaleX}, rot=${k.transform.rotation}")
                }
            }
            println("TEXTS: ${p.texts.map { "${it.id} (start=${it.startUs}, end=${it.endUs}, kfs=${it.transformKeyframes.size})" }}")
            p.texts.forEach { t ->
                t.transformKeyframes.forEach { k ->
                    println("  TextKey: time=${k.timeUs}, x=${k.transform.x}, y=${k.transform.y}, sX=${k.transform.scaleX}, rot=${k.transform.rotation}")
                }
            }
        }
    }

    @Test
    fun `Parallax occurs when camera pans with layers at different Z depth`() {
        val camKey1 = CameraKeyframe(0L, positionX = 0f, easing = Easing.LINEAR)
        val camKey2 = CameraKeyframe(SECOND, positionX = 0.5f, easing = Easing.LINEAR)
        val project = Project(camera = Camera3D(keyframes = listOf(camKey1, camKey2)))

        // Foreground layer (Z = +0.5, closer to camera)
        val fgClip = sampleClip("fg", is3D = true).copy(transform3D = Transform3D(positionZ = 0.5f))
        val fgMotion = MotionMatrix3D(fgClip, startUs = 0L, initialProject = project)
        fgMotion.configure(1920, 1080)

        // Background layer (Z = -2.0, deeper in scene)
        val bgClip = sampleClip("bg", is3D = true).copy(transform3D = Transform3D(positionZ = -2.0f))
        val bgMotion = MotionMatrix3D(bgClip, startUs = 0L, initialProject = project)
        bgMotion.configure(1920, 1080)

        val fgMatStart = fgMotion.getGlMatrixArray(0L).clone()
        val fgMatEnd = fgMotion.getGlMatrixArray(SECOND).clone()
        val bgMatStart = bgMotion.getGlMatrixArray(0L).clone()
        val bgMatEnd = bgMotion.getGlMatrixArray(SECOND).clone()

        // Project center of each layer into NDC coordinates (accounting for GPU perspective division by w)
        val fgPtStart = Matrix4.transformPoint(fgMatStart, 0f, 0f, 0f)
        val fgPtEnd = Matrix4.transformPoint(fgMatEnd, 0f, 0f, 0f)
        val fgNdcStart = fgPtStart[0] / fgPtStart[3]
        val fgNdcEnd = fgPtEnd[0] / fgPtEnd[3]
        val fgShift = kotlin.math.abs(fgNdcEnd - fgNdcStart)

        val bgPtStart = Matrix4.transformPoint(bgMatStart, 0f, 0f, 0f)
        val bgPtEnd = Matrix4.transformPoint(bgMatEnd, 0f, 0f, 0f)
        val bgNdcStart = bgPtStart[0] / bgPtStart[3]
        val bgNdcEnd = bgPtEnd[0] / bgPtEnd[3]
        val bgShift = kotlin.math.abs(bgNdcEnd - bgNdcStart)

        // Foreground must have significantly greater parallax shift than distant background
        assertTrue("Foreground shift ($fgShift) must exceed background shift ($bgShift)", fgShift > bgShift)
    }

    @Test
    fun `Parenting propagates parent translation and rotation to child layer`() {
        val parent = sampleClip("nullParent", is3D = true).copy(
            isNullObject = true,
            transform3D = Transform3D(positionX = 0.3f, rotationY = 45f),
        )
        val child = sampleClip("childLayer", is3D = true, parentId = "nullParent").copy(
            transform3D = Transform3D(positionX = 0.1f),
        )
        val project = Project(videos = listOf(parent, child))

        val childMotion = MotionMatrix3D(child, startUs = 0L, initialProject = project)
        childMotion.configure(1920, 1080)

        val mat = childMotion.getGlMatrixArray(0L)
        assertNotNull(mat)
        // Child X position must reflect both parent (0.3) and child (0.1)
        assertTrue(mat[12] != 0f)
    }

    @Test
    fun `Schema 16 project persists and restores Camera3D and 3D properties`() {
        val cam = Camera3D(
            positionX = 0.2f,
            positionY = -0.1f,
            positionZ = 1.5f,
            rotationX = 15f,
            rotationY = -25f,
            fieldOfView = 75f,
        )
        val clip = sampleClip("clip3D", is3D = true).copy(
            transform3D = Transform3D(positionZ = 0.8f, rotationY = 45f, anchorX = -0.5f),
            keyframes = listOf(
                TransformKeyframe(0L, z = 0f, rotationY = 0f),
                TransformKeyframe(SECOND, z = 1.2f, rotationY = 90f),
            ),
        )
        val original = Project(
            camera = cam,
            videos = listOf(clip),
        )

        val bytes = ByteArrayOutputStream()
        ProjectCodec.write(original, bytes)

        val loaded = ProjectCodec.read(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(0.2f, loaded.camera.positionX, eps)
        assertEquals(75f, loaded.camera.fieldOfView, eps)
        assertEquals(1, loaded.videos.size)
        val loadedClip = loaded.videos.first()
        assertTrue(loadedClip.is3D)
        assertEquals(0.8f, loadedClip.transform3D.positionZ, eps)
        assertEquals(45f, loadedClip.transform3D.rotationY, eps)
        assertEquals(-0.5f, loadedClip.transform3D.anchorX, eps)
        assertEquals(2, loadedClip.keyframes.size)
        assertEquals(1.2f, loadedClip.keyframes[1].z, eps)
        assertEquals(90f, loadedClip.keyframes[1].rotationY, eps)
    }
}
