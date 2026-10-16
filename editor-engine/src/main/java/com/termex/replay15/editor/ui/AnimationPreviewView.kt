package com.termex.replay15.editor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import com.termex.replay15.editor.animation.AnimationCategory
import com.termex.replay15.editor.animation.AnimationPreset
import com.termex.replay15.editor.animation.AnimationPreviewSampler
import com.termex.replay15.editor.preview.ReferencePreviewFrames

/**
 * Live thumbnail of an animation preset: a small "clip" card moving inside a stage, driven by
 * the same points that become keyframes. Animates only while on screen.
 *
 * O "clipe" que se mexe é o vídeo de referência tocando (como nas outras galerias); sem o asset,
 * volta ao cartão vermelho com o ícone de play.
 */
class AnimationPreviewView(context: Context, preset: AnimationPreset, durationMs: Long = defaultDuration(preset)) : View(context) {
    var preset: AnimationPreset = preset
        set(value) { field = value; invalidate() }
    var durationMs: Long = durationMs
        set(value) { field = value.coerceAtLeast(100L); startedAt = SystemClock.uptimeMillis(); invalidate() }

    private val density = resources.displayMetrics.density
    private val stagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0B0B0C.toInt() }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x10FFFFFF; strokeWidth = density }
    private val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val playPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFFFFF.toInt() }
    private val stage = RectF()
    private val card = RectF()
    private val stageClip = Path()
    private val play = Path()
    private val visible = Rect()
    private var startedAt = SystemClock.uptimeMillis()
    private var reference: List<android.graphics.Bitmap>? = null
    private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val cardPath = Path()
    private val src = Rect()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        stage.set(0f, 0f, w.toFloat(), h.toFloat())
        stageClip.reset()
        stageClip.addRoundRect(stage, 12f * density, 12f * density, Path.Direction.CW)
        clipPaint.shader = LinearGradient(0f, 0f, w * .6f, h * .6f, EditorStyle.ACTION_TEXT, EditorStyle.ACTION, Shader.TileMode.CLAMP)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startedAt = SystemClock.uptimeMillis()
        if (reference == null) ReferencePreviewFrames.withLoop(context) { frames -> reference = frames; invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.save()
        canvas.clipPath(stageClip)
        canvas.drawRect(stage, stagePaint)
        // Faint thirds grid so movement reads even when the card is small.
        for (i in 1..2) {
            canvas.drawLine(w * i / 3f, 0f, w * i / 3f, h, gridPaint)
            canvas.drawLine(0f, h * i / 3f, w, h * i / 3f, gridPaint)
        }
        val loop = preset.category == AnimationCategory.LOOP
        val progress = AnimationPreviewSampler.progressAt(SystemClock.uptimeMillis() - startedAt, durationMs, loop)
        val pose = AnimationPreviewSampler.sample(preset.points, progress)
        val cardW = w * .5f; val cardH = h * .56f
        canvas.save()
        canvas.translate(w / 2f + pose.x * w, h / 2f + pose.y * h)
        canvas.rotate(pose.rotation)
        canvas.scale(pose.zoom, pose.zoom)
        card.set(-cardW / 2f, -cardH / 2f, cardW / 2f, cardH / 2f)
        val alpha = (pose.opacity * 255).toInt().coerceIn(0, 255)
        clipPaint.alpha = alpha
        shinePaint.alpha = (alpha * .2f).toInt()
        playPaint.alpha = (alpha * .9f).toInt()
        val frames = reference
        if (!frames.isNullOrEmpty()) {
            val frame = frames[((SystemClock.uptimeMillis() / ReferencePreviewFrames.FRAME_MS) % frames.size).toInt()]
            // Recorte central do quadro quadrado na proporção do cartão.
            val side = frame.width
            val srcH = (side * cardH / cardW).toInt().coerceAtMost(frame.height)
            src.set(0, (frame.height - srcH) / 2, side, (frame.height + srcH) / 2)
            cardPath.reset(); cardPath.addRoundRect(card, 7f * density, 7f * density, Path.Direction.CW)
            framePaint.alpha = alpha
            canvas.save(); canvas.clipPath(cardPath)
            canvas.drawBitmap(frame, src, card, framePaint)
            canvas.restore()
        } else {
            canvas.drawRoundRect(card, 7f * density, 7f * density, clipPaint)
            canvas.drawRoundRect(card.left, card.top, card.right, card.top + cardH * .42f, 7f * density, 7f * density, shinePaint)
            val s = cardH * .17f
            play.reset(); play.moveTo(-s * .7f, -s); play.lineTo(s, 0f); play.lineTo(-s * .7f, s); play.close()
            canvas.drawPath(play, playPaint)
        }
        canvas.restore()
        canvas.restore()

        if (!isAttachedToWindow) return
        // Off-screen tiles (scrolled away in the grid) only poll a few times per second.
        if (isShown && getGlobalVisibleRect(visible)) postInvalidateOnAnimation()
        else postInvalidateDelayed(250L)
    }

    companion object {
        fun defaultDuration(preset: AnimationPreset): Long = if (preset.category == AnimationCategory.LOOP) 1400L else 700L
    }
}
