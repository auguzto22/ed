package com.termex.replay15.editor.ui

import android.app.*
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.*
import android.widget.*

/**
 * Recly design tokens — flat, dark, 1.5dp hairlines, no glass.
 * Ground #121214, surfaces #1C1C1F, lines #2C2C30, warm ink #EDEBE6.
 * Coral is the call to action, yellow marks selection/captions, blue is the video track.
 */
object EditorStyle {
    const val BG = 0xFF121214.toInt()
    const val PANEL = 0xFF1C1C1F.toInt()
    const val CARD = 0xFF1C1C1F.toInt()
    const val CARD_ELEVATED = 0xFF26262A.toInt()
    const val TIMELINE = 0xFF1A1A1D.toInt()
    const val BORDER = 0xFF2C2C30.toInt()
    const val BORDER_LIGHT = 0xFF4A4A50.toInt()
    const val INK = 0xFFEDEBE6.toInt()
    const val MUTED = 0xFF9A968C.toInt()
    const val DIM = 0xFF6E6B64.toInt()
    const val ON_ACCENT = 0xFF141414.toInt()
    /** Primary call to action (Exportar, Novo projeto, playhead). */
    const val PRIMARY = 0xFFFF5A36.toInt()
    const val PRIMARY_SOFT = 0xFFFF8C70.toInt()
    /** Selection and caption highlight. */
    const val ACCENT = 0xFFFFD23F.toInt()
    const val BLUE = 0xFF7F92B8.toInt()
    const val BLUE_DEEP = 0xFF4F6290.toInt()
    const val BLUE_LIGHT = 0xFFC9D3E8.toInt()
    const val WHITE = 0xFFEDEBE6.toInt()
    const val DANGER = 0xFFFF5A36.toInt()
    const val GREEN = 0xFF7FB89A.toInt()
    const val RADIUS_PANEL = 20
    const val RADIUS_CARD = 14
    const val RADIUS_CONTROL = 10
    const val STROKE = 1.5f
    const val TOUCH_TARGET = 44
    const val MOTION_PRESS = 80L
    const val MOTION_SELECTION = 120L
    const val MOTION_TOOLBAR = 160L
    const val MOTION_PANEL = 200L
}

/** Recly type: a heavy geometric display face, a friendly body face and a mono for timecode. */
object ReclyType {
    private val cache = HashMap<String, android.graphics.Typeface>()
    private fun variable(context: Context, file: String, weight: Int): android.graphics.Typeface = synchronized(cache) {
        cache.getOrPut("$file@$weight") {
            runCatching {
                android.graphics.Typeface.Builder(context.assets, "fonts/$file")
                    .setFontVariationSettings("'wght' $weight")
                    .build()
            }.getOrNull() ?: android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, weight, false)
        }
    }
    fun display(context: Context): android.graphics.Typeface = variable(context, "montserrat.ttf", 800)
    fun body(context: Context, weight: Int = 400): android.graphics.Typeface = variable(context, "outfit.ttf", weight)
    fun mono(context: Context): android.graphics.Typeface = synchronized(cache) {
        cache.getOrPut("mono") {
            runCatching { android.graphics.Typeface.createFromAsset(context.assets, "fonts/spacemono.ttf") }
                .getOrNull() ?: android.graphics.Typeface.MONOSPACE
        }
    }
}

/** Flat Recly surface: solid fill, optional 1.5dp hairline, rounded corners and a soft ripple. */
fun Context.reclySurface(
    fill: Int,
    stroke: Int? = EditorStyle.BORDER,
    radiusDp: Float = EditorStyle.RADIUS_CARD.toFloat(),
    ripple: Int = 0x1FFFFFFF,
): android.graphics.drawable.Drawable {
    val shape = GradientDrawable().apply {
        setColor(fill)
        if (stroke != null) setStroke(dp(EditorStyle.STROKE), stroke)
        cornerRadius = dp(radiusDp).toFloat()
    }
    return RippleDrawable(android.content.res.ColorStateList.valueOf(ripple), shape, null)
}

fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
fun Context.dp(value: Float) = (value * resources.displayMetrics.density).toInt()
fun Context.label(value: String, size: Float = 14f, color: Int = EditorStyle.INK) = TextView(this).apply {
    text = value; textSize = size; setTextColor(color); setPadding(dp(8), dp(5), dp(8), dp(5))
    typeface = ReclyType.body(this@label, 500)
}
/** Display heading in the Recly voice (heavy, tight tracking). */
fun Context.heading(value: String, size: Float = 20f, color: Int = EditorStyle.INK) = TextView(this).apply {
    text = value; textSize = size; setTextColor(color)
    typeface = ReclyType.display(this@heading)
    letterSpacing = -0.02f
    includeFontPadding = false
}
/**
 * Primary/secondary button. `accent = true` is the coral call to action with dark ink;
 * otherwise a flat surface with a hairline.
 */
fun Context.action(value: String, accent: Boolean = false, onClick: () -> Unit) = Button(this).apply {
    text = value; contentDescription = value; textSize = 13f; isAllCaps = false
    typeface = ReclyType.body(this@action, 600)
    minWidth = dp(48); minimumWidth = dp(48); minHeight = dp(44); minimumHeight = dp(44)
    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
    stateListAnimator = null
    background = if (accent) reclySurface(EditorStyle.PRIMARY, null, EditorStyle.RADIUS_CONTROL.toFloat(), 0x33000000)
        else reclySurface(EditorStyle.CARD, EditorStyle.BORDER, EditorStyle.RADIUS_CONTROL.toFloat())
    setTextColor(if (accent) EditorStyle.ON_ACCENT else EditorStyle.INK)
    setOnClickListener { onClick() }
}
fun Context.column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
fun Activity.sheet(title: String, body: LinearLayout): Dialog {
    val dialog = Dialog(this)
    val root = column().apply {
        setPadding(dp(16), dp(10), dp(16), dp(20))
        background = GradientDrawable().apply {
            setColor(EditorStyle.BG)
            val r = dp(EditorStyle.RADIUS_PANEL).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setStroke(dp(EditorStyle.STROKE), EditorStyle.BORDER)
        }
        addView(View(context).apply { background = GradientDrawable().apply { setColor(EditorStyle.BORDER_LIGHT); cornerRadius = dp(2).toFloat() } },
            LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER; bottomMargin = dp(12) })
        val headingRow = row()
        headingRow.addView(heading(title, 20f).apply { setPadding(dp(4), 0, dp(8), 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        headingRow.addView(iconAction("Fechar") { dialog.dismiss() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(headingRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        addView(object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val limit = resources.displayMetrics.heightPixels * 7 / 10
                val available = View.MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: limit
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(minOf(limit, available), View.MeasureSpec.AT_MOST))
            }
        }.apply { addView(body) }, LinearLayout.LayoutParams(-1, -2))
    }
    dialog.setContentView(root)
    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        setGravity(Gravity.BOTTOM)
        setLayout(-1, -2)
        setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    dialog.show()
    dialog.window?.setLayout(-1, -2)
    installEditorMotion(root)
    root.alpha = 0f
    root.translationY = dp(18).toFloat()
    root.animate().cancel()
    root.animate().alpha(1f).translationY(0f).setDuration(EditorStyle.MOTION_PANEL).start()
    return dialog
}
fun Context.slider(parent: LinearLayout, title: String, initial: Int, maximum: Int, minimum: Int = 0,
    format: (Int) -> String = { it.toString() }, onStop: ((Int) -> Unit)? = null, change: (Int) -> Unit): SeekBar {
    val headingRow = row()
    headingRow.addView(label(title, 13f, EditorStyle.MUTED), LinearLayout.LayoutParams(0, -2, 1f))
    val value = label(format(initial), 12f, EditorStyle.INK).apply {
        gravity = Gravity.END
        typeface = ReclyType.mono(this@slider)
    }
    headingRow.addView(value, LinearLayout.LayoutParams(dp(72), -2)); parent.addView(headingRow)
    val slider = SeekBar(this).apply {
        min = minimum; max = maximum; progress = initial; minimumHeight = dp(48)
        progressTintList = android.content.res.ColorStateList.valueOf(EditorStyle.PRIMARY)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(EditorStyle.BORDER)
        thumbTintList = android.content.res.ColorStateList.valueOf(EditorStyle.INK)
    }
    slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            value.text = format(progress); if (fromUser) change(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar) {
            seekBar.parent?.requestDisallowInterceptTouchEvent(true)
        }
        override fun onStopTrackingTouch(seekBar: SeekBar) {
            onStop?.invoke(seekBar.progress)
            seekBar.parent?.requestDisallowInterceptTouchEvent(false)
        }
    })
    parent.addView(slider)
    return slider
}

/** Editor-only motion: short, interruptible, and deliberately silent (haptics belong to edit events). */
@android.annotation.SuppressLint("ClickableViewAccessibility")
fun installEditorMotion(view: View) {
    if (view is ViewGroup) for (index in 0 until view.childCount) installEditorMotion(view.getChildAt(index))
    if (!view.isClickable) return
    view.setOnTouchListener { target, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                target.animate().cancel()
                target.animate().scaleX(.96f).scaleY(.96f).setDuration(EditorStyle.MOTION_PRESS).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                target.animate().cancel()
                target.animate().scaleX(1f).scaleY(1f).setDuration(EditorStyle.MOTION_SELECTION).start()
            }
        }
        false
    }
}

fun Activity.choiceSheet(title: String, options: List<String>, selected: Int = -1, choose: (Int) -> Unit) {
    val body = column()
    val grid = GridLayout(this).apply { columnCount = if (options.any { it.length > 45 }) 1 else 2 }
    lateinit var dialog: Dialog
    options.forEachIndexed { index, option ->
        grid.addView(pillButton(option, index == selected) { dialog.dismiss(); choose(index) }, GridLayout.LayoutParams().apply {
            width = 0; height = dp(56); columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(dp(4), dp(4), dp(4), dp(4))
        })
    }
    body.addView(grid); dialog = sheet(title, body)
}

fun Context.sectionTitle(title: String, description: String = ""): LinearLayout = column().apply {
    setPadding(0, dp(14), 0, dp(8))
    addView(heading(title, 16f).apply { setPadding(dp(8), 0, dp(8), dp(2)) })
    if (description.isNotBlank()) addView(label(description, 12f, EditorStyle.MUTED))
}

fun Context.editorToolCard(name: String, subtitle: String, accent: Boolean = false, danger: Boolean = false, click: () -> Unit) =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(10))
        isClickable = true; isFocusable = true; contentDescription = "$name. $subtitle"
        background = when {
            accent -> reclySurface(EditorStyle.BLUE_DEEP, EditorStyle.BLUE)
            danger -> reclySurface(EditorStyle.CARD, EditorStyle.DANGER)
            else -> reclySurface(EditorStyle.CARD, EditorStyle.BORDER)
        }
        val badge = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(EditorStyle.BG)
                cornerRadius = dp(17).toFloat()
            }
            addView(ImageView(context).apply {
                setImageDrawable(EditorGlyph(name, when { danger -> EditorStyle.DANGER; accent -> EditorStyle.ACCENT; else -> EditorStyle.INK }, context))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        }
        addView(badge, LinearLayout.LayoutParams(dp(34), dp(34)))
        addView(label(name, 14f, when { danger -> EditorStyle.PRIMARY_SOFT; else -> EditorStyle.INK }).apply {
            setPadding(0, dp(8), 0, 0); typeface = ReclyType.body(context, 700)
        })
        addView(label(subtitle, 11f, if (accent) EditorStyle.BLUE_LIGHT else EditorStyle.MUTED).apply { setPadding(0, dp(1), 0, 0); maxLines = 1 })
        setOnClickListener { click() }
    }

fun Context.editorIconButton(
    name: String,
    iconRes: Int,
    accent: Boolean = false,
    onClick: () -> Unit,
): FrameLayout = FrameLayout(this).apply {
    contentDescription = name
    tooltipText = name
    isClickable = true
    isFocusable = true
    background = if (accent) reclySurface(EditorStyle.INK, null, 25f, 0x33000000)
        else reclySurface(EditorStyle.CARD, EditorStyle.BORDER, 12f)
    val icon = ImageView(context).apply {
        setImageResource(iconRes)
        imageTintList = android.content.res.ColorStateList.valueOf(if (accent) EditorStyle.ON_ACCENT else EditorStyle.INK)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    addView(icon, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
    setOnClickListener { onClick() }
}

@Suppress("DEPRECATION")
fun editorInsets(root: View, horizontalPadding: Int = 0, verticalPadding: Int = 0) {
    root.setOnApplyWindowInsetsListener { view, insets ->
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsets.Type.ime())
            view.setPadding(bars.left + horizontalPadding, bars.top + verticalPadding,
                bars.right + horizontalPadding, maxOf(bars.bottom, keyboard.bottom) + verticalPadding)
        } else {
            view.setPadding(insets.systemWindowInsetLeft + horizontalPadding, insets.systemWindowInsetTop + verticalPadding,
                insets.systemWindowInsetRight + horizontalPadding, insets.systemWindowInsetBottom + verticalPadding)
        }
        insets
    }
}
fun timeLabel(us: Long): String {
    val ms = us.coerceAtLeast(0) / 1000
    return java.lang.String.format(java.util.Locale.ROOT, "%02d:%02d.%03d", ms / 60_000, ms / 1000 % 60, ms % 1000)
}

fun timeLabelCapCut(positionUs: Long, durationUs: Long): String {
    val posSec = (positionUs.coerceAtLeast(0) / 1_000_000).toInt()
    val durSec = (durationUs.coerceAtLeast(0) / 1_000_000).toInt()
    return String.format(java.util.Locale.ROOT, "%02d:%02d / %02d:%02d", posSec / 60, posSec % 60, durSec / 60, durSec % 60)
}

fun Context.pillButton(value: String, active: Boolean = false, onClick: () -> Unit) = Button(this).apply {
    text = value
    contentDescription = value
    textSize = 13f
    isAllCaps = false
    stateListAnimator = null
    typeface = ReclyType.body(this@pillButton, 600)
    minWidth = dp(48)
    minHeight = dp(40)
    setPadding(dp(14), dp(6), dp(14), dp(6))
    background = if (active) reclySurface(EditorStyle.BLUE_DEEP, EditorStyle.BLUE, EditorStyle.RADIUS_CONTROL.toFloat())
        else reclySurface(EditorStyle.CARD, EditorStyle.BORDER, EditorStyle.RADIUS_CONTROL.toFloat())
    setTextColor(if (active) 0xFFFFFFFF.toInt() else EditorStyle.INK)
    setOnClickListener { onClick() }
}

fun Context.exportPill(onClick: () -> Unit) = Button(this).apply {
    text = "Exportar"
    contentDescription = "Exportar vídeo"
    textSize = 13f
    isAllCaps = false
    stateListAnimator = null
    typeface = ReclyType.body(this@exportPill, 700)
    minWidth = dp(76)
    minHeight = dp(40)
    setPadding(dp(14), dp(6), dp(14), dp(6))
    background = reclySurface(EditorStyle.PRIMARY, null, EditorStyle.RADIUS_CONTROL.toFloat(), 0x33000000)
    setTextColor(EditorStyle.ON_ACCENT)
    setOnClickListener { onClick() }
}
