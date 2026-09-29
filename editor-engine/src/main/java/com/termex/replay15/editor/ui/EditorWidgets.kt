package com.termex.replay15.editor.ui

import android.app.*
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.*
import android.widget.*
import com.termex.replay15.editor.domain.TextFont
import com.termex.replay15.editor.render.EditorFonts

object EditorStyle {
    const val BG = 0xFF0C0F12.toInt()
    const val PANEL = 0xFF15191D.toInt()
    const val CARD = 0xFF15191D.toInt()
    const val CARD_ELEVATED = 0xFF1E2328.toInt()
    const val BORDER = 0xFF242A30.toInt()
    const val BORDER_LIGHT = 0x22FFFFFF.toInt()
    const val MUTED = 0xFFA7A7A7.toInt()
    const val ACCENT = 0xFFFFFFFF.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val DANGER = 0xFFEF4444.toInt()
    const val GREEN = 0xFF10B981.toInt()
    const val RADIUS_PANEL = 20
    const val RADIUS_CONTROL = 12
    const val TOUCH_TARGET = 44
    const val MOTION_PRESS = 80L
    const val MOTION_SELECTION = 120L
    const val MOTION_TOOLBAR = 160L
    const val MOTION_PANEL = 200L
}

fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
fun Context.dp(value: Float) = (value * resources.displayMetrics.density).toInt()
fun Context.label(value: String, size: Float = 14f, color: Int = Color.WHITE) = TextView(this).apply {
    text = value; textSize = size; setTextColor(color); setPadding(dp(8), dp(5), dp(8), dp(5))
    typeface = EditorFonts.get(this@label, TextFont.OUTFIT)
}
fun Context.action(value: String, accent: Boolean = false, onClick: () -> Unit) = Button(this).apply {
    text = value; contentDescription = value; textSize = 13f; isAllCaps = false
    typeface = EditorFonts.get(this@action, TextFont.OUTFIT, android.graphics.Typeface.BOLD)
    minWidth = dp(48); minimumWidth = dp(48); minHeight = dp(44); minimumHeight = dp(44)
    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
    val bg = GradientDrawable().apply {
        if (accent) {
            setColor(Color.WHITE)
            cornerRadius = dp(20).toFloat()
        } else {
            setColor(EditorStyle.CARD)
            setStroke(dp(1), EditorStyle.BORDER)
            cornerRadius = dp(12).toFloat()
        }
    }
    background = RippleDrawable(
        android.content.res.ColorStateList.valueOf(if (accent) 0x33000000 else 0x22FFFFFF),
        bg,
        null,
    )
    setTextColor(if (accent) Color.BLACK else Color.WHITE)
    setOnClickListener { onClick() }
}
fun Context.column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
fun Activity.sheet(title: String, body: LinearLayout): Dialog {
    val dialog = Dialog(this)
    val root = column().apply {
        setPadding(dp(16), dp(12), dp(16), dp(20))
        background = GradientDrawable().apply {
            setColor(EditorStyle.PANEL)
            cornerRadius = dp(EditorStyle.RADIUS_PANEL).toFloat()
            setStroke(dp(1), EditorStyle.BORDER)
        }
        addView(View(context).apply { background = GradientDrawable().apply { setColor(0xFF3F3F46.toInt()); cornerRadius = dp(2).toFloat() } },
            LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER; bottomMargin = dp(14) })
        val heading = row()
        heading.addView(label(title, 21f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(iconAction("Fechar") { dialog.dismiss() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(heading)
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
    val heading = row()
    heading.addView(label(title, 13f, EditorStyle.MUTED), LinearLayout.LayoutParams(0, -2, 1f))
    val value = label(format(initial), 13f, EditorStyle.ACCENT).apply { gravity = Gravity.END }
    heading.addView(value, LinearLayout.LayoutParams(dp(62), -2)); parent.addView(heading)
    val slider = SeekBar(this).apply {
        min = minimum; max = maximum; progress = initial; minimumHeight = dp(48)
        progressTintList = android.content.res.ColorStateList.valueOf(EditorStyle.WHITE)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(EditorStyle.BORDER)
        thumbTintList = android.content.res.ColorStateList.valueOf(EditorStyle.WHITE)
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
        grid.addView(action(option, index == selected) { dialog.dismiss(); choose(index) }, GridLayout.LayoutParams().apply {
            width = 0; height = dp(60); columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(dp(4), dp(4), dp(4), dp(4))
        })
    }
    body.addView(grid); dialog = sheet(title, body)
}

fun Context.sectionTitle(title: String, description: String = ""): LinearLayout = column().apply {
    setPadding(0, dp(12), 0, dp(8))
    addView(label(title, 16f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
    if (description.isNotBlank()) addView(label(description, 12f, EditorStyle.MUTED))
}

fun Context.editorToolCard(name: String, subtitle: String, accent: Boolean = false, danger: Boolean = false, click: () -> Unit) =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(11), dp(12), dp(10))
        isClickable = true; isFocusable = true; contentDescription = "$name. $subtitle"
        background = RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x26FFFFFF),
            GradientDrawable().apply {
                setColor(if (accent) EditorStyle.CARD_ELEVATED else EditorStyle.CARD)
                setStroke(dp(1), when { danger -> EditorStyle.DANGER; accent -> EditorStyle.BORDER_LIGHT; else -> EditorStyle.BORDER })
                cornerRadius = dp(14).toFloat()
            },
            null,
        )
        addView(ImageView(context).apply {
            setImageDrawable(EditorGlyph(name, when { danger -> EditorStyle.DANGER; accent -> EditorStyle.WHITE; else -> 0xFFE4E4E7.toInt() }, context))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(25), dp(25)))
        addView(label(name, 13f, when { danger -> EditorStyle.DANGER; else -> Color.WHITE }).apply {
            setPadding(0, dp(5), 0, 0); setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        addView(label(subtitle, 10f, EditorStyle.MUTED).apply { setPadding(0, dp(1), 0, 0); maxLines = 1 })
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
    val bg = GradientDrawable().apply {
        if (accent) {
            setColor(Color.WHITE)
            cornerRadius = dp(14).toFloat()
        } else {
            setColor(EditorStyle.CARD)
            setStroke(dp(1), EditorStyle.BORDER)
            cornerRadius = dp(14).toFloat()
        }
    }
    background = RippleDrawable(
        android.content.res.ColorStateList.valueOf(if (accent) 0x33000000 else 0x26FFFFFF),
        bg,
        null,
    )
    val icon = ImageView(context).apply {
        setImageResource(iconRes)
        imageTintList = android.content.res.ColorStateList.valueOf(if (accent) Color.BLACK else Color.WHITE)
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
    textSize = 12.5f
    isAllCaps = false
    typeface = EditorFonts.get(this@pillButton, TextFont.OUTFIT, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    minWidth = dp(48)
    minHeight = dp(38)
    setPadding(dp(12), dp(6), dp(12), dp(6))
    val bg = GradientDrawable().apply {
        if (active) {
            setColor(EditorStyle.PANEL)
            setStroke(dp(1.5f), Color.WHITE)
        } else {
            setColor(EditorStyle.CARD)
            setStroke(dp(1), EditorStyle.BORDER)
        }
        cornerRadius = dp(16).toFloat()
    }
    background = RippleDrawable(android.content.res.ColorStateList.valueOf(0x22FFFFFF), bg, null)
    setTextColor(if (active) Color.WHITE else EditorStyle.MUTED)
    setOnClickListener { onClick() }
}

fun Context.exportPill(onClick: () -> Unit) = Button(this).apply {
    text = "Exportar"
    contentDescription = "Exportar vídeo"
    textSize = 13f
    isAllCaps = false
    typeface = EditorFonts.get(this@exportPill, TextFont.OUTFIT, android.graphics.Typeface.BOLD)
    minWidth = dp(76)
    minHeight = dp(38)
    setPadding(dp(16), dp(6), dp(16), dp(6))
    val bg = GradientDrawable().apply {
        setColor(Color.WHITE)
        cornerRadius = dp(18).toFloat()
    }
    background = RippleDrawable(android.content.res.ColorStateList.valueOf(0x44000000), bg, null)
    setTextColor(Color.BLACK)
    setOnClickListener { onClick() }
}
