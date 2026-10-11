package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.*
import com.recly.editor.engine.R
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.font.FontAsset
import com.termex.replay15.editor.font.FontCatalog
import com.termex.replay15.editor.font.FontCategory
import com.termex.replay15.editor.font.FontLibraryPreferences
import com.termex.replay15.editor.font.FontSource
import com.termex.replay15.editor.font.FontTag
import com.termex.replay15.editor.render.EditorFonts
import java.io.File

object StudioPanels {
    fun grade(activity: Activity, clip: VideoClip, apply: (VideoClip) -> Unit, importLut: () -> Unit,
        preview: (VideoClip) -> Unit = {}, restore: () -> Unit = {}, lutDirectory: File? = null,
        timestampUs: Long = -1L) = with(activity) {
        val body = column()
        var grade = clip.grade
        fun update(change: (StudioGrade) -> StudioGrade) { grade = change(grade); preview(clip.copy(grade = grade)) }
        body.addView(sectionTitle("Luz e profundidade", "Exposicao em stops, recuperacao tonal e acabamento."))
        slider(body, "Exposicao", (grade.exposure * 100).toInt(), 300, -300,
            format = { String.format(java.util.Locale.ROOT, "%.2f", it / 100f) }) { update { grade -> grade.copy(exposure = it / 100f) } }
        slider(body, "Sombras", (grade.shadows * 100).toInt(), 100, -100) { update { grade -> grade.copy(shadows = it / 100f) } }
        slider(body, "Altas luzes", (grade.highlights * 100).toInt(), 100, -100) { update { grade -> grade.copy(highlights = it / 100f) } }
        body.addView(sectionTitle("Curvas RGB", "Arraste os pontos. A diagonal preserva o canal original."))
        var curveChannel = 0
        val channel = Spinner(this).apply { adapter = ArrayAdapter(this@with, android.R.layout.simple_spinner_dropdown_item, listOf("Master / RGB", "Vermelho", "Verde", "Azul")) }
        body.addView(channel, LinearLayout.LayoutParams(-1, dp(52)))
        val curve = CurveEditorView(this, grade.curve).apply { onChange = { points ->
            grade = if (curveChannel == 0) grade.copy(curve = points)
                else grade.copy(channelCurves = grade.channelCurves.mapIndexed { i, old -> if (i == curveChannel - 1) points else old })
            preview(clip.copy(grade = grade))
        } }
        channel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                curveChannel = position; curve.reset(if (position == 0) grade.curve else grade.channelCurves[position - 1])
            }
        }
        body.addView(curve, LinearLayout.LayoutParams(-1, dp(200)))
        val presets = row()
        listOf("Linear" to listOf(0f, .25f, .5f, .75f, 1f), "Contraste" to listOf(0f, .17f, .5f, .83f, 1f), "Matte" to listOf(.07f, .28f, .51f, .75f, .95f))
            .forEach { (name, points) -> presets.addView(action(name) { curve.reset(points) }, LinearLayout.LayoutParams(0, dp(48), 1f)) }
        body.addView(presets)
        body.addView(sectionTitle("HSL por cor", "Ajuste matiz, saturacao e luminosidade em oito faixas de cor."))
        val band = Spinner(this).apply { adapter = ArrayAdapter(this@with, android.R.layout.simple_spinner_dropdown_item, HslBand.entries.map { it.label }) }
        val hslBody = column(); body.addView(band, LinearLayout.LayoutParams(-1, dp(52))); body.addView(hslBody)
        fun editBand(index: Int) {
            hslBody.removeAllViews()
            val selected = grade.hsl[index]
            fun change(value: HslAdjustment) { update { grade -> grade.copy(hsl = grade.hsl.mapIndexed { i, old -> if (i == index) value else old }) } }
            slider(hslBody, "Matiz (graus)", selected.hue.toInt(), 180, -180) { change(grade.hsl[index].copy(hue = it.toFloat())) }
            slider(hslBody, "Saturacao (%)", (selected.saturation * 100).toInt(), 100, -100) { change(grade.hsl[index].copy(saturation = it / 100f)) }
            slider(hslBody, "Luminosidade (%)", (selected.luminance * 100).toInt(), 100, -100) { change(grade.hsl[index].copy(luminance = it / 100f)) }
        }
        band.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) { editBand(position) }
        }
        body.addView(sectionTitle("Acabamento"))
        slider(body, "Vinheta (%)", (grade.vignette * 100).toInt(), 100) { update { grade -> grade.copy(vignette = it / 100f) } }
        slider(body, "Grao de filme (%)", (grade.grain * 100).toInt(), 100) { update { grade -> grade.copy(grain = it / 100f) } }
        slider(body, "Nitidez (%)", (grade.sharpen * 100).toInt(), 100) { update { grade -> grade.copy(sharpen = it / 100f) } }
        lateinit var dialog: Dialog
        body.addView(sectionTitle("LUT 3D"))
        val lutName = label(if (grade.lutPath.isBlank()) "Nenhuma LUT selecionada" else File(grade.lutPath).name, 13f, EditorStyle.MUTED)
        body.addView(lutName)
        val lutStrengthSlider = slider(body, "Intensidade da LUT (%)", (grade.lutStrength * 100).toInt(), 100) {
            update { grade -> grade.copy(lutStrength = it / 100f) }
        }
        if (lutDirectory != null) body.addView(action("Abrir navegador de LUTs") {
            LutBrowser.show(
                activity = this,
                directory = lutDirectory,
                clip = clip,
                timestampUs = timestampUs.takeIf { it >= 0L } ?: ((clip.inUs + clip.outUs) / 2),
                currentPath = grade.lutPath,
                currentStrength = grade.lutStrength,
                preview = { path, strength -> preview(clip.copy(grade = grade.copy(lutPath = path, lutStrength = strength))) },
                restore = { preview(clip.copy(grade = grade)) },
                choose = { path, strength ->
                    grade = grade.copy(lutPath = path, lutStrength = strength)
                    lutName.text = File(path).name
                    lutStrengthSlider.progress = (strength * 100).toInt()
                    preview(clip.copy(grade = grade))
                },
                onImport = { apply(clip.copy(grade = grade)); dialog.dismiss(); importLut() },
            )
        })
        body.addView(action("Importar LUT .cube") { apply(clip.copy(grade = grade)); dialog.dismiss(); importLut() })
        if (grade.lutPath.isNotBlank()) body.addView(action("Remover LUT") { grade = grade.copy(lutPath = ""); apply(clip.copy(grade = grade)); dialog.dismiss() })
        body.addView(action("Aplicar cor e efeitos", true) { apply(clip.copy(grade = grade)); dialog.dismiss() })
        body.addView(action("Restaurar cor e acabamento") {
            apply(clip.copy(grade = grade.copy(exposure = 0f, shadows = 0f, highlights = 0f, vignette = 0f, grain = 0f, sharpen = 0f,
                curve = StudioGrade().curve, channelCurves = StudioGrade().channelCurves, hsl = StudioGrade().hsl, lutPath = ""))); dialog.dismiss()
        })
        dialog = sheet("Estudio de cor", body)
        dialog.setOnDismissListener { restore() }
    }

    fun masks(activity: Activity, clip: VideoClip, apply: (VideoClip) -> Unit,
        preview: (VideoClip) -> Unit = {}, restore: () -> Unit = {}) = with(activity) {
        val body = column(); var grade = clip.grade
        fun update(change: (StudioGrade) -> StudioGrade) { grade = change(grade); preview(clip.copy(grade = grade)) }
        body.addView(sectionTitle("Mascara de enquadramento", "As areas removidas revelam os videos abaixo e o fundo do projeto."))
        val shape = Spinner(this).apply {
            adapter = ArrayAdapter(this@with, android.R.layout.simple_spinner_dropdown_item, MaskShape.entries.map { it.label })
            setSelection(grade.mask.ordinal)
        }
        body.addView(shape, LinearLayout.LayoutParams(-1, dp(56)))
        slider(body, "Tamanho (%)", (grade.maskSize * 100).toInt(), 100, 10) { update { grade -> grade.copy(maskSize = it / 100f) } }
        slider(body, "Horizontal (%)", (grade.maskX * 100).toInt(), 50, -50) { update { grade -> grade.copy(maskX = it / 100f) } }
        slider(body, "Vertical (%)", (grade.maskY * 100).toInt(), 50, -50) { update { grade -> grade.copy(maskY = it / 100f) } }
        slider(body, "Rotacao (graus)", grade.maskRotation.toInt(), 180, -180) { update { grade -> grade.copy(maskRotation = it.toFloat()) } }
        slider(body, "Proporcao horizontal (%)", (grade.maskAspect * 100).toInt(), 500, 10) { update { grade -> grade.copy(maskAspect = it / 100f) } }
        slider(body, "Intensidade da mascara (%)", (grade.maskOpacity * 100).toInt(), 100) { update { grade -> grade.copy(maskOpacity = it / 100f) } }
        slider(body, "Suavidade da borda", (grade.feather * 1000).toInt(), 500, 1,
            format = { "${it / 10f}%" }) { update { grade -> grade.copy(feather = it / 1000f) } }
        val invert = CheckBox(this).apply { text = "Inverter mascara"; isChecked = grade.invertMask }; body.addView(invert)
        body.addView(sectionTitle("Chroma key", "Remove uma cor uniforme para revelar as camadas abaixo."))
        val enabled = CheckBox(this).apply { text = "Ativar remocao de cor"; isChecked = grade.chromaEnabled }; body.addView(enabled)
        val color = EditText(this).apply { hint = "Cor #00FF00"; setText(String.format("#%06X", grade.chromaColor and 0xFFFFFF)); isSingleLine = true }; body.addView(color)
        val keys = row()
        listOf("Verde" to "#00FF00", "Azul" to "#0000FF", "Preto" to "#000000").forEach { (label, value) -> keys.addView(action(label) { color.setText(value) }, LinearLayout.LayoutParams(0, dp(48), 1f)) }
        body.addView(keys)
        slider(body, "Tolerancia (%)", (grade.chromaTolerance * 100).toInt(), 80, 1) { update { grade -> grade.copy(chromaTolerance = it / 100f) } }
        slider(body, "Suavidade do chroma (%)", (grade.chromaSmoothness * 1000).toInt(), 500, 1, { "${it / 10f}" }) { update { grade -> grade.copy(chromaSmoothness = it / 1000f) } }
        slider(body, "Remover reflexo da cor (%)", (grade.chromaSpill * 100).toInt(), 100) { update { grade -> grade.copy(chromaSpill = it / 100f) } }
        slider(body, "Ajuste da borda (%)", (grade.chromaEdge * 100).toInt(), 20, -20) { update { grade -> grade.copy(chromaEdge = it / 100f) } }
        lateinit var dialog: Dialog
        body.addView(action("Aplicar composicao", true) {
            val parsed = runCatching { Color.parseColor(color.text.toString()) }.getOrNull()
            if (parsed == null) color.error = "Use #RRGGBB" else {
                apply(clip.copy(grade = grade.copy(mask = MaskShape.entries[shape.selectedItemPosition], invertMask = invert.isChecked,
                    chromaEnabled = enabled.isChecked, chromaColor = parsed))); dialog.dismiss()
            }
        })
        body.addView(action("Remover mascara e chroma") { apply(clip.copy(grade = grade.copy(mask = MaskShape.NONE, chromaEnabled = false))); dialog.dismiss() })
        dialog = sheet("Mascaras e chroma", body)
        dialog.setOnDismissListener { restore() }
    }

    fun keyframe(activity: Activity, clip: VideoClip, timeUs: Long, seek: (Long) -> Unit = {},
        preview: (VideoClip) -> Unit = {}, restore: () -> Unit = {}, project: Project? = null, apply: (VideoClip) -> Unit) =
        KeyframeTools.show(activity, clip, timeUs, seek, preview, restore, project, apply)

    fun camera(activity: Activity, project: Project, timeUs: Long, seek: (Long) -> Unit = {},
        preview: (Project) -> Unit = {}, restore: () -> Unit = {}, apply: (Project) -> Unit) =
        CameraTools.show(activity, project, timeUs, seek, preview, restore, apply)


    fun canvas(activity: Activity, project: Project, apply: (Project) -> Unit) = with(activity) {
        val body = column(); var candidate = project
        body.addView(sectionTitle("Formato de entrega"))
        val ratios = listOf("Original" to 0f, "Reels / Stories 9:16" to 9f / 16, "YouTube 16:9" to 16f / 9, "Quadrado 1:1" to 1f,
            "Retrato 4:5" to 4f / 5, "Cinema 21:9" to 21f / 9, "Classico 4:3" to 4f / 3)
        val ratio = Spinner(this).apply { adapter = ArrayAdapter(this@with, android.R.layout.simple_spinner_dropdown_item, ratios.map { it.first }); setSelection(ratios.indexOfFirst { it.second == project.aspect }.coerceAtLeast(0)) }
        body.addView(ratio, LinearLayout.LayoutParams(-1, dp(56)))
        val fill = CheckBox(this).apply { text = "Preencher tela (recorta as bordas)"; isChecked = project.canvasFill == CanvasFill.FILL }; body.addView(fill)
        body.addView(sectionTitle("Cor do fundo", "Aparece nas margens, mascaras e areas transparentes."))
        val swatches = row()
        val input = EditText(this).apply { setText(String.format("#%06X", project.backgroundColor and 0xFFFFFF)); hint = "#RRGGBB"; isSingleLine = true }
        listOf(Color.BLACK, Color.WHITE, 0xFF20132E.toInt(), 0xFFD8C6FC.toInt(), 0xFF182D39.toInt(), 0xFFFFCF71.toInt()).forEach { color ->
            swatches.addView(action("●") { input.setText(String.format("#%06X", color and 0xFFFFFF)) }.apply { setTextColor(color); textSize = 24f }, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        body.addView(swatches); body.addView(input)
        lateinit var dialog: Dialog
        body.addView(action("Aplicar formato e fundo", true) {
            val color = runCatching { Color.parseColor(input.text.toString()) or 0xFF000000.toInt() }.getOrNull()
            if (color == null) input.error = "Use #RRGGBB" else {
                candidate = candidate.copy(aspect = ratios[ratio.selectedItemPosition].second, backgroundColor = color, canvasFill = if (fill.isChecked) CanvasFill.FILL else CanvasFill.FIT)
                apply(candidate); dialog.dismiss()
            }
        })
        dialog = sheet("Formato e fundo", body)
    }

    fun fonts(activity: Activity, currentId: String, select: (String) -> Unit) = with(activity) {
        val body = column()
        body.addView(sectionTitle("Biblioteca de fontes", "Toque para selecionar. Fontes remotas são baixadas sob demanda."))

        // ── Search field ────────────────────────────────────────────────────
        val search = EditText(this).apply { hint = "Buscar por nome"; isSingleLine = true }
        body.addView(search, LinearLayout.LayoutParams(-1, dp(52)))

        // ── Horizontal tab bar ──────────────────────────────────────────────
        val tabs = listOf(
            "Todos", "Em alta", "Recentes", "Favoritos", "Baixadas",
            "Sans", "Serif", "Display", "Escrita", "Mono",
            "Gaming", "Bold", "Minimal", "Elegante", "Retro",
            "Pixel", "Gothic", "Recly Originals"
        )
        val tabScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var selectedTab = 0
        tabScroll.addView(tabRow)
        body.addView(tabScroll, LinearLayout.LayoutParams(-1, dp(48)))

        // ── RecyclerView (virtualized font list) ────────────────────────────
        val recyclerView = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@with)
            setHasFixedSize(false)
        }
        body.addView(recyclerView, LinearLayout.LayoutParams(-1, dp(420)))

        val preferences = FontLibraryPreferences(this)
        val fontRepo = com.termex.replay15.editor.font.FontRepository(this)
        lateinit var dialog: Dialog

        // ── Data source ─────────────────────────────────────────────────────
        fun filteredFonts(): List<FontAsset> {
            val query = search.text.toString().trim()
            val base: List<FontAsset> = when (selectedTab) {
                0 -> FontCatalog.all()
                1 -> FontCatalog.all().filter { FontTag.TRENDING in FontCatalog.tagsFor(it.id) }
                2 -> preferences.recent().mapNotNull { FontCatalog.find(it) }
                3 -> FontCatalog.all().filter { it.id in preferences.favorites() }
                4 -> FontCatalog.builtIns + FontCatalog.remotes().filter { it.downloaded }.map { it.toFontAsset() }
                5 -> FontCatalog.filter(category = FontCategory.SANS)
                6 -> FontCatalog.filter(category = FontCategory.SERIF)
                7 -> FontCatalog.filter(category = FontCategory.DISPLAY)
                8 -> FontCatalog.filter(category = FontCategory.SCRIPT) + FontCatalog.filter(category = FontCategory.HANDWRITING)
                9 -> FontCatalog.filter(category = FontCategory.MONO)
                10 -> FontCatalog.all().filter { FontTag.GAMING in FontCatalog.tagsFor(it.id) } +
                        FontCatalog.filter(category = FontCategory.GAMING)
                11 -> FontCatalog.all().filter { FontTag.BOLD in FontCatalog.tagsFor(it.id) }
                12 -> FontCatalog.all().filter { FontTag.MINIMAL in FontCatalog.tagsFor(it.id) }
                13 -> FontCatalog.all().filter { FontTag.ELEGANT in FontCatalog.tagsFor(it.id) }
                14 -> FontCatalog.all().filter { FontTag.RETRO in FontCatalog.tagsFor(it.id) }
                15 -> FontCatalog.filter(category = FontCategory.PIXEL)
                16 -> FontCatalog.filter(category = FontCategory.GOTHIC)
                17 -> FontCatalog.builtIns.filter { it.isOriginalRecly }
                else -> FontCatalog.all()
            }
            val unique = base.distinctBy { it.id }
            return if (query.isBlank()) unique
            else unique.filter { it.displayName.contains(query, true) || it.family.contains(query, true) }
        }

        // ── Adapter ─────────────────────────────────────────────────────────
        class FontViewHolder(val row: LinearLayout) : androidx.recyclerview.widget.RecyclerView.ViewHolder(row)

        val adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<FontViewHolder>() {
            private var items: List<FontAsset> = filteredFonts()

            fun refresh() {
                items = filteredFonts()
                notifyDataSetChanged()
            }

            override fun getItemCount() = items.size

            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): FontViewHolder {
                val row = row()
                return FontViewHolder(row)
            }

            override fun onBindViewHolder(holder: FontViewHolder, position: Int) {
                val font = items[position]
                holder.row.removeAllViews()

                // Font name + preview
                val isDownloaded = font.source != FontSource.DOWNLOADED ||
                    FontCatalog.findRemote(font.id)?.downloaded == true
                val label = action("${font.displayName}  ·  Grave seus momentos", font.id == currentId) {
                    if (isDownloaded) {
                        preferences.markRecent(font.id)
                        select(font.id)
                        dialog.dismiss()
                    } else {
                        // Trigger download, then select
                        fontRepo.ensureFont(font.id,
                            onProgress = { /* Could show progress spinner */ },
                            onComplete = { result ->
                                activity.runOnUiThread {
                                    result.onSuccess {
                                        preferences.markRecent(font.id)
                                        select(font.id)
                                        dialog.dismiss()
                                    }.onFailure {
                                        android.widget.Toast.makeText(activity,
                                            "Falha ao baixar ${font.displayName}", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }
                }.apply {
                    textSize = 18f
                    if (isDownloaded) {
                        typeface = EditorFonts.get(this@with, font.id)
                    }
                }
                holder.row.addView(label, LinearLayout.LayoutParams(0, dp(68), 1f))

                // Download status indicator
                if (!isDownloaded) {
                    val dlIcon = ImageView(activity).apply {
                        setImageResource(android.R.drawable.stat_sys_download)
                        contentDescription = "Baixar ${font.displayName}"
                    }
                    val dlFrame = FrameLayout(activity).apply {
                        val bg = GradientDrawable().apply {
                            setColor(EditorStyle.CARD)
                            setStroke(dp(1), EditorStyle.BORDER)
                            cornerRadius = dp(12).toFloat()
                        }
                        background = RippleDrawable(ColorStateList.valueOf(0x26FFFFFF), bg, null)
                        addView(dlIcon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                        setOnClickListener {
                            fontRepo.ensureFont(font.id, onComplete = { result ->
                                activity.runOnUiThread {
                                    result.onSuccess { refresh() }
                                    result.onFailure {
                                        android.widget.Toast.makeText(activity,
                                            "Falha ao baixar ${font.displayName}", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            })
                        }
                    }
                    holder.row.addView(dlFrame, LinearLayout.LayoutParams(dp(52), dp(56)).apply { marginStart = dp(6) })
                }

                // Favorite button
                val isFav = font.id in preferences.favorites()
                val favBtn = FrameLayout(activity).apply {
                    contentDescription = if (isFav) "Remover dos favoritos" else "Adicionar aos favoritos"
                    val bg = GradientDrawable().apply {
                        setColor(EditorStyle.CARD)
                        setStroke(dp(1), EditorStyle.BORDER)
                        cornerRadius = dp(12).toFloat()
                    }
                    background = RippleDrawable(ColorStateList.valueOf(0x26FFFFFF), bg, null)
                    val starIcon = ImageView(context).apply {
                        setImageResource(if (isFav) R.drawable.ic_recly_star_filled else R.drawable.ic_recly_star_outline)
                    }
                    addView(starIcon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                    setOnClickListener {
                        preferences.toggleFavorite(font.id)
                        refresh()
                    }
                }
                holder.row.addView(favBtn, LinearLayout.LayoutParams(dp(52), dp(56)).apply { marginStart = dp(6) })
            }
        }
        recyclerView.adapter = adapter

        // ── Tab buttons ─────────────────────────────────────────────────────
        tabs.forEachIndexed { index, tabLabel ->
            val tabBtn = action(tabLabel, index == selectedTab) {
                selectedTab = index
                // Rebuild tab highlight
                for (i in 0 until tabRow.childCount) {
                    (tabRow.getChildAt(i) as? Button)?.let { btn ->
                        btn.setTextColor(if (i == index) 0xFFFFFFFF.toInt() else 0x99FFFFFF.toInt())
                    }
                }
                adapter.refresh()
            }.apply {
                textSize = 13f
                setTextColor(if (index == selectedTab) 0xFFFFFFFF.toInt() else 0x99FFFFFF.toInt())
                setPadding(dp(14), dp(8), dp(14), dp(8))
            }
            tabRow.addView(tabBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(4) })
        }

        // ── Text watcher for search ─────────────────────────────────────────
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = adapter.refresh()
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })

        dialog = sheet("Galeria de fontes", body)
        adapter.refresh()
    }
}
