package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.*
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.export.EncoderSupport
import com.termex.replay15.editor.render.EditorFonts
import java.util.concurrent.Executor

object ExportPanel {
    fun show(activity: Activity, project: Project, export: Boolean, io: Executor, apply: (Project) -> Unit) = with(activity) {
        val body = column()
        val status = label("Consultando encoder do aparelho...", 13f, EditorStyle.MUTED)
        body.addView(status)
        val controls = column(); body.addView(controls)
        val dialog = sheet(if (export) "Exportar vídeo" else "Qualidade de exportação", body)
        io.execute {
            val result = runCatching {
                listOf(480, 720, 1080, 1440, 2160).associateWith { size ->
                    listOf(24, 25, 30, 50, 60, 120, 144).filter { fps ->
                        EncoderSupport.supportsSizeAndRate(project.copy(export = ExportSettings(size, fps, project.export.bitrate)))
                    }
                }.filterValues { it.isNotEmpty() }
            }
            runOnUiThread {
                if (isDestroyed || !dialog.isShowing) return@runOnUiThread
                result.fold({ supported ->
                    if (supported.isEmpty()) status.text = "Nenhuma configuração de hardware compatível encontrada."
                    else {
                        status.visibility = View.GONE
                        buildControls(this, controls, dialog, project, supported, export, io, apply)
                    }
                }, { status.text = "Não foi possível consultar o encoder: ${it.message}" })
            }
        }
    }

    private fun buildControls(
        activity: Activity, body: LinearLayout, dialog: Dialog, project: Project,
        supported: Map<Int, List<Int>>, export: Boolean, io: Executor, apply: (Project) -> Unit
    ) = with(activity) {
        val sizes = supported.keys.toList()
        var selectedSize = if (sizes.contains(project.export.shortSide)) project.export.shortSide else sizes.firstOrNull() ?: 720
        var availableFps = supported[selectedSize] ?: listOf(30)
        var selectedFps = if (availableFps.contains(project.export.fps)) project.export.fps else availableFps.firstOrNull() ?: 30
        var bitrate = (project.export.bitrate / 1_000_000).coerceIn(1, 160)
        val audioBitrate = project.export.audioBitrate

        fun settings() = ExportSettings(selectedSize, selectedFps, bitrate * 1_000_000, audioBitrate)

        // Status Card: current format, dimensions, FPS, and size
        val infoCard = column().apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(EditorStyle.CARD)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), EditorStyle.BORDER)
            }
        }
        val estimateLabel = label("", 14f, Color.WHITE).apply {
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val infoFormat = label("MP4  •  H.264 + AAC", 12f, EditorStyle.MUTED)
        infoCard.addView(estimateLabel)
        infoCard.addView(infoFormat)
        body.addView(infoCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        fun updateEstimate() {
            val bytes = settings().estimatedBytes(project.durationUs)
            val mb = bytes / 1_000_000
            val dim = project.copy(export = settings()).dimensions()
            estimateLabel.text = "${dim.first}x${dim.second}  •  ${selectedFps} FPS  •  ~${mb} MB"
        }

        // Chip button builder
        fun chip(text: String, isSelected: Boolean, onClick: () -> Unit): Button {
            return Button(this).apply {
                this.text = text
                contentDescription = text
                textSize = 13f
                isAllCaps = false
                typeface = EditorFonts.get(activity, TextFont.OUTFIT, android.graphics.Typeface.BOLD)
                minWidth = dp(56); minimumWidth = dp(56)
                minHeight = dp(36); minimumHeight = dp(36)
                setPadding(dp(12), dp(4), dp(12), dp(4))
                val bg = GradientDrawable().apply {
                    if (isSelected) {
                        setColor(Color.WHITE)
                        cornerRadius = dp(18).toFloat()
                    } else {
                        setColor(EditorStyle.CARD)
                        setStroke(dp(1), EditorStyle.BORDER)
                        cornerRadius = dp(10).toFloat()
                    }
                }
                background = RippleDrawable(
                    ColorStateList.valueOf(if (isSelected) 0x33000000 else 0x22FFFFFF),
                    bg,
                    null,
                )
                setTextColor(if (isSelected) Color.BLACK else Color.WHITE)
                setOnClickListener { onClick() }
            }
        }

        // Resolution section
        body.addView(label("Resolução", 13f, EditorStyle.MUTED))
        val resContainer = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val resRow = row().apply { setPadding(0, dp(4), 0, dp(10)) }
        resContainer.addView(resRow)
        body.addView(resContainer)

        // FPS section
        body.addView(label("Taxa de quadros (FPS)", 13f, EditorStyle.MUTED))
        val fpsContainer = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val fpsRow = row().apply { setPadding(0, dp(4), 0, dp(12)) }
        fpsContainer.addView(fpsRow)
        body.addView(fpsContainer)

        lateinit var rebuildFpsChips: () -> Unit

        fun rebuildResChips() {
            resRow.removeAllViews()
            sizes.forEach { size ->
                val chipBtn = chip("${size}p", size == selectedSize) {
                    selectedSize = size
                    availableFps = supported[selectedSize] ?: listOf(30)
                    if (!availableFps.contains(selectedFps)) {
                        selectedFps = availableFps.firstOrNull() ?: 30
                    }
                    rebuildResChips()
                    rebuildFpsChips()
                    updateEstimate()
                }
                resRow.addView(chipBtn, LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(8) })
            }
        }

        rebuildFpsChips = {
            fpsRow.removeAllViews()
            availableFps.forEach { fpsVal ->
                val chipBtn = chip("$fpsVal FPS", fpsVal == selectedFps) {
                    selectedFps = fpsVal
                    rebuildFpsChips()
                    updateEstimate()
                }
                fpsRow.addView(chipBtn, LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(8) })
            }
        }

        rebuildResChips()
        rebuildFpsChips()
        updateEstimate()

        // Submit action button
        val submit = action(if (export) "Exportar vídeo" else "Salvar qualidade", true) {}
        submit.apply {
            minHeight = dp(46); minimumHeight = dp(46)
            textSize = 15f
        }
        submit.setOnClickListener {
            val candidate = project.copy(export = settings())
            submit.isEnabled = false
            submit.text = "Verificando..."
            io.execute {
                val valid = runCatching { EncoderSupport.supports(candidate) }.getOrDefault(false)
                runOnUiThread {
                    if (isDestroyed || !dialog.isShowing) return@runOnUiThread
                    submit.isEnabled = true
                    submit.text = if (export) "Exportar vídeo" else "Salvar qualidade"
                    if (valid) {
                        dialog.dismiss()
                        apply(candidate)
                    } else {
                        estimateLabel.text = "Configuração não suportada pelo aparelho"
                    }
                }
            }
        }
        body.addView(submit, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(4); bottomMargin = dp(8) })

        // Optional advanced bitrate section
        val advancedContent = column().apply { visibility = View.GONE }
        val advancedToggle = TextView(this).apply {
            text = "Opções avançadas (Bitrate)  ▾"
            textSize = 12f
            setTextColor(EditorStyle.MUTED)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            setOnClickListener {
                if (advancedContent.visibility == View.VISIBLE) {
                    advancedContent.visibility = View.GONE
                    text = "Opções avançadas (Bitrate)  ▾"
                } else {
                    advancedContent.visibility = View.VISIBLE
                    text = "Ocultar opções avançadas  ▴"
                }
            }
        }
        body.addView(advancedToggle)

        val bitrateBar = slider(advancedContent, "Bitrate (Mbps)", bitrate, 160, minimum = 1) {
            bitrate = it
            updateEstimate()
        }
        val presets = row()
        listOf("Economia" to .65, "Padrão" to 1.0, "Alta" to 1.5).forEach { (name, multiplier) ->
            presets.addView(action(name) {
                val candidate = project.copy(export = settings())
                val (w, h) = candidate.dimensions()
                val reference = 8.0 * w * h / (1280 * 720) * (candidate.export.fps / 30.0).coerceAtLeast(.75)
                bitrate = (reference * multiplier).toInt().coerceIn(1, 160)
                bitrateBar.progress = bitrate
                updateEstimate()
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        }
        advancedContent.addView(presets)
        body.addView(advancedContent)
    }
}
