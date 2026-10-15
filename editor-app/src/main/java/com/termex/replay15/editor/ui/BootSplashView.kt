package com.termex.replay15.editor.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import com.recly.editor.R
import kotlin.math.min

/**
 * Opening animation shown once per cold start, continuing the Android 12 system splash.
 * O símbolo BLITZ (o mesmo da splash do sistema) cresce de leve sobre um brilho vermelho suave,
 * a legenda aparece embaixo e a camada inteira dá um zoom e some para mostrar o app.
 * About 1.1 s, skipped with a tap.
 */
class BootSplashView(context: Context) : View(context) {
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val subtitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = EditorStyle.MUTED
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    /** Primeiro plano do ícone adaptável: o símbolo ocupa 56/108 do desenho, centralizado. */
    private val mark: Drawable? = context.getDrawable(R.drawable.ic_launcher_foreground)
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
        contentDescription = "BLITZ"
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
        // Brilho que "respira" atrás do símbolo.
        val glowAmount = phase(0f, .5f)
        glow.shader = RadialGradient(cx, cy, r * 3.2f, intArrayOf((0x66C50022 and 0x00FFFFFF) or ((0x66 * glowAmount).toInt() shl 24), 0x00C50022), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 3.2f, glow)
        // Símbolo: aparece e cresce de 82% até o tamanho final.
        val appear = phase(.05f, .6f)
        mark?.let { d ->
            // O desenho tem 108 de lado e o símbolo ocupa 56 deles; o símbolo final fica com ~2r.
            val side = r * 2f * (108f / 56f) * (.82f + .18f * appear)
            val half = side / 2f
            d.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
            d.alpha = (255 * appear).toInt()
            d.draw(canvas)
        }
        subtitle.textSize = r * .28f
        subtitle.alpha = (200 * phase(.45f, .9f)).toInt()
        canvas.drawText("Editor de vídeo", cx, cy + r * 1.9f, subtitle)
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
