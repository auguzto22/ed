package com.termex.replay15.editor.ui

import android.app.*
import android.net.Uri
import android.widget.*
import android.text.Editable
import android.text.TextWatcher
import com.termex.replay15.editor.assets.*
import com.termex.replay15.editor.domain.*
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

class EffectTools(private val activity: Activity, private val project: () -> Project, private val position: () -> Long,
    private val seek: (Long) -> Unit, private val apply: (Project) -> Unit,
    private val previewApply: (Project) -> Unit,
    private val pickPackage: () -> Unit,
    private val openFilters: ((String) -> Unit)? = null) : AutoCloseable {
    private val repository = AssetRepository(activity)
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    private var download: AssetDownloadManager? = null
    private sealed interface Target {
        data class Clip(val id: String) : Target
        data class Adjustment(val id: String) : Target
    }
    private fun report(message: String) { if (!closed) Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
    private fun safely(action: () -> Unit) { runCatching(action).onFailure { report(it.message ?: "Efeito indisponivel") } }
    private fun change(id: String, effects: List<EffectInstance>) = apply(project().mapVideo(id) { it.copy(effects = effects) })
    private fun effects(target: Target, snapshot: Project = project()): List<EffectInstance> = when (target) {
        is Target.Clip -> snapshot.allVideos.firstOrNull { it.id == target.id }?.effects
        is Target.Adjustment -> snapshot.adjustmentClips.firstOrNull { it.id == target.id }?.effects
    } ?: error("Destino de efeitos nao encontrado")
    private fun changed(target: Target, newEffects: List<EffectInstance>, preview: Boolean = false) {
        val current = project()
        val updated = when (target) {
            is Target.Clip -> current.mapVideo(target.id) { it.copy(effects = newEffects) }
            is Target.Adjustment -> current.copy(adjustmentClips = current.adjustmentClips.map {
                if (it.id == target.id) it.copy(effects = newEffects) else it
            })
        }
        if (preview) previewApply(updated) else apply(updated)
    }
    private fun sourceAt(target: Target): Long = when (target) {
        is Target.Clip -> sourceAt(target.id)
        is Target.Adjustment -> position().coerceAtLeast(0L)
    }
    private fun timelineAt(target: Target, sourceUs: Long): Long = when (target) {
        is Target.Adjustment -> sourceUs
        is Target.Clip -> {
            val p = project(); val clip = p.allVideos.first { it.id == target.id }
            val main = p.videos.indexOfFirst { it.id == target.id }
            val start = if (main >= 0) p.startOf(main) else p.videoTracks.flatMap { it.clips }.first { it.clip.id == target.id }.startUs
            start + clip.timeMap.timelineAt(sourceUs)
        }
    }
    private fun reopen(target: Target) = when (target) {
        is Target.Clip -> open(target.id)
        is Target.Adjustment -> openAdjustment(target.id)
    }
    private fun sourceAt(clipId: String): Long {
        val p = project()
        val main = p.videos.indexOfFirst { it.id == clipId }
        if (main >= 0) return p.videos[main].timeMap.sourceAt(position() - p.startOf(main))
        p.videoTracks.forEach { track -> track.clips.firstOrNull { it.clip.id == clipId }?.let { item ->
            return item.clip.timeMap.sourceAt(position() - item.startUs)
        } }
        error("Clipe nao encontrado")
    }
    fun open(clipId: String): Unit = with(activity) {
        val clip = project().allVideos.firstOrNull { it.id == clipId } ?: return@with
        val body = column(); lateinit var dialog: Dialog
        body.addView(sectionTitle("Pilha de efeitos", "Processados de cima para baixo. Todos entram na previa e na exportacao."))
        openFilters?.let { opener ->
            body.addView(action("✨ Filtros de cor (Looks com prévia real)", accent = true) {
                dialog.dismiss()
                opener(clipId)
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(6) })
        }
        body.addView(action("⚡ Presets de efeitos (com prévia real)", accent = true) {
            dialog.dismiss()
            presets(clipId)
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
        if (clip.effects.isNotEmpty()) body.addView(action("Comparar sem efeitos (bypass)") {
            previewApply(project().mapVideo(clipId) { current -> current.copy(effects = current.effects.map { it.copy(enabled = false) }) })
        })
        clip.effects.forEachIndexed { index, effect ->
            val name = runCatching { repository.resolve(effect.assetId, effect.version).first.name }.getOrDefault(effect.assetId)
            body.addView(action("${index + 1}. $name ${if (effect.enabled) "" else "(desligado)"}") { dialog.dismiss(); parameters(Target.Clip(clipId), effect) })
            val controls = row()
            controls.addView(action(if (effect.enabled) "Desligar" else "Ligar") { change(clipId, clip.effects.map { if (it.id == effect.id) it.copy(enabled = !it.enabled) else it }); dialog.dismiss(); open(clipId) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            if (index > 0) controls.addView(action("Subir") { val effects = clip.effects.toMutableList(); effects.add(index - 1, effects.removeAt(index)); change(clipId, effects); dialog.dismiss(); open(clipId) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            if (index < clip.effects.lastIndex) controls.addView(action("Descer") { val effects = clip.effects.toMutableList(); effects.add(index + 1, effects.removeAt(index)); change(clipId, effects); dialog.dismiss(); open(clipId) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            controls.addView(action("Excluir") { change(clipId, clip.effects.filter { it.id != effect.id }); dialog.dismiss(); open(clipId) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            body.addView(controls)
            if (clip.effects.size < 12) body.addView(action("Duplicar $name") {
                val duplicate = effect.copy(id = newId())
                val effects = clip.effects.toMutableList().apply { add(index + 1, duplicate) }
                change(clipId, effects); dialog.dismiss(); open(clipId)
            })
        }
        presetIntensityControls(body, Target.Clip(clipId), clip.effects) { dialog.dismiss(); open(clipId) }
        if (clip.effects.size < 12) body.addView(action("Adicionar efeito", true) { dialog.dismiss(); library(clipId) })
        if (clip.effects.size < 12) body.addView(action("Presets compostos (com prévia real)") { dialog.dismiss(); presets(clipId) })
        body.addView(action("Camadas de ajuste") { dialog.dismiss(); adjustments() })
        body.addView(action("Importar pacote .reclyfx") { dialog.dismiss(); pickPackage() })
        body.addView(action("Abrir catalogo HTTPS") { dialog.dismiss(); online() })
        dialog = sheet("Efeitos", body)
        dialog.setOnDismissListener { previewApply(project()) }
    }
    fun openAdjustment(adjustmentId: String): Unit = with(activity) {
        val adjustment = project().adjustmentClips.firstOrNull { it.id == adjustmentId } ?: return@with
        val body = column(); lateinit var dialog: Dialog
        body.addView(sectionTitle(adjustment.name,
            "${timeLabel(adjustment.startUs)}–${timeLabel(adjustment.endUs)}. A pilha e aplicada ao resultado das faixas abaixo."))
        if (adjustment.effects.isNotEmpty()) body.addView(action("Comparar sem efeitos (bypass)") {
            changed(Target.Adjustment(adjustmentId), adjustment.effects.map { it.copy(enabled = false) }, preview = true)
        })
        adjustment.effects.forEachIndexed { index, effect ->
            val name = runCatching { repository.resolve(effect.assetId, effect.version).first.name }.getOrDefault(effect.assetId)
            body.addView(action("${index + 1}. $name ${if (effect.enabled) "" else "(desligado)"}") {
                dialog.dismiss(); parameters(Target.Adjustment(adjustmentId), effect)
            })
            val controls = row()
            controls.addView(action(if (effect.enabled) "Desligar" else "Ligar") {
                changed(Target.Adjustment(adjustmentId), adjustment.effects.map {
                    if (it.id == effect.id) it.copy(enabled = !it.enabled) else it
                }); dialog.dismiss(); openAdjustment(adjustmentId)
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            if (index > 0) controls.addView(action("Subir") {
                val effects = adjustment.effects.toMutableList(); effects.add(index - 1, effects.removeAt(index))
                changed(Target.Adjustment(adjustmentId), effects); dialog.dismiss(); openAdjustment(adjustmentId)
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            if (index < adjustment.effects.lastIndex) controls.addView(action("Descer") {
                val effects = adjustment.effects.toMutableList(); effects.add(index + 1, effects.removeAt(index))
                changed(Target.Adjustment(adjustmentId), effects); dialog.dismiss(); openAdjustment(adjustmentId)
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            controls.addView(action("Excluir") {
                changed(Target.Adjustment(adjustmentId), adjustment.effects.filter { it.id != effect.id })
                dialog.dismiss(); openAdjustment(adjustmentId)
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            body.addView(controls)
        }
        presetIntensityControls(body, Target.Adjustment(adjustmentId), adjustment.effects) {
            dialog.dismiss(); openAdjustment(adjustmentId)
        }
        dialog = sheet("Camada de ajuste", body)
        dialog.setOnDismissListener { previewApply(project()) }
    }

    private fun presetIntensityControls(body: LinearLayout, target: Target, baseEffects: List<EffectInstance>, done: () -> Unit) {
        if (baseEffects.size < 2) return
        var scaled = baseEffects
        body.addView(activity.sectionTitle("Intensidade do preset", "Ajusta todos os passes mantendo a proporcao original."))
        activity.slider(body, "Intensidade global (%)", 100, 150) { percent ->
            val scale = percent / 100f
            scaled = baseEffects.map { it.copy(intensity = (it.intensity * scale).coerceIn(0f, 1f)) }
            changed(target, scaled, preview = true)
        }
        body.addView(activity.action("Aplicar intensidade") { changed(target, scaled); done() })
    }
    fun adjustments(): Unit = with(activity) {
        val snapshot = project()
        val body = column(); lateinit var dialog: Dialog
        body.addView(sectionTitle("Adjustment clips", "Aplicam uma unica pilha ao resultado das faixas abaixo, sem duplicar efeitos nos clipes."))
        snapshot.adjustmentClips.forEach { layer ->
            val names = layer.effects.map { effect ->
                runCatching { repository.resolve(effect.assetId, effect.version).first.name }.getOrDefault(effect.assetId)
            }.joinToString(" + ")
            body.addView(label("${layer.name}  ${timeLabel(layer.startUs)}–${timeLabel(layer.endUs)}\n$names", 13f))
            val controls = row()
            controls.addView(action("Editar") { dialog.dismiss(); openAdjustment(layer.id) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            controls.addView(action(if (layer.enabled) "Desligar" else "Ligar") {
                apply(project().copy(adjustmentClips = project().adjustmentClips.map {
                    if (it.id == layer.id) it.copy(enabled = !it.enabled) else it
                })); dialog.dismiss(); adjustments()
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            controls.addView(action("Excluir") {
                apply(project().copy(adjustmentClips = project().adjustmentClips.filter { it.id != layer.id }))
                dialog.dismiss(); adjustments()
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            body.addView(controls)
        }
        if (snapshot.adjustmentClips.size < 8) body.addView(action("Adicionar preset na camada", true) {
            dialog.dismiss(); adjustmentPresets()
        })
        dialog = sheet("Camadas de ajuste", body)
    }
    private fun adjustmentPresets(): Unit = with(activity) {
        val snapshot = project()
        if (snapshot.durationUs < 100_000L) { report("Adicione uma midia antes da camada de ajuste"); return@with }
        val representativeClip = snapshot.allVideos.firstOrNull() ?: return@with
        val start = position().coerceIn(0L, (snapshot.durationUs - 100_000L).coerceAtLeast(0L))
        EffectPresetGallery.show(
            activity = this,
            clip = representativeClip,
            sessionTimestampUs = start,
            onApply = { preset, intensity ->
                safely {
                    preset.nodes.forEach { repository.resolve(it.assetId, it.version) }
                    val duration = if (preset.category.equals("Gaming", true)) impactDurationUs(preset.id) else 3 * SECOND
                    val end = (start + duration).coerceAtMost(snapshot.durationUs)
                    val effects = preset.nodes.map { node ->
                        EffectInstance(
                            newId(), node.assetId, node.version,
                            intensity = (node.intensity * intensity).coerceIn(0f, 1f),
                            values = node.values
                        )
                    }
                    apply(snapshot.copy(adjustmentClips = snapshot.adjustmentClips + AdjustmentClip(
                        startUs = start, endUs = end, name = preset.name, effects = effects
                    )))
                    adjustments()
                }
            }
        )
    }
    private fun library(clipId: String) = with(activity) {
        val definitions = repository.definitions()
        val body = column()
        lateinit var dialog: Dialog
        openFilters?.let { opener ->
            body.addView(action("✨ Filtros de cor (Looks com prévia real)", accent = true) {
                dialog.dismiss()
                opener(clipId)
            }, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(6) })
        }
        body.addView(action("⚡ Presets de efeitos (com prévia real)", accent = true) {
            dialog.dismiss()
            presets(clipId)
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(6) })
        val categories = EffectCatalog.UI_CATEGORIES
        val category = Spinner(this).apply {
            adapter = ArrayAdapter(this@with, android.R.layout.simple_spinner_dropdown_item, categories)
        }
        body.addView(category, LinearLayout.LayoutParams(-1, dp(52)))
        val search = EditText(this).apply { hint = "Buscar efeito ou categoria"; isSingleLine = true }; body.addView(search)
        var shown = definitions
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        val list = ListView(this).apply { this.adapter = adapter }; body.addView(list, LinearLayout.LayoutParams(-1, dp(320)))
        fun refresh() {
            val selected = categories[category.selectedItemPosition]
            val query = search.text.toString()
            val recent = repository.recent()
            val favorites = definitions.map { it.id }.filter { repository.favorite(it) }.toSet()
            shown = EffectCatalog.filter(definitions, selected, query, recent, favorites)
            adapter.clear()
            adapter.addAll(shown.map { "${if (repository.favorite(it.id)) "★ " else ""}${it.name}  •  ${EffectCatalog.canonicalCategory(it.category)}" })
        }
        category.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, index: Int, id: Long) = refresh()
        }
        list.setOnItemClickListener { _, _, position, _ ->
            val definition = shown[position]
            val clip = project().allVideos.firstOrNull { it.id == clipId } ?: return@setOnItemClickListener
            safely { require(clip.effects.size < 12); val instance = EffectInstance(newId(), definition.id, definition.version)
                change(clipId, clip.effects + instance); repository.used(definition.id); dialog.dismiss(); parameters(clipId, instance) }
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            val selectedId = shown[position].id
            repository.toggleFavorite(selectedId)
            report(if (repository.favorite(selectedId)) "Adicionado aos favoritos" else "Removido dos favoritos")
            refresh()
            true
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refresh()
            override fun afterTextChanged(s: Editable?) {}
        })
        refresh()
        body.addView(label("Toque para aplicar. Segure para alternar favorito.", 12f, EditorStyle.MUTED))
        dialog = sheet("Biblioteca de efeitos (${definitions.size})", body)
    }
    fun presets(clipId: String) = with(activity) {
        val clip = project().allVideos.firstOrNull { it.id == clipId } ?: return@with
        val sessionTimestampUs = sourceAt(clipId)
        EffectPresetGallery.show(
            activity = this,
            clip = clip,
            sessionTimestampUs = sessionTimestampUs,
            onPreview = { preset, intensity ->
                val range = if (preset.category.equals("Gaming", true) && clip.outUs - clip.inUs >= 100_000L) {
                    val start = sessionTimestampUs.coerceIn(clip.inUs, (clip.outUs - 100_000L).coerceAtLeast(clip.inUs))
                    start to (start + impactDurationUs(preset.id)).coerceAtMost(clip.outUs)
                } else null
                val added = preset.nodes.map { node ->
                    EffectInstance(
                        id = newId(),
                        assetId = node.assetId,
                        version = node.version,
                        intensity = (node.intensity * intensity).coerceIn(0f, 1f),
                        values = node.values,
                        startTimeUs = range?.first,
                        endTimeUs = range?.second,
                    )
                }
                previewApply(project().mapVideo(clipId) { it.copy(effects = it.effects + added) })
            },
            onRestore = {
                previewApply(project())
            },
            onApply = { preset, intensity ->
                if (clip.effects.size + preset.nodes.size > 12) {
                    report("A pilha aceita no maximo 12 efeitos")
                } else safely {
                    preset.nodes.forEach { repository.resolve(it.assetId, it.version) }
                    val range = if (preset.category.equals("Gaming", true) && clip.outUs - clip.inUs >= 100_000L) {
                        val start = sessionTimestampUs.coerceIn(clip.inUs, (clip.outUs - 100_000L).coerceAtLeast(clip.inUs))
                        start to (start + impactDurationUs(preset.id)).coerceAtMost(clip.outUs)
                    } else null
                    val added = preset.nodes.map { node ->
                        EffectInstance(
                            id = newId(),
                            assetId = node.assetId,
                            version = node.version,
                            intensity = (node.intensity * intensity).coerceIn(0f, 1f),
                            values = node.values,
                            startTimeUs = range?.first,
                            endTimeUs = range?.second,
                        )
                    }
                    change(clipId, clip.effects + added)
                    open(clipId)
                }
            }
        )
    }
    private fun impactDurationUs(id: String): Long = when (id) {
        "damage" -> 180_000L
        "kill_impact", "headshot" -> 250_000L
        else -> 350_000L
    }
    private fun canonicalCategory(category: String): String = EffectCatalog.canonicalCategory(category)
    private fun parameters(target: Target, effect: EffectInstance) {
        when (target) {
            is Target.Clip -> parameters(target.id, effect)
            is Target.Adjustment -> adjustmentParameters(target, effect)
        }
    }

    private fun adjustmentParameters(target: Target.Adjustment, effect: EffectInstance): Unit = safely { with(activity) {
        val definition = repository.resolve(effect.assetId, effect.version).first
        val sourceUs = sourceAt(target)
        val body = column()
        var intensity = effect.valueAt(EffectInstance.INTENSITY, sourceUs, effect.intensity).coerceIn(0f, 1f)
        val values = definition.parameters.associate {
            it.id to effect.valueAt(it.id, sourceUs, effect.values[it.id] ?: it.default).coerceIn(it.min, it.max)
        }.toMutableMap()
        var blendMode = effect.blendMode
        fun updated() = effects(target).map {
            if (it.id == effect.id) it.copy(intensity = intensity, values = values.toMap(), blendMode = blendMode) else it
        }
        fun previewValues() = changed(target, updated(), preview = true)
        slider(body, "Intensidade (%)", (intensity * 100).toInt(), 100) { intensity = it / 100f; previewValues() }
        definition.parameters.forEach { parameter ->
            val current = requireNotNull(values[parameter.id])
            slider(body, parameter.name, ((current - parameter.min) / (parameter.max - parameter.min) * 1000).toInt(), 1000,
                format = { String.format(java.util.Locale.ROOT, "%.2f", parameter.min + (parameter.max - parameter.min) * it / 1000f) }) {
                values[parameter.id] = parameter.min + (parameter.max - parameter.min) * it / 1000f
                previewValues()
            }
        }
        val blend = Spinner(this).apply {
            adapter = ArrayAdapter(this@with, android.R.layout.simple_spinner_dropdown_item,
                EffectBlendMode.entries.map { it.name.lowercase().replaceFirstChar { char -> char.uppercase() } })
            setSelection(blendMode.ordinal)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, index: Int, id: Long) {
                    blendMode = EffectBlendMode.entries[index]; previewValues()
                }
            }
        }
        body.addView(sectionTitle("Composicao", "O ajuste continua nao destrutivo."))
        body.addView(blend, LinearLayout.LayoutParams(-1, dp(52)))
        lateinit var dialog: Dialog
        body.addView(action("Aplicar", true) { changed(target, updated()); dialog.dismiss() })
        body.addView(action("Redefinir efeito") {
            intensity = .7f; values.clear(); definition.parameters.forEach { values[it.id] = it.default }
            blendMode = EffectBlendMode.NORMAL; previewValues()
        })
        body.addView(label("Licenca: ${definition.license}", 11f, EditorStyle.MUTED))
        dialog = sheet(definition.name, body)
        dialog.setOnDismissListener { previewApply(project()) }
    } }
    private fun parameters(clipId: String, effect: EffectInstance): Unit = safely { with(activity) {
        val definition = repository.resolve(effect.assetId, effect.version).first
        val clip = project().allVideos.firstOrNull { it.id == clipId } ?: return@with
        val sourceUs = sourceAt(clipId)
        val body = column(); var intensity = effect.valueAt(EffectInstance.INTENSITY, sourceUs, effect.intensity).coerceIn(0f, 1f)
        val values = definition.parameters.associate { it.id to effect.valueAt(it.id, sourceUs, effect.values[it.id] ?: it.default).coerceIn(it.min, it.max) }.toMutableMap()
        var mask = effect.mask
        var blendMode = effect.blendMode
        var startTimeUs = effect.startTimeUs
        var endTimeUs = effect.endTimeUs
        fun previewValues() = previewApply(project().mapVideo(clipId) { current -> current.copy(effects = current.effects.map {
            if (it.id == effect.id) it.copy(intensity = intensity, values = values.toMap(), mask = mask,
                blendMode = blendMode, startTimeUs = startTimeUs, endTimeUs = endTimeUs) else it
        }) })
        slider(body, "Intensidade (%)", (intensity * 100).toInt(), 100) { intensity = it / 100f; previewValues() }
        definition.parameters.forEach { parameter ->
            val current = requireNotNull(values[parameter.id])
            slider(body, parameter.name, ((current - parameter.min) / (parameter.max - parameter.min) * 1000).toInt(), 1000,
                format = { String.format(java.util.Locale.ROOT, "%.2f", parameter.min + (parameter.max - parameter.min) * it / 1000f) }) {
                values[parameter.id] = parameter.min + (parameter.max - parameter.min) * it / 1000f
                previewValues()
            }
        }
        val blend = Spinner(this).apply {
            adapter = ArrayAdapter(this@with, android.R.layout.simple_spinner_dropdown_item,
                EffectBlendMode.entries.map { it.name.lowercase().replaceFirstChar { char -> char.uppercase() } })
            setSelection(blendMode.ordinal)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, index: Int, id: Long) {
                    blendMode = EffectBlendMode.entries[index]; previewValues()
                }
            }
        }
        body.addView(sectionTitle("Composicao", "Modo de mistura e intervalo nao alteram a midia original."))
        body.addView(blend, LinearLayout.LayoutParams(-1, dp(52)))
        val range = row()
        range.addView(action("Inicio no cursor") { startTimeUs = sourceUs; if (endTimeUs != null && endTimeUs!! <= sourceUs) endTimeUs = null; previewValues() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        range.addView(action("Fim no cursor") { if (sourceUs > (startTimeUs ?: -1L)) endTimeUs = sourceUs; previewValues() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        range.addView(action("Limpar intervalo") { startTimeUs = null; endTimeUs = null; previewValues() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        body.addView(range)
        if (definition.supportsMask) {
            body.addView(sectionTitle("Mascara", "Retangulo, elipse e gradientes com feather; os controles tambem aceitam keyframe."))
            val shapes = EffectMaskShape.entries
            val maskShape = Spinner(this).apply {
                adapter = ArrayAdapter(this@with, android.R.layout.simple_spinner_dropdown_item, shapes.map { it.name.lowercase().replace('_', ' ') })
                setSelection((mask?.shape ?: EffectMaskShape.NONE).ordinal)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                    override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, index: Int, id: Long) {
                        mask = if (shapes[index] == EffectMaskShape.NONE) null else (mask ?: EffectMask()).copy(shape = shapes[index])
                        previewValues()
                    }
                }
            }
            body.addView(maskShape, LinearLayout.LayoutParams(-1, dp(52)))
            fun updateMask(change: (EffectMask) -> EffectMask) { mask = change(mask ?: EffectMask(shape = EffectMaskShape.ELLIPSE)); previewValues() }
            slider(body, "Posicao X", (((mask?.x ?: 0f) + .5f) * 100).toInt(), 100) { updateMask { m -> m.copy(x = it / 100f - .5f) } }
            slider(body, "Posicao Y", (((mask?.y ?: 0f) + .5f) * 100).toInt(), 100) { updateMask { m -> m.copy(y = it / 100f - .5f) } }
            slider(body, "Tamanho", (((mask?.size ?: .75f) - .05f) / 1.95f * 100).toInt(), 100) { updateMask { m -> m.copy(size = .05f + it / 100f * 1.95f) } }
            slider(body, "Proporcao", (((mask?.aspect ?: 1f) - .1f) / 9.9f * 100).toInt(), 100) { updateMask { m -> m.copy(aspect = .1f + it / 100f * 9.9f) } }
            slider(body, "Feather", (((mask?.feather ?: .08f) - .001f) / .499f * 100).toInt(), 100) { updateMask { m -> m.copy(feather = .001f + it / 100f * .499f) } }
            slider(body, "Opacidade (%)", ((mask?.opacity ?: 1f) * 100f).toInt(), 100) { updateMask { m -> m.copy(opacity = it / 100f) } }
            slider(body, "Rotacao", (((mask?.rotation ?: 0f) + 180f) / 360f * 100).toInt(), 100) { updateMask { m -> m.copy(rotation = it / 100f * 360f - 180f) } }
            body.addView(action(if (mask?.invert == true) "Desativar inversao" else "Inverter mascara") { updateMask { m -> m.copy(invert = !m.invert) } })
        }
        val allKeys = effect.keyframes.values.flatten().map { it.sourceUs }.distinct().sorted()
        val nearby = allKeys.minByOrNull { abs(it - sourceUs) }?.takeIf { abs(it - sourceUs) < 20_000 }
        body.addView(sectionTitle("Animar parametros", "Cursor: ${timeLabel(project().allVideos.first { it.id == clipId }.timeMap.timelineAt(sourceUs))}. O mesmo calculo e usado na previa e exportacao."))
        val easing = Spinner(this).apply {
            adapter = ArrayAdapter(this@with, android.R.layout.simple_spinner_dropdown_item, Easing.entries.map { it.label })
            setSelection(effect.keyframes.values.flatten().firstOrNull { it.sourceUs == nearby }?.easing?.ordinal ?: Easing.SMOOTH.ordinal)
        }
        body.addView(easing, LinearLayout.LayoutParams(-1, dp(52)))
        val graph = EasingGraphView(this).apply { this.easing = Easing.entries[easing.selectedItemPosition] }
        body.addView(graph, LinearLayout.LayoutParams(-1, dp(180)))
        easing.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, index: Int, id: Long) {
                graph.easing = Easing.entries[index]; graph.invalidate()
            }
        }
        lateinit var dialog: Dialog
        body.addView(action("Aplicar", true) {
            change(clipId, clip.effects.map { if (it.id == effect.id) it.copy(intensity = intensity, values = values.toMap(),
                mask = mask, blendMode = blendMode, startTimeUs = startTimeUs, endTimeUs = endTimeUs) else it }); dialog.dismiss()
        })
        body.addView(action("Redefinir efeito") {
            intensity = .7f; values.clear(); definition.parameters.forEach { values[it.id] = it.default }
            mask = null; blendMode = EffectBlendMode.NORMAL; startTimeUs = null; endTimeUs = null
            previewValues()
        })
        body.addView(action(if (nearby == null) "Adicionar keyframes no cursor" else "Atualizar keyframes no cursor", true) {
            val selectedEasing = Easing.entries[easing.selectedItemPosition]
            val bezier = graph.bezier
            val animated = effect.keyframes.toMutableMap()
            fun put(property: String, value: Float) {
                val existing = animated[property].orEmpty().filterNot { it.sourceUs == (nearby ?: sourceUs) }
                animated[property] = (existing + EffectValueKeyframe(nearby ?: sourceUs, value, selectedEasing, bezier)).sortedBy { it.sourceUs }
            }
            put(EffectInstance.INTENSITY, intensity); values.forEach(::put)
            mask?.let { current ->
                put(EffectInstance.MASK_X, current.x); put(EffectInstance.MASK_Y, current.y)
                put(EffectInstance.MASK_SIZE, current.size); put(EffectInstance.MASK_ASPECT, current.aspect)
                put(EffectInstance.MASK_ROTATION, current.rotation); put(EffectInstance.MASK_FEATHER, current.feather)
                put(EffectInstance.MASK_OPACITY, current.opacity)
            }
            safely { change(clipId, clip.effects.map { if (it.id == effect.id) it.copy(
                intensity = intensity, values = values.toMap(), keyframes = animated, mask = mask,
                blendMode = blendMode, startTimeUs = startTimeUs, endTimeUs = endTimeUs,
            ) else it }); dialog.dismiss() }
        })
        if (nearby != null) body.addView(action("Excluir keyframes deste cursor") {
            val animated = effect.keyframes.mapValues { (_, keys) -> keys.filterNot { it.sourceUs == nearby } }.filterValues { it.isNotEmpty() }
            change(clipId, clip.effects.map { if (it.id == effect.id) it.copy(keyframes = animated) else it }); dialog.dismiss()
        })
        if (allKeys.isNotEmpty()) {
            val navigation = row()
            listOf("Anterior" to allKeys.lastOrNull { it < sourceUs }, "Proximo" to allKeys.firstOrNull { it > sourceUs }).forEach { (label, target) ->
                navigation.addView(action(label) {
                    if (target != null) {
                        val p = project(); val main = p.videos.indexOfFirst { it.id == clipId }
                        val start = if (main >= 0) p.startOf(main) else p.videoTracks.flatMap { it.clips }.first { it.clip.id == clipId }.startUs
                        dialog.dismiss(); seek(start + clip.timeMap.timelineAt(target)); parameters(clipId, effect)
                    }
                }.apply { isEnabled = target != null; alpha = if (target != null) 1f else .4f }, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            body.addView(navigation)
        }
        body.addView(label("Licenca: ${definition.license}", 11f, EditorStyle.MUTED))
        dialog = sheet(definition.name, body)
        dialog.setOnDismissListener { previewApply(project()) }
    } }
    fun importPackage(uri: Uri) {
        worker.execute {
            val temporary = File.createTempFile("effect-", ".reclyfx", activity.cacheDir)
            val result = runCatching {
                activity.contentResolver.openInputStream(uri)?.use { input -> temporary.outputStream().use { output ->
                    var total = 0L; val buffer = ByteArray(16_384)
                    while (true) { check(!closed); val n = input.read(buffer); if (n < 0) break; total += n
                        require(total <= AssetValidator.MAX_PACKAGE); output.write(buffer, 0, n) }
                } } ?: throw IllegalArgumentException("Arquivo indisponivel")
                repository.install(temporary)
            }
            temporary.delete()
            activity.runOnUiThread { result.fold({ report("Efeito instalado: ${it.name}") }, { report("Pacote recusado: ${it.message}") }) }
        }
    }
    private fun online() = with(activity) {
        val input = EditText(this).apply { hint = "https://seu-servidor/catalogo.json"; isSingleLine = true }
        AlertDialog.Builder(this).setTitle("Catalogo de efeitos").setMessage("Informe o endereco de um catalogo Recly. Nenhum video sera enviado.")
            .setView(input).setNegativeButton("Cancelar", null).setPositiveButton("Abrir") { _, _ ->
                val manager = AssetDownloadManager(); download?.cancelled?.set(true); download = manager
                val url = input.text.toString().trim()
                worker.execute {
                    val result = runCatching { manager.catalog(url) }
                    runOnUiThread { if (!closed) result.fold(::onlineList, { report("Catalogo indisponivel: ${it.message}") }) }
                }
            }.show()
    }
    private fun onlineList(assets: List<OnlineAsset>) = with(activity) {
        val body = column(); val list = ListView(this)
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, assets.map { "${it.name} / ${it.bytes / 1024} KB" })
        body.addView(list, LinearLayout.LayoutParams(-1, dp(300)))
        val status = label("Toque em um pacote para baixar", 13f); body.addView(status)
        var downloading = false
        val manager = AssetDownloadManager(); download = manager
        body.addView(action("Cancelar download") { manager.cancelled.set(true) })
        list.setOnItemClickListener { _, _, n, _ ->
            if (!downloading) {
                downloading = true; val asset = assets[n]
                manager.cancelled.set(false)
                worker.execute {
                    val file = File.createTempFile("download-", ".reclyfx", cacheDir)
                    val result = runCatching { manager.download(asset, file) { percent -> runOnUiThread { if (!closed) status.text = "${asset.name}: $percent%" } }; repository.install(file, asset) }
                    file.delete()
                    runOnUiThread { downloading = false; if (!closed) status.text = result.fold({ "Instalado: ${it.name}" }, { "Falha: ${it.message}" }) }
                }
            }
        }
        sheet("Catalogo online", body)
    }
    override fun close() { closed = true; download?.cancelled?.set(true); worker.shutdownNow() }
}
