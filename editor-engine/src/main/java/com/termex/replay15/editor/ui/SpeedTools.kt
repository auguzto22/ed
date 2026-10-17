package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.termex.replay15.editor.domain.CubicBezier
import com.termex.replay15.editor.domain.Easing
import com.termex.replay15.editor.domain.MAX_CLIP_SPEED
import com.termex.replay15.editor.domain.MIN_CLIP_SPEED
import com.termex.replay15.editor.domain.SECOND
import com.termex.replay15.editor.domain.SpeedPoint
import com.termex.replay15.editor.domain.VideoClip
import com.termex.replay15.editor.domain.formatSpeed
import com.termex.replay15.editor.speed.MotionBlurConfig
import com.termex.replay15.editor.speed.MotionBlurQuality
import com.termex.replay15.editor.speed.MotionBlurSource
import com.termex.replay15.editor.speed.NativeSpeed
import com.termex.replay15.editor.speed.SlowMotionMode
import kotlin.math.roundToLong

/**
 * Painel de velocidade: velocidade constante, curva (pontos, easing, Bézier, presets), câmera
 * lenta, motion blur e áudio — tudo editando o mesmo [VideoClip] que o motor em Rust usa. Nada
 * aqui é só visual: a curva desenhada é a amostrada pelo motor e cada controle muda o clipe da
 * prévia e da exportação.
 *
 * Durante arrastes a prévia é atualizada no máximo a cada ~60 ms em modo de busca rápida; ao
 * soltar, uma busca precisa no ponto final. Desfazer/refazer valem dentro do painel; ao aplicar,
 * a mudança inteira vira um passo do histórico do projeto.
 */
object SpeedTools {

    class Callbacks(
        /** Mostra [clip] na prévia ([interactive] = gesto em andamento: busca rápida). */
        val preview: (clip: VideoClip, keepInSync: Boolean, interactive: Boolean) -> Unit,
        /** Volta a prévia ao projeto original (painel fechado sem aplicar). */
        val restore: () -> Unit,
        /** Aplica de verdade; lança exceção se o projeto não aceitar (ex.: duração máxima). */
        val apply: (clip: VideoClip, keepInSync: Boolean) -> Unit,
        /** Posição da agulha no tempo da mídia deste clipe, para a linha do editor de curva. */
        val playheadSourceUs: () -> Long? = { null },
        /** Aviso do motor quando a técnica usada não é a escolhida (aparelho sem optical flow…). */
        val motionNotice: (VideoClip) -> String? = { null },
    )

    /** Onde o painel abre: velocidade (padrão) ou direto na seção de câmera lenta e motion blur. */
    enum class Focus { SPEED, MOTION }

    private val SEGMENT_EASINGS = listOf(
        Easing.LINEAR to "Linear", Easing.SMOOTH to "Suave", Easing.EASE_IN to "Acelerando", Easing.EASE_OUT to "Freando",
        Easing.EASE_IN_OUT to "Entrada e saída", Easing.BEZIER to "Bézier", Easing.HOLD to "Degrau",
    )

    private fun secondsLabel(us: Long): String = String.format(java.util.Locale.ROOT, "%.1fs", us / SECOND.toDouble())

    fun show(activity: Activity, clip: VideoClip, callbacks: Callbacks, focus: Focus = Focus.SPEED) = with(activity) {
        val main = Handler(Looper.getMainLooper())
        val body = column()
        lateinit var dialog: Dialog
        var committed = false
        var draft = clip
        var keepInSync = true
        var advanced = false
        val undo = ArrayDeque<VideoClip>()
        val redo = ArrayDeque<VideoClip>()
        var curveMode = clip.speedCurve.isNotEmpty()
        var lastPreset = -1
        var presetIntensity = 1f

        // ---- prévia com vazão limitada -------------------------------------------------------
        var previewPending = false
        val previewRunnable = Runnable { previewPending = false; callbacks.preview(draft, keepInSync, true) }
        fun schedulePreview(interactive: Boolean) {
            if (interactive) {
                if (!previewPending) { previewPending = true; main.postDelayed(previewRunnable, 60) }
            } else {
                main.removeCallbacks(previewRunnable); previewPending = false
                callbacks.preview(draft, keepInSync, false)
            }
        }

        // ---- cabeçalho: modo e nível -----------------------------------------------------------
        val modeRow = row()
        val levelRow = row().apply { setPadding(0, dp(6), 0, 0) }
        body.addView(modeRow); body.addView(levelRow)
        val normalPanel = column()
        val curvePanel = column()
        val motionPanel = column()
        val audioPanel = column()
        body.addView(normalPanel); body.addView(curvePanel); body.addView(motionPanel); body.addView(audioPanel)

        lateinit var refresh: () -> Unit
        fun commit(next: VideoClip, interactive: Boolean = false, record: Boolean = true) {
            if (record && next != draft) {
                undo.addLast(draft); if (undo.size > 60) undo.removeFirst()
                redo.clear()
            }
            draft = next
            refresh()
            schedulePreview(interactive)
        }

        // ---- velocidade constante --------------------------------------------------------------
        val originalDuration = clip.copy(speed = 1f, speedCurve = emptyList()).durationUs
        val durationRow = row().apply { setPadding(dp(2), dp(12), dp(2), dp(4)) }
        val durationBefore = label("Duração: ${secondsLabel(originalDuration)}", 15f, EditorStyle.MUTED)
        val durationAfter = label("", 15f, EditorStyle.TEXT).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
        durationRow.addView(durationBefore); durationRow.addView(label("  →  ", 15f, EditorStyle.ACTION_TEXT)); durationRow.addView(durationAfter)
        normalPanel.addView(durationRow)
        val ruler = SpeedRulerView(this)
        normalPanel.addView(ruler, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(4) })
        var rulerStart: VideoClip? = null
        ruler.onSpeedChange = { value ->
            if (rulerStart == null) rulerStart = draft
            commit(draft.copy(speed = value.coerceIn(MIN_CLIP_SPEED, MAX_CLIP_SPEED), speedCurve = emptyList()), interactive = true, record = false)
        }
        ruler.onRelease = { value ->
            val start = rulerStart; rulerStart = null
            if (start != null && start != draft) { undo.addLast(start); redo.clear() }
            commit(draft.copy(speed = value.coerceIn(MIN_CLIP_SPEED, MAX_CLIP_SPEED), speedCurve = emptyList()), record = false)
        }
        val (quickScroll, quick) = chipScroller()
        listOf(.1f, .25f, .5f, 1f, 2f, 4f, 8f).forEach { value ->
            quick.addChip(chip(formatSpeed(value)) { commit(draft.copy(speed = value, speedCurve = emptyList())) })
        }
        normalPanel.addView(quickScroll)

        // ---- curva -------------------------------------------------------------------------------
        curvePanel.addView(sectionTitle("Curva de velocidade", "Arraste os pontos, toque duas vezes na curva para criar um. O eixo horizontal é o arquivo original."))
        val (presetScroll, presetRow) = chipScroller()
        curvePanel.addView(presetScroll)
        val intensitySlot = column()
        curvePanel.addView(intensitySlot)
        val graph = SpeedCurveView(this)
        curvePanel.addView(graph, LinearLayout.LayoutParams(-1, dp(220)).apply { topMargin = dp(6) })
        val curveInfo = label("", 13f, EditorStyle.MUTED)
        curvePanel.addView(curveInfo)
        val pointBar = row()
        curvePanel.addView(pointBar)
        val easingTitle = label("Transição até o próximo ponto", 13f, EditorStyle.MUTED)
        curvePanel.addView(easingTitle)
        val (easingScroll, easingRow) = chipScroller()
        curvePanel.addView(easingScroll)
        val bezierPanel = column()
        curvePanel.addView(bezierPanel)
        val numericPanel = column()
        curvePanel.addView(numericPanel)

        fun points(): List<SpeedPoint> = draft.speedCurve.ifEmpty {
            listOf(SpeedPoint(draft.inUs, draft.speed), SpeedPoint(draft.outUs, draft.speed))
        }
        fun withPoints(list: List<SpeedPoint>): VideoClip = draft.copy(speedCurve = list.sortedBy { it.sourceUs })

        fun addPointAt(sourceUs: Long) {
            val list = points()
            if (list.size >= 32) { Toast.makeText(this, "Limite de 32 pontos", Toast.LENGTH_SHORT).show(); return }
            val at = sourceUs.coerceIn(0L, draft.sourceUs)
            if (list.any { kotlin.math.abs(it.sourceUs - at) < 10_000 }) return
            // A velocidade nova é a que o motor já toca ali: a curva não muda de forma ao criar o ponto.
            val point = SpeedPoint(at, withPoints(list).timeMap.curveSpeedAtSource(at).coerceIn(MIN_CLIP_SPEED, MAX_CLIP_SPEED),
                list.lastOrNull { it.sourceUs < at }?.easing ?: Easing.LINEAR)
            val next = (list + point).sortedBy { it.sourceUs }
            graph.selected = next.indexOf(point)
            commit(withPoints(next))
        }

        var dragStart: VideoClip? = null
        graph.onEdit = { list, index, finished ->
            graph.selected = index
            if (dragStart == null && !finished) dragStart = draft
            val next = runCatching { withPoints(list) }.getOrNull()
            if (next != null) {
                if (finished) {
                    val start = dragStart; dragStart = null
                    if (start != null && start != next) { undo.addLast(start); redo.clear() }
                    commit(next, record = false)
                } else commit(next, interactive = true, record = false)
            }
        }
        graph.onAddAt = ::addPointAt

        pointBar.addView(action("‹") { graph.selected = (graph.selected - 1).coerceAtLeast(0); refresh() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        pointBar.addView(action("›") { graph.selected = (graph.selected + 1).coerceAtMost(points().lastIndex); refresh() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        pointBar.addView(action("+ Ponto") { addPointAt(callbacks.playheadSourceUs() ?: ((draft.inUs + draft.outUs) / 2)) }, LinearLayout.LayoutParams(0, dp(46), 1.4f))
        pointBar.addView(action("− Ponto") {
            val list = points()
            if (list.size > 1) { commit(withPoints(list.filterIndexed { i, _ -> i != graph.selected })); graph.selected = graph.selected.coerceAtMost(list.size - 2) }
        }, LinearLayout.LayoutParams(0, dp(46), 1.4f))
        pointBar.addView(action("↶") { undo.removeLastOrNull()?.let { redo.addLast(draft); draft = it; refresh(); schedulePreview(false) } }, LinearLayout.LayoutParams(0, dp(46), 1f))
        pointBar.addView(action("↷") { redo.removeLastOrNull()?.let { undo.addLast(draft); draft = it; refresh(); schedulePreview(false) } }, LinearLayout.LayoutParams(0, dp(46), 1f))

        fun setSelected(transform: (SpeedPoint) -> SpeedPoint) {
            val list = points()
            val index = graph.selected.coerceIn(list.indices)
            runCatching { withPoints(list.mapIndexed { i, p -> if (i == index) transform(p) else p }) }.onSuccess { commit(it) }
        }

        // Valores numéricos do ponto selecionado (modo avançado).
        fun number(hint: String) = EditText(this).apply {
            this.hint = hint; isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val timeField = number("Tempo no arquivo (s)")
        val speedField = number("Velocidade (${formatSpeed(MIN_CLIP_SPEED)} a ${formatSpeed(MAX_CLIP_SPEED)})")
        val fields = row()
        fields.addView(timeField, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        fields.addView(speedField, LinearLayout.LayoutParams(0, -2, 1f))
        numericPanel.addView(fields)
        numericPanel.addView(action("Aplicar valores ao ponto") {
            runCatching {
                val seconds = timeField.text.toString().replace(',', '.').toDouble().also { require(it.isFinite()) }
                val value = speedField.text.toString().replace(',', '.').toFloat()
                val list = points()
                val index = graph.selected.coerceIn(list.indices)
                val point = list[index].copy(sourceUs = (seconds * SECOND).roundToLong(), speed = value)
                require(point.sourceUs in 0..draft.sourceUs)
                val changed = list.toMutableList().apply { set(index, point) }.sortedBy { it.sourceUs }
                require(changed.zipWithNext().all { (a, b) -> a.sourceUs < b.sourceUs })
                commit(withPoints(changed)); graph.selected = changed.indexOf(point)
            }.onFailure { timeField.error = "Use um tempo livre e velocidade entre ${formatSpeed(MIN_CLIP_SPEED)} e ${formatSpeed(MAX_CLIP_SPEED)}" }
        })

        // ---- câmera lenta e motion blur -------------------------------------------------------
        motionPanel.addView(sectionTitle("Câmera lenta", "Como criar os quadros que faltam quando o clipe fica mais lento."))
        val (modeScroll, modeChips) = chipScroller()
        motionPanel.addView(modeScroll)
        val modeDescription = label("", 12.5f, EditorStyle.MUTED)
        motionPanel.addView(modeDescription)
        val motionNotice = label("", 12.5f, EditorStyle.ACTION_TEXT)
        motionPanel.addView(motionNotice)

        motionPanel.addView(sectionTitle("Motion blur", "Rastro de movimento como numa câmera real: só o que se move é borrado, na própria direção."))
        val blurToggle = CheckBox(this).apply {
            text = "Ativar motion blur"; setTextColor(EditorStyle.TEXT); textSize = 15f
            setOnCheckedChangeListener { _, checked -> if (checked != draft.motionBlur.enabled) commit(draft.copy(motionBlur = draft.motionBlur.copy(enabled = checked))) }
        }
        motionPanel.addView(blurToggle)
        val blurPanel = column()
        motionPanel.addView(blurPanel)
        val shutterTitle = label("Obturador", 13f, EditorStyle.MUTED)
        blurPanel.addView(shutterTitle)
        val (shutterScroll, shutterChips) = chipScroller()
        blurPanel.addView(shutterScroll)
        val intensitySlider = slider(blurPanel, "Intensidade", (clip.motionBlur.intensity * 100).toInt(), 200, format = { "$it%" },
            onStop = { commit(draft.copy(motionBlur = draft.motionBlur.copy(intensity = it / 100f))) }) { value ->
            commit(draft.copy(motionBlur = draft.motionBlur.copy(intensity = value / 100f)), interactive = true, record = false)
        }
        val blurAdvanced = column()
        blurPanel.addView(blurAdvanced)
        blurAdvanced.addView(label("Qualidade", 13f, EditorStyle.MUTED))
        val (qualityScroll, qualityChips) = chipScroller()
        blurAdvanced.addView(qualityScroll)
        blurAdvanced.addView(label("Fonte do movimento", 13f, EditorStyle.MUTED))
        val (sourceScroll, sourceChips) = chipScroller()
        blurAdvanced.addView(sourceScroll)

        // ---- áudio e sincronia ------------------------------------------------------------------
        audioPanel.addView(sectionTitle("Áudio", "O som segue a velocidade e continua sincronizado com o vídeo."))
        val (audioScroll, audioChips) = chipScroller()
        audioPanel.addView(audioScroll)
        val syncBox = CheckBox(this).apply {
            text = "Manter textos, legendas e stickers sincronizados"; isChecked = true
            setTextColor(EditorStyle.TEXT); textSize = 14f
            setOnCheckedChangeListener { _, checked -> keepInSync = checked; schedulePreview(false) }
        }
        audioPanel.addView(syncBox)

        // ---- atualização de tudo a partir do rascunho -------------------------------------------
        refresh = {
            modeRow.removeAllViews()
            modeRow.addView(chip("Normal", !curveMode) {
                if (curveMode) { curveMode = false; commit(draft.copy(speedCurve = emptyList(), speed = draft.timeMap.speedRange().let { (it.start + it.endInclusive) / 2f }.coerceIn(MIN_CLIP_SPEED, MAX_CLIP_SPEED))) }
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4) })
            modeRow.addView(chip("Curva", curveMode) {
                if (!curveMode) { curveMode = true; commit(withPoints(points())) }
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(4) })
            levelRow.removeAllViews()
            levelRow.addView(chip("Básico", !advanced) { advanced = false; refresh() }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(4) })
            levelRow.addView(chip("Avançado", advanced) { advanced = true; refresh() }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(4) })

            normalPanel.visibility = if (curveMode) View.GONE else View.VISIBLE
            curvePanel.visibility = if (curveMode) View.VISIBLE else View.GONE
            durationAfter.text = secondsLabel(draft.durationUs)
            if (!curveMode && kotlin.math.abs(ruler.speed - draft.speed) > .001f) ruler.speed = draft.speed

            // Presets do motor.
            presetRow.removeAllViews()
            (0 until NativeSpeed.presetCount()).forEach { index ->
                presetRow.addChip(chip(NativeSpeed.presetLabel(index), index == lastPreset) {
                    lastPreset = index
                    runCatching { SpeedPoint.unpack(NativeSpeed.buildPreset(index, draft.inUs, draft.outUs, presetIntensity)) }
                        .onSuccess { graph.selected = 0; commit(withPoints(it)) }
                    Toast.makeText(this, NativeSpeed.presetDescription(index), Toast.LENGTH_SHORT).show()
                })
            }
            intensitySlot.removeAllViews()
            if (lastPreset >= 0) {
                slider(intensitySlot, "Intensidade do preset", (presetIntensity * 100).toInt(), 200, format = { "$it%" },
                    onStop = { value ->
                        presetIntensity = value / 100f
                        runCatching { SpeedPoint.unpack(NativeSpeed.buildPreset(lastPreset, draft.inUs, draft.outUs, presetIntensity)) }
                            .onSuccess { commit(withPoints(it)) }
                    }) { value ->
                    presetIntensity = value / 100f
                    runCatching { SpeedPoint.unpack(NativeSpeed.buildPreset(lastPreset, draft.inUs, draft.outUs, presetIntensity)) }
                        .onSuccess { commit(withPoints(it), interactive = true, record = false) }
                }
            }

            val list = points()
            graph.selected = graph.selected.coerceIn(list.indices)
            graph.points = list
            graph.clip = if (curveMode) withPoints(list) else draft
            graph.playheadSourceUs = callbacks.playheadSourceUs()
            val point = list[graph.selected]
            curveInfo.text = "Ponto ${graph.selected + 1} de ${list.size} • ${formatSpeed(point.speed)} em ${secondsLabel(point.sourceUs)} • duração ${timeLabel(draft.durationUs)}"
            easingRow.removeAllViews()
            SEGMENT_EASINGS.forEach { (easing, name) ->
                easingRow.addChip(chip(name, point.easing == easing) { setSelected { it.copy(easing = easing) } })
            }
            val last = graph.selected == list.lastIndex
            easingTitle.visibility = if (last) View.GONE else View.VISIBLE
            easingScroll.visibility = if (last) View.GONE else View.VISIBLE
            bezierPanel.removeAllViews()
            if (!last && point.easing == Easing.BEZIER && advanced) {
                val b = point.bezier
                fun bez(update: (CubicBezier, Float) -> CubicBezier, value: Float, title: String, min: Int, max: Int) =
                    slider(bezierPanel, title, (value * 100).toInt(), max, min, format = { String.format(java.util.Locale.ROOT, "%.2f", it / 100f) },
                        onStop = { setSelected { p -> p.copy(bezier = update(p.bezier, it / 100f)) } }) { }
                bez({ c, v -> c.copy(x1 = v) }, b.x1, "Bézier x1", 0, 100)
                bez({ c, v -> c.copy(y1 = v) }, b.y1, "Bézier y1", -50, 150)
                bez({ c, v -> c.copy(x2 = v) }, b.x2, "Bézier x2", 0, 100)
                bez({ c, v -> c.copy(y2 = v) }, b.y2, "Bézier y2", -50, 150)
            }
            numericPanel.visibility = if (advanced) View.VISIBLE else View.GONE
            timeField.setText(String.format(java.util.Locale.ROOT, "%.3f", point.sourceUs / SECOND.toDouble()))
            speedField.setText(String.format(java.util.Locale.ROOT, "%.2f", point.speed))

            modeChips.removeAllViews()
            SlowMotionMode.entries.forEach { mode ->
                modeChips.addChip(chip(mode.label, draft.slowMotion == mode) { commit(draft.copy(slowMotion = mode)) })
            }
            val slowest = runCatching { draft.timeMap.speedRange().start }.getOrDefault(draft.speed)
            modeDescription.text = draft.slowMotion.description +
                if (slowest * draft.fps >= 29f) "\nEste clipe tem quadros suficientes; a síntese entra quando ficar mais lento que ${formatSpeed(29f / draft.fps)}." else ""
            val notice = callbacks.motionNotice(draft)
            motionNotice.text = notice.orEmpty()
            motionNotice.visibility = if (notice.isNullOrBlank()) View.GONE else View.VISIBLE

            if (blurToggle.isChecked != draft.motionBlur.enabled) blurToggle.isChecked = draft.motionBlur.enabled
            blurPanel.visibility = if (draft.motionBlur.enabled) View.VISIBLE else View.GONE
            shutterChips.removeAllViews()
            MotionBlurConfig.SHUTTER_PRESETS.forEach { angle ->
                shutterChips.addChip(chip("${angle.toInt()}°", draft.motionBlur.shutterAngle == angle) {
                    commit(draft.copy(motionBlur = draft.motionBlur.copy(shutterAngle = angle)))
                })
            }
            if (intensitySlider.progress != (draft.motionBlur.intensity * 100).toInt()) intensitySlider.progress = (draft.motionBlur.intensity * 100).toInt()
            blurAdvanced.visibility = if (advanced) View.VISIBLE else View.GONE
            qualityChips.removeAllViews()
            MotionBlurQuality.entries.forEach { quality ->
                qualityChips.addChip(chip(quality.label, draft.motionBlur.quality == quality) { commit(draft.copy(motionBlur = draft.motionBlur.copy(quality = quality))) })
            }
            sourceChips.removeAllViews()
            MotionBlurSource.entries.forEach { source ->
                sourceChips.addChip(chip(source.label, draft.motionBlur.source == source) { commit(draft.copy(motionBlur = draft.motionBlur.copy(source = source))) })
            }

            audioChips.removeAllViews()
            audioChips.addChip(chip("Manter tom", draft.preservePitch && !draft.retimedAudioMuted) { commit(draft.copy(preservePitch = true, retimedAudioMuted = false)) })
            audioChips.addChip(chip("Tom acompanha", !draft.preservePitch && !draft.retimedAudioMuted) { commit(draft.copy(preservePitch = false, retimedAudioMuted = false)) })
            audioChips.addChip(chip("Silenciar", draft.retimedAudioMuted) { commit(draft.copy(retimedAudioMuted = true)) })
        }

        // ---- rodapé ------------------------------------------------------------------------------
        val footer = row().apply { setPadding(0, dp(12), 0, 0) }
        footer.addView(action("Restaurar 1×") { curveMode = false; commit(draft.copy(speed = 1f, speedCurve = emptyList())) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        footer.addView(action("Aplicar", accent = true) {
            main.removeCallbacks(previewRunnable)
            runCatching { callbacks.apply(draft, keepInSync) }.fold({ committed = true; dialog.dismiss() }, {
                Toast.makeText(this, it.message ?: "Não foi possível aplicar. Verifique a duração do projeto.", Toast.LENGTH_LONG).show()
            })
        }, LinearLayout.LayoutParams(0, dp(48), 1.3f))
        body.addView(footer)

        refresh()
        dialog = sheet(if (focus == Focus.MOTION) "Movimento" else "Velocidade", body, maxHeightRatio = .82f,
            subtitle = "Duração original ${secondsLabel(originalDuration)}")
        // A folha já rola o conteúdo; para o foco em movimento, desce até a seção certa.
        if (focus == Focus.MOTION) (body.parent as? android.widget.ScrollView)?.let { scroll -> scroll.post { scroll.smoothScrollTo(0, motionPanel.top) } }
        dialog.setOnDismissListener {
            main.removeCallbacks(previewRunnable)
            if (!committed) callbacks.restore()
        }
    }
}
