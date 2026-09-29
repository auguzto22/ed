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
import com.termex.replay15.editor.assets.BuiltInEffectPresets
import com.termex.replay15.editor.assets.EffectPreset
import com.termex.replay15.editor.core.isEditorDebuggable
import com.termex.replay15.editor.domain.VideoClip
import com.termex.replay15.editor.preview.AtlasPreviewManager

object EffectPresetGallery {

    fun show(
        activity: Activity,
        clip: VideoClip,
        sessionTimestampUs: Long = -1L,
        onPreview: (preset: EffectPreset, intensity: Float) -> Unit = { _, _ -> },
        onRestore: () -> Unit = {},
        onApply: (preset: EffectPreset, intensity: Float) -> Unit,
    ) = with(activity) {
        val openStartMs = System.currentTimeMillis()
        val body = column()
        body.addView(sectionTitle(
            "Presets de Efeitos",
            "Combinações multi-pass prontas com prévia real no seu vídeo.",
        ))

        val allPresets = runCatching { BuiltInEffectPresets.definitions(this) }.getOrDefault(emptyList())
        val categories = listOf("Todos", "Cinema", "Film", "Gaming", "Retro", "Distorsao")

        var selectedPreset: EffectPreset? = null
        var currentIntensity = 1.0f
        var currentCategoryIndex = 0

        val targetTimestampUs = if (sessionTimestampUs >= 0L) {
            sessionTimestampUs
        } else {
            (clip.inUs + clip.outUs) / 2
        }

        val presetCardMap = mutableMapOf<String, CardViews>()

        // Categories horizontal strip
        val tabs = row()
        val categoryStrip = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabs)
            setPadding(0, 0, 0, dp(10))
        }
        body.addView(categoryStrip)

        // Thumbnails container
        val thumbStripContainer = row().apply {
            setPadding(0, dp(4), 0, dp(8))
        }
        val thumbScrollView = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(thumbStripContainer)
        }
        body.addView(thumbScrollView)

        fun updateCardVisuals() {
            presetCardMap.forEach { (presetId, views) ->
                val isChosen = selectedPreset?.id == presetId
                views.container.background = GradientDrawable().apply {
                    setColor(if (isChosen) EditorStyle.CARD_ELEVATED else EditorStyle.CARD)
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(if (isChosen) 2 else 1), if (isChosen) EditorStyle.ACCENT else EditorStyle.BORDER)
                }
                views.label.setTextColor(if (isChosen) EditorStyle.ACCENT else EditorStyle.MUTED)
                views.label.setTypeface(views.label.typeface, if (isChosen) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
        }

        fun populateThumbnails() {
            thumbStripContainer.removeAllViews()
            presetCardMap.clear()

            val filterCategory = categories[currentCategoryIndex]
            val visiblePresets = allPresets.filter {
                currentCategoryIndex == 0 || it.category.equals(filterCategory, ignoreCase = true)
            }

            visiblePresets.forEach { preset ->
                val isChosen = selectedPreset?.id == preset.id

                val cardContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    isClickable = true
                    isFocusable = true
                    contentDescription = "${preset.name} - ${preset.category}"
                    setPadding(dp(6), dp(6), dp(6), dp(6))

                    background = GradientDrawable().apply {
                        setColor(if (isChosen) EditorStyle.CARD_ELEVATED else EditorStyle.CARD)
                        cornerRadius = dp(12).toFloat()
                        setStroke(dp(if (isChosen) 2 else 1), if (isChosen) EditorStyle.ACCENT else EditorStyle.BORDER)
                    }
                }

                val imageFrame = FrameLayout(this).apply {
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat())
                        }
                    }
                    clipToOutline = true
                }

                val placeholder = View(this).apply {
                    background = GradientDrawable().apply {
                        setColor(0xFF222226.toInt())
                        cornerRadius = dp(8).toFloat()
                    }
                }
                imageFrame.addView(placeholder, FrameLayout.LayoutParams(dp(76), dp(76)))

                val imageView = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                imageFrame.addView(imageView, FrameLayout.LayoutParams(dp(76), dp(76)))

                cardContainer.addView(imageFrame)

                val labelView = TextView(this).apply {
                    text = preset.name
                    textSize = 11f
                    setTextColor(if (isChosen) EditorStyle.ACCENT else EditorStyle.MUTED)
                    gravity = Gravity.CENTER_HORIZONTAL
                    isSingleLine = true
                    maxWidth = dp(84)
                    setPadding(0, dp(6), 0, dp(2))
                }
                cardContainer.addView(labelView)

                val tagView = TextView(this).apply {
                    text = preset.category
                    textSize = 9f
                    setTextColor(0xFF888890.toInt())
                    gravity = Gravity.CENTER_HORIZONTAL
                    isSingleLine = true
                }
                cardContainer.addView(tagView)

                cardContainer.setOnClickListener {
                    selectedPreset = preset
                    updateCardVisuals()
                    onPreview(preset, currentIntensity)
                }

                presetCardMap[preset.id] = CardViews(cardContainer, imageView, placeholder, labelView)

                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(dp(4), 0, dp(4), 0)
                }
                thumbStripContainer.addView(cardContainer, params)

                // Load pre-rendered thumbnail directly from atlas
                AtlasPreviewManager.getEffectPresetPreview(activity, preset.id) { drawable ->
                    imageView.setImageDrawable(drawable)
                    placeholder.visibility = View.GONE
                }
            }
        }

        fun updateTabs() {
            tabs.removeAllViews()
            categories.forEachIndexed { index, title ->
                val isSelected = index == currentCategoryIndex
                val pill = TextView(this).apply {
                    text = title
                    textSize = 12f
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    setTextColor(if (isSelected) Color.WHITE else EditorStyle.MUTED)
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

        dialogRef = sheet("Presets de Efeitos", body)
        if (activity.isEditorDebuggable()) {
            android.util.Log.d("ReclyPerf", "EFFECT_PRESET_GALLERY_OPENED timeMs=${System.currentTimeMillis() - openStartMs} shaders=0 mediaCodec=0 ${AtlasPreviewManager.dumpMetrics()}")
        }
        dialogRef?.setOnDismissListener {
            onRestore()
        }
    }

    private var dialogRef: Dialog? = null

    private data class CardViews(
        val container: View,
        val image: ImageView,
        val placeholder: View,
        val label: TextView,
    )
}
