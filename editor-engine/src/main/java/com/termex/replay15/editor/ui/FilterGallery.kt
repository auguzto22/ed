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
import com.termex.replay15.editor.core.isEditorDebuggable
import com.termex.replay15.editor.domain.VideoClip
import com.termex.replay15.editor.domain.VideoFilter
import com.termex.replay15.editor.preview.AtlasPreviewManager

object FilterGallery {

    fun show(
        activity: Activity,
        clip: VideoClip,
        allowApplyAll: Boolean = true,
        sessionTimestampUs: Long = -1L,
        preview: (Int, Float) -> Unit = { _, _ -> },
        restore: () -> Unit = {},
        apply: (Int, Float, Boolean) -> Unit,
    ) = with(activity) {
        val openStartMs = System.currentTimeMillis()
        val body = column()
        body.addView(sectionTitle(
            "Sua assinatura de cor",
            "Escolha um look e ajuste a intensidade. A cor aparece na prévia ao selecionar.",
        ))

        val groups = listOf("Todos", "Cinema", "Retrato", "Vintage", "Criativos")
        var selected = clip.filter
        var strength = (clip.filterStrength * 100).toInt()
        var category = 0

        // Resolve timestamp for thumbnail extraction
        val targetTimestampUs = if (sessionTimestampUs >= 0L) {
            sessionTimestampUs
        } else {
            (clip.inUs + clip.outUs) / 2
        }

        // Map filter to category index
        fun categoryOf(filter: VideoFilter): Int = when (filter) {
            VideoFilter.CINEMA, VideoFilter.TEAL_ORANGE, VideoFilter.NIGHT, VideoFilter.URBAN,
            VideoFilter.DOCUMENTARY, VideoFilter.NOIR, VideoFilter.DRAMA -> 1

            VideoFilter.PORTRAIT, VideoFilter.SOFT, VideoFilter.ROSE, VideoFilter.GOLDEN,
            VideoFilter.FOOD, VideoFilter.CLEAN -> 2

            VideoFilter.VINTAGE, VideoFilter.FADE, VideoFilter.MATTE, VideoFilter.RETRO,
            VideoFilter.AUTUMN -> 3

            else -> 4
        }

        // Store active views for selection border/visual updates
        val filterCardMap = mutableMapOf<VideoFilter, CardViews>()

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

        fun updateCardSelectionVisuals() {
            filterCardMap.forEach { (filter, views) ->
                val isChosen = filter.ordinal == selected
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
            filterCardMap.clear()

            val visibleFilters = VideoFilter.entries.filter { category == 0 || categoryOf(it) == category }

            visibleFilters.forEach { filter ->
                val isChosen = filter.ordinal == selected

                val cardContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    isClickable = true
                    isFocusable = true
                    contentDescription = filter.label
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

                // Placeholder view while thumbnail renders
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

                val nameLabel = label(filter.label, 11f, if (isChosen) EditorStyle.ACCENT else EditorStyle.MUTED).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(2), dp(4), dp(2), 0)
                }
                cardContainer.addView(nameLabel)

                cardContainer.setOnClickListener {
                    selected = filter.ordinal
                    updateCardSelectionVisuals()
                    preview(selected, strength / 100f)
                }

                filterCardMap[filter] = CardViews(cardContainer, imageView, placeholder, nameLabel)

                thumbStripContainer.addView(
                    cardContainer,
                    LinearLayout.LayoutParams(dp(88), dp(104)).apply {
                        marginEnd = dp(8)
                    },
                )

                // Load pre-rendered thumbnail directly from atlas
                AtlasPreviewManager.getFilterPreview(activity, filter) { drawable ->
                    imageView.setImageDrawable(drawable)
                    placeholder.visibility = View.GONE
                }
            }
        }

        fun updateTabs() {
            for (i in 0 until tabs.childCount) {
                val btn = tabs.getChildAt(i) as Button
                val isCurrent = i == category
                btn.setTextColor(if (isCurrent) Color.BLACK else Color.WHITE)
                btn.background = GradientDrawable().apply {
                    if (isCurrent) {
                        setColor(Color.WHITE)
                        cornerRadius = dp(20).toFloat()
                    } else {
                        setColor(EditorStyle.CARD)
                        setStroke(dp(1), EditorStyle.BORDER)
                        cornerRadius = dp(12).toFloat()
                    }
                }
            }
        }

        groups.forEachIndexed { index, name ->
            val tabBtn = action(name) {
                category = index
                updateTabs()
                populateThumbnails()
            }
            tabs.addView(tabBtn, LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(6) })
        }

        updateTabs()
        populateThumbnails()

        // Strength slider
        slider(body, "Intensidade (%)", strength, 100) {
            strength = it
            preview(selected, strength / 100f)
        }

        val all = CheckBox(this).apply {
            text = "Aplicar a todos os clipes"
            setTextColor(Color.WHITE)
            setPadding(dp(8), 0, 0, 0)
        }
        if (allowApplyAll) {
            body.addView(all, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(8) })
        }

        lateinit var dialog: Dialog
        val footer = row().apply { setPadding(0, dp(8), 0, 0) }

        var applied = false
        footer.addView(
            action("Remover filtro") {
                applied = true
                apply(0, 1f, all.isChecked)
                dialog.dismiss()
            },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) },
        )

        footer.addView(
            action("Aplicar look", true) {
                applied = true
                apply(selected, strength / 100f, all.isChecked)
                dialog.dismiss()
            },
            LinearLayout.LayoutParams(0, dp(48), 1f),
        )

        body.addView(footer)

        dialog = sheet("Galeria de filtros", body)
        if (activity.isEditorDebuggable()) {
            android.util.Log.d("ReclyPerf", "FILTER_GALLERY_OPENED timeMs=${System.currentTimeMillis() - openStartMs} shaders=0 mediaCodec=0 ${AtlasPreviewManager.dumpMetrics()}")
        }
        dialog.setOnDismissListener {
            if (!applied) {
                restore()
            }
        }
    }

    private data class CardViews(
        val container: View,
        val image: ImageView,
        val placeholder: View,
        val label: TextView,
    )
}
