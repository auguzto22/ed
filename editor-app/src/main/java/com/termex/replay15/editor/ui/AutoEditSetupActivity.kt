package com.termex.replay15.editor.ui

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.recly.editor.R
import com.termex.replay15.editor.autoedit.AutoEditStyle
import com.termex.replay15.editor.media.GalleryPickerActivity
import com.termex.replay15.editor.media.MediaSourceAccess
import com.termex.replay15.editor.media.MediaSourceStatus
import com.termex.replay15.ui.BlitzAppearance
import com.termex.replay15.ui.BlitzColors
import java.util.concurrent.Executors

/**
 * Tela Auto Edit do Início (direção C do canvas "Blitz – Tela inicial"): escolhe o vídeo, o
 * estilo, o formato e as opções, e "Gerar edição" abre o editor já rodando o Auto Edit.
 * O resultado continua sendo um projeto editável (nada é renderizado antes de exportar).
 */
class AutoEditSetupActivity : Activity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(BlitzAppearance.wrap(newBase))
    }

    private companion object {
        const val REQ_PICK = 820
        const val ACCENT = 0xFFE5163A.toInt()
        const val STATE_URI = "uri"
        const val STATE_STYLE = "style"
        const val STATE_FORMAT = "format"
        const val STATE_FLAGS = "flags"
    }

    /** Estilos mostrados como cartões: nome, apoio e o estilo do motor. */
    private data class StyleCard(val label: String, val hint: String, val style: AutoEditStyle)

    private val styleCards = listOf(
        StyleCard("Gameplay", "Cortes rápidos", AutoEditStyle.GAMING),
        StyleCard("Fala", "Podcast, tira pausas", AutoEditStyle.PODCAST),
        StyleCard("Shorts", "Ritmo e zoom", AutoEditStyle.SHORTS),
    )

    private val io = Executors.newSingleThreadExecutor()
    private var videoUri: Uri? = null
    private var styleIndex = 0
    private var format = AutoEditLaunch.Format.VERTICAL
    private var cleanPauses = true
    private var captions = true
    private var smartZoom = true
    private var audioEnhancement = true

    private lateinit var videoSlot: LinearLayout
    private lateinit var styleRow: LinearLayout
    private lateinit var formatRow: LinearLayout
    private lateinit var generate: TextView
    private lateinit var summary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BlitzAppearance.applyWindow(this, BlitzColors.bg)
        savedInstanceState?.let { state ->
            videoUri = state.getString(STATE_URI)?.let(Uri::parse)
            styleIndex = state.getInt(STATE_STYLE, 0).coerceIn(0, styleCards.lastIndex)
            format = AutoEditLaunch.Format.entries.getOrElse(state.getInt(STATE_FORMAT, 0)) { AutoEditLaunch.Format.VERTICAL }
            state.getBooleanArray(STATE_FLAGS)?.takeIf { it.size == 4 }?.let { f ->
                cleanPauses = f[0]; captions = f[1]; smartZoom = f[2]; audioEnhancement = f[3]
            }
        }
        setContentView(buildScreen())
        renderVideo()
        renderStyles()
        renderFormats()
        renderSummary()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        videoUri?.let { outState.putString(STATE_URI, it.toString()) }
        outState.putInt(STATE_STYLE, styleIndex)
        outState.putInt(STATE_FORMAT, format.ordinal)
        outState.putBooleanArray(STATE_FLAGS, booleanArrayOf(cleanPauses, captions, smartZoom, audioEnhancement))
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    // ---------- Montagem ----------

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BlitzColors.bg)
        }
        // Barras do sistema por cima (edge-to-edge): o espaço delas vira padding.
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(0, bars.top, 0, bars.bottom)
            }
            insets
        }

        // Topo: voltar + raio + "Auto Edit"
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(16), dp(4))
        }
        top.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_pro_back)
            imageTintList = ColorStateList.valueOf(BlitzColors.text)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "Voltar"
            isClickable = true
            isFocusable = true
            background = RippleDrawable(ColorStateList.valueOf(BlitzColors.ripple), null, null)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        top.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_canvas_bolt)
            imageTintList = ColorStateList.valueOf(ACCENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(4) })
        top.addView(TextView(this).apply {
            text = "Auto Edit"
            textSize = 20f
            setTextColor(BlitzColors.text)
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        root.addView(top, LinearLayout.LayoutParams(-1, -2))

        // Conteúdo rolável
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(24))
        }
        content.addView(sectionLabel("1 · Vídeo"))
        videoSlot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        content.addView(videoSlot, LinearLayout.LayoutParams(-1, -2))

        content.addView(sectionLabel("2 · Estilo", top = 24))
        styleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        content.addView(styleRow, LinearLayout.LayoutParams(-1, dp(76)))

        content.addView(sectionLabel("3 · Formato", top = 24))
        formatRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        content.addView(formatRow, LinearLayout.LayoutParams(-1, dp(40)))

        val options = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(BlitzColors.surface)
                setStroke(dp(1), BlitzColors.line)
                cornerRadius = dp(18).toFloat()
            }
            clipToOutline = true
        }
        options.addView(toggleRow("Remover silêncios", "Tira pausas e respirações longas", cleanPauses) { cleanPauses = it; renderSummary() })
        options.addView(divider())
        options.addView(toggleRow("Legendas automáticas", "Cria as legendas da fala (precisa de conta)", captions) { captions = it; renderSummary() })
        options.addView(divider())
        options.addView(toggleRow("Zoom nos rostos", "Punch-in no rosto a cada frase", smartZoom) { smartZoom = it; renderSummary() })
        options.addView(divider())
        options.addView(toggleRow("Equilibrar áudio", "Nivela o volume da fala", audioEnhancement) { audioEnhancement = it; renderSummary() })
        content.addView(options, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // Rodapé fixo: Gerar edição + resumo
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(16))
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                android.graphics.drawable.ColorDrawable(BlitzColors.line),
                android.graphics.drawable.ColorDrawable(BlitzColors.bg),
            )).apply { setLayerInset(1, 0, dp(1), 0, 0) }
        }
        generate = TextView(this).apply {
            text = "Gerar edição"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_canvas_bolt, 0, 0, 0)
            compoundDrawablePadding = dp(8)
            compoundDrawableTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(dp(24), 0, dp(24), 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        // O ícone fica junto do texto no centro: o botão em si é um FrameLayout largo.
        val generateBox = FrameLayout(this).apply {
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), GradientDrawable().apply {
                setColor(ACCENT); cornerRadius = dp(28).toFloat()
            }, null)
            isClickable = true
            isFocusable = true
            contentDescription = "Gerar edição"
            setOnClickListener { startEdit() }
            addView(generate, FrameLayout.LayoutParams(-2, -1, Gravity.CENTER))
        }
        footer.addView(generateBox, LinearLayout.LayoutParams(-1, dp(56)))
        summary = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(BlitzColors.muted)
        }
        footer.addView(summary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(footer, LinearLayout.LayoutParams(-1, -2))
        return root
    }

    private fun sectionLabel(text: String, top: Int = 8) = TextView(this).apply {
        this.text = text.uppercase()
        textSize = 12f
        letterSpacing = 0.08f
        setTextColor(BlitzColors.muted)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(0, dp(top), 0, dp(10))
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(BlitzColors.line)
        layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(16) }
    }

    /** Linha do cartão de opções com um interruptor desenhado (vermelho quando ligado). */
    private fun toggleRow(label: String, hint: String, initial: Boolean, onChange: (Boolean) -> Unit): View {
        var on = initial
        val knob = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        }
        val track = FrameLayout(this).apply { setPadding(dp(3), dp(3), dp(3), dp(3)) }
        track.addView(knob, FrameLayout.LayoutParams(dp(20), dp(20)))
        fun paint() {
            track.background = GradientDrawable().apply {
                cornerRadius = dp(13).toFloat()
                setColor(if (on) ACCENT else BlitzColors.faint)
            }
            (knob.layoutParams as FrameLayout.LayoutParams).gravity = if (on) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.START or Gravity.CENTER_VERTICAL
            knob.requestLayout()
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            isFocusable = true
            background = RippleDrawable(ColorStateList.valueOf(BlitzColors.ripple), null, null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) stateDescription = if (on) "ligado" else "desligado"
            contentDescription = label
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(BlitzColors.text)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        texts.addView(TextView(this).apply {
            text = hint
            textSize = 12f
            setTextColor(BlitzColors.muted)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
        row.addView(track, LinearLayout.LayoutParams(dp(46), dp(26)))
        paint()
        row.setOnClickListener {
            on = !on
            paint()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) row.stateDescription = if (on) "ligado" else "desligado"
            onChange(on)
        }
        return row
    }

    // ---------- Estado ----------

    private fun renderVideo() {
        videoSlot.removeAllViews()
        val uri = videoUri
        val thumb = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                if (uri == null) {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1.5f), BlitzColors.faint, dp(6).toFloat(), dp(4).toFloat())
                } else setColor(BlitzColors.surfaceRaised)
            }
            clipToOutline = true
            isClickable = true
            isFocusable = true
            foreground = RippleDrawable(ColorStateList.valueOf(BlitzColors.ripple), null,
                GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(14).toFloat() })
            contentDescription = if (uri == null) "Escolher vídeo" else "Trocar vídeo"
            setOnClickListener { pickVideo() }
        }
        if (uri == null) {
            thumb.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(ImageView(this@AutoEditSetupActivity).apply {
                    setImageResource(R.drawable.ic_canvas_plus)
                    imageTintList = ColorStateList.valueOf(BlitzColors.muted)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(22), dp(22)))
                addView(TextView(this@AutoEditSetupActivity).apply {
                    text = "Escolher"
                    textSize = 12f
                    setTextColor(BlitzColors.muted)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            }, FrameLayout.LayoutParams(-1, -1))
        } else {
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            thumb.addView(image, FrameLayout.LayoutParams(-1, -1))
            val durationChip = TextView(this).apply {
                textSize = 11f
                setTextColor(Color.parseColor("#F2F0EA"))
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = GradientDrawable().apply { setColor(Color.parseColor("#B3000000")); cornerRadius = dp(6).toFloat() }
                visibility = View.GONE
            }
            thumb.addView(durationChip, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.BOTTOM).apply { setMargins(dp(6), 0, 0, dp(6)) })
            loadPreview(uri) { bitmap, durationMs ->
                bitmap?.let(image::setImageBitmap)
                if (durationMs > 0) { durationChip.text = shortDuration(durationMs); durationChip.visibility = View.VISIBLE }
            }
        }
        videoSlot.addView(thumb, LinearLayout.LayoutParams(dp(96), dp(128)))

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = if (uri == null) "Escolha o vídeo" else "Vídeo escolhido"
            textSize = 15f
            setTextColor(BlitzColors.text)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        texts.addView(TextView(this).apply {
            text = if (uri == null) "A IA corta, legenda e monta a edição a partir dele."
                else "Toque na miniatura para trocar."
            textSize = 12f
            setTextColor(BlitzColors.muted)
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        videoSlot.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
        renderSummary()
    }

    private fun renderStyles() {
        styleRow.removeAllViews()
        styleCards.forEachIndexed { index, card ->
            val on = index == styleIndex
            val view = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
                background = RippleDrawable(ColorStateList.valueOf(BlitzColors.ripple), GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    if (on) { setColor(getColor(R.color.blitz_autoedit_bg)); setStroke(dp(2), ACCENT) }
                    else { setColor(BlitzColors.surface); setStroke(dp(1), BlitzColors.line) }
                }, null)
                isClickable = true
                isFocusable = true
                contentDescription = "Estilo ${card.label}: ${card.hint}" + if (on) ", selecionado" else ""
                setOnClickListener { styleIndex = index; renderStyles(); renderSummary() }
                addView(TextView(this@AutoEditSetupActivity).apply {
                    text = card.label
                    textSize = 14f
                    setTextColor(BlitzColors.text)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    maxLines = 1
                })
                addView(TextView(this@AutoEditSetupActivity).apply {
                    text = card.hint
                    textSize = 11f
                    setTextColor(BlitzColors.muted)
                    maxLines = 2
                })
            }
            styleRow.addView(view, LinearLayout.LayoutParams(0, -1, 1f).apply {
                if (index > 0) marginStart = dp(8)
            })
        }
    }

    private fun renderFormats() {
        formatRow.removeAllViews()
        AutoEditLaunch.Format.entries.forEachIndexed { index, value ->
            val on = value == format
            formatRow.addView(TextView(this).apply {
                text = value.label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(if (on) BlitzColors.bg else BlitzColors.text)
                background = RippleDrawable(ColorStateList.valueOf(BlitzColors.ripple), GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat()
                    if (on) setColor(BlitzColors.text) else { setColor(Color.TRANSPARENT); setStroke(dp(1), BlitzColors.line) }
                }, null)
                contentDescription = "Formato ${value.label}" + if (on) ", selecionado" else ""
                isClickable = true
                isFocusable = true
                setOnClickListener { format = value; renderFormats(); renderSummary() }
            }, LinearLayout.LayoutParams(-2, -1).apply { if (index > 0) marginStart = dp(8) })
        }
    }

    private fun renderSummary() {
        if (!::summary.isInitialized) return
        val ready = videoUri != null
        // Sem vídeo o botão fica apagado; tocar nele abre o seletor.
        (generate.parent as? View)?.alpha = if (ready) 1f else 0.55f
        summary.text = if (!ready) "Escolha um vídeo para começar"
        else listOf(
            styleCards[styleIndex].label,
            format.label,
            if (captions) "com legendas" else "sem legendas",
        ).joinToString(" · ") + " · sai como projeto editável"
    }

    // ---------- Ações ----------

    private fun pickVideo() {
        startActivityForResult(
            GalleryPickerActivity.intent(this, GalleryPickerActivity.MODE_VIDEOS, title = "Vídeo para o Auto Edit"),
            REQ_PICK,
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val gallery = GalleryPickerActivity.isGalleryResult(data)
        val ok = if (gallery) {
            MediaSourceAccess.verify(contentResolver, uri) == MediaSourceStatus.Available
        } else {
            runCatching { MediaSourceAccess.persistReadPermission(contentResolver, uri, data.flags) }.getOrDefault(false)
        }
        if (!ok) {
            Toast.makeText(this, "Não foi possível manter acesso a este vídeo", Toast.LENGTH_LONG).show()
            return
        }
        videoUri = uri
        renderVideo()
    }

    private fun startEdit() {
        val uri = videoUri ?: run { pickVideo(); return }
        val launch = AutoEditLaunch(
            style = styleCards[styleIndex].style,
            format = format,
            cleanPauses = cleanPauses,
            captions = captions,
            smartZoom = smartZoom,
            audioEnhancement = audioEnhancement,
        )
        val editor = Intent(this, EditorActivity::class.java)
            .putExtra("uri", uri.toString())
            .putExtra("open_tool", "auto_edit")
        startActivity(launch.writeTo(editor))
        finish()
    }

    /** Primeiro quadro e duração do vídeo, lidos fora da tela principal. */
    private fun loadPreview(uri: Uri, done: (Bitmap?, Long) -> Unit) {
        io.execute {
            val retriever = MediaMetadataRetriever()
            val result = runCatching {
                retriever.setDataSource(this, uri)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val frame = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, dp(192), dp(256))
                frame to duration
            }.getOrNull()
            runCatching { retriever.release() }
            runOnUiThread {
                if (isDestroyed || uri != videoUri) return@runOnUiThread
                done(result?.first, result?.second ?: 0L)
            }
        }
    }

    private fun shortDuration(durationMs: Long): String {
        val total = (durationMs + 500) / 1000
        return String.format(java.util.Locale.ROOT, "%d:%02d", total / 60, total % 60)
    }
}
