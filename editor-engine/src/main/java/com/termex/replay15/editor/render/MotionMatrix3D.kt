package com.termex.replay15.editor.render

import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlMatrixTransformation
import com.termex.replay15.editor.domain.*

/**
 * 2.5D / 3D OpenGL vertex transformation effect for Media3.
 *
 * Implements MVP = Projection * View * Model_world directly on the GPU in the vertex shader.
 * Zero CPU bitmap conversion; zero allocation on the render thread.
 * Uses the immutable export project snapshot supplied at construction.
 */
@UnstableApi
class MotionMatrix3D(
    private val clip: VideoClip,
    private val startUs: Long,
    private val initialProject: Project? = null,
    private val applyProjectCamera: Boolean = true,
) : GlMatrixTransformation {

    private class ThreadBuffers {
        val outMatrix = FloatArray(16)
        val localMatrix = FloatArray(16)
        val parentMatrix = FloatArray(16)
        val worldMatrix = FloatArray(16)
        val viewMatrix = FloatArray(16)
        val projMatrix = FloatArray(16)
        val vpMatrix = FloatArray(16)
    }

    private val buffers = object : ThreadLocal<ThreadBuffers>() {
        override fun initialValue() = ThreadBuffers()
    }

    private var aspect = 1f

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        aspect = inputWidth.toFloat() / inputHeight.coerceAtLeast(1)
        return Size(inputWidth, inputHeight)
    }

    override fun getGlMatrixArray(presentationTimeUs: Long): FloatArray {
        val b = buffers.get()!!
        val currentProject = initialProject
        val current = clip
        val durationUs = current.durationUs.coerceAtLeast(1L)
        val projectTimeUs = if (presentationTimeUs in startUs..(startUs + durationUs)) {
            presentationTimeUs
        } else {
            startUs + presentationTimeUs
        }
        val timelineUs = (projectTimeUs - startUs).coerceIn(0L, durationUs)
        val sourceTime = current.timeMap.sourceAt(timelineUs)
        val key = current.transformAt(sourceTime)

        // Procedural clip motion (Zoom In/Out, Pan Left/Right)
        val progress = (timelineUs.toFloat() / durationUs).coerceIn(0f, 1f)
        val animatedScale = when (current.motion) {
            ClipMotion.ZOOM_IN -> 1f + .12f * progress
            ClipMotion.ZOOM_OUT -> 1.12f - .12f * progress
            ClipMotion.PAN_LEFT, ClipMotion.PAN_RIGHT -> 1.1f
            ClipMotion.NONE -> 1f
        }
        val pan = when (current.motion) {
            ClipMotion.PAN_LEFT -> .16f - .32f * progress
            ClipMotion.PAN_RIGHT -> -.16f + .32f * progress
            else -> 0f
        }

        val camera = if (applyProjectCamera) currentProject?.camera?.cameraAt(projectTimeUs) ?: Camera3D() else Camera3D()
        val is3D = current.is3D || !camera.isDefault || current.parentId != null

        if (!is3D) {
            // =========================================================================
            // Fast path: pure 2D affine transform in a 4x4 matrix.
            // =========================================================================
            val s = key.zoom * animatedScale
            val rad = Math.toRadians(key.rotation.toDouble())
            val c = kotlin.math.cos(rad).toFloat()
            val sn = kotlin.math.sin(rad).toFloat()
            val tx = key.x * 2f + pan
            val ty = -key.y * 2f

            val out = b.outMatrix
            out[0] = s * c;                    out[4] = -s * aspect * sn;       out[8]  = 0f; out[12] = tx
            out[1] = (s / aspect) * sn;        out[5] = s * c;                  out[9]  = 0f; out[13] = ty
            out[2] = 0f;                       out[6] = 0f;                     out[10] = 1f; out[14] = 0f
            out[3] = 0f;                       out[7] = 0f;                     out[11] = 0f; out[15] = 1f
            return out
        }

        // =========================================================================
        // FULL 2.5D / 3D PATH: MVP = Projection * View * Model_world
        // =========================================================================
        // 1. Compute Local Model Matrix
        val model = b.localMatrix
        Matrix4.setIdentity(model)
        val posX = key.x * 2f + pan
        val posY = -key.y * 2f
        val posZ = key.z
        Matrix4.translate(model, posX, posY, posZ)
        if (key.anchorX != 0f || key.anchorY != 0f || key.anchorZ != 0f) {
            Matrix4.translate(model, key.anchorX * 2f, -key.anchorY * 2f, key.anchorZ)
        }
        Matrix4.rotateEuler(model, key.rotationX, key.rotationY, key.rotation)
        val sx = key.zoom * animatedScale * aspect
        val sy = key.zoom * animatedScale
        val sz = key.scaleZ
        Matrix4.scale(model, sx, sy, sz)
        if (key.anchorX != 0f || key.anchorY != 0f || key.anchorZ != 0f) {
            Matrix4.translate(model, -key.anchorX * 2f, key.anchorY * 2f, -key.anchorZ)
        }

        // 2. Hierarchy Parenting: M_world = M_parent_world * M_local
        val world = b.worldMatrix
        Matrix4.copy(world, model)
        if (current.parentId != null && currentProject != null) {
            applyParentHierarchy(current.parentId, currentProject, projectTimeUs, world, b)
        }

        // 3. View Matrix (Camera)
        val view = b.viewMatrix
        camera.toViewMatrix(view)

        // 4. Projection Matrix (Perspective)
        val proj = b.projMatrix
        camera.toProjectionMatrix(aspect, proj)

        // 5. Compute MVP = (Projection * View) * World
        val vp = b.vpMatrix
        Matrix4.multiply(vp, proj, view)
        Matrix4.multiply(b.outMatrix, vp, world)

        if (b.outMatrix.any { !it.isFinite() }) Matrix4.setIdentity(b.outMatrix)
        return b.outMatrix
    }

    private fun applyParentHierarchy(
        initialParentId: String,
        project: Project,
        timeUs: Long,
        worldMatrix: FloatArray,
        b: ThreadBuffers,
    ) {
        var currentParentId: String? = initialParentId
        var depth = 0
        val visited = HashSet<String>(8)

        while (currentParentId != null && depth < 32) {
            if (!visited.add(currentParentId)) break // Cycle detected
            val parentClip = project.allVideos.firstOrNull { it.id == currentParentId } ?: break
            val parentStartUs = project.videoTracks.flatMap { it.clips }
                .firstOrNull { it.clip.id == currentParentId }?.startUs
                ?: project.videos.indexOfFirst { it.id == currentParentId }.takeIf { it >= 0 }
                    ?.let { project.startOf(it) } ?: 0L
            val parentTimelineUs = (timeUs - parentStartUs).coerceIn(0L, parentClip.durationUs)
            val parentSourceTime = parentClip.timeMap.sourceAt(parentTimelineUs)
            val parentKey = parentClip.transformAt(parentSourceTime)

            val pMat = b.parentMatrix
            Matrix4.setIdentity(pMat)
            Matrix4.translate(pMat, parentKey.x * 2f, -parentKey.y * 2f, parentKey.z)
            Matrix4.rotateEuler(pMat, parentKey.rotationX, parentKey.rotationY, parentKey.rotation)
            Matrix4.scale(pMat, parentKey.zoom, parentKey.zoom, parentKey.scaleZ)
            if (parentKey.anchorX != 0f || parentKey.anchorY != 0f || parentKey.anchorZ != 0f) {
                Matrix4.translate(pMat, -parentKey.anchorX * 2f, parentKey.anchorY * 2f, -parentKey.anchorZ)
            }

            Matrix4.multiply(worldMatrix, pMat, worldMatrix)
            currentParentId = parentClip.parentId
            depth++
        }
    }
}
