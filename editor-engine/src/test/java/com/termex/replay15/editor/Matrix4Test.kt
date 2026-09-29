package com.termex.replay15.editor

import com.termex.replay15.editor.domain.Matrix4
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class Matrix4Test {

    private val eps = 1e-4f

    @Test
    fun `identity matrix preserves point coordinates`() {
        val m = Matrix4.createIdentity()
        val pt = Matrix4.transformPoint(m, 0.5f, -0.3f, 0.8f, 1f)
        assertEquals(0.5f, pt[0], eps)
        assertEquals(-0.3f, pt[1], eps)
        assertEquals(0.8f, pt[2], eps)
        assertEquals(1f, pt[3], eps)
    }

    @Test
    fun `translation translates point correctly`() {
        val m = Matrix4.createIdentity()
        Matrix4.translate(m, 2f, -3f, 5f)
        val pt = Matrix4.transformPoint(m, 1f, 1f, 1f, 1f)
        assertEquals(3f, pt[0], eps)
        assertEquals(-2f, pt[1], eps)
        assertEquals(6f, pt[2], eps)
    }

    @Test
    fun `scale scales point correctly`() {
        val m = Matrix4.createIdentity()
        Matrix4.scale(m, 2f, 0.5f, 3f)
        val pt = Matrix4.transformPoint(m, 3f, 4f, 2f, 1f)
        assertEquals(6f, pt[0], eps)
        assertEquals(2f, pt[1], eps)
        assertEquals(6f, pt[2], eps)
    }

    @Test
    fun `rotation around Z axis rotates point in XY plane`() {
        val m = Matrix4.createIdentity()
        Matrix4.rotateZ(m, 90f)
        val pt = Matrix4.transformPoint(m, 1f, 0f, 0f, 1f)
        assertEquals(0f, pt[0], eps)
        assertEquals(1f, pt[1], eps)
        assertEquals(0f, pt[2], eps)
    }

    @Test
    fun `rotation around Y axis card flip rotates point in XZ plane`() {
        val m = Matrix4.createIdentity()
        Matrix4.rotateY(m, 90f)
        val pt = Matrix4.transformPoint(m, 1f, 0f, 0f, 1f)
        assertEquals(0f, pt[0], eps)
        assertEquals(0f, pt[1], eps)
        assertEquals(-1f, pt[2], eps)
    }

    @Test
    fun `rotation around X axis tilts point in YZ plane`() {
        val m = Matrix4.createIdentity()
        Matrix4.rotateX(m, 90f)
        val pt = Matrix4.transformPoint(m, 0f, 1f, 0f, 1f)
        assertEquals(0f, pt[0], eps)
        assertEquals(0f, pt[1], eps)
        assertEquals(1f, pt[2], eps)
    }

    @Test
    fun `perspective projection at canonical distance preserves 2D scale at Z zero`() {
        val fov = 60f
        val aspect = 16f / 9f
        val proj = FloatArray(16)
        Matrix4.perspective(proj, fov, aspect, 0.05f, 1000f)

        val d = (1.0 / kotlin.math.tan(Math.toRadians(fov / 2.0))).toFloat()

        // Point at Z = 0 in scene, eye at (0, 0, d)
        // In view space, eye translation puts scene point at Z = -d
        // Local point with aspect scaling: x = 0.5f * aspect, y = 0.5f, z = -d
        val pt = Matrix4.transformPoint(proj, 0.5f * aspect, 0.5f, -d, 1f)

        // After perspective divide (dividing by w = d):
        val ndcX = pt[0] / pt[3]
        val ndcY = pt[1] / pt[3]

        assertEquals(0.5f, ndcX, eps)
        assertEquals(0.5f, ndcY, eps)
    }

    @Test
    fun `parent hierarchy matrix multiplication propagates parent transform to child`() {
        val parent = Matrix4.createIdentity()
        Matrix4.translate(parent, 10f, 20f, 0f)
        Matrix4.scale(parent, 2f, 2f, 1f)

        val child = Matrix4.createIdentity()
        Matrix4.translate(child, 1f, 2f, 0f)

        val world = FloatArray(16)
        Matrix4.multiply(world, parent, child)

        // Point at child local origin (0, 0, 0)
        val pt = Matrix4.transformPoint(world, 0f, 0f, 0f, 1f)
        // Local origin moved to child translation (1, 2), then scaled by 2 (2, 4), then translated by (10, 20) = (12, 24)
        assertEquals(12f, pt[0], eps)
        assertEquals(24f, pt[1], eps)
    }

    @Test
    fun `circular parenting cycle detection correctly identifies cycles`() {
        val map = mapOf(
            "child" to "parent",
            "parent" to "grandparent",
            "grandparent" to "root",
        )
        assertFalse(Matrix4.detectCycle("child", "parent") { map[it] })
        assertTrue(Matrix4.detectCycle("root", "child") { map[it] })
        assertTrue(Matrix4.detectCycle("nodeA", "nodeA") { null })
    }
}
