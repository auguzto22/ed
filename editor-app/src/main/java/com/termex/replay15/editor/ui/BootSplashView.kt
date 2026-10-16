package com.termex.replay15.editor.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Abertura animada (uma vez por cold start), continuando a splash do Android 12.
 * Monta o símbolo BLITZ peça por peça: as cantoneiras fecham o enquadramento, o "B" aparece já
 * com o corte, o raio vermelho é riscado dentro do corte e o ponto de gravação "acende".
 * Depois a camada dá um zoom e some. ~1,3 s; um toque pula.
 *
 * O desenho usa as mesmas coordenadas do vetor (res/drawable/ic_launcher_foreground.xml):
 * símbolo em 100×100, "B" em 70×100 com translate(30.4, 22) e escala 0.56.
 */
class BootSplashView(context: Context) : View(context) {
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; color = WHITE
        strokeCap = Paint.Cap.BUTT; strokeJoin = Paint.Join.MITER
    }
    private val bPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = WHITE }
    private val boltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5f; color = RED
        strokeCap = Paint.Cap.BUTT; strokeJoin = Paint.Join.MITER
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = RED }
    private val subtitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8E8E93.toInt() // splash é sempre preta (continua a do sistema)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val boltMeasure = PathMeasure(BOLT, false)
    private val boltLength = boltMeasure.length
    private val boltSegment = Path()

    private var t = 0f
    private val ease = PathInterpolator(.2f, .9f, .25f, 1f)
    private var done: (() -> Unit)? = null
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1300
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) { exit() }
        })
    }

    init {
        setBackgroundColor(0xFF000000.toInt()) // sempre preta, nos dois temas
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
        val side = r * 3.4f // lado do símbolo (100 unidades)

        // Brilho vermelho que "respira" atrás do símbolo.
        val glowAmount = phase(0f, .5f)
        glow.shader = RadialGradient(cx, cy, r * 3.2f, intArrayOf((EditorStyle.ACTION and 0x00FFFFFF) or ((0x66 * glowAmount).toInt() shl 24), EditorStyle.alpha(EditorStyle.ACTION, 0x00)), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 3.2f, glow)

        canvas.save()
        canvas.translate(cx - side / 2f, cy - side / 2f)
        canvas.scale(side / 100f, side / 100f)

        // 1. Cantoneiras chegam de fora e fecham o enquadramento.
        val pBrackets = phase(0f, .4f)
        val slide = 16f * (1f - pBrackets)
        bracketPaint.alpha = (115 * pBrackets).toInt() // 45% como no ícone
        for (i in CORNERS.indices) {
            canvas.save()
            canvas.translate(CORNER_DIRS[i * 2] * slide, CORNER_DIRS[i * 2 + 1] * slide)
            canvas.drawPath(CORNERS[i], bracketPaint)
            canvas.restore()
        }

        // 4. Ponto de gravação acende com um pequeno "pulo".
        val pDot = phase(.66f, .92f)
        if (pDot > 0f) {
            dotPaint.alpha = (255 * pDot).toInt()
            val radius = 4.5f * (pDot + .45f * sin(PI * pDot).toFloat())
            canvas.drawCircle(80f, 20f, radius, dotPaint)
        }

        canvas.save()
        canvas.translate(30.4f, 22f)
        canvas.scale(.56f, .56f)

        // 2. O "B" cresce de 88% e aparece, já com o vão do raio.
        val pB = phase(.14f, .56f)
        if (pB > 0f) {
            val s = .88f + .12f * pB
            canvas.save()
            canvas.scale(s, s, 35f, 50f)
            canvas.clipOutPath(STRIP)
            bPaint.alpha = (255 * pB).toInt()
            canvas.drawPath(LETTER_B, bPaint)
            canvas.restore()
        }

        // 3. O raio vermelho é riscado dentro do vão, da esquerda para a direita.
        val pBolt = phase(.42f, .8f)
        if (pBolt > 0f) {
            boltSegment.reset()
            boltMeasure.getSegment(0f, boltLength * pBolt, boltSegment, true)
            canvas.drawPath(boltSegment, boltPaint)
        }

        canvas.restore()
        canvas.restore()

        subtitle.textSize = r * .28f
        subtitle.alpha = (200 * phase(.55f, .95f)).toInt()
        canvas.drawText("Editor de vídeo", cx, cy + side / 2f + r * .7f, subtitle)
    }

    companion object {
        private val WHITE = 0xFFFFFFFF.toInt()
        private val RED = EditorStyle.ACTION

        private val CORNERS = arrayOf(
            Path().apply { moveTo(8f, 26f); lineTo(8f, 8f); lineTo(26f, 8f) },
            Path().apply { moveTo(74f, 8f); lineTo(92f, 8f); lineTo(92f, 26f) },
            Path().apply { moveTo(92f, 74f); lineTo(92f, 92f); lineTo(74f, 92f) },
            Path().apply { moveTo(26f, 92f); lineTo(8f, 92f); lineTo(8f, 74f) },
        )
        /** Direção (x, y) de onde cada cantoneira chega. */
        private val CORNER_DIRS = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f)

        private val LETTER_B = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            moveTo(0f, 0f); lineTo(44f, 0f)
            cubicTo(58f, 0f, 66f, 9f, 66f, 24f); cubicTo(66f, 35f, 61f, 42f, 53f, 46f)
            cubicTo(64f, 50f, 70f, 58f, 70f, 72f); cubicTo(70f, 89f, 60f, 100f, 44f, 100f)
            lineTo(0f, 100f); close()
            moveTo(20f, 18f); lineTo(42f, 18f)
            cubicTo(46f, 18f, 47f, 21f, 47f, 27f); cubicTo(47f, 33f, 45f, 37f, 41f, 37f)
            lineTo(20f, 37f); close()
            moveTo(20f, 55f); lineTo(44f, 55f)
            cubicTo(49f, 55f, 51f, 59f, 51f, 67f); cubicTo(51f, 77f, 48f, 82f, 43f, 82f)
            lineTo(20f, 82f); close()
        }

        /** Faixa do corte (linha do raio com 7 de cada lado, cantos em quina). */
        private val STRIP = Path().apply {
            moveTo(-3.34f, 68.48f); lineTo(29.28f, 55.09f); lineTo(35.35f, 69.25f); lineTo(82.81f, 48.41f)
            lineTo(77.19f, 35.59f); lineTo(42.65f, 50.75f); lineTo(36.72f, 36.91f); lineTo(-8.66f, 55.52f); close()
        }

        private val BOLT = Path().apply { moveTo(-6f, 62f); lineTo(33f, 46f); lineTo(39f, 60f); lineTo(80f, 42f) }

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
