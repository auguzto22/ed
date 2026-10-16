package com.termex.replay15.editor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Régua que corre sob um ponteiro fixo no centro (como "Eixo X / Eixo Y" do Básico no CapCut).
 * Arrastar para a esquerda aumenta o valor; o valor anda [unitsPerTick] a cada marquinha.
 * Uma marca maior a cada [majorEvery] marquinhas e um "tique" ao passar pelo zero.
 */
class ValueRulerView(
    context: Context,
    private val minimum: Float,
    private val maximum: Float,
    private val unitsPerTick: Float,
    private val majorEvery: Int = 10,
) : View(context) {
    var value: Float = 0f
        set(v) {
            field = v.coerceIn(minimum, maximum)
            invalidate()
        }
    /** Chamado a cada mudança feita com o dedo. */
    var onValueChange: (Float) -> Unit = {}
    /** Chamado quando o dedo sai da régua. */
    var onRelease: (Float) -> Unit = {}

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickSpacing get() = context.dp(9).toFloat()
    private var lastX = 0f
    private var dragValue = 0f

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(context.dp(40), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val centerX = width / 2f
        val midY = height / 2f
        val spacing = tickSpacing
        val offsetTicks = value / unitsPerTick
        val firstTick = floor(offsetTicks - centerX / spacing).toInt() - 1
        val lastTick = (offsetTicks + centerX / spacing).toInt() + 1
        for (tick in firstTick..lastTick) {
            val tickValue = tick * unitsPerTick
            if (tickValue < minimum - 1e-3f || tickValue > maximum + 1e-3f) continue
            val x = centerX + (tick - offsetTicks) * spacing
            val major = tick % majorEvery == 0
            val half = if (major) context.dp(9).toFloat() else context.dp(5).toFloat()
            paint.color = if (major) EditorStyle.alpha(EditorStyle.TEXT, 0xB0) else EditorStyle.alpha(EditorStyle.TEXT, 0x55)
            paint.strokeWidth = context.dp(if (major) 1.4f else 1f).toFloat()
            canvas.drawLine(x, midY - half, x, midY + half, paint)
        }
        // Bordas esmaecidas, como na régua do CapCut.
        val fade = width * .18f
        fadePaint.shader = LinearGradient(0f, 0f, fade, 0f, EditorStyle.PANEL, EditorStyle.PANEL and 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, fade, height.toFloat(), fadePaint)
        fadePaint.shader = LinearGradient(width - fade, 0f, width.toFloat(), 0f, EditorStyle.PANEL and 0x00FFFFFF, EditorStyle.PANEL, Shader.TileMode.CLAMP)
        canvas.drawRect(width - fade, 0f, width.toFloat(), height.toFloat(), fadePaint)
        // Ponteiro fixo.
        paint.color = EditorStyle.ACTION
        paint.strokeWidth = context.dp(2.2f).toFloat()
        canvas.drawLine(centerX, midY - context.dp(13), centerX, midY + context.dp(13), paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = event.x; dragValue = value
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = (lastX - event.x) / tickSpacing * unitsPerTick
                lastX = event.x
                val before = dragValue
                dragValue = (dragValue + delta).coerceIn(minimum, maximum)
                // Gruda no zero e avisa com um tique.
                val crossedZero = (before < 0f && dragValue >= 0f) || (before > 0f && dragValue <= 0f)
                if (crossedZero) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                val snapped = if (abs(dragValue) < unitsPerTick * .6f && minimum <= 0f && maximum >= 0f) 0f else dragValue
                val rounded = roundToStep(snapped)
                if (rounded != value) { value = rounded; onValueChange(value) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                onRelease(value)
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun roundToStep(v: Float): Float {
        val step = if (unitsPerTick >= 1f) 1f else unitsPerTick
        return ((v / step).roundToInt() * step).coerceIn(minimum, maximum)
    }
}
