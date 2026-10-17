package com.termex.replay15.editor.scene3d

import com.termex.replay15.editor.domain.CameraBasis
import com.termex.replay15.editor.domain.OrbitCamera
import com.termex.replay15.editor.domain.fromBasis

/**
 * Turns the motion of a real phone (a 6DoF pose stream such as ARCore's) into the motion of a
 * scene camera: the camera at the moment of [reset] is the origin, and how the phone has moved
 * since then (in the phone's own axes at that moment) is applied to the camera the same way.
 * Moving the phone forward moves the camera forward; turning it left turns the camera left.
 *
 * Poses are column-major 4x4 matrices of the device in a fixed world (OpenGL camera
 * convention: +X right, +Y up, -Z forward), exactly what `Pose.toMatrix` produces.
 */
class RealCameraMapper(private val start: OrbitCamera) {
    private var origin: FloatArray? = null

    val hasOrigin: Boolean get() = origin != null

    /** The phone's current pose becomes "no motion". */
    fun reset(pose: FloatArray) { origin = pose.copyOf(16) }

    /**
     * The camera for [pose]. [multiplier] scales the phone's translation (1 = real metres);
     * [invert] applies the opposite motion, as if the world moved instead of the camera.
     */
    fun map(pose: FloatArray, multiplier: Float = 1f, invert: Boolean = false): OrbitCamera? {
        val p0 = origin ?: return null
        if (pose.size < 16 || pose.any { !it.isFinite() }) return null
        // D = P0^-1 * Pt: rotation R0^T Rt and translation R0^T (tt - t0).
        val r0 = rotationOf(p0); val rt = rotationOf(pose)
        var rd = GizmoMath.multiply3(GizmoMath.transpose3(r0), rt)
        var td = GizmoMath.apply3(GizmoMath.transpose3(r0), floatArrayOf(pose[12] - p0[12], pose[13] - p0[13], pose[14] - p0[14]))
        td = Vec.scale(td, multiplier)
        if (invert) {
            val back = GizmoMath.transpose3(rd)
            td = Vec.scale(GizmoMath.apply3(back, td), -1f)
            rd = back
        }
        val b: CameraBasis = start.basis()
        val forward0 = Vec.normalize(Vec.sub(b.target, b.eye))
        val x0 = Vec.normalize(Vec.cross(forward0, b.up))
        val y0 = Vec.cross(x0, forward0)
        val z0 = Vec.scale(forward0, -1f)
        // Columns of B0 are the start camera's right, up and back in scene space.
        val b0 = floatArrayOf(x0[0], y0[0], z0[0], x0[1], y0[1], z0[1], x0[2], y0[2], z0[2])
        val rs = GizmoMath.multiply3(b0, rd)
        val eye = Vec.add(b.eye, GizmoMath.apply3(b0, td))
        val forward = floatArrayOf(-rs[2], -rs[5], -rs[8])
        val up = floatArrayOf(rs[1], rs[4], rs[7])
        return runCatching { OrbitCamera.fromBasis(eye, forward, up, start) }.getOrNull()
    }

    private fun rotationOf(m: FloatArray) = floatArrayOf(m[0], m[4], m[8], m[1], m[5], m[9], m[2], m[6], m[10])
}
