package com.termex.replay15.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

object ReclyMotion {

    fun install(root: View) {
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) install(root.getChildAt(i))
        }
        if (!root.isClickable) return
        root.enablePressAnimation()
    }

    /**
     * Entrada suave com escala e opacidade para cards (especificação visual).
     */
    fun enter(view: View, delay: Long = 0) {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
            view.translationY = 0f
            return
        }
        view.alpha = 0f
        view.scaleX = 0.95f
        view.scaleY = 0.95f
        val density = view.resources.displayMetrics.density
        view.translationY = density * 12f
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setStartDelay(delay)
            .setDuration(280)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()
    }

    /**
     * Efeito de profundidade e retorno elástico com feedback tátil imediato no toque.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun View.enablePressAnimation() {
        val initialElevation = elevation
        setOnTouchListener { view, event ->
            if (!view.isEnabled) return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    view.animate()
                        .scaleX(PRESSED_SCALE)
                        .scaleY(PRESSED_SCALE)
                        .translationZ(-2f * resources.displayMetrics.density)
                        .setDuration(PRESS_DURATION_MS)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .translationZ(0f)
                        .setDuration(RELEASE_DURATION_MS)
                        .setInterpolator(OvershootInterpolator(SPRING_TENSION))
                        .start()
                }
            }

            // Retornar false preserva o ripple nativo e o OnClickListener associado
            false
        }
    }

    /**
     * Reflexo de luz (Light Sweep / Shimmer) percorrendo o botão principal com feixe prateado.
     */
    fun attachLightSweep(button: View): ValueAnimator {
        val shimmerDrawable = ShimmerBeamDrawable()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            button.foreground = shimmerDrawable
        } else {
            button.overlay.add(shimmerDrawable)
        }

        val animator = ValueAnimator.ofFloat(-1.2f, 2.2f).apply {
            duration = 2600L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val progress = anim.animatedValue as Float
                shimmerDrawable.setProgress(progress, button.width, button.height)
                button.invalidate()
            }
        }
        button.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { animator.start() }
            override fun onViewDetachedFromWindow(v: View) { animator.cancel() }
        })
        if (button.isAttachedToWindow) animator.start()
        return animator
    }

    /**
     * Pulsação suave para o indicador de gravação ou estado ativo.
     */
    fun startPulsing(view: View): ValueAnimator {
        val animator = ValueAnimator.ofFloat(1.0f, 1.25f).apply {
            duration = 800L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val scale = anim.animatedValue as Float
                view.scaleX = scale
                view.scaleY = scale
                view.alpha = 0.70f + (1.25f - scale) * 1.2f
            }
        }
        animator.start()
        return animator
    }

    /**
     * Animação de celebração/sucesso ao salvar um momento do replay.
     */
    fun animateSaveSuccess(card: View, onComplete: (() -> Unit)? = null) {
        card.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.LONG_PRESS
        )
        card.animate()
            .scaleX(1.03f)
            .scaleY(1.03f)
            .setDuration(120)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                card.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(260)
                    .setInterpolator(OvershootInterpolator(2.5f))
                    .withEndAction { onComplete?.invoke() }
                    .start()
            }
            .start()
    }

    /**
     * Drawable que desenha um feixe diagonal de luz especular prateada deslizando sobre o botão.
     */
    private class ShimmerBeamDrawable : Drawable() {
        private var progress = -1.2f
        private var viewWidth = 0
        private var viewHeight = 0
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val matrix = Matrix()
        private val clipPath = Path()
        private val rectF = RectF()

        fun setProgress(prog: Float, w: Int, h: Int) {
            this.progress = prog
            this.viewWidth = w
            this.viewHeight = h
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            if (viewWidth <= 0 || viewHeight <= 0 || progress < -0.5f || progress > 1.5f) return

            val density = canvas.density.toFloat().takeIf { it > 0f } ?: 2.75f
            val cornerRadius = 24f * density
            rectF.set(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
            clipPath.reset()
            clipPath.addRoundRect(rectF, cornerRadius, cornerRadius, Path.Direction.CW)

            val saveCount = canvas.save()
            canvas.clipPath(clipPath)

            val beamWidth = viewWidth * 0.45f
            val currentX = progress * (viewWidth + beamWidth) - beamWidth

            val shader = LinearGradient(
                currentX, 0f, currentX + beamWidth, viewHeight.toFloat(),
                intArrayOf(0x00FFFFFF, 0x40FFFFFF, 0x75FFFFFF, 0x40FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.35f, 0.50f, 0.65f, 1f),
                Shader.TileMode.CLAMP
            )
            paint.shader = shader
            canvas.drawRect(rectF, paint)

            canvas.restoreToCount(saveCount)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private const val PRESSED_SCALE = .97f
    private const val PRESS_DURATION_MS = 80L
    private const val RELEASE_DURATION_MS = 180L
    private const val SPRING_TENSION = 2.2f
}
