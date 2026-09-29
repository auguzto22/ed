package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.Dialog
import android.net.Uri
import android.text.InputType
import android.widget.*
import com.termex.replay15.editor.animation.*
import com.termex.replay15.editor.domain.*
import java.util.concurrent.Executors

class AnimationTools(private val activity: Activity, private val project: () -> Project,
    private val apply: (Project) -> Unit, private val pick: () -> Unit) : AutoCloseable {
    private val repository = AnimationRepository(activity)
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    private fun report(message: String) { if (!closed) Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
    fun open(clipId: String) {
        if (closed) return
        worker.execute {
            val result = runCatching { repository.list() }
            activity.runOnUiThread { if (!closed && !activity.isFinishing) result.fold({ library(clipId, it) }, { report("Nao foi possivel abrir as animacoes") }) }
        }
    }
    private fun library(clipId: String, definitions: List<AnimationPreset>) = with(activity) {
        val body = column(); lateinit var dialog: Dialog
        body.addView(sectionTitle("Animacoes editaveis", "Presets viram keyframes reais de video, incluindo camadas PIP. Voce pode ajustar cada ponto depois."))
        (listOf("Favoritas", "Recentes") + AnimationCategory.entries.map { it.label }).forEachIndexed { index, name ->
            body.addView(action(name) {
                val shown = when (index) { 0 -> definitions.filter { repository.favorite(it.id) }; 1 -> definitions.filter { it.id in repository.recent() }
                    else -> definitions.filter { it.category == AnimationCategory.entries[index - 2] } }
                if (shown.isEmpty()) report("Nenhuma animacao nesta colecao")
                else choiceSheet(name, shown.map { it.name }) { selected -> dialog.dismiss(); configure(clipId, shown[selected]) }
            })
        }
        body.addView(action("Importar animacao .json") { dialog.dismiss(); pick() })
        dialog = sheet("Biblioteca de animacoes", body)
    }
    private fun configure(clipId: String, preset: AnimationPreset) = with(activity) {
        val clip = project().allVideos.firstOrNull { it.id == clipId } ?: return@with
        val body = column(); lateinit var dialog: Dialog
        body.addView(sectionTitle(preset.name, "Substitui os keyframes no intervalo da animacao. Fora dele, seus pontos sao preservados. Desfazer restaura o estado anterior."))
        body.addView(label(if (preset.category == AnimationCategory.LOOP) "Periodo de cada repeticao (segundos)" else "Duracao (segundos)"))
        val duration = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (preset.category == AnimationCategory.LOOP) "2.0" else "0.6"); body.addView(this)
        }
        body.addView(action(if (repository.favorite(preset.id)) "Remover dos favoritos" else "Favoritar") { repository.toggleFavorite(preset.id); report("Favorito atualizado") })
        body.addView(action("Aplicar animacao", true) {
            runCatching {
                val seconds = duration.text.toString().replace(',', '.').toDouble().also { require(it.isFinite() && it in .05..86400.0) }
                val next = project().mapVideo(clipId) { preset.applyTo(it, (seconds * SECOND).toLong()) }
                apply(next); repository.used(preset.id)
            }.fold({ dialog.dismiss() }, { duration.error = it.message ?: "Nao foi possivel aplicar a animacao" })
        })
        body.addView(label("Licenca: ${preset.license}", 11f, EditorStyle.MUTED))
        dialog = sheet("Animacao ${preset.category.label.lowercase()}", body)
    }
    fun importPackage(uri: Uri) {
        if (closed) return
        worker.execute {
            val result = runCatching {
                activity.contentResolver.openInputStream(uri)?.use { repository.install(it) } ?: error("Arquivo indisponivel")
            }
            activity.runOnUiThread { if (!closed) result.fold({ report("Animacao instalada: ${it.name}") }, { report("Pacote recusado: ${it.message}") }) }
        }
    }
    override fun close() { closed = true; worker.shutdownNow() }
}
