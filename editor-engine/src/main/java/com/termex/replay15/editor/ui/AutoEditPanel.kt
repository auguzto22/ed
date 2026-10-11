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
import com.termex.replay15.editor.domain.Project
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

        // Opções: uma linha por opção, separadas por fios, com interruptor.
        sectionHeader(body, "Opções")
        val card = activity.column()
        val captions = optionRow(card, "Legendas automáticas",
            if (words.isEmpty()) "Importe um SRT temporizado para usar" else "Usa o estilo de legenda do editor",
            words.isNotEmpty(), words.isNotEmpty())
        val clean = optionRow(card, "Cortar silêncios", "Tira pausas e respirações longas", true)
        val zoom = optionRow(card, "Zoom nos destaques", "Keyframes de zoom nas falas-chave", true)
        val reframe = optionRow(card, "Auto Reframe para 9:16", "Enquadra o sujeito na vertical", true)
        val audio = optionRow(card, "Equilibrar áudio", "Nivela o volume da fala", true, last = true)
        body.addView(card, LinearLayout.LayoutParams(-1, -2))

        body.addView(activity.action("Montar minha edição  ››", true) {
            dialog.dismiss()
            generate(snapshot, index, words, AutoEditOptions(
                style = AutoEditStyle.entries[style.index],
                intensity = AutoEditIntensity.entries[intensity.index],
                cleanup = SpeechCleanup.entries[cleanup.index],
                captions = captions.isChecked,
                cleanPauses = clean.isChecked,
                smartZoom = zoom.isChecked,
                autoReframe = reframe.isChecked,
                audioEnhancement = audio.isChecked,
            ))
        }, LinearLayout.LayoutParams(-1, activity.dp(52)).apply { topMargin = activity.dp(16) })
        body.addView(activity.label("SAI COMO PROJETO EDITÁVEL · NADA É RENDERIZADO ATÉ EXPORTAR", 9.5f, EditorStyle.MUTED).apply {
            gravity = Gravity.CENTER; letterSpacing = 0.04f
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(8) })
        dialog = activity.sheet("Auto Edit", body)
    }

    private class Choice(var index: Int)

    /** Referência: bloco vermelho com o título e bloco branco "Trocar", como as colunas da referência visual. */
    private fun referenceCard(onChoose: () -> Unit): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        val red = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(EditorStyle.ACTION)
            setPadding(activity.dp(14), activity.dp(14), activity.dp(14), activity.dp(14))
        }
        red.addView(activity.label("REFERÊNCIA", 10f, EditorStyle.ON_ACTION).apply {
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL); letterSpacing = 0.14f; alpha = 0.85f
        })
        red.addView(View(activity), LinearLayout.LayoutParams(1, 0, 1f))
        red.addView(activity.label("VÍDEO DE\nREFERÊNCIA", 19f, EditorStyle.ON_ACTION).apply {
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL); setLineSpacing(0f, 0.92f)
        })
        red.addView(activity.label("OPCIONAL · CORTES, LEGENDAS E RITMO", 9.5f, EditorStyle.ON_ACTION).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); letterSpacing = 0.04f
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = activity.dp(8) })
        addView(red, LinearLayout.LayoutParams(0, activity.dp(132), 2f))

        val white = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(EditorStyle.TEXT)
            setPadding(activity.dp(14), activity.dp(14), activity.dp(14), activity.dp(14))
            isClickable = true
            isFocusable = true
            contentDescription = "Trocar vídeo de referência"
            setOnClickListener { onChoose() }
        }
        white.addView(activity.label("→", 12f, EditorStyle.INK).apply { alpha = 0.6f })
        white.addView(View(activity), LinearLayout.LayoutParams(1, 0, 1f))
        white.addView(activity.label("TRO\nCAR", 15f, EditorStyle.INK).apply {
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL); setLineSpacing(0f, 0.92f)
        })
        addView(white, LinearLayout.LayoutParams(0, activity.dp(132), 1f))
    }

    /** Legenda de seção em caixa alta com um fio que preenche a linha. */
    private fun sectionHeader(parent: LinearLayout, title: String, top: Int = 18, bottom: Int = 10) {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(activity.label(title.uppercase(), 10f, EditorStyle.TEXT).apply {
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL); letterSpacing = 0.14f
        })
        row.addView(View(activity).apply { setBackgroundColor(EditorStyle.BORDER) },
            LinearLayout.LayoutParams(0, activity.dp(1), 1f).apply { marginStart = activity.dp(10) })
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(top); bottomMargin = activity.dp(bottom) })
    }

    /** Título + botões de escolha única (o selecionado em azul), quebrando de linha como no canvas. */
    private fun chips(parent: LinearLayout, title: String, values: List<String>, initial: Int): Choice {
        val state = Choice(initial)
        sectionHeader(parent, title)
        val wrap = WrapLayout(activity, activity.dp(6))
        val views = mutableListOf<TextView>()
        fun paint() = views.forEachIndexed { i, view ->
            val on = i == state.index
            view.isSelected = on
            view.setTextColor(if (on) EditorStyle.WHITE else EditorStyle.TEXT)
            view.background = GradientDrawable().apply {
                setColor(if (on) EditorStyle.VIDEO_DEEP else EditorStyle.CARD)
                setStroke(activity.dp(1.5f), if (on) EditorStyle.VIDEO else EditorStyle.BORDER)
                cornerRadius = activity.dp(0).toFloat()
            }
        }
        values.forEachIndexed { i, value ->
            val chip = TextView(activity).apply {
                text = value
                textSize = 11f
                typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
                isAllCaps = true
                letterSpacing = 0.08f
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
        texts.addView(activity.label(name, 13f, EditorStyle.TEXT).apply {
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        })
        texts.addView(activity.label(hint.uppercase(), 9.5f, EditorStyle.MUTED).apply { letterSpacing = 0.04f })
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

    private fun generate(snapshot: Project, index: Int, words: List<TimedWord>, options: AutoEditOptions) {
        cancellation?.cancel()
        val token = AutoEditCancellation().also { cancellation = it }
        processing(true)
        val progressText = activity.label(AutoEditProgress.Inspecting.label, 15f)
        progressDialog = AlertDialog.Builder(activity)
            .setTitle("Analisando clipe")
            .setView(progressText)
            .setNegativeButton("Cancelar") { _, _ -> token.cancel() }
            .setOnCancelListener { token.cancel() }
            .show()
        worker.execute {
            val result = runCatching {
                val clip = snapshot.videos[index]
                val analysis = ClipAnalyzer.analyze(activity, clip, words, token) { stage ->
                    activity.runOnUiThread { if (!activity.isDestroyed) progressText.text = stage.label }
                }
                activity.runOnUiThread { if (!activity.isDestroyed) progressText.text = AutoEditProgress.Planning.label }
                token.check()
                val plan = AutoEditPlanner.plan(analysis, options)
                token.check()
                activity.runOnUiThread { if (!activity.isDestroyed) progressText.text = AutoEditProgress.Validating.label }
                AutoEditPlanner.validate(plan)
                token.check()
                plan to AutoEditExecutor.execute(snapshot, index, plan)
            }
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                progressDialog?.dismiss(); progressDialog = null; processing(false)
                result.fold(
                    onSuccess = { (plan, edited) -> showPreview(snapshot, index, words, options, plan, edited) },
                    onFailure = { error ->
                        if (error is InterruptedException) Toast.makeText(activity, "Auto Edit cancelado", Toast.LENGTH_SHORT).show()
                        else AlertDialog.Builder(activity).setTitle("Auto Edit").setMessage("Nao foi possivel analisar o clipe: ${error.message}").setPositiveButton("OK", null).show()
                    },
                )
            }
        }
    }

    private fun showPreview(original: Project, index: Int, words: List<TimedWord>, options: AutoEditOptions, plan: AutoEditPlan, edited: Project) {
        preview(edited)
        val body = activity.column().apply { setPadding(activity.dp(8), 0, activity.dp(8), activity.dp(8)) }
        body.addView(activity.sectionTitle("Preview pronto", plan.summary()))
        if (plan.warnings.isNotEmpty()) body.addView(activity.label(plan.warnings.joinToString("\n"), 11f, 0xFFFAFAF6.toInt()))
        val compare = activity.row()
        compare.addView(activity.action("Original") { preview(original) }, LinearLayout.LayoutParams(0, activity.dp(46), 1f).apply { rightMargin = activity.dp(4) })
        compare.addView(activity.action("Editado", true) { preview(edited) }, LinearLayout.LayoutParams(0, activity.dp(46), 1f).apply { leftMargin = activity.dp(4) })
        body.addView(compare)
        lateinit var dialog: Dialog
        body.addView(activity.action("Aplicar Auto Edit", true) {
            dialog.dismiss(); preview(original); apply(edited)
        }, LinearLayout.LayoutParams(-1, activity.dp(52)).apply { topMargin = activity.dp(10) })
        body.addView(activity.action("Gerar outra versao") {
            dialog.dismiss(); preview(original); generate(original, index, words, options.copy(variation = options.variation + 1))
        }, LinearLayout.LayoutParams(-1, activity.dp(48)).apply { topMargin = activity.dp(6) })
        body.addView(activity.action("Cancelar") { dialog.dismiss(); preview(original) }, LinearLayout.LayoutParams(-1, activity.dp(48)).apply { topMargin = activity.dp(6) })
        dialog = activity.sheet("Auto Edit", body)
        suggestionDialog = dialog
        dialog.setOnDismissListener {
            suggestionDialog = null
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
