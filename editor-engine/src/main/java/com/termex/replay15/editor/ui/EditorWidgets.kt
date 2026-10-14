package com.termex.replay15.editor.ui

import android.app.*
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.*
import android.widget.*
import com.recly.editor.engine.R
import com.termex.replay15.editor.domain.TextFont
import com.termex.replay15.editor.render.EditorFonts

object EditorStyle {
    // Paleta do canvas "Recly — Editor de vídeo mobile": fundo preto + 3 cores de função.
    const val BG = 0xFF000000.toInt()
    const val PANEL = 0xFF121213.toInt()
    const val CARD = 0xFF121213.toInt()
    const val CARD_ELEVATED = 0xFF1C1C1E.toInt()
    const val TIMELINE_BG = 0xFF0A0A0B.toInt()
    const val BORDER = 0xFF2C2C30.toInt()
    const val BORDER_LIGHT = 0x22FFFFFF.toInt()
    /** Very subtle 1 dp outline that separates cards from the panel without boxing them in. */
    const val HAIRLINE = 0x17FFFFFF
    const val TEXT = 0xFFFAFAF6.toInt()
    const val MUTED = 0xFF9A968C.toInt()
    const val ACCENT = 0xFFFFFFFF.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val INK = 0xFF141414.toInt()
    /** Texto e ícones sobre o vermelho de ação. */
    const val ON_ACTION = 0xFFFFFFFF.toInt()
    /** Vermelho mais claro, para texto pequeno sobre o preto. */
    const val ACTION_TEXT = 0xFFE5304F.toInt()

    /** Vermelho: ações principais (Exportar, aplicar, agulha da timeline, item selecionado). */
    const val ACTION = 0xFFC50022.toInt()
    /** Amarelo: tudo que é legenda. */
    const val CAPTION = 0xFFFAFAF6.toInt()
    /** Azul-acinzentado: trechos de vídeo e análise. */
    const val VIDEO = 0xFF8C8880.toInt()
    /** Azul mais fundo para botões com texto branco. */
    const val VIDEO_DEEP = 0xFFC50022.toInt()
    const val DANGER = 0xFFEF4444.toInt()
    const val GREEN = 0xFF10B981.toInt()
    const val RADIUS_PANEL = 20
    const val RADIUS_CONTROL = 12
    const val TOUCH_TARGET = 44
    const val MOTION_PRESS = 90L
    const val MOTION_SELECTION = 160L
    const val MOTION_TOOLBAR = 220L
    const val MOTION_PANEL = 280L

    // Nomes antigos ainda usados pelo editor: agora todos apontam para o vermelho de ação.
    const val ACCENT_DEEP = ACTION
    const val ACCENT_PINK = ACTION_TEXT
    const val ACCENT_SOFT = 0x33C50022
    const val CYAN = MUTED

    /** Preenchimento dos botões principais: vermelho chapado, como no canvas. */
    fun accentGradient(radius: Float): GradientDrawable = GradientDrawable().apply {
        setColor(ACTION)
        cornerRadius = radius
    }
}

fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
fun Context.dp(value: Float) = (value * resources.displayMetrics.density).toInt()
fun Context.label(value: String, size: Float = 14f, color: Int = Color.WHITE) = TextView(this).apply {
    text = value; textSize = size; setTextColor(color); setPadding(dp(8), dp(5), dp(8), dp(5))
    typeface = EditorFonts.get(this@label, TextFont.OUTFIT)
    setLineSpacing(0f, 1.12f)
}

/** Rounded rectangle used by every control so corners, fills and strokes stay consistent. */
fun Context.editorShape(fill: Int, radiusDp: Int = EditorStyle.RADIUS_CONTROL, stroke: Int = 0, strokeDp: Float = 1f) =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != 0) setStroke(dp(strokeDp).coerceAtLeast(1), stroke)
    }

fun Context.editorRipple(content: android.graphics.drawable.Drawable, light: Boolean = true) = RippleDrawable(
    android.content.res.ColorStateList.valueOf(if (light) 0x26FFFFFF else 0x33000000), content, null,
)

/**
 * Standard editor button. [accent] marks the primary action of a sheet and also the selected
 * option of a group (quality, provider...), always in the app's action red.
 */
fun Context.action(value: String, accent: Boolean = false, onClick: () -> Unit) = Button(this).apply {
    text = value; contentDescription = value; textSize = 13.5f; isAllCaps = false; letterSpacing = .01f
    typeface = EditorFonts.get(this@action, TextFont.OUTFIT, android.graphics.Typeface.BOLD)
    minWidth = dp(48); minimumWidth = dp(48); minHeight = dp(46); minimumHeight = dp(46)
    setPadding(dp(14), dp(8), dp(14), dp(8))
    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
    // Flat, like the rest of the editor: no Material press shadow jumping under the button.
    stateListAnimator = null; elevation = 0f
    background = editorRipple(
        if (accent) editorShape(EditorStyle.ACTION, 14)
        else editorShape(EditorStyle.CARD_ELEVATED, 14, EditorStyle.HAIRLINE),
        light = true,
    )
    setTextColor(if (accent) EditorStyle.ON_ACTION else EditorStyle.TEXT)
    setOnClickListener { onClick() }
}
fun Context.column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

/** Round "close" button used in the sheet header. */
fun Context.sheetCloseButton(onClick: () -> Unit) = FrameLayout(this).apply {
    contentDescription = "Fechar"; tooltipText = "Fechar"; isClickable = true; isFocusable = true
    background = editorRipple(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(EditorStyle.CARD_ELEVATED) })
    addView(ImageView(context).apply {
        setImageDrawable(EditorGlyph("Fechar", EditorStyle.TEXT, context))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
    setOnClickListener { onClick() }
}

/**
 * Bottom sheet used by every editor tool. [dim] is the scrim behind it; tools that preview their
 * result on the video (effects, transitions) pass a low value and a smaller [maxHeightRatio] so
 * the preview stays visible while choosing. The sheet is anchored to the bottom edge with only the
 * top corners rounded, and every raw platform control inside [body] gets the editor look
 * (see [polishControls]).
 */
fun Activity.sheet(title: String, body: LinearLayout, dim: Float = .42f, maxHeightRatio: Float = .7f, subtitle: String = ""): Dialog {
    val dialog = Dialog(this)
    val radius = dp(EditorStyle.RADIUS_PANEL + 4).toFloat()
    val root = column().apply {
        setPadding(dp(18), dp(8), dp(18), dp(18))
        background = GradientDrawable().apply {
            setColor(EditorStyle.PANEL)
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            setStroke(dp(1), EditorStyle.HAIRLINE)
        }
        addView(View(context).apply { background = editorShape(0xFF3A3A3F.toInt(), 2) },
            LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER; bottomMargin = dp(12) })
        val heading = row().apply { setPadding(0, 0, 0, dp(10)) }
        val titles = column()
        titles.addView(label(title, 20f).apply {
            typeface = EditorFonts.get(this@sheet, TextFont.OUTFIT, android.graphics.Typeface.BOLD)
            letterSpacing = -.01f; setPadding(dp(2), 0, dp(8), 0); maxLines = 2
        })
        if (subtitle.isNotBlank()) titles.addView(label(subtitle, 12.5f, EditorStyle.MUTED).apply { setPadding(dp(2), dp(2), dp(8), 0) })
        heading.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(sheetCloseButton { dialog.dismiss() }, LinearLayout.LayoutParams(dp(36), dp(36)))
        addView(heading)
        addView(View(context).apply { setBackgroundColor(EditorStyle.HAIRLINE) }, LinearLayout.LayoutParams(-1, dp(1)).apply { bottomMargin = dp(4) })
        addView(object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val limit = (resources.displayMetrics.heightPixels * maxHeightRatio).toInt()
                val available = View.MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: limit
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(minOf(limit, available), View.MeasureSpec.AT_MOST))
            }
        }.apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(body) }, LinearLayout.LayoutParams(-1, -2))
    }
    polishControls(body)
    spaceSheetBody(body)
    dialog.setContentView(root)
    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        setGravity(Gravity.BOTTOM)
        setLayout(-1, -2)
        setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (dim <= 0f) clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        else { addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(dim.coerceAtMost(.8f)) }
        setWindowAnimations(0)
    }
    dialog.show()
    dialog.window?.setLayout(-1, -2)
    installEditorMotion(root)
    // Rise from below the screen edge with a soft deceleration, children follow slightly later.
    root.alpha = 0f
    root.translationY = resources.displayMetrics.heightPixels * .25f
    root.animate().cancel()
    root.animate().alpha(1f).translationY(0f).setDuration(EditorStyle.MOTION_PANEL)
        .setInterpolator(android.view.animation.PathInterpolator(.2f, .9f, .25f, 1f)).start()
    staggerChildren(body, startDelay = 60L)
    return dialog
}

/** Gives full-width buttons stacked directly in a sheet some breathing room. */
private fun Context.spaceSheetBody(body: LinearLayout) {
    for (index in 0 until body.childCount) {
        val child = body.getChildAt(index)
        if (child !is Button) continue
        val params = child.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(-1, -2).also { child.layoutParams = it }
        if (params.topMargin == 0) params.topMargin = dp(6)
        if (params.bottomMargin == 0) params.bottomMargin = dp(2)
    }
}

/**
 * Applies the editor look to raw platform widgets created by the tools (CheckBox, Switch,
 * EditText, Spinner, ListView), so every menu matches without rewriting each tool.
 * Only appearance changes: listeners, adapters and values are left untouched.
 */
fun Context.polishControls(view: View) {
    if (view.getTag(R.id.recly_polished) == true) return
    when (view) {
        is Switch -> {
            styleCompound(view)
            view.thumbTintList = checkedTint(EditorStyle.WHITE, 0xFFBDBAB2.toInt())
            view.trackTintList = checkedTint(EditorStyle.ACTION, 0xFF3A3A3F.toInt())
        }
        is CompoundButton -> {
            styleCompound(view)
            view.buttonTintList = checkedTint(EditorStyle.ACTION, EditorStyle.MUTED)
        }
        is EditText -> styleField(view)
        is Spinner -> styleSpinner(view)
        is ListView -> {
            view.divider = android.graphics.drawable.ColorDrawable(EditorStyle.HAIRLINE)
            view.dividerHeight = dp(1)
            view.selector = editorRipple(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            view.background = editorShape(EditorStyle.CARD, 14, EditorStyle.HAIRLINE)
            view.setPadding(dp(4), dp(4), dp(4), dp(4)); view.clipToPadding = false
        }
    }
    view.setTag(R.id.recly_polished, true)
    if (view is ViewGroup && view !is AdapterView<*>) for (index in 0 until view.childCount) polishControls(view.getChildAt(index))
}

private fun checkedTint(on: Int, off: Int) = android.content.res.ColorStateList(
    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(on, off),
)

private fun Context.styleCompound(view: CompoundButton) {
    view.setTextColor(EditorStyle.TEXT)
    view.textSize = 14f
    view.typeface = EditorFonts.get(this, TextFont.OUTFIT)
    view.minHeight = dp(46)
    view.setPadding(dp(8), view.paddingTop, view.paddingRight, view.paddingBottom)
}

/** Rounded filled text field. */
fun Context.styleField(view: EditText) {
    view.background = editorShape(EditorStyle.CARD_ELEVATED, 14, EditorStyle.HAIRLINE)
    view.setTextColor(EditorStyle.TEXT)
    view.setHintTextColor(0xFF6E6B64.toInt())
    view.textSize = 14.5f
    view.typeface = EditorFonts.get(this, TextFont.OUTFIT)
    view.minHeight = dp(48)
    val start = if (view.compoundDrawablesRelative[0] != null) dp(12) else dp(14)
    view.setPaddingRelative(start, dp(10), dp(14), dp(10))
    val margins = view.layoutParams as? ViewGroup.MarginLayoutParams
    if (margins != null) {
        if (margins.topMargin == 0) margins.topMargin = dp(4)
        if (margins.bottomMargin == 0) margins.bottomMargin = dp(4)
    }
}

fun Context.styleSpinner(view: Spinner) {
    val chevron = getDrawable(R.drawable.ic_pro_chevron_down)?.mutate()?.apply { setTint(EditorStyle.MUTED) }
    val layers = listOfNotNull(editorShape(EditorStyle.CARD_ELEVATED, 14, EditorStyle.HAIRLINE), chevron)
    view.background = android.graphics.drawable.LayerDrawable(layers.toTypedArray()).apply {
        if (chevron != null) {
            setLayerGravity(1, Gravity.END or Gravity.CENTER_VERTICAL)
            setLayerSize(1, dp(18), dp(18))
            setLayerInsetEnd(1, dp(14))
        }
    }
    view.minimumHeight = dp(48)
    view.setPaddingRelative(dp(6), dp(4), dp(40), dp(4))
    view.setPopupBackgroundDrawable(editorShape(EditorStyle.CARD_ELEVATED, 14, EditorStyle.BORDER))
}

/** Pill used for categories and filters. */
fun Context.chip(value: String, selected: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
    text = value; contentDescription = value; textSize = 13f; gravity = Gravity.CENTER
    typeface = EditorFonts.get(this@chip, TextFont.OUTFIT, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    setPadding(dp(14), 0, dp(14), 0); minHeight = dp(36); isSingleLine = true
    isClickable = true; isFocusable = true
    background = editorRipple(
        if (selected) editorShape(EditorStyle.ACCENT_SOFT, 18, EditorStyle.ACTION, 1.5f)
        else editorShape(EditorStyle.CARD_ELEVATED, 18, EditorStyle.HAIRLINE),
    )
    setTextColor(if (selected) EditorStyle.TEXT else EditorStyle.MUTED)
    setOnClickListener { onClick() }
}

/** Horizontally scrolling row of chips; returns the scroller and the row to fill. */
fun Context.chipScroller(): Pair<HorizontalScrollView, LinearLayout> {
    val strip = row().apply { setPadding(0, dp(6), 0, dp(6)) }
    val scroller = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(strip)
    }
    return scroller to strip
}

fun LinearLayout.addChip(chip: View) {
    addView(chip, LinearLayout.LayoutParams(-2, context.dp(36)).apply { marginEnd = context.dp(8) })
}

/** Search box with a leading magnifier. */
fun Context.searchField(hint: String) = EditText(this).apply {
    this.hint = hint; isSingleLine = true
    imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
    getDrawable(R.drawable.ic_pro_search)?.mutate()?.let { icon ->
        icon.setTint(EditorStyle.MUTED); icon.setBounds(0, 0, dp(18), dp(18))
        setCompoundDrawablesRelative(icon, null, null, null)
        compoundDrawablePadding = dp(10)
    }
    styleField(this)
    setTag(R.id.recly_polished, true)
}

/** Tappable list row with title, optional subtitle and a trailing chevron. */
fun Context.listRow(title: String, subtitle: String = "", onClick: () -> Unit) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(14), dp(10), dp(10), dp(10)); minimumHeight = dp(54)
    isClickable = true; isFocusable = true
    contentDescription = if (subtitle.isBlank()) title else "$title. $subtitle"
    background = editorRipple(editorShape(EditorStyle.CARD_ELEVATED, 14, EditorStyle.HAIRLINE))
    val texts = column()
    texts.addView(label(title, 14f, EditorStyle.TEXT).apply {
        setPadding(0, 0, 0, 0); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        typeface = EditorFonts.get(this@listRow, TextFont.OUTFIT, android.graphics.Typeface.BOLD)
    })
    if (subtitle.isNotBlank()) texts.addView(label(subtitle, 11.5f, EditorStyle.MUTED).apply {
        setPadding(0, dp(1), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
    })
    addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
    addView(ImageView(context).apply {
        setImageResource(R.drawable.ic_pro_chevron_right); setColorFilter(EditorStyle.MUTED)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(8) })
    setOnClickListener { onClick() }
}

fun LinearLayout.addRow(row: View) {
    addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(6) })
}

/** Accent- and case-insensitive text used to match searches ("transicao" finds "Transição"). */
fun searchKey(value: String): String =
    java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase().trim()

/** Centered message for empty lists. */
fun Context.emptyState(message: String) = label(message, 13f, EditorStyle.MUTED).apply {
    gravity = Gravity.CENTER; setPadding(dp(16), dp(28), dp(16), dp(28))
}

/** Dark, rounded confirmation/message sheet that replaces stock AlertDialogs. */
fun Activity.messageSheet(title: String, message: String, confirm: String = "OK", cancel: String? = null,
    danger: Boolean = false, onConfirm: () -> Unit = {}): Dialog {
    val body = column()
    body.addView(label(message, 14f, EditorStyle.MUTED).apply { setPadding(dp(2), dp(8), dp(2), dp(14)) })
    lateinit var dialog: Dialog
    val buttons = row()
    if (cancel != null) buttons.addView(action(cancel) { dialog.dismiss() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
    buttons.addView(action(confirm, true) { dialog.dismiss(); onConfirm() }.apply {
        if (danger) background = editorRipple(editorShape(EditorStyle.DANGER, 14))
    }, LinearLayout.LayoutParams(0, -2, 1f))
    body.addView(buttons)
    dialog = sheet(title, body)
    return dialog
}

/** Fades and lifts the direct children of [group] in sequence (cheap, interruptible). */
fun staggerChildren(group: ViewGroup, startDelay: Long = 0L, stepMs: Long = 22L, maxItems: Int = 14) {
    val lift = 10f * group.resources.displayMetrics.density
    for (index in 0 until group.childCount) {
        val child = group.getChildAt(index)
        if (index >= maxItems) { child.alpha = 1f; child.translationY = 0f; continue }
        child.alpha = 0f
        child.translationY = lift
        child.animate().cancel()
        child.animate().alpha(1f).translationY(0f).setStartDelay(startDelay + index * stepMs)
            .setDuration(EditorStyle.MOTION_TOOLBAR)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
    }
}

/** Thin rounded track with a red fill, shared by every slider. */
private fun Context.sliderTrack(): android.graphics.drawable.LayerDrawable {
    val height = dp(4)
    val track = editorShape(0xFF2C2C30.toInt(), 2)
    val fill = android.graphics.drawable.ClipDrawable(editorShape(EditorStyle.ACTION, 2), Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
    return android.graphics.drawable.LayerDrawable(arrayOf(track, fill)).apply {
        setId(0, android.R.id.background); setId(1, android.R.id.progress)
        for (layer in 0..1) { setLayerHeight(layer, height); setLayerGravity(layer, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL) }
    }
}

private fun Context.sliderThumb() = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(EditorStyle.WHITE)
    setStroke(dp(3), EditorStyle.ACTION)
    setSize(dp(20), dp(20))
}

fun Context.slider(parent: LinearLayout, title: String, initial: Int, maximum: Int, minimum: Int = 0,
    format: (Int) -> String = { it.toString() }, onStop: ((Int) -> Unit)? = null, change: (Int) -> Unit): SeekBar {
    val heading = row().apply { setPadding(0, dp(10), 0, 0) }
    heading.addView(label(title, 13f, EditorStyle.MUTED), LinearLayout.LayoutParams(0, -2, 1f))
    val value = label(format(initial), 12.5f, EditorStyle.TEXT).apply {
        gravity = Gravity.CENTER; minWidth = dp(52)
        typeface = EditorFonts.get(this@slider, TextFont.OUTFIT, android.graphics.Typeface.BOLD)
        setPadding(dp(10), dp(3), dp(10), dp(3))
        background = editorShape(EditorStyle.CARD_ELEVATED, 10, EditorStyle.HAIRLINE)
    }
    heading.addView(value, LinearLayout.LayoutParams(-2, -2)); parent.addView(heading)
    val slider = SeekBar(this).apply {
        min = minimum; max = maximum; progress = initial; minimumHeight = dp(44)
        progressDrawable = sliderTrack()
        thumb = sliderThumb()
        splitTrack = false
        setPadding(dp(12), 0, dp(12), 0)
    }
    slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            value.text = format(progress); if (fromUser) change(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar) {
            seekBar.parent?.requestDisallowInterceptTouchEvent(true)
            value.animate().cancel(); value.animate().scaleX(1.08f).scaleY(1.08f).setDuration(EditorStyle.MOTION_PRESS).start()
        }
        override fun onStopTrackingTouch(seekBar: SeekBar) {
            onStop?.invoke(seekBar.progress)
            seekBar.parent?.requestDisallowInterceptTouchEvent(false)
            value.animate().cancel(); value.animate().scaleX(1f).scaleY(1f).setDuration(EditorStyle.MOTION_SELECTION).start()
        }
    })
    parent.addView(slider, LinearLayout.LayoutParams(-1, dp(44)))
    return slider
}

/** Editor-only motion: short, interruptible, and deliberately silent (haptics belong to edit events). */
@android.annotation.SuppressLint("ClickableViewAccessibility")
fun installEditorMotion(view: View) {
    if (view is ViewGroup) for (index in 0 until view.childCount) installEditorMotion(view.getChildAt(index))
    if (!view.isClickable || view is SeekBar || view is EditText || view is AdapterView<*>) return
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

/** Selectable option tile used by [choiceSheet]: selected tiles get a red outline and a check. */
fun Context.optionTile(value: String, selected: Boolean, onClick: () -> Unit) = TextView(this).apply {
    text = value; contentDescription = value; textSize = 13.5f
    typeface = EditorFonts.get(this@optionTile, TextFont.OUTFIT, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    gravity = Gravity.CENTER_VERTICAL; maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
    setPadding(dp(14), dp(10), dp(12), dp(10))
    setTextColor(EditorStyle.TEXT)
    isClickable = true; isFocusable = true
    background = editorRipple(
        if (selected) editorShape(EditorStyle.ACCENT_SOFT, 14, EditorStyle.ACTION, 1.5f)
        else editorShape(EditorStyle.CARD_ELEVATED, 14, EditorStyle.HAIRLINE),
    )
    if (selected) {
        getDrawable(R.drawable.ic_recly_check)?.mutate()?.let { icon ->
            icon.setTint(EditorStyle.ACTION_TEXT); icon.setBounds(0, 0, dp(16), dp(16))
            setCompoundDrawablesRelative(null, null, icon, null); compoundDrawablePadding = dp(8)
        }
    }
    setOnClickListener { onClick() }
}

fun Activity.choiceSheet(title: String, options: List<String>, selected: Int = -1, choose: (Int) -> Unit) {
    val body = column()
    val grid = GridLayout(this).apply { columnCount = if (options.any { it.length > 45 }) 1 else 2 }
    lateinit var dialog: Dialog
    options.forEachIndexed { index, option ->
        val tile = optionTile(option, index == selected) { dialog.dismiss(); choose(index) }.apply { minHeight = dp(56) }
        grid.addView(tile, GridLayout.LayoutParams().apply {
            width = 0; height = ViewGroup.LayoutParams.WRAP_CONTENT
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(dp(4), dp(4), dp(4), dp(4))
        })
    }
    body.addView(grid); dialog = sheet(title, body)
}

fun Context.sectionTitle(title: String, description: String = ""): LinearLayout = column().apply {
    setPadding(0, dp(14), 0, dp(6))
    addView(label(title, 15.5f).apply {
        typeface = EditorFonts.get(this@sectionTitle, TextFont.OUTFIT, android.graphics.Typeface.BOLD)
        setPadding(dp(2), 0, dp(2), dp(2))
    })
    if (description.isNotBlank()) addView(label(description, 12.5f, EditorStyle.MUTED).apply { setPadding(dp(2), 0, dp(2), dp(4)) })
}

fun Context.editorToolCard(name: String, subtitle: String, accent: Boolean = false, danger: Boolean = false, click: () -> Unit) =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(12), dp(11))
        isClickable = true; isFocusable = true; contentDescription = "$name. $subtitle"
        background = RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x26FFFFFF),
            GradientDrawable().apply {
                setColor(if (danger) 0x14EF4444 else EditorStyle.CARD_ELEVATED)
                setStroke(dp(1), when { danger -> 0x66EF4444; accent -> EditorStyle.BORDER_LIGHT; else -> EditorStyle.HAIRLINE })
                cornerRadius = dp(16).toFloat()
            },
            null,
        )
        addView(ImageView(context).apply {
            setImageDrawable(EditorGlyph(name, when { danger -> EditorStyle.DANGER; accent -> EditorStyle.WHITE; else -> 0xFFFAFAF6.toInt() }, context))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(25), dp(25)))
        addView(label(name, 13f, when { danger -> EditorStyle.DANGER; else -> Color.WHITE }).apply {
            setPadding(0, dp(5), 0, 0); setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        addView(label(subtitle, 11f, EditorStyle.MUTED).apply { setPadding(0, dp(1), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
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
            setColor(EditorStyle.ACTION)
            cornerRadius = dp(12).toFloat()
        } else {
            setColor(EditorStyle.CARD)
            setStroke(dp(1.5f), EditorStyle.BORDER)
            cornerRadius = dp(12).toFloat()
        }
    }
    background = RippleDrawable(
        android.content.res.ColorStateList.valueOf(if (accent) 0x33000000 else 0x26FFFFFF),
        bg,
        null,
    )
    val icon = ImageView(context).apply {
        setImageResource(iconRes)
        imageTintList = android.content.res.ColorStateList.valueOf(if (accent) EditorStyle.ON_ACTION else EditorStyle.TEXT)
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
            setColor(EditorStyle.ACCENT_SOFT)
            setStroke(dp(1.5f), EditorStyle.ACTION)
        } else {
            setColor(EditorStyle.CARD_ELEVATED)
            setStroke(dp(1), EditorStyle.HAIRLINE)
        }
        cornerRadius = dp(12).toFloat()
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
        setColor(EditorStyle.ACTION)
        cornerRadius = dp(10).toFloat()
    }
    background = RippleDrawable(android.content.res.ColorStateList.valueOf(0x44000000), bg, null)
    setTextColor(EditorStyle.ON_ACTION)
    setOnClickListener { onClick() }
}
