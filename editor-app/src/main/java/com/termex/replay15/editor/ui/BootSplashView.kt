package com.termex.replay15.editor.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import kotlin.math.min

/**
 * Opening animation shown once per cold start, continuing the Android 12 system splash.
 * The two Recly rings sweep in around a soft glow, the wordmark rises into place, then the whole
 * layer zooms slightly and fades to reveal the app. About 1.1 s, skipped with a tap.
 */
class BootSplashView(context: Context) : View(context) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
    }
    private val subtitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = EditorStyle.MUTED
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private var t = 0f
    private val ease = PathInterpolator(.2f, .9f, .25f, 1f)
    private var done: (() -> Unit)? = null
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1100
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) { exit() }
        })
    }

    init {
        setBackgroundColor(EditorStyle.BG)
        isClickable = true
        contentDescription = "Blitz"
        setOnClickListener { animator.cancel(); exit() }
    }

    fun play(onDone: () -> Unit) { done = onDone; animator.start() }

    private var exiting = false
    private fun exit() {
        if (exiting) return
        exiting = true
        animate().alpha(0f).scaleX(1.08f).scaleY(1.08f).setDuration(320)
            .setInterpolator(PathInterpolator(.4f, 0f, .2f, 1f))
            .withEndAction { (parent as? ViewGroup)?.removeView(this); done?.invoke() }.start()
    }

    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    private fun phase(from: Float, to: Float) = ease.getInterpolation(((t - from) / (to - from)).coerceIn(0f, 1f))

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h * .44f
        val r = min(w, h) * .13f
        // Breathing glow behind the mark.
        val glowAmount = phase(0f, .5f)
        glow.shader = RadialGradient(cx, cy, r * 3.2f, intArrayOf((0x66C50022 and 0x00FFFFFF) or ((0x66 * glowAmount).toInt() shl 24), 0x00C50022), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 3.2f, glow)
        // Outer ring (orange sweep) then inner ring (white), drawn as growing arcs.
        val outer = phase(.02f, .55f); val inner = phase(.18f, .7f)
        ring.strokeWidth = r * .3f
        ring.shader = SweepGradient(cx, cy, intArrayOf(EditorStyle.ACCENT_DEEP, EditorStyle.ACCENT, EditorStyle.ACCENT_PINK, EditorStyle.ACCENT_DEEP), null)
        canvas.save(); canvas.rotate(-90f + 120f * (1f - outer), cx, cy)
        canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), 0f, 300f * outer, false, ring)
        canvas.restore()
        ring.shader = null; ring.color = Color.WHITE; ring.strokeWidth = r * .24f
        val ri = r * .52f
        canvas.save(); canvas.rotate(90f - 90f * (1f - inner), cx, cy)
        canvas.drawArc(RectF(cx - ri, cy - ri, cx + ri, cy + ri), 0f, 290f * inner, false, ring)
        canvas.restore()
        // Wordmark rises and sharpens.
        val word = phase(.35f, .85f)
        text.textSize = r * .78f
        text.alpha = (255 * word).toInt()
        text.letterSpacing = .18f * (1f - word) + .02f
        text.shader = LinearGradient(cx - r * 2, 0f, cx + r * 2, 0f, Color.WHITE, 0xFFE5DEFF.toInt(), Shader.TileMode.CLAMP)
        canvas.drawText("Blitz", cx, cy + r * 2.25f + r * .5f * (1f - word), text)
        subtitle.textSize = r * .28f
        subtitle.alpha = (200 * phase(.55f, .95f)).toInt()
        canvas.drawText("Editor de vídeo", cx, cy + r * 2.85f, subtitle)
    }

    companion object {
        @Volatile private var shownThisProcess = false

        /** Adds the intro over [activity]'s content once per process (cold start only). */
        fun showOnce(activity: Activity) {
            if (shownThisProcess) return
            shownThisProcess = true
            val root = activity.window.decorView as? ViewGroup ?: return
            val splash = BootSplashView(activity)
            root.addView(splash, ViewGroup.LayoutParams(-1, -1))
            splash.play {}
        }
    }
}
