package com.termex.replay15.editor.preview.engine

data class Vec2(val x: Float, val y: Float)

/** Normalized project-canvas coordinates shared by renderer and editing handles. */
object PreviewCoordinates {
    fun canvasToNdc(x: Float, y: Float): Vec2 = Vec2(x * 2f - 1f, 1f - y * 2f)
    fun canvasToView(x: Float, y: Float, width: Int, height: Int): Vec2 = Vec2(x * width, y * height)
    fun viewDeltaToCanvas(dx: Float, dy: Float, width: Int, height: Int): Vec2 =
        Vec2(dx / width.coerceAtLeast(1), dy / height.coerceAtLeast(1))
}
