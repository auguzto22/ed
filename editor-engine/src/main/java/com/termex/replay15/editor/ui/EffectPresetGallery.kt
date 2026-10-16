package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.termex.replay15.editor.assets.BuiltInEffectPresets
import com.termex.replay15.editor.assets.EffectPreset
import com.termex.replay15.editor.core.isEditorDebuggable
import com.termex.replay15.editor.domain.VideoClip
import com.termex.replay15.editor.preview.AtlasPreviewManager
import com.termex.replay15.editor.preview.ReferenceEffectPreviews
import com.termex.replay15.editor.preview.ReferenceLoopDrawable

object EffectPresetGallery {

    fun show(
        activity: Activity,
        clip: VideoClip,
        sessionTimestampUs: Long = -1L,
        moreSubtitle: String = "",
        onMore: (() -> Unit)? = null,
        onPreview: (preset: EffectPreset, intensity: Float) -> Unit = { _, _ -> },
        onRestore: () -> Unit = {},
        onApply: (preset: EffectPreset, intensity: Float) -> Unit,
    ) = with(activity) {
        val openStartMs = System.currentTimeMillis()
        val body = column()
        // Sem texto explicativo: o título da folha já diz o que é. "Mais efeitos" leva à
        // biblioteca completa, aos filtros e aos efeitos já aplicados (para remover).
        if (onMore != null) body.addRow(listRow("Mais efeitos", moreSubtitle) {
            dialogRef?.dismiss(); onMore()
        })

        val allPresets = runCatching { BuiltInEffectPresets.definitions(this) }.getOrDefault(emptyList())
        // As abas saem do próprio arquivo: com ~1000 presets em várias categorias, lista fixa ficaria velha.
        val categories = listOf("Todos") + BuiltInEffectPresets.categories(allPresets)

        var selectedPreset: EffectPreset? = null
        var currentIntensity = 1.0f
        var currentCategoryIndex = 0
        var query = ""

        val targetTimestampUs = if (sessionTimestampUs >= 0L) {
            sessionTimestampUs
        } else {
            (clip.inUs + clip.outUs) / 2
        }

        // Cartão visível agora para cada preset; a lista recicla, então só os da tela ficam aqui.
        val presetCardMap = mutableMapOf<String, CardViews>()
        // Prévia tipo GIF: cada preset roda sobre o vídeo de referência em loop (renderizado na GPU,
        // quadro a quadro, com o tempo do efeito andando), igual para todo mundo, para comparar os
        // looks. O vídeo da pessoa só recebe o preset na prévia grande, ao tocar no cartão.
        val referencePreviews = runCatching {
            ReferenceEffectPreviews.createOrNull(activity) { presetId, frames ->
                presetCardMap[presetId]?.takeIf { it.boundId == presetId }?.let { views ->
                    val firstTime = views.image.drawable !is ReferenceLoopDrawable
                    views.image.setImageDrawable(ReferenceLoopDrawable(frames).also { it.start() })
                    views.placeholder.visibility = View.GONE
                    if (firstTime) {
                        views.image.alpha = 0f
                        views.image.animate().alpha(1f).setDuration(EditorStyle.MOTION_SELECTION).start()
                    }
                }
            }
        }.getOrNull()
        // Sem o asset de referência: modo antigo, miniatura parada num quadro do clipe.
        val ownFrames = if (referencePreviews != null) null else runCatching {
            com.termex.replay15.editor.preview.EffectPreviewController(activity, onPresetThumbnailReady = { preset, bitmap ->
                presetCardMap[preset.id]?.takeIf { it.boundId == preset.id }?.let { views ->
                    views.image.setImageBitmap(bitmap)
                    views.placeholder.visibility = View.GONE
                    views.image.alpha = 0f
                    views.image.animate().alpha(1f).setDuration(EditorStyle.MOTION_SELECTION).start()
                }
            }).also { it.startSession(clip, targetTimestampUs) }
        }.getOrNull()

        // Busca por nome: com 1000 presets, rolar não basta.
        val search = EditText(this).apply {
            hint = "Buscar entre ${allPresets.size} presets"
            textSize = 13f
            isSingleLine = true
            setTextColor(EditorStyle.TEXT)
            setHintTextColor(EditorStyle.MUTED)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(EditorStyle.CARD)
                cornerRadius = dp(12).toFloat()
            }
        }
        body.addView(search, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        // Categories horizontal strip
        val tabs = row()
        val categoryStrip = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabs)
            setPadding(0, 0, 0, dp(10))
        }
        body.addView(categoryStrip)

        val countLabel = TextView(this).apply {
            textSize = 11f
            setTextColor(EditorStyle.MUTED)
            setPadding(dp(4), 0, 0, dp(4))
        }
        body.addView(countLabel)

        fun cardBackground(chosen: Boolean) = GradientDrawable().apply {
            setColor(if (chosen) EditorStyle.CARD_ELEVATED else EditorStyle.CARD)
            cornerRadius = dp(12).toFloat()
            setStroke(dp(if (chosen) 2 else 1), if (chosen) EditorStyle.ACCENT else EditorStyle.BORDER)
        }

        fun styleCard(views: CardViews, chosen: Boolean) {
            views.container.background = cardBackground(chosen)
            views.label.setTextColor(if (chosen) EditorStyle.ACCENT else EditorStyle.MUTED)
            views.label.setTypeface(null, if (chosen) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }

        fun updateCardVisuals() {
            presetCardMap.forEach { (presetId, views) -> styleCard(views, selectedPreset?.id == presetId) }
        }

        val visiblePresets = mutableListOf<EffectPreset>()

        // RecyclerView horizontal: só os cartões da tela existem e só eles pedem miniatura.
        // Antes eram criados todos de uma vez — com 1000 presets, 1000 views e 1000 renders na GPU.
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = visiblePresets.size

            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val cardContainer = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    isClickable = true
                    isFocusable = true
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.WRAP_CONTENT, RecyclerView.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(dp(4), 0, dp(4), 0) }
                }
                val imageFrame = FrameLayout(activity).apply {
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat())
                        }
                    }
                    clipToOutline = true
                }
                val placeholder = View(activity).apply {
                    background = GradientDrawable().apply {
                        setColor(EditorStyle.CARD_ELEVATED)
                        cornerRadius = dp(8).toFloat()
                    }
                }
                imageFrame.addView(placeholder, FrameLayout.LayoutParams(dp(76), dp(76)))
                val imageView = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                imageFrame.addView(imageView, FrameLayout.LayoutParams(dp(76), dp(76)))
                cardContainer.addView(imageFrame)

                val labelView = TextView(activity).apply {
                    textSize = 11f
                    gravity = Gravity.CENTER_HORIZONTAL
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setWidth(dp(84))
                    setPadding(0, dp(6), 0, dp(2))
                }
                cardContainer.addView(labelView)
                val tagView = TextView(activity).apply {
                    textSize = 9f
                    setTextColor(EditorStyle.MUTED)
                    gravity = Gravity.CENTER_HORIZONTAL
                    isSingleLine = true
                }
                cardContainer.addView(tagView)
                val views = CardViews(cardContainer, imageView, placeholder, labelView, tagView)
                return object : RecyclerView.ViewHolder(cardContainer) {}.also { cardContainer.tag = views }
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val preset = visiblePresets[position]
                val views = holder.itemView.tag as CardViews
                views.boundId?.let { old -> if (presetCardMap[old] === views) presetCardMap.remove(old) }
                views.boundId = preset.id
                presetCardMap[preset.id] = views

                views.container.contentDescription = "${preset.name} - ${preset.category}"
                views.label.text = preset.name
                views.tag.text = preset.category
                views.image.setImageDrawable(null)
                views.image.animate().cancel()
                views.image.alpha = 1f
                views.placeholder.visibility = View.VISIBLE
                styleCard(views, selectedPreset?.id == preset.id)
                views.container.setOnClickListener {
                    selectedPreset = preset
                    updateCardVisuals()
                    onPreview(preset, currentIntensity)
                }

                // Atlas art first (instant), then the preset rendered on the user's frame.
                AtlasPreviewManager.getEffectPresetPreview(activity, preset.id) { drawable ->
                    if (views.boundId == preset.id && views.image.drawable == null) {
                        views.image.setImageDrawable(drawable)
                        views.placeholder.visibility = View.GONE
                    }
                }
                referencePreviews?.request(preset)
                ownFrames?.requestPresetThumbnail(preset)
            }

            override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
                val views = holder.itemView.tag as CardViews
                views.boundId?.let { id ->
                    referencePreviews?.cancel(id)
                    ownFrames?.cancelPresetThumbnail(id)
                    if (presetCardMap[id] === views) presetCardMap.remove(id)
                }
                views.boundId = null
            }
        }

        val thumbList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
            this.adapter = adapter
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, dp(4), 0, dp(8))
            clipToPadding = false
        }
        body.addView(thumbList, LinearLayout.LayoutParams(-1, -2))

        fun populateThumbnails() {
            val filterCategory = categories[currentCategoryIndex]
            val needle = query.trim()
            visiblePresets.clear()
            allPresets.filterTo(visiblePresets) {
                (currentCategoryIndex == 0 || it.category.equals(filterCategory, ignoreCase = true)) &&
                    (needle.isEmpty() || it.name.contains(needle, ignoreCase = true) || it.category.contains(needle, ignoreCase = true))
            }
            countLabel.text = if (visiblePresets.isEmpty()) "Nenhum preset encontrado" else "${visiblePresets.size} presets"
            @Suppress("NotifyDataSetChanged")
            adapter.notifyDataSetChanged()
            thumbList.scrollToPosition(0)
        }

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                query = s?.toString().orEmpty()
                populateThumbnails()
            }
        })

        fun updateTabs() {
            tabs.removeAllViews()
            categories.forEachIndexed { index, title ->
                val isSelected = index == currentCategoryIndex
                val pill = TextView(this).apply {
                    text = title
                    textSize = 12f
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    setTextColor(if (isSelected) EditorStyle.ON_ACCENT else EditorStyle.MUTED)
                    background = GradientDrawable().apply {
                        setColor(if (isSelected) EditorStyle.ACCENT else EditorStyle.CARD)
                        cornerRadius = dp(16).toFloat()
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        if (currentCategoryIndex != index) {
                            currentCategoryIndex = index
                            updateTabs()
                            populateThumbnails()
                        }
                    }
                }
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(dp(3), 0, dp(3), 0)
                }
                tabs.addView(pill, params)
            }
        }

        updateTabs()
        populateThumbnails()

        // Live Intensity slider
        slider(body, "Intensidade do preset (%)", 100, 150) { percent ->
            currentIntensity = percent / 100f
            selectedPreset?.let { onPreview(it, currentIntensity) }
        }

        // Action buttons
        var isBypassed = false
        val bypassButton = action("Comparar (Bypass)") {
            isBypassed = !isBypassed
            if (isBypassed) {
                onRestore()
            } else {
                selectedPreset?.let { onPreview(it, currentIntensity) }
            }
        }
        body.addView(bypassButton, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(4) })

        val bottomRow = row()
        bottomRow.addView(action("Aplicar", accent = true) {
            val preset = selectedPreset
            if (preset != null) {
                onApply(preset, currentIntensity)
            } else {
                onRestore()
            }
            dialogRef?.dismiss()
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) })

        bottomRow.addView(action("Cancelar") {
            onRestore()
            dialogRef?.dismiss()
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4) })

        body.addView(bottomRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        // Low scrim and half height: the chosen preset plays on the video above the sheet.
        dialogRef = sheet("Presets de Efeitos", body, dim = .1f, maxHeightRatio = .5f)
        if (activity.isEditorDebuggable()) {
            android.util.Log.d("ReclyPerf", "EFFECT_PRESET_GALLERY_OPENED timeMs=${System.currentTimeMillis() - openStartMs} shaders=0 mediaCodec=0 ${AtlasPreviewManager.dumpMetrics()}")
        }
        dialogRef?.setOnDismissListener {
            referencePreviews?.close()
            ownFrames?.close()
            onRestore()
        }
    }

    private var dialogRef: Dialog? = null

    private class CardViews(
        val container: View,
        val image: ImageView,
        val placeholder: View,
        val label: TextView,
        val tag: TextView,
        var boundId: String? = null,
    )
}
