package com.termex.replay15.editor.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import kotlin.math.sin

/**
 * Small, non-blocking feedback for editor actions: a pill that drops in over the preview with an
 * animated check, then leaves on its own. Replaces stacked system toasts.
 */
object ActionFeedback {
    private var current: WeakReference<View>? = null

    fun show(host: ViewGroup, message: String, kind: Kind = Kind.SUCCESS) {
        val context = host.context
        current?.get()?.let { old -> old.animate().cancel(); (old.parent as? ViewGroup)?.removeView(old) }
        val density = context.resources.displayMetrics.density
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((10 * density).toInt(), (7 * density).toInt(), (14 * density).toInt(), (7 * density).toInt())
            background = GradientDrawable().apply {
                setColor(0xE6141419.toInt())
                cornerRadius = 20 * density
                setStroke((1 * density).toInt(), if (kind == Kind.ERROR) 0x66FF5C7A else 0x55C50022)
            }
            elevation = 10 * density
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = message
        }
        val mark = CheckMark(context, if (kind == Kind.ERROR) EditorStyle.DANGER else EditorStyle.ACCENT, kind == Kind.ERROR)
        pill.addView(mark, LinearLayout.LayoutParams((18 * density).toInt(), (18 * density).toInt()).apply {
            marginEnd = (8 * density).toInt()
        })
        pill.addView(TextView(context).apply {
            text = message
            textSize = 12.5f
            setTextColor(Color.WHITE)
            maxLines = 2
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        })
        host.addView(pill, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = (12 * density).toInt()
            leftMargin = (24 * density).toInt(); rightMargin = (24 * density).toInt()
        })
        current = WeakReference(pill)
        pill.alpha = 0f
        pill.translationY = -18 * density
        pill.scaleX = .92f; pill.scaleY = .92f
        pill.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(260)
            .setInterpolator(OvershootInterpolator(1.4f)).withEndAction { mark.play() }.start()
        pill.announceForAccessibility(message)
        pill.performHapticFeedback(if (kind == Kind.ERROR) HapticFeedbackConstants.LONG_PRESS else confirmHaptic())
        pill.postDelayed({
            pill.animate().alpha(0f).translationY(-10 * density).setDuration(220)
                .setInterpolator(DecelerateInterpolator()).withEndAction { (pill.parent as? ViewGroup)?.removeView(pill) }.start()
        }, if (message.length > 40) 2600L else 1700L)
    }

    enum class Kind { SUCCESS, ERROR }
}

internal fun confirmHaptic(): Int =
    if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY

/** Animated check (or cross) drawn stroke by stroke. */
private class CheckMark(context: Context, private val color: Int, private val cross: Boolean) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private var progress = 0f

    fun play() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280; interpolator = DecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
        }.start()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        paint.color = color; paint.style = Paint.Style.FILL; paint.alpha = 50
        canvas.drawCircle(w / 2f, h / 2f, w / 2f, paint)
        paint.style = Paint.Style.STROKE; paint.alpha = 255; paint.strokeWidth = w * .12f
        val path = Path()
        if (cross) {
            path.moveTo(w * .32f, h * .32f); path.lineTo(w * .68f, h * .68f)
            path.moveTo(w * .68f, h * .32f); path.lineTo(w * .32f, h * .68f)
        } else {
            path.moveTo(w * .28f, h * .52f); path.lineTo(w * .44f, h * .68f); path.lineTo(w * .73f, h * .34f)
        }
        val measure = android.graphics.PathMeasure(path, false)
        val out = Path()
        do {
            measure.getSegment(0f, measure.length * progress.coerceIn(0f, 1f), out, true)
        } while (measure.nextContour())
        canvas.drawPath(out, paint)
    }
}

/**
 * Spinning gradient ring with a breathing core, used while something works in the background.
 * Switches to a success check (or error cross) with a small pop.
 */
class ProgressOrb(context: Context) : View(context) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.WHITE }
    private var angle = 0f
    private var phase = 0f
    private var state = 0 // 0 working, 1 done, 2 failed
    private var markProgress = 0f
    /** Optional determinate progress 0..1 drawn as a second arc. */
    var progress: Float? = null; set(value) { field = value; invalidate() }
    private val spin = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1100; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { angle = (it.animatedValue as Float) * 360f; phase += .05f; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (state == 0) spin.start() }
    override fun onDetachedFromWindow() { spin.cancel(); super.onDetachedFromWindow() }

    fun finish(success: Boolean) {
        spin.cancel()
        state = if (success) 1 else 2
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320; interpolator = DecelerateInterpolator()
            addUpdateListener { markProgress = it.animatedValue as Float; invalidate() }
        }.start()
        animate().scaleX(1.15f).scaleY(1.15f).setDuration(120).withEndAction {
            animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(OvershootInterpolator(2f)).start()
        }.start()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val stroke = w * .1f
        val box = RectF(stroke, stroke, w - stroke, h - stroke)
        ring.strokeWidth = stroke
        when (state) {
            0 -> {
                ring.shader = SweepGradient(w / 2f, h / 2f,
                    intArrayOf(0x00C50022, EditorStyle.ACCENT, EditorStyle.ACCENT_PINK, EditorStyle.CYAN, 0x00C50022), null)
                canvas.save(); canvas.rotate(angle, w / 2f, h / 2f)
                canvas.drawArc(box, 0f, 300f, false, ring)
                canvas.restore()
                ring.shader = null
                progress?.let { value ->
                    ring.color = 0x33FFFFFF
                    canvas.drawArc(box, -90f, 360f, false, ring)
                    ring.color = Color.WHITE
                    canvas.drawArc(box, -90f, 360f * value.coerceIn(0f, 1f), false, ring)
                }
                val pulse = .5f + .5f * sin(phase)
                core.color = EditorStyle.ACCENT; core.alpha = (90 + 80 * pulse).toInt()
                canvas.drawCircle(w / 2f, h / 2f, w * (.14f + .06f * pulse), core)
            }
            else -> {
                core.color = if (state == 1) EditorStyle.GREEN else EditorStyle.DANGER
                core.alpha = 255
                canvas.drawCircle(w / 2f, h / 2f, w / 2f - 1f, core)
                mark.strokeWidth = w * .1f
                val path = Path()
                if (state == 1) { path.moveTo(w * .28f, h * .52f); path.lineTo(w * .44f, h * .68f); path.lineTo(w * .73f, h * .34f) }
                else { path.moveTo(w * .34f, h * .34f); path.lineTo(w * .66f, h * .66f); path.moveTo(w * .66f, h * .34f); path.lineTo(w * .34f, h * .66f) }
                val measure = android.graphics.PathMeasure(path, false)
                val out = Path()
                do { measure.getSegment(0f, measure.length * markProgress, out, true) } while (measure.nextContour())
                canvas.drawPath(out, mark)
            }
        }
    }
}

/**
 * Floating card for long work (analysis, automatic captions). It docks to the side of the preview
 * and never blocks the editor: the user keeps editing while it runs, can collapse it to a small
 * orb, cancel it, and gets an action button when the result is ready.
 */
class BackgroundTaskCard private constructor(
    private val host: ViewGroup,
    title: String,
    private val onCancel: (() -> Unit)?,
) {
    private val context = host.context
    private val density = context.resources.displayMetrics.density
    private fun px(v: Float) = (v * density).toInt()
    private val orb = ProgressOrb(context)
    private val titleView = TextView(context).apply {
        text = title; textSize = 13f; setTextColor(Color.WHITE); maxLines = 1
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
    }
    private val stageView = TextView(context).apply { textSize = 11f; setTextColor(EditorStyle.MUTED); maxLines = 2 }
    private val textColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(titleView); addView(stageView)
    }
    private val shimmer = ShimmerBar(context)
    private val actionView = TextView(context).apply {
        textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setPadding(px(12f), px(6f), px(12f), px(6f))
        background = EditorStyle.accentGradient(14 * density)
        visibility = View.GONE
    }
    private val closeView = TextView(context).apply {
        text = "✕"; textSize = 13f; setTextColor(EditorStyle.MUTED); gravity = Gravity.CENTER
        contentDescription = "Cancelar"
        setPadding(px(6f), px(2f), px(2f), px(2f))
    }
    private var collapsed = false
    private var finished = false
    val view: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(10f), px(10f), px(10f), px(10f))
        background = GradientDrawable().apply {
            setColor(0xEB121217.toInt()); cornerRadius = 18 * density
            setStroke(px(1f), 0x44C50022)
        }
        elevation = 14 * density
        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(orb, LinearLayout.LayoutParams(px(30f), px(30f)).apply { marginEnd = px(10f) })
        top.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(closeView, LinearLayout.LayoutParams(px(28f), px(28f)))
        addView(top)
        addView(shimmer, LinearLayout.LayoutParams(-1, px(3f)).apply { topMargin = px(9f) })
        addView(actionView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
        isClickable = true
        setOnClickListener { toggleCollapsed() }
    }

    init {
        closeView.setOnClickListener {
            if (finished) dismiss() else { onCancel?.invoke(); update("Cancelando...") }
        }
        closeView.visibility = if (onCancel == null) View.GONE else View.VISIBLE
        host.addView(view, FrameLayout.LayoutParams(px(232f), -2, Gravity.TOP or Gravity.END).apply {
            topMargin = px(10f); rightMargin = px(10f)
        })
        view.alpha = 0f; view.translationX = 40 * density
        view.animate().alpha(1f).translationX(0f).setDuration(320)
            .setInterpolator(PathInterpolator(.2f, .9f, .25f, 1f)).start()
    }

    fun update(stage: String, progress: Float? = null) {
        if (finished) return
        if (stageView.text != stage) {
            stageView.animate().cancel()
            stageView.alpha = .3f
            stageView.text = stage
            stageView.animate().alpha(1f).setDuration(180).start()
        }
        orb.progress = progress
    }

    fun succeed(message: String, actionLabel: String? = null, action: (() -> Unit)? = null) =
        finish(true, message, actionLabel, action)

    fun fail(message: String) = finish(false, message, null, null)

    private fun finish(success: Boolean, message: String, actionLabel: String?, action: (() -> Unit)?) {
        if (finished) return
        finished = true
        if (collapsed) toggleCollapsed()
        orb.finish(success)
        shimmer.stop(success)
        stageView.text = message
        closeView.text = "✕"; closeView.visibility = View.VISIBLE; closeView.contentDescription = "Fechar"
        if (actionLabel != null && action != null) {
            actionView.text = actionLabel
            actionView.visibility = View.VISIBLE
            actionView.alpha = 0f
            actionView.animate().alpha(1f).setDuration(220).start()
            actionView.setOnClickListener { dismiss(); action() }
        } else {
            view.postDelayed({ dismiss() }, if (success) 3500L else 6000L)
        }
        view.performHapticFeedback(if (success) confirmHaptic() else HapticFeedbackConstants.LONG_PRESS)
    }

    private fun toggleCollapsed() {
        if (finished && !collapsed) return
        collapsed = !collapsed
        val visibility = if (collapsed) View.GONE else View.VISIBLE
        textColumn.visibility = visibility; closeView.visibility = if (collapsed || onCancel == null && !finished) View.GONE else View.VISIBLE
        shimmer.visibility = visibility
        if (actionView.text.isNotEmpty() && finished) actionView.visibility = visibility
        val params = view.layoutParams
        params.width = if (collapsed) -2 else px(232f)
        view.layoutParams = params
        view.contentDescription = if (collapsed) "${titleView.text}. Toque para expandir" else null
    }

    fun dismiss() {
        view.animate().cancel()
        view.animate().alpha(0f).translationX(30 * density).setDuration(220)
            .withEndAction { host.removeView(view) }.start()
    }

    companion object {
        fun show(host: ViewGroup, title: String, onCancel: (() -> Unit)? = null): BackgroundTaskCard =
            BackgroundTaskCard(host, title, onCancel)
    }
}

/** Indeterminate shimmer line that settles into a solid colour when the task ends. */
private class ShimmerBar(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var offset = 0f
    private var endColor: Int? = null
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1300; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { offset = it.animatedValue as Float; invalidate() }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (endColor == null) animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }
    fun stop(success: Boolean) { animator.cancel(); endColor = if (success) EditorStyle.GREEN else EditorStyle.DANGER; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = h / 2f
        paint.shader = null; paint.color = 0x22FFFFFF
        canvas.drawRoundRect(0f, 0f, w, h, r, r, paint)
        val done = endColor
        if (done != null) { paint.color = done; canvas.drawRoundRect(0f, 0f, w, h, r, r, paint); return }
        val band = w * .4f
        val start = -band + (w + band) * offset
        paint.shader = android.graphics.LinearGradient(start, 0f, start + band, 0f,
            intArrayOf(0x00C50022, EditorStyle.ACCENT, EditorStyle.CYAN, 0x00E5304F), null, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRoundRect(maxOf(0f, start), 0f, minOf(w, start + band), h, r, r, paint)
    }
}

/**
 * Where background work and action feedback appear. The editor screen registers its preview
 * container; tools in the engine module use it without knowing the activity layout.
 */
object EditorOverlayHost {
    private var host: WeakReference<ViewGroup>? = null

    fun attach(container: ViewGroup) { host = WeakReference(container) }
    fun detach(container: ViewGroup) { if (host?.get() === container) host = null }
    fun get(): ViewGroup? = host?.get()?.takeIf { it.isAttachedToWindow }

    fun feedback(message: String, kind: ActionFeedback.Kind = ActionFeedback.Kind.SUCCESS): Boolean {
        val container = get() ?: return false
        ActionFeedback.show(container, message, kind); return true
    }

    fun task(title: String, onCancel: (() -> Unit)? = null): BackgroundTaskCard? =
        get()?.let { BackgroundTaskCard.show(it, title, onCancel) }
}
