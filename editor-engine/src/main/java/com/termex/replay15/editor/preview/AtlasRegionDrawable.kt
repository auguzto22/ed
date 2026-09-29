package com.termex.replay15.editor.preview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

/**
 * A lightweight Drawable that renders a specific sub-region of a shared Atlas Bitmap.
 * Eliminates the need to allocate separate Bitmaps for individual thumbnails.
 */
class AtlasRegionDrawable(
    private val atlas: Bitmap,
    val srcRect: Rect,
) : Drawable() {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        if (!atlas.isRecycled) {
            canvas.drawBitmap(atlas, srcRect, bounds, paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = srcRect.width()

    override fun getIntrinsicHeight(): Int = srcRect.height()
}
