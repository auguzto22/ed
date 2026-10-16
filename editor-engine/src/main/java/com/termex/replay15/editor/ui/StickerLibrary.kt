package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieDrawable
import com.termex.replay15.editor.lottie.LottieLayerRenderer
import com.termex.replay15.editor.stickers.StickerCatalog
import com.termex.replay15.editor.stickers.StickerCategory
import com.termex.replay15.editor.stickers.StickerDef
import java.util.concurrent.Executors

/** Biblioteca de stickers animados: categorias, busca, favoritos (toque longo) e miniaturas vetoriais. */
object StickerLibrary {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "sticker-thumbs").apply { isDaemon = true } }
    private val thumbs = android.util.LruCache<String, Bitmap>(8 * 1024 * 1024)
    private const val THUMB_PX = 160

    private fun render(activity: Activity, def: StickerDef): Bitmap? = runCatching {
        val composition = LottieLayerRenderer.loadComposition(activity, def.uri) ?: return null
        val drawable = LottieDrawable().apply { this.composition = composition; progress = .42f }
        val bitmap = Bitmap.createBitmap(THUMB_PX, THUMB_PX, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, THUMB_PX, THUMB_PX)
        drawable.draw(Canvas(bitmap))
        bitmap
    }.getOrNull()

    fun show(activity: Activity, onPick: (StickerDef) -> Unit) = with(activity) {
        val body = column()
        lateinit var dialog: Dialog
        val categories: List<StickerCategory?> = listOf(null) + StickerCategory.entries.filter { StickerCatalog.byCategory(it).isNotEmpty() }
        var category: StickerCategory? = null
        var showFavorites = false
        var shown: List<StickerDef> = emptyList()

        val search = EditText(this).apply {
            hint = "Buscar sticker (ex.: seta, fogo, festa)"; isSingleLine = true
            setTextColor(EditorStyle.TEXT); setHintTextColor(EditorStyle.MUTED)
        }
        body.addView(search)

        val chips = row()
        val chipScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips); setPadding(0, dp(4), 0, dp(8)) }
        body.addView(chipScroll)

        val grid = RecyclerView(this).apply { layoutManager = GridLayoutManager(this@with, 4); overScrollMode = RecyclerView.OVER_SCROLL_NEVER }
        val countLabel = label("", 11f, EditorStyle.MUTED)

        class Holder(val frame: FrameLayout, val image: ImageView, val name: TextView) : RecyclerView.ViewHolder(frame) { var key = "" }
        val adapter = object : RecyclerView.Adapter<Holder>() {
            override fun getItemCount() = shown.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
                val cell = FrameLayout(this@with).apply {
                    layoutParams = RecyclerView.LayoutParams(-1, dp(98))
                    setPadding(dp(3), dp(3), dp(3), dp(3))
                    isClickable = true; isFocusable = true
                }
                val card = android.widget.LinearLayout(this@with).apply {
                    orientation = android.widget.LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(4), dp(6), dp(4), dp(4))
                    background = GradientDrawable().apply { setColor(EditorStyle.CARD); cornerRadius = dp(12).toFloat(); setStroke(dp(1), EditorStyle.BORDER) }
                }
                val image = ImageView(this@with).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    // Figurinha por cima do vídeo de referência tocando: mostra como fica no vídeo.
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat())
                        }
                    }
                    clipToOutline = true
                    com.termex.replay15.editor.preview.ReferencePreviewFrames.withLoop(this@with) { frames ->
                        if (frames != null) background = com.termex.replay15.editor.preview.ReferenceLoopDrawable(frames).also { it.start() }
                    }
                }
                val name = label("", 10f, EditorStyle.MUTED).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
                card.addView(image, android.widget.LinearLayout.LayoutParams(dp(52), dp(52)))
                card.addView(name, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                cell.addView(card, FrameLayout.LayoutParams(-1, -1))
                return Holder(cell, image, name)
            }
            override fun onBindViewHolder(holder: Holder, position: Int) {
                val def = shown[position]
                holder.key = def.id
                holder.name.text = (if (StickerCatalog.isFavorite(def.id)) "★ " else "") + def.name
                holder.frame.contentDescription = def.name
                holder.image.setImageBitmap(thumbs.get(def.id))
                if (thumbs.get(def.id) == null) worker.execute {
                    val bitmap = render(this@with, def) ?: return@execute
                    thumbs.put(def.id, bitmap)
                    holder.image.post { if (holder.key == def.id) holder.image.setImageBitmap(bitmap) }
                }
                holder.frame.setOnClickListener { dialog.dismiss(); StickerCatalog.recordRecent(def.id); onPick(def) }
                holder.frame.setOnLongClickListener {
                    StickerCatalog.setFavorite(def.id, !StickerCatalog.isFavorite(def.id)); notifyItemChanged(holder.bindingAdapterPosition); true
                }
            }
        }
        grid.adapter = adapter

        fun refresh() {
            val query = search.text.toString()
            val base = when {
                showFavorites -> StickerCatalog.favorites()
                query.isNotBlank() -> StickerCatalog.search(query)
                else -> StickerCatalog.all()
            }
            shown = if (category != null && !showFavorites) base.filter { it.category == category } else base
            adapter.notifyDataSetChanged()
            grid.scrollToPosition(0)
            countLabel.text = "${shown.size} stickers  •  toque longo para favoritar"
        }

        val chipViews = mutableListOf<Pair<TextView, () -> Boolean>>()
        fun styleChips() = chipViews.forEach { (view, active) ->
            val on = active()
            view.setTextColor(if (on) EditorStyle.ON_ACCENT else EditorStyle.TEXT)
            view.background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                if (on) setColor(EditorStyle.ACCENT) else { setColor(EditorStyle.CARD); setStroke(dp(1), EditorStyle.BORDER) }
            }
        }
        fun chip(text: String, active: () -> Boolean, click: () -> Unit) {
            val view = label(text, 12f).apply {
                setPadding(dp(12), dp(7), dp(12), dp(7)); isClickable = true
                setOnClickListener { click(); styleChips(); refresh() }
            }
            chipViews += view to active
            chips.addView(view, android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }
        chip("Todos", { category == null && !showFavorites }) { category = null; showFavorites = false }
        chip("★ Favoritos", { showFavorites }) { showFavorites = true }
        categories.filterNotNull().forEach { c -> chip(c.label, { category == c && !showFavorites }) { category = c; showFavorites = false } }
        styleChips()

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { showFavorites = false; styleChips(); refresh() }
            override fun afterTextChanged(s: Editable?) {}
        })
        body.addView(grid, android.widget.LinearLayout.LayoutParams(-1, dp(360)))
        body.addView(countLabel, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        refresh()
        dialog = sheet("Stickers animados (${StickerCatalog.all().size})", body, maxHeightRatio = .85f)
    }
}
