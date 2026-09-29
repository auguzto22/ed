package com.termex.replay15.editor.domain

import kotlin.math.*

/**
 * High-performance 4x4 column-major matrix library for GPU vertex transformations in Recly.
 *
 * Index mapping for column-major 4x4:
 * [  0   4   8  12 ]   [ M00 M01 M02 M03 ]
 * [  1   5   9  13 ] = [ M10 M11 M12 M13 ]
 * [  2   6  10  14 ]   [ M20 M21 M22 M23 ]
 * [  3   7  11  15 ]   [ M30 M31 M32 M33 ]
 *
 * Fully zero-allocation on the render thread via thread-local scratch buffers.
 */
object Matrix4 {

    private class Scratch {
        val temp = FloatArray(16)
        val temp2 = FloatArray(16)
        val model = FloatArray(16)
        val view = FloatArray(16)
        val projection = FloatArray(16)
        val mvp = FloatArray(16)
    }

    private val threadScratch = object : ThreadLocal<Scratch>() {
        override fun initialValue(): Scratch = Scratch()
    }

    fun createIdentity(): FloatArray = FloatArray(16).also { setIdentity(it) }

    fun setIdentity(m: FloatArray) {
        m[0] = 1f;  m[1] = 0f;  m[2] = 0f;  m[3] = 0f
        m[4] = 0f;  m[5] = 1f;  m[6] = 0f;  m[7] = 0f
        m[8] = 0f;  m[9] = 0f;  m[10] = 1f; m[11] = 0f
        m[12] = 0f; m[13] = 0f; m[14] = 0f; m[15] = 1f
    }

    fun copy(dest: FloatArray, src: FloatArray) {
        System.arraycopy(src, 0, dest, 0, 16)
    }

    /** Multiplies result = lhs * rhs in column-major order. result can safely be lhs or rhs. */
    fun multiply(result: FloatArray, lhs: FloatArray, rhs: FloatArray) {
        val s = threadScratch.get()!!
        val temp = if (result === lhs || result === rhs) s.temp else result

        // Row 0
        temp[0]  = lhs[0] * rhs[0]  + lhs[4] * rhs[1]  + lhs[8]  * rhs[2]  + lhs[12] * rhs[3]
        temp[4]  = lhs[0] * rhs[4]  + lhs[4] * rhs[5]  + lhs[8]  * rhs[6]  + lhs[12] * rhs[7]
        temp[8]  = lhs[0] * rhs[8]  + lhs[4] * rhs[9]  + lhs[8]  * rhs[10] + lhs[12] * rhs[11]
        temp[12] = lhs[0] * rhs[12] + lhs[4] * rhs[13] + lhs[8]  * rhs[14] + lhs[12] * rhs[15]

        // Row 1
        temp[1]  = lhs[1] * rhs[0]  + lhs[5] * rhs[1]  + lhs[9]  * rhs[2]  + lhs[13] * rhs[3]
        temp[5]  = lhs[1] * rhs[4]  + lhs[5] * rhs[5]  + lhs[9]  * rhs[6]  + lhs[13] * rhs[7]
        temp[9]  = lhs[1] * rhs[8]  + lhs[5] * rhs[9]  + lhs[9]  * rhs[10] + lhs[13] * rhs[11]
        temp[13] = lhs[1] * rhs[12] + lhs[5] * rhs[13] + lhs[9]  * rhs[14] + lhs[13] * rhs[15]

        // Row 2
        temp[2]  = lhs[2] * rhs[0]  + lhs[6] * rhs[1]  + lhs[10] * rhs[2]  + lhs[14] * rhs[3]
        temp[6]  = lhs[2] * rhs[4]  + lhs[6] * rhs[5]  + lhs[10] * rhs[6]  + lhs[14] * rhs[7]
        temp[10] = lhs[2] * rhs[8]  + lhs[6] * rhs[9]  + lhs[10] * rhs[10] + lhs[14] * rhs[11]
        temp[14] = lhs[2] * rhs[12] + lhs[6] * rhs[13] + lhs[10] * rhs[14] + lhs[14] * rhs[15]

        // Row 3
        temp[3]  = lhs[3] * rhs[0]  + lhs[7] * rhs[1]  + lhs[11] * rhs[2]  + lhs[15] * rhs[3]
        temp[7]  = lhs[3] * rhs[4]  + lhs[7] * rhs[5]  + lhs[11] * rhs[6]  + lhs[15] * rhs[7]
        temp[11] = lhs[3] * rhs[8]  + lhs[7] * rhs[9]  + lhs[11] * rhs[10] + lhs[15] * rhs[11]
        temp[15] = lhs[3] * rhs[12] + lhs[7] * rhs[13] + lhs[11] * rhs[14] + lhs[15] * rhs[15]

        if (temp !== result) {
            System.arraycopy(temp, 0, result, 0, 16)
        }
    }

    /** In-place translation: m = m * T(x, y, z) */
    fun translate(m: FloatArray, x: Float, y: Float, z: Float) {
        m[12] += m[0] * x + m[4] * y + m[8]  * z
        m[13] += m[1] * x + m[5] * y + m[9]  * z
        m[14] += m[2] * x + m[6] * y + m[10] * z
        m[15] += m[3] * x + m[7] * y + m[11] * z
    }

    /** In-place scale: m = m * S(sx, sy, sz) */
    fun scale(m: FloatArray, sx: Float, sy: Float, sz: Float) {
        m[0] *= sx; m[1] *= sx; m[2] *= sx; m[3] *= sx
        m[4] *= sy; m[5] *= sy; m[6] *= sy; m[7] *= sy
        m[8] *= sz; m[9] *= sz; m[10] *= sz; m[11] *= sz
    }

    /** In-place rotation around X axis (pitch) in degrees: m = m * Rx(angleDeg) */
    fun rotateX(m: FloatArray, angleDeg: Float) {
        if (angleDeg == 0f) return
        val rad = Math.toRadians(angleDeg.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()

        val m4 = m[4];  val m5 = m[5];  val m6 = m[6];  val m7 = m[7]
        val m8 = m[8];  val m9 = m[9];  val m10 = m[10]; val m11 = m[11]

        m[4]  = m4 * c + m8 * s
        m[5]  = m5 * c + m9 * s
        m[6]  = m6 * c + m10 * s
        m[7]  = m7 * c + m11 * s

        m[8]  = -m4 * s + m8 * c
        m[9]  = -m5 * s + m9 * c
        m[10] = -m6 * s + m10 * c
        m[11] = -m7 * s + m11 * c
    }

    /** In-place rotation around Y axis (yaw / card flip) in degrees: m = m * Ry(angleDeg) */
    fun rotateY(m: FloatArray, angleDeg: Float) {
        if (angleDeg == 0f) return
        val rad = Math.toRadians(angleDeg.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()

        val m0 = m[0];  val m1 = m[1];  val m2 = m[2];  val m3 = m[3]
        val m8 = m[8];  val m9 = m[9];  val m10 = m[10]; val m11 = m[11]

        m[0]  = m0 * c - m8 * s
        m[1]  = m1 * c - m9 * s
        m[2]  = m2 * c - m10 * s
        m[3]  = m3 * c - m11 * s

        m[8]  = m0 * s + m8 * c
        m[9]  = m1 * s + m9 * c
        m[10] = m2 * s + m10 * c
        m[11] = m3 * s + m11 * c
    }

    /** In-place rotation around Z axis (roll / in-plane 2D rotation) in degrees: m = m * Rz(angleDeg) */
    fun rotateZ(m: FloatArray, angleDeg: Float) {
        if (angleDeg == 0f) return
        val rad = Math.toRadians(angleDeg.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()

        val m0 = m[0];  val m1 = m[1];  val m2 = m[2];  val m3 = m[3]
        val m4 = m[4];  val m5 = m[5];  val m6 = m[6];  val m7 = m[7]

        m[0] = m0 * c + m4 * s
        m[1] = m1 * c + m5 * s
        m[2] = m2 * c + m6 * s
        m[3] = m3 * c + m7 * s

        m[4] = -m0 * s + m4 * c
        m[5] = -m1 * s + m5 * c
        m[6] = -m2 * s + m6 * c
        m[7] = -m3 * s + m7 * c
    }

    /**
     * Standard Euler rotation applied in consistent Z-Y-X order:
     * m = m * Rz(rotZ) * Ry(rotY) * Rx(rotX)
     */
    fun rotateEuler(m: FloatArray, rotX: Float, rotY: Float, rotZ: Float) {
        rotateZ(m, rotZ)
        rotateY(m, rotY)
        rotateX(m, rotX)
    }

    /**
     * Creates a standard symmetric OpenGL perspective projection matrix.
     *
     * @param out 16-element float array to populate.
     * @param fovYDeg Vertical field of view in degrees.
     * @param aspect Aspect ratio (width / height).
     * @param near Distance to near clipping plane.
     * @param far Distance to far clipping plane.
     */
    fun perspective(out: FloatArray, fovYDeg: Float, aspect: Float, near: Float = 0.05f, far: Float = 1000f) {
        val fovRad = Math.toRadians(fovYDeg.toDouble())
        val f = (1.0 / tan(fovRad / 2.0)).toFloat()
        val nf = 1f / (near - far)

        out[0] = f / aspect
        out[1] = 0f
        out[2] = 0f
        out[3] = 0f

        out[4] = 0f
        out[5] = f
        out[6] = 0f
        out[7] = 0f

        out[8] = 0f
        out[9] = 0f
        out[10] = (far + near) * nf
        out[11] = -1f

        out[12] = 0f
        out[13] = 0f
        out[14] = (2f * far * near) * nf
        out[15] = 0f
    }

    /**
     * Creates an orthographic projection matrix.
     */
    fun ortho(out: FloatArray, left: Float, right: Float, bottom: Float, top: Float, near: Float = -1000f, far: Float = 1000f) {
        val lr = 1f / (left - right)
        val bt = 1f / (bottom - top)
        val nf = 1f / (near - far)

        out[0] = -2f * lr
        out[1] = 0f
        out[2] = 0f
        out[3] = 0f

        out[4] = 0f
        out[5] = -2f * bt
        out[6] = 0f
        out[7] = 0f

        out[8] = 0f
        out[9] = 0f
        out[10] = 2f * nf
        out[11] = 0f

        out[12] = (left + right) * lr
        out[13] = (top + bottom) * bt
        out[14] = (far + near) * nf
        out[15] = 1f
    }

    /**
     * Builds a View matrix using camera position, target point of interest, and up vector.
     */
    fun lookAt(
        out: FloatArray,
        eyeX: Float, eyeY: Float, eyeZ: Float,
        centerX: Float, centerY: Float, centerZ: Float,
        upX: Float = 0f, upY: Float = 1f, upZ: Float = 0f,
    ) {
        var fx = centerX - eyeX
        var fy = centerY - eyeY
        var fz = centerZ - eyeZ
        val rlf = 1.0f / sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(1e-6f)
        fx *= rlf; fy *= rlf; fz *= rlf

        var sx = fy * upZ - fz * upY
        var sy = fz * upX - fx * upZ
        var sz = fx * upY - fy * upX
        val rls = 1.0f / sqrt(sx * sx + sy * sy + sz * sz).coerceAtLeast(1e-6f)
        sx *= rls; sy *= rls; sz *= rls

        val ux = sy * fz - sz * fy
        val uy = sz * fx - sx * fz
        val uz = sx * fy - sy * fx

        out[0] = sx;  out[1] = ux;  out[2] = -fx;  out[3] = 0f
        out[4] = sy;  out[5] = uy;  out[6] = -fy;  out[7] = 0f
        out[8] = sz;  out[9] = uz;  out[10] = -fz; out[11] = 0f
        out[12] = 0f; out[13] = 0f; out[14] = 0f;  out[15] = 1f

        translate(out, -eyeX, -eyeY, -eyeZ)
    }

    /**
     * Transforms a 4D vector/point: out = M * [x, y, z, w]^T.
     */
    fun transformPoint(
        m: FloatArray,
        x: Float, y: Float, z: Float, w: Float = 1f,
        out: FloatArray = FloatArray(4),
    ): FloatArray {
        out[0] = m[0] * x + m[4] * y + m[8]  * z + m[12] * w
        out[1] = m[1] * x + m[5] * y + m[9]  * z + m[13] * w
        out[2] = m[2] * x + m[6] * y + m[10] * z + m[14] * w
        out[3] = m[3] * x + m[7] * y + m[11] * z + m[15] * w
        return out
    }

    /**
     * Detects circular parenting hierarchies to prevent infinite loops.
     * Returns true if adding parentId to childId would form a cycle.
     */
    fun detectCycle(childId: String, parentId: String?, getParentId: (String) -> String?): Boolean {
        if (parentId == null) return false
        if (childId == parentId) return true
        var current: String? = parentId
        var depth = 0
        while (current != null && depth < 256) {
            if (current == childId) return true
            current = getParentId(current)
            depth++
        }
        return false
    }
}
