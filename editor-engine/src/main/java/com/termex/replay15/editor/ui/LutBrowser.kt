package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.termex.replay15.editor.domain.VideoClip
import com.termex.replay15.editor.media.CubeLut
import com.termex.replay15.editor.preview.LutThumbnailCache
import com.termex.replay15.editor.preview.LutThumbnailCacheKey
import com.termex.replay15.editor.preview.RepresentativeFrameProvider
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Browses imported cube files and renders only thumbnails requested by visible grid cells. */
object LutBrowser {
    private const val PREFS = "recly_lut_browser"
    private const val FAVORITES = "favorites"

    fun show(
        activity: Activity,
        directory: File,
        clip: VideoClip,
        timestampUs: Long,
        currentPath: String,
        currentStrength: Float,
        preview: (String, Float) -> Unit,
        restore: () -> Unit,
        choose: (String, Float) -> Unit,
        onImport: () -> Unit,
    ) = with(activity) {
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "recly-lut-thumbs").apply { isDaemon = true } }
        val main = Handler(Looper.getMainLooper())
        val pending = ConcurrentHashMap.newKeySet<LutThumbnailCacheKey>()
        var baseAttempted = false
        var baseFrame: Bitmap? = null
        var strength = currentStrength.coerceIn(0f, 1f)
        var selectedPath: String? = currentPath.takeIf { path -> File(path).isFile }
        var favorites = getSharedPreferences(PREFS, 0).getStringSet(FAVORITES, emptySet()).orEmpty().toMutableSet()
        var favoritesOnly = false
        lateinit var updateTabs: () -> Unit
        lateinit var refreshGrid: () -> Unit

        val files = directory.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("cube", ignoreCase = true) && it.length() in 1L..12_000_000L }
            .sortedBy { displayName(it).lowercase(java.util.Locale.ROOT) }

        val body = column().apply { setPadding(dp(4), 0, dp(4), 0) }
        body.addView(sectionTitle("Navegador de LUTs", "Toque em um look para comparar no preview. Favoritos ficam salvos neste aparelho."))

        val detailRow = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(8)) }
        val detailImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { setColor(EditorStyle.CARD_ELEVATED); cornerRadius = dp(10).toFloat() }
            clipToOutline = true
        }
        detailRow.addView(detailImage, LinearLayout.LayoutParams(dp(112), dp(72)).apply { marginEnd = dp(10) })
        val selectedLabel = label("Selecione uma LUT", 14f, EditorStyle.MUTED).apply { maxLines = 2 }
        detailRow.addView(selectedLabel, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(detailRow)

        val filterTabs = row()
        val allButton = action("Todas", true) { favoritesOnly = false; updateTabs(); refreshGrid() }
        val favoritesButton = action("Favoritas") { favoritesOnly = true; updateTabs(); refreshGrid() }
        filterTabs.addView(allButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
        filterTabs.addView(favoritesButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        body.addView(filterTabs)

        val grid = GridView(this).apply {
            numColumns = 3
            verticalSpacing = dp(8)
            horizontalSpacing = dp(8)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }
        body.addView(grid, LinearLayout.LayoutParams(-1, dp(288)).apply { topMargin = dp(8); bottomMargin = dp(8) })
        val empty = label("Nenhuma LUT importada ainda.", 14f, EditorStyle.MUTED).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        body.addView(empty, LinearLayout.LayoutParams(-1, dp(110)))

        lateinit var dialog: Dialog

        fun getBaseFrame(): Bitmap? {
            if (!baseAttempted) {
                baseFrame = RepresentativeFrameProvider.extractBaseFrame(this, clip, timestampUs, maxDimension = 144)
                baseAttempted = true
            }
            return baseFrame
        }

        fun render(base: Bitmap, lut: CubeLut, amount: Float): Bitmap {
            val width = base.width; val height = base.height
            val pixels = IntArray(width * height)
            base.getPixels(pixels, 0, width, 0, 0, width, height)
            for (index in pixels.indices) {
                pixels[index] = lut.applyPixel(pixels[index], amount)
            }
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                it.setPixels(pixels, 0, width, 0, 0, width, height)
            }
        }

        fun requestThumbnail(file: File, amount: Float, target: ImageView) {
            val cacheKey = LutThumbnailCache.key(file, clip, timestampUs, amount)
            target.tag = cacheKey
            LutThumbnailCache.get(cacheKey)?.let { target.setImageBitmap(it); return }
            if (!pending.add(cacheKey)) return
            val targetRef = WeakReference(target)
            worker.execute {
                try {
                    if (!active.get()) return@execute
                    val view = targetRef.get() ?: return@execute
                    if (view.tag != cacheKey) return@execute
                    val base = getBaseFrame() ?: return@execute
                    val bitmap = render(base, CubeLut.parse(file.reader()), amount)
                    if (!active.get()) { bitmap.recycle(); return@execute }
                    LutThumbnailCache.put(cacheKey, bitmap)
                    main.post {
                        val current = targetRef.get()
                        if (active.get() && current?.tag == cacheKey) current.setImageBitmap(bitmap)
                    }
                } catch (_: Exception) {
                    main.post {
                        val current = targetRef.get()
                        if (active.get() && current?.tag == cacheKey) current.setImageDrawable(null)
                    }
                } finally {
                    pending.remove(cacheKey)
                }
            }
        }

        fun updateDetailPreview() {
            val file = files.firstOrNull { it.absolutePath == selectedPath }
            selectedLabel.text = file?.let { displayName(it) } ?: "Selecione uma LUT"
            detailImage.tag = null
            detailImage.setImageDrawable(null)
            if (file != null) requestThumbnail(file, strength, detailImage)
        }

        fun filteredFiles(): List<File> = if (favoritesOnly) files.filter { it.absolutePath in favorites } else files

        val adapter = object : BaseAdapter() {
            override fun getCount() = filteredFiles().size
            override fun getItem(position: Int) = filteredFiles()[position]
            override fun getItemId(position: Int) = getItem(position).absolutePath.hashCode().toLong()
            override fun hasStableIds() = true

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val file = getItem(position)
                val card = (convertView as? LinearLayout) ?: column().apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; tag = null }
                    addView(image, LinearLayout.LayoutParams(-1, dp(68)))
                    val name = label("", 11f, Color.WHITE).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
                    addView(name, LinearLayout.LayoutParams(-1, dp(28)))
                    val favorite = TextView(context).apply { gravity = Gravity.CENTER; textSize = 17f; minHeight = dp(26) }
                    addView(favorite, LinearLayout.LayoutParams(-1, dp(26)))
                    tag = LutCardViews(image, name, favorite)
                }
                val views = card.tag as LutCardViews
                views.name.text = displayName(file)
                views.favorite.text = if (file.absolutePath in favorites) "★" else "☆"
                views.favorite.setTextColor(if (file.absolutePath in favorites) 0xFFFFD166.toInt() else EditorStyle.MUTED)
                val chosen = file.absolutePath == selectedPath
                card.background = GradientDrawable().apply {
                    setColor(if (chosen) EditorStyle.CARD_ELEVATED else EditorStyle.CARD)
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(if (chosen) 2 else 1), if (chosen) EditorStyle.ACCENT else EditorStyle.BORDER)
                }
                card.setOnClickListener {
                    selectedPath = file.absolutePath
                    updateDetailPreview()
                    notifyDataSetChanged()
                    preview(file.absolutePath, strength)
                }
                views.favorite.setOnClickListener {
                    if (!favorites.add(file.absolutePath)) favorites.remove(file.absolutePath)
                    getSharedPreferences(PREFS, 0).edit().putStringSet(FAVORITES, favorites.toSet()).apply()
                    refreshGrid()
                }
                views.image.setImageDrawable(null)
                requestThumbnail(file, 1f, views.image)
                return card
            }
        }
        grid.adapter = adapter
        refreshGrid = {
            adapter.notifyDataSetChanged()
            val hasVisibleLuts = filteredFiles().isNotEmpty()
            grid.visibility = if (hasVisibleLuts) View.VISIBLE else View.GONE
            empty.visibility = if (hasVisibleLuts) View.GONE else View.VISIBLE
            empty.text = if (favoritesOnly && files.isNotEmpty()) "Nenhuma LUT favorita ainda." else "Nenhuma LUT importada ainda."
        }
        updateTabs = {
            allButton.background = tabBackground(!favoritesOnly)
            favoritesButton.background = tabBackground(favoritesOnly)
        }
        updateTabs()
        refreshGrid()

        slider(body, "Intensidade (%)", (strength * 100).toInt(), 100,
            onStop = { updateDetailPreview() }) { percent ->
            strength = percent / 100f
            selectedPath?.let { preview(it, strength) }
        }
        body.addView(action("Importar arquivo .cube") {
            completed.set(true)
            dialog.dismiss()
            onImport()
        })
        body.addView(action("Usar LUT selecionada", true) {
            val path = selectedPath
            if (path == null) {
                Toast.makeText(this, "Selecione uma LUT primeiro", Toast.LENGTH_SHORT).show()
            } else {
                completed.set(true)
                choose(path, strength)
                dialog.dismiss()
            }
        })

        dialog = sheet("LUTs", body)
        dialog.setOnDismissListener {
            active.set(false)
            worker.execute { baseFrame?.let { if (!it.isRecycled) it.recycle() }; baseFrame = null }
            worker.shutdown()
            if (!completed.get()) restore()
        }
        updateDetailPreview()
    }

    private data class LutCardViews(val image: ImageView, val name: TextView, val favorite: TextView)

    private fun Activity.tabBackground(selected: Boolean) = GradientDrawable().apply {
        if (selected) setColor(Color.WHITE) else { setColor(EditorStyle.CARD); setStroke(dp(1), EditorStyle.BORDER) }
        cornerRadius = dp(20).toFloat()
    }

    private fun displayName(file: File): String {
        val raw = file.nameWithoutExtension.substringAfter('_', file.nameWithoutExtension)
        return raw.ifBlank { file.nameWithoutExtension }.take(36)
    }
}
