package com.termex.replay15.editor.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.view.View

/**
 * Plays an effect's preview loop (frames rendered by EffectThumbnailRenderer.renderEffectLoop on
 * a frame of the user's own clip), like [AnimatedCaptionPreview] does for caption styles. Frames
 * are center-cropped; only views on screen keep redrawing, at a low frame rate to save battery.
 */
class AnimatedEffectThumbnail(context: Context) : View(context) {
    private var frames: List<Bitmap> = emptyList()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private var startedAt = System.nanoTime()

    /** Which item this view currently shows; recycled list cells use it to drop late results. */
    var boundKey: String? = null

    fun setFrames(value: List<Bitmap>?) {
        frames = value.orEmpty().filterNot { it.isRecycled }
        startedAt = System.nanoTime()
        invalidate()
    }

    fun hasFrames(): Boolean = frames.isNotEmpty()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = frames
        if (current.isEmpty() || width <= 0 || height <= 0) return
        val index = (((System.nanoTime() - startedAt) / 1_000_000L / FRAME_MS) % current.size).toInt()
        val frame = current[index]
        if (frame.isRecycled) return
        val scale = maxOf(width / frame.width.toFloat(), height / frame.height.toFloat())
        matrix.setScale(scale, scale)
        matrix.postTranslate((width - frame.width * scale) / 2f, (height - frame.height * scale) / 2f)
        canvas.drawBitmap(frame, matrix, paint)
        if (current.size > 1 && isAttachedToWindow && isShown) postInvalidateDelayed(FRAME_MS)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        invalidate()
    }

    companion object {
        /** 10 fps: smooth enough for a small preview, cheap with a full grid on screen. */
        private const val FRAME_MS = 100L
    }
}
