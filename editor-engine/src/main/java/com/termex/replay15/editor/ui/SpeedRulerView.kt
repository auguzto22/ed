package com.termex.replay15.editor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.termex.replay15.editor.domain.MAX_CLIP_SPEED
import com.termex.replay15.editor.domain.MIN_CLIP_SPEED
import com.termex.replay15.editor.domain.formatSpeed
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Escala da régua de velocidade (jeito do CapCut): as marcas principais ficam com o mesmo
 * espaço entre si e, dentro de cada trecho, o valor anda em linha reta. Assim 0,1×–1× ocupa
 * tanto espaço quanto 10×–16×, e as velocidades mais usadas ficam fáceis de acertar com o dedo.
 */
object SpeedRulerScale {
    val STOPS: List<Float> = listOf(MIN_CLIP_SPEED, 1f, 2f, 5f, 10f, MAX_CLIP_SPEED)
    /** Marquinhas entre duas marcas principais. */
    const val MINOR_TICKS = 9

    /** Posição (0..1) na régua para uma velocidade. */
    fun fractionOf(speed: Float): Float {
        val value = speed.coerceIn(STOPS.first(), STOPS.last())
        val segment = STOPS.zipWithNext().indexOfFirst { value <= it.second }.coerceAtLeast(0)
        val (a, b) = STOPS[segment] to STOPS[segment + 1]
        return (segment + (value - a) / (b - a)) / (STOPS.size - 1)
    }

    /** Velocidade para uma posição (0..1), arredondada para 0,1× como no CapCut. */
    fun speedAt(fraction: Float): Float {
        val position = fraction.coerceIn(0f, 1f) * (STOPS.size - 1)
        val segment = floor(position).toInt().coerceAtMost(STOPS.size - 2)
        val t = position - segment
        val raw = STOPS[segment] + (STOPS[segment + 1] - STOPS[segment]) * t
        return round(raw)
    }

    fun round(speed: Float): Float =
        ((speed * 10f).roundToInt() / 10f).coerceIn(STOPS.first(), STOPS.last())
}

/** Régua de velocidade com marcas 0,1× · 1× · 2× · 5× · 10× · 16× e um anel que se arrasta. */
class SpeedRulerView(context: Context) : View(context) {
    var speed: Float = 1f
        set(value) {
            field = SpeedRulerScale.round(value)
            updateAccessibility(); invalidate()
        }
    /** Chamado a cada mudança feita com o dedo. */
    var onSpeedChange: (Float) -> Unit = {}
    /** Chamado quando o dedo sai da régua. */
    var onRelease: (Float) -> Unit = {}

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val pad get() = context.dp(26).toFloat()
    private val trackY get() = context.dp(26).toFloat()
    private val thumbRadius get() = context.dp(11).toFloat()
    private var lastStop = -1

    init {
        isFocusable = true
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updateAccessibility()
    }

    private fun updateAccessibility() {
        contentDescription = "Velocidade ${formatSpeed(speed)}. Arraste para os lados para mudar."
        if (android.os.Build.VERSION.SDK_INT >= 30) stateDescription = formatSpeed(speed)
    }

    private fun xOf(fraction: Float) = pad + (width - 2 * pad) * fraction

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = context.dp(76)
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val stops = SpeedRulerScale.STOPS
        val segments = stops.size - 1
        val majorHalf = context.dp(11).toFloat()
        val minorHalf = context.dp(5).toFloat()
        paint.strokeCap = Paint.Cap.ROUND

        // Marquinhas e marcas principais.
        for (segment in 0 until segments) {
            for (tick in 0..SpeedRulerScale.MINOR_TICKS) {
                if (tick == 0 && segment > 0) continue
                val fraction = (segment + tick / (SpeedRulerScale.MINOR_TICKS + 1f)) / segments
                val major = tick == 0
                val x = xOf(fraction)
                val half = if (major) majorHalf else minorHalf
                paint.color = if (major) EditorStyle.alpha(EditorStyle.TEXT, 0xCC) else EditorStyle.MUTED
                paint.strokeWidth = context.dp(if (major) 1.6f else 1f).toFloat()
                canvas.drawLine(x, trackY - half, x, trackY + half, paint)
            }
        }
        // Última marca principal.
        paint.color = EditorStyle.alpha(EditorStyle.TEXT, 0xCC)
        paint.strokeWidth = context.dp(1.6f).toFloat()
        canvas.drawLine(xOf(1f), trackY - majorHalf, xOf(1f), trackY + majorHalf, paint)

        // Rótulos 0,1× · 1× · 2× ...
        textPaint.textSize = context.dp(15).toFloat()
        val labelY = trackY + majorHalf + context.dp(26)
        stops.forEachIndexed { index, value ->
            val selected = abs(value - speed) < .001f
            textPaint.color = if (selected) EditorStyle.TEXT else EditorStyle.alpha(EditorStyle.TEXT, 0xD9)
            textPaint.isFakeBoldText = selected
            canvas.drawText(formatSpeed(value).replace('×', 'x'), xOf(index.toFloat() / segments), labelY, textPaint)
        }

        // Anel (alça) na velocidade atual.
        val x = xOf(SpeedRulerScale.fractionOf(speed))
        paint.style = Paint.Style.FILL
        paint.color = EditorStyle.PANEL
        canvas.drawCircle(x, trackY, thumbRadius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dp(2.5f).toFloat()
        paint.color = EditorStyle.ACTION
        canvas.drawCircle(x, trackY, thumbRadius, paint)
        paint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || width <= 2 * pad) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (event.actionMasked == MotionEvent.ACTION_DOWN) isPressed = true
                moveTo(event.x)
            }
            MotionEvent.ACTION_UP -> {
                moveTo(event.x); isPressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick(); onRelease(speed)
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onRelease(speed)
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun moveTo(touchX: Float) {
        val fraction = ((touchX - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
        val segments = SpeedRulerScale.STOPS.size - 1
        // Gruda nas marcas principais quando o dedo passa bem perto delas.
        val nearestStop = (fraction * segments).roundToInt()
        val snapped = abs(fraction * segments - nearestStop) < .06f
        val next = if (snapped) SpeedRulerScale.STOPS[nearestStop] else SpeedRulerScale.speedAt(fraction)
        if (snapped && nearestStop != lastStop) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        lastStop = if (snapped) nearestStop else -1
        if (abs(next - speed) < .001f) return
        speed = next
        onSpeedChange(speed)
    }
}
