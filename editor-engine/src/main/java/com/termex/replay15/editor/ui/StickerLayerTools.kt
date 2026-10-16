package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieDrawable
import com.termex.replay15.editor.domain.StickerClip
import com.termex.replay15.editor.lottie.LottieLayerRenderer
import java.util.concurrent.Executors

/** Miniaturas de sticker (imagem ou Lottie) para os painéis, feitas em segundo plano. */
object StickerThumbnails {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "sticker-layer-thumbs").apply { isDaemon = true } }
    private val cache = android.util.LruCache<String, Bitmap>(6 * 1024 * 1024)
    private const val SIZE = 144

    fun load(activity: Activity, uri: String, done: (Bitmap) -> Unit) {
        cache.get(uri)?.let { done(it); return }
        worker.execute {
            val bitmap = runCatching { render(activity, uri) }.getOrNull() ?: return@execute
            cache.put(uri, bitmap)
            activity.runOnUiThread { done(bitmap) }
        }
    }

    private fun render(activity: Activity, uri: String): Bitmap? {
        if (LottieLayerRenderer.isLottieUri(uri)) {
            val composition = LottieLayerRenderer.loadComposition(activity, uri) ?: return null
            val drawable = LottieDrawable().apply { this.composition = composition; progress = .42f }
            val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, SIZE, SIZE)
            drawable.draw(Canvas(bitmap))
            return bitmap
        }
        val isAsset = uri.startsWith("asset:///") || uri.startsWith("file:///android_asset/") ||
            (uri.startsWith("editor/") && !uri.startsWith("/"))
        val source = if (isAsset) {
            ImageDecoder.createSource(activity.assets, uri.removePrefix("file:///android_asset/").removePrefix("asset:///").removePrefix("/"))
        } else ImageDecoder.createSource(activity.contentResolver, Uri.parse(uri))
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height).coerceAtLeast(1)
            val scale = SIZE / longest.toFloat()
            if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        }
    }
}

/**
 * "Organizar camadas" do CapCut: os stickers numa fileira da frente (esquerda) para trás
 * (direita); toque e arraste para mudar a ordem. Botões levam o selecionado direto para a frente
 * ou para trás.
 */
object StickerLayerTools {
    fun show(
        activity: Activity,
        /** Da frente para trás. */
        stickersFrontToBack: List<StickerClip>,
        selectedId: String?,
        onSelect: (String) -> Unit,
        /** Nova ordem, ids da frente para trás. */
        apply: (List<String>) -> Unit,
    ) = with(activity) {
        val body = column()
        lateinit var dialog: Dialog
        val order = stickersFrontToBack.toMutableList()
        var selected = selectedId ?: order.firstOrNull()?.id

        if (order.size < 2) {
            body.addView(label("Adicione mais de um sticker ou imagem para organizar as camadas.", 13f, EditorStyle.MUTED).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(18))
            })
            dialog = sheet("Organizar camadas", body, dim = 0f)
            return@with
        }

        val strip = row()
        strip.addView(label("Frente", 12f, EditorStyle.MUTED).apply { setPadding(0, 0, dp(8), 0) })
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@with, LinearLayoutManager.HORIZONTAL, false)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        strip.addView(list, LinearLayout.LayoutParams(0, dp(92), 1f))
        strip.addView(label("Trás", 12f, EditorStyle.MUTED).apply { setPadding(dp(8), 0, 0, 0) })
        body.addView(strip)
        body.addView(label("Toque e arraste para organizar as camadas", 12f, EditorStyle.MUTED).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(6))
        })

        class Holder(val frame: FrameLayout, val image: ImageView) : RecyclerView.ViewHolder(frame) { var key = "" }
        val adapter = object : RecyclerView.Adapter<Holder>() {
            override fun getItemCount() = order.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
                val frame = FrameLayout(this@with).apply {
                    layoutParams = RecyclerView.LayoutParams(dp(76), dp(84)).apply { marginEnd = dp(8) }
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                }
                val image = ImageView(this@with).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                frame.addView(image, FrameLayout.LayoutParams(-1, -1))
                return Holder(frame, image)
            }
            override fun onBindViewHolder(holder: Holder, position: Int) {
                val sticker = order[position]
                holder.key = sticker.id
                val on = sticker.id == selected
                holder.frame.background = GradientDrawable().apply {
                    // Laranja das camadas no CapCut; o selecionado ganha borda.
                    setColor(0xFFF2A93B.toInt()); cornerRadius = dp(10).toFloat()
                    if (on) setStroke(dp(2.5f), EditorStyle.ACCENT)
                }
                holder.frame.contentDescription = "Camada ${position + 1}: ${sticker.name}"
                holder.image.setImageBitmap(null)
                StickerThumbnails.load(this@with, sticker.uri) { bitmap -> if (holder.key == sticker.id) holder.image.setImageBitmap(bitmap) }
                holder.frame.setOnClickListener {
                    selected = sticker.id; onSelect(sticker.id); notifyDataSetChanged()
                }
            }
        }
        list.adapter = adapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0) {
            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                val a = from.bindingAdapterPosition; val b = to.bindingAdapterPosition
                if (a < 0 || b < 0) return false
                order.add(b, order.removeAt(a))
                adapter.notifyItemMoved(a, b)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                apply(order.map { it.id })
            }
        }).attachToRecyclerView(list)

        fun move(toFront: Boolean) {
            val id = selected ?: return
            val index = order.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
            val item = order.removeAt(index)
            if (toFront) order.add(0, item) else order.add(item)
            adapter.notifyDataSetChanged()
            apply(order.map { it.id })
        }
        fun step(towardFront: Boolean) {
            val id = selected ?: return
            val index = order.indexOfFirst { it.id == id }
            val target = if (towardFront) index - 1 else index + 1
            if (index < 0 || target !in order.indices) return
            order.add(target, order.removeAt(index))
            adapter.notifyItemMoved(index, target)
            apply(order.map { it.id })
        }
        val buttons = row().apply { setPadding(0, dp(6), 0, 0) }
        fun button(text: String, click: () -> Unit) = buttons.addView(action(text, onClick = click),
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) })
        button("⇤ Frente") { move(true) }
        button("‹ Avançar") { step(true) }
        button("Recuar ›") { step(false) }
        button("Trás ⇥") { move(false) }
        body.addView(buttons)

        dialog = sheet("Organizar camadas", body, dim = 0f)
    }
}
