package com.termex.replay15.editor.preview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock

/**
 * Toca uma sequência de quadros em loop, como um GIF, sem decodificar nada no caminho.
 *
 * O quadro sai do relógio do sistema, então todos os cartões de uma galeria andam juntos (o mesmo
 * passo do vídeo de referência em todos), o que facilita comparar os looks lado a lado. Só anima
 * enquanto está visível: o ImageView chama setVisible(false) quando o cartão sai da tela.
 *
 * Os filtros de cor reaproveitam os mesmos quadros com um ColorMatrixColorFilter (colorFilter).
 */
class ReferenceLoopDrawable(
    private val frames: List<Bitmap>,
    private val frameMs: Long = ReferencePreviewFrames.FRAME_MS,
) : Drawable(), Animatable, Runnable {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var running = false

    override fun draw(canvas: Canvas) {
        if (frames.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val frame = frames[((now / frameMs) % frames.size).toInt()]
        if (!frame.isRecycled) canvas.drawBitmap(frame, null, bounds, paint)
        if (running && frames.size > 1) {
            unscheduleSelf(this)
            scheduleSelf(this, now - now % frameMs + frameMs)
        }
    }

    override fun run() = invalidateSelf()

    override fun start() {
        if (running) return
        running = true
        invalidateSelf()
    }

    override fun stop() {
        running = false
        unscheduleSelf(this)
    }

    override fun isRunning() = running

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible) start() else stop()
        return changed
    }

    override fun getIntrinsicWidth() = frames.firstOrNull()?.width ?: -1
    override fun getIntrinsicHeight() = frames.firstOrNull()?.height ?: -1

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
