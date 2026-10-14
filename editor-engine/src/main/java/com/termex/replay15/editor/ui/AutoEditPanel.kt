package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.termex.replay15.editor.autoedit.*
import com.termex.replay15.editor.captions.AnimatedCaptionStyles
import com.termex.replay15.editor.captions.CaptionBackendNotConfigured
import com.termex.replay15.editor.captions.CaptionGenerationOptions
import com.termex.replay15.editor.captions.GeminiCaptionGenerator
import com.termex.replay15.editor.captions.InvalidApiKey
import com.termex.replay15.editor.captions.NoInternet
import com.termex.replay15.editor.captions.QuotaExceeded
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.TextClip
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AutoEditPanel(
    private val activity: Activity,
    private val project: () -> Project,
    private val selectedIndex: () -> Int,
    private val preview: (Project) -> Unit,
    private val apply: (Project) -> Unit,
    private val processing: (Boolean) -> Unit,
    /** Abre a análise de um vídeo de referência (cartão "Referência" do canvas). */
    private val openReference: (() -> Unit)? = null,
) {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var cancellation: AutoEditCancellation? = null
    private var progressDialog: AlertDialog? = null
    private var suggestionDialog: Dialog? = null
    private var closing = false

    fun open() {
        val snapshot = project()
        val index = selectedIndex()
        val clip = snapshot.videos.getOrNull(index) ?: run {
            Toast.makeText(activity, "Selecione um clipe existente", Toast.LENGTH_SHORT).show(); return
        }
        val words = ClipAnalyzer.wordsFromCaptions(snapshot.texts, snapshot.startOf(index), clip.durationUs)
        val body = activity.column().apply { setPadding(activity.dp(8), 0, activity.dp(8), activity.dp(10)) }
        lateinit var dialog: Dialog

        // Referência (canvas): de onde vem o ritmo da edição.
        body.addView(referenceCard { dialog.dismiss(); openReference?.invoke() }, LinearLayout.LayoutParams(-1, -2))

        // Preset (canvas): o estilo da edição, em botões que quebram de linha.
        val style = chips(body, "Preset", AutoEditStyle.entries.map { it.label }, AutoEditStyle.DYNAMIC.ordinal)
        val intensity = chips(body, "Intensidade", AutoEditIntensity.entries.map { it.label }, AutoEditIntensity.BALANCED.ordinal)
        val cleanup = chips(body, "Limpeza de pausas", SpeechCleanup.entries.map { it.label }, SpeechCleanup.BALANCED.ordinal)

        // Opções (canvas): cartão com uma linha por opção e um interruptor.
        val card = activity.column().apply {
            background = GradientDrawable().apply {
                setColor(EditorStyle.CARD)
                setStroke(activity.dp(1.5f), EditorStyle.BORDER)
                cornerRadius = activity.dp(12).toFloat()
            }
            clipToOutline = true
        }
        val clipStart = snapshot.startOf(index)
        // Generated captions (word timing) are already split and styled: Auto Edit keeps them and
        // moves them through the cuts. Only untimed captions (imported SRT) get re-split.
        val timedCaptions = snapshot.texts.any { ClipAnalyzer.isCaptionOf(it, clipStart, clipStart + clip.durationUs) && it.wordCues.isNotEmpty() }
        val captions = optionRow(card, "Legendas automáticas",
            when {
                words.isEmpty() -> "Transcreve a fala com IA e cria as legendas"
                timedCaptions -> "Usa suas legendas; o estilo podcast troca o visual delas"
                else -> "Divide as legendas em frases curtas no estilo atual"
            },
            true)
        // Estilo da referência de podcast: 1 a 3 palavras, CAIXA ALTA, palavra falada em amarelo.
        val podcastCaptions = optionRow(card, "Legendas estilo podcast",
            "Frases curtas em caixa alta com a palavra falada em amarelo", true)
        val broll = optionRow(card, "Imagens da internet (B-roll)",
            if (snapshot.videoTracks.size >= AutoEditExecutor.MAX_VIDEO_TRACKS) "Limite de camadas atingido: remova uma camada para usar"
            else "Quando a fala cita algo (dinheiro, trabalho...), mostra uma foto",
            snapshot.videoTracks.size < AutoEditExecutor.MAX_VIDEO_TRACKS, snapshot.videoTracks.size < AutoEditExecutor.MAX_VIDEO_TRACKS)
        val clean = optionRow(card, "Cortar silêncios", "Tira pausas e respirações longas", true)
        val zoom = optionRow(card, "Zoom nos destaques", "Keyframes de zoom nas falas-chave", true)
        val reframe = optionRow(card, "Auto Reframe para 9:16", "Enquadra o sujeito na vertical", true)
        val audio = optionRow(card, "Equilibrar áudio", "Nivela o volume da fala", true, last = true)
        body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(16) })

        body.addView(activity.action("Montar minha edição", true) {
            dialog.dismiss()
            generate(snapshot, index, words, AutoEditOptions(
                style = AutoEditStyle.entries[style.index],
                intensity = AutoEditIntensity.entries[intensity.index],
                cleanup = SpeechCleanup.entries[cleanup.index],
                // Timed captions are only rebuilt for the podcast look; otherwise they are kept as they are.
                captions = captions.isChecked && (!timedCaptions || podcastCaptions.isChecked),
                cleanPauses = clean.isChecked,
                smartZoom = zoom.isChecked,
                autoReframe = reframe.isChecked,
                audioEnhancement = audio.isChecked,
                podcastCaptions = captions.isChecked && podcastCaptions.isChecked,
                broll = broll.isChecked,
                transcribe = true,
            ))
        }, LinearLayout.LayoutParams(-1, activity.dp(52)).apply { topMargin = activity.dp(16) })
        body.addView(activity.label("Sai como projeto editável — nada é renderizado até exportar.", 12f, EditorStyle.MUTED).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(8) })
        dialog = activity.sheet("Auto Edit", body)
    }

    private class Choice(var index: Int)

    /** Cartão "Referência" do canvas: etiqueta azul, título, linha de apoio e o botão para trocar. */
    private fun referenceCard(onChoose: () -> Unit): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(activity.dp(10), activity.dp(10), activity.dp(10), activity.dp(10))
        background = GradientDrawable().apply {
            setColor(EditorStyle.CARD)
            setStroke(activity.dp(1.5f), EditorStyle.BORDER)
            cornerRadius = activity.dp(14).toFloat()
        }
        addView(View(activity).apply {
            background = GradientDrawable().apply {
                setColor(0xFF2E2B34.toInt())
                setStroke(activity.dp(1.5f), EditorStyle.BORDER)
                cornerRadius = activity.dp(8).toFloat()
            }
        }, LinearLayout.LayoutParams(activity.dp(56), activity.dp(96)))
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(12), 0, activity.dp(8), 0)
        }
        texts.addView(activity.label("REFERÊNCIA", 11f, EditorStyle.VIDEO).apply {
            typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f
        })
        texts.addView(activity.label("Vídeo de referência", 15f, EditorStyle.TEXT).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = activity.dp(4) })
        texts.addView(activity.label("Opcional: leia cortes, legendas e ritmo de um vídeo que vende.", 12f, EditorStyle.MUTED),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = activity.dp(4) })
        addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(activity).apply {
            text = "Escolher"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(EditorStyle.TEXT)
            gravity = Gravity.CENTER
            setPadding(activity.dp(12), 0, activity.dp(12), 0)
            background = GradientDrawable().apply {
                setColor(EditorStyle.BG)
                setStroke(activity.dp(1.5f), EditorStyle.BORDER)
                cornerRadius = activity.dp(10).toFloat()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onChoose() }
        }, LinearLayout.LayoutParams(-2, activity.dp(38)))
    }

    /** Título + botões de escolha única (o selecionado em azul), quebrando de linha como no canvas. */
    private fun chips(parent: LinearLayout, title: String, values: List<String>, initial: Int): Choice {
        val state = Choice(initial)
        parent.addView(activity.label(title, 18f, EditorStyle.TEXT).apply {
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(16); bottomMargin = activity.dp(8) })
        val wrap = WrapLayout(activity, activity.dp(8))
        val views = mutableListOf<TextView>()
        fun paint() = views.forEachIndexed { i, view ->
            val on = i == state.index
            view.isSelected = on
            view.setTextColor(if (on) EditorStyle.WHITE else EditorStyle.TEXT)
            view.background = GradientDrawable().apply {
                setColor(if (on) EditorStyle.VIDEO_DEEP else EditorStyle.CARD)
                setStroke(activity.dp(1.5f), if (on) EditorStyle.VIDEO else EditorStyle.BORDER)
                cornerRadius = activity.dp(10).toFloat()
            }
        }
        values.forEachIndexed { i, value ->
            val chip = TextView(activity).apply {
                text = value
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(activity.dp(14), 0, activity.dp(14), 0)
                isClickable = true
                isFocusable = true
                contentDescription = "$title: $value"
                setOnClickListener { state.index = i; paint() }
            }
            views.add(chip)
            wrap.addView(chip, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(40)))
        }
        paint()
        parent.addView(wrap, LinearLayout.LayoutParams(-1, -2))
        return state
    }

    /** Linha do cartão de opções: nome, apoio e interruptor (vermelho quando ligado). */
    private fun optionRow(card: LinearLayout, name: String, hint: String, checked: Boolean, enabled: Boolean = true, last: Boolean = false): Switch {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = activity.dp(54)
            setPadding(activity.dp(12), activity.dp(6), activity.dp(12), activity.dp(6))
        }
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(activity.label(name, 15f, EditorStyle.TEXT).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        texts.addView(activity.label(hint, 12f, EditorStyle.MUTED))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = Switch(activity).apply {
            isChecked = checked
            isEnabled = enabled
            contentDescription = name
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            trackTintList = ColorStateList(states, intArrayOf(EditorStyle.ACTION, EditorStyle.BORDER))
            thumbTintList = ColorStateList(states, intArrayOf(EditorStyle.WHITE, EditorStyle.TEXT))
        }
        row.addView(toggle, LinearLayout.LayoutParams(-2, -2))
        row.setOnClickListener { if (toggle.isEnabled) toggle.toggle() }
        card.addView(row, LinearLayout.LayoutParams(-1, -2))
        if (!last) card.addView(View(activity).apply { setBackgroundColor(EditorStyle.BORDER) }, LinearLayout.LayoutParams(-1, activity.dp(1.5f)))
        return toggle
    }

    /** Distribui os filhos em linhas, quebrando quando não cabe (botões de preset). */
    private class WrapLayout(context: Context, private val gap: Int) : ViewGroup(context) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val maxWidth = MeasureSpec.getSize(widthSpec)
            var x = 0
            var y = 0
            var rowHeight = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == GONE) continue
                measureChild(child, MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST), heightSpec)
                if (x > 0 && x + child.measuredWidth > maxWidth) { x = 0; y += rowHeight + gap; rowHeight = 0 }
                x += child.measuredWidth + gap
                rowHeight = maxOf(rowHeight, child.measuredHeight)
            }
            setMeasuredDimension(maxWidth, y + rowHeight)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val maxWidth = r - l
            var x = 0
            var y = 0
            var rowHeight = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == GONE) continue
                if (x > 0 && x + child.measuredWidth > maxWidth) { x = 0; y += rowHeight + gap; rowHeight = 0 }
                child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
                x += child.measuredWidth + gap
                rowHeight = maxOf(rowHeight, child.measuredHeight)
            }
        }
    }

    /** What the worker hands back to the UI thread. */
    private class Prepared(val plan: AutoEditPlan, val generatedCaptions: List<TextClip>, val words: List<TimedWord>)

    /**
     * @param pretranscribed captions already transcribed for this clip (placed on [snapshot]'s
     * timeline) by an earlier run; "Gerar outra versão" reuses them instead of paying again.
     */
    private fun generate(
        snapshot: Project, index: Int, words: List<TimedWord>, options: AutoEditOptions,
        pretranscribed: List<TextClip> = emptyList(),
    ) {
        cancellation?.cancel()
        val token = AutoEditCancellation().also { cancellation = it }
        // The analysis runs beside the editor in a floating card; editing stays available.
        val card = EditorOverlayHost.task("Analisando clipe") { token.cancel() }
        val fallbackText = activity.label(AutoEditProgress.Inspecting.label, 15f)
        if (card == null) {
            processing(true)
            progressDialog = AlertDialog.Builder(activity)
                .setTitle("Analisando clipe")
                .setView(fallbackText)
                .setNegativeButton("Cancelar") { _, _ -> token.cancel() }
                .setOnCancelListener { token.cancel() }
                .show()
        }
        fun stage(label: String, progress: Float) = activity.runOnUiThread {
            if (activity.isDestroyed) return@runOnUiThread
            card?.update(label, progress) ?: run { fallbackText.text = label }
        }
        stage(AutoEditProgress.Inspecting.label, .05f)
        val clip = snapshot.videos[index]
        val snapshotStart = snapshot.startOf(index)
        // Images already on disk for this run; deleted again if the run does not reach the preview.
        val downloaded = java.util.concurrent.CopyOnWriteArrayList<BrollDecision>()
        worker.execute {
            val result = runCatching {
                val notes = mutableListOf<String>()
                var clipWords = words
                var generated = pretranscribed
                if (generated.isNotEmpty() && clipWords.isEmpty()) {
                    clipWords = ClipAnalyzer.wordsFromCaptions(generated, snapshotStart, clip.durationUs)
                }
                // No timed text yet: transcribe with the caption service, so captions and B-roll
                // follow what is actually said. A failure only removes those two features.
                if (options.transcribe && clipWords.isEmpty() && (options.captions || options.broll) && !clip.image) {
                    stage(AutoEditProgress.Transcribing.label, .06f)
                    try {
                        generated = transcribe(snapshot, index, token) { label -> stage(label, .12f) }
                        clipWords = ClipAnalyzer.wordsFromCaptions(generated, snapshotStart, clip.durationUs)
                    } catch (error: InterruptedException) {
                        throw error
                    } catch (error: Exception) {
                        notes += "Transcrição indisponível: ${transcriptionMessage(error)}"
                    }
                }
                token.check()
                val analysis = ClipAnalyzer.analyze(activity, clip, clipWords, token) { progress ->
                    stage(progress.label, when (progress) {
                        AutoEditProgress.Audio -> .25f
                        AutoEditProgress.Visuals -> .45f
                        else -> .2f
                    })
                }
                token.check()
                var suggestions: List<BrollSuggestion>? = null
                if (options.broll && analysis.words.isNotEmpty()) {
                    val key = com.termex.replay15.editor.ai.GeminiApiKeyConfig.getApiKey()
                    if (key.isNotBlank()) {
                        stage("Escolhendo momentos para imagens", .55f)
                        val ai = BrollAiSuggester(key)
                        suggestions = ai.suggest(analysis.words, analysis.durationUs, snapshot.captionLanguage, token)
                        if (suggestions == null) notes += "IA de imagens indisponível; usei as palavras-chave da fala"
                    }
                }
                stage(AutoEditProgress.Planning.label, .62f)
                token.check()
                var plan = AutoEditPlanner.plan(analysis, options, suggestions)
                if (plan.brolls.isNotEmpty()) {
                    stage(AutoEditProgress.Broll.label, .66f)
                    val fetched = BrollImageFetcher(activity, BrollSources.default()).resolve(
                        plan.brolls, AutoEditExecutor.canvasAspectAfter(snapshot, plan), token,
                    ) { done, total -> stage("${AutoEditProgress.Broll.label} ($done/$total)", .66f + .24f * done / total.coerceAtLeast(1)) }
                    downloaded += fetched.decisions
                    plan = plan.copy(brolls = fetched.decisions, warnings = plan.warnings + fetched.warnings)
                }
                plan = plan.copy(warnings = plan.warnings + notes)
                token.check()
                stage(AutoEditProgress.Validating.label, .92f)
                AutoEditPlanner.validate(plan)
                token.check()
                Prepared(plan, if (options.captions) generated else emptyList(), clipWords)
            }
            activity.runOnUiThread {
                fun discard() = BrollImageFetcher.discard(activity, downloaded)
                if (activity.isDestroyed) { discard(); return@runOnUiThread }
                if (card == null) { progressDialog?.dismiss(); progressDialog = null; processing(false) }
                result.fold(
                    onSuccess = { prepared ->
                        val plan = prepared.plan
                        // The user may have kept editing: apply the plan to the project as it is now,
                        // as long as the analysed clip itself was not changed.
                        val current = project()
                        val currentIndex = current.videos.indexOfFirst { it.id == clip.id }
                        if (currentIndex < 0 || current.videos[currentIndex] != clip) {
                            discard()
                            card?.fail("O clipe mudou durante a análise. Analise de novo.")
                                ?: Toast.makeText(activity, "O clipe mudou durante a analise", Toast.LENGTH_SHORT).show()
                            return@fold
                        }
                        val shift = current.startOf(currentIndex) - snapshotStart
                        val captionsHere = AutoEditExecutor.shiftedCaptions(prepared.generatedCaptions, shift)
                        val edited = runCatching {
                            AutoEditExecutor.execute(AutoEditExecutor.withCaptions(current, captionsHere), currentIndex, plan)
                        }.getOrElse { error ->
                            discard()
                            card?.fail("Não foi possível montar a edição: ${error.message}")
                                ?: Toast.makeText(activity, "Nao foi possivel montar a edicao: ${error.message}", Toast.LENGTH_LONG).show()
                            return@fold
                        }
                        val show = { showPreview(current, currentIndex, prepared.words, options, plan, edited, captionsHere) }
                        if (card != null) card.succeed(plan.summary().lineSequence().firstOrNull().orEmpty().ifBlank { "Edição pronta" }, "Ver resultado") { show() }
                        else show()
                    },
                    onFailure = { error ->
                        discard()
                        if (error is InterruptedException) {
                            card?.fail("Análise cancelada") ?: Toast.makeText(activity, "Auto Edit cancelado", Toast.LENGTH_SHORT).show()
                        } else if (card != null) card.fail("Não foi possível analisar: ${error.message}")
                        else AlertDialog.Builder(activity).setTitle("Auto Edit").setMessage("Nao foi possivel analisar o clipe: ${error.message}").setPositiveButton("OK", null).show()
                    },
                )
            }
        }
    }

    /** Transcribes the clip with the app's caption service; returns only the new caption layers. */
    private fun transcribe(snapshot: Project, index: Int, token: AutoEditCancellation, onStage: (String) -> Unit): List<TextClip> {
        val options = CaptionGenerationOptions(
            languageCode = snapshot.captionLanguage,
            maxWords = PodcastCaptions.MAX_WORDS,
            maxLines = 1,
        )
        val (updated, _) = kotlinx.coroutines.runBlocking {
            GeminiCaptionGenerator(activity.applicationContext).generate(snapshot, index, options, { event -> onStage(event.stage) }, token::check)
        }
        val known = snapshot.texts.mapTo(HashSet()) { it.id }
        val added = updated.texts.filter { it.id !in known }
        require(added.isNotEmpty()) { "nenhuma fala reconhecida" }
        // Readable default look (white, black outline); the podcast option restyles them anyway.
        val look = AnimatedCaptionStyles.byId("none")
        return if (look == null) added else added.map { look.patch().applyTo(it) }
    }

    private fun transcriptionMessage(error: Throwable): String = when (error) {
        is CaptionBackendNotConfigured -> "serviço de legendas não configurado neste build"
        is InvalidApiKey, is NoInternet, is QuotaExceeded -> error.message ?: "serviço indisponível"
        else -> error.message?.takeIf(String::isNotBlank) ?: "tente novamente mais tarde"
    }

    private fun showPreview(
        original: Project, index: Int, words: List<TimedWord>, options: AutoEditOptions, plan: AutoEditPlan, edited: Project,
        generatedCaptions: List<TextClip>,
    ) {
        preview(edited)
        val body = activity.column().apply { setPadding(activity.dp(8), 0, activity.dp(8), activity.dp(8)) }
        body.addView(activity.sectionTitle("Preview pronto", plan.summary()))
        if (plan.warnings.isNotEmpty()) body.addView(activity.label(plan.warnings.joinToString("\n"), 11f, 0xFFFAFAF6.toInt()))
        val compare = activity.row()
        compare.addView(activity.action("Original") { preview(original) }, LinearLayout.LayoutParams(0, activity.dp(46), 1f).apply { rightMargin = activity.dp(4) })
        compare.addView(activity.action("Editado", true) { preview(edited) }, LinearLayout.LayoutParams(0, activity.dp(46), 1f).apply { leftMargin = activity.dp(4) })
        body.addView(compare)
        lateinit var dialog: Dialog
        // Downloaded B-roll images stay on disk only if this edit is applied.
        var keepImages = false
        body.addView(activity.action("Aplicar Auto Edit", true) {
            keepImages = true
            dialog.dismiss(); preview(original); apply(edited)
        }, LinearLayout.LayoutParams(-1, activity.dp(52)).apply { topMargin = activity.dp(10) })
        body.addView(activity.action("Gerar outra versao") {
            dialog.dismiss(); preview(original)
            generate(original, index, words, options.copy(variation = options.variation + 1), generatedCaptions)
        }, LinearLayout.LayoutParams(-1, activity.dp(48)).apply { topMargin = activity.dp(6) })
        body.addView(activity.action("Cancelar") { dialog.dismiss(); preview(original) }, LinearLayout.LayoutParams(-1, activity.dp(48)).apply { topMargin = activity.dp(6) })
        dialog = activity.sheet("Auto Edit", body)
        suggestionDialog = dialog
        dialog.setOnDismissListener {
            suggestionDialog = null
            if (!keepImages) BrollImageFetcher.discard(activity, plan.placedBrolls)
            if (!closing && !activity.isDestroyed) preview(original)
        }
    }

    fun close() {
        closing = true
        cancellation?.cancel(); progressDialog?.dismiss(); progressDialog = null
        suggestionDialog?.dismiss(); suggestionDialog = null
        worker.shutdownNow()
    }
}
