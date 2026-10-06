package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.Dialog
import android.app.AlertDialog
import android.net.Uri
import android.text.InputType
import android.widget.*
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.media.MediaImport
import com.termex.replay15.editor.render.RenderPlan
import java.io.File
import java.util.concurrent.Executors

/** Track panels and imports own no player; every committed operation goes through history. */
class VideoTrackTools(
    private val activity: Activity, private val project: () -> Project, private val position: () -> Long,
    private val apply: (Project) -> Unit, private val preview: (Project) -> Unit, private val seek: (Long) -> Unit,
    private val pause: () -> Unit, private val pick: () -> Unit,
    private val select: (String, String) -> Unit, private val pickLut: (String) -> Unit,
    private val effects: (String) -> Unit,
    private val backgroundRemoval: (String) -> Unit,
    private val animations: (String) -> Unit,
) : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    private var importing = false
    private fun attempt(action: () -> Unit) {
        runCatching(action).onFailure { if (!closed) AlertDialog.Builder(activity).setTitle("Camadas")
            .setMessage(it.message ?: "Nao foi possivel alterar a camada").setPositiveButton("OK", null).show() }
    }
    fun importVideo(uri: Uri) {
        if (importing) return
        pause(); importing = true
        val at = position()
        Toast.makeText(activity, "Importando video sobreposto...", Toast.LENGTH_SHORT).show()
        worker.execute {
            val result = runCatching { MediaImport.video(activity.applicationContext, uri).copy(zoom = .4f, offsetX = .27f, offsetY = -.27f) }
            activity.runOnUiThread {
                importing = false
                if (closed || activity.isDestroyed) return@runOnUiThread
                attempt {
                    val p = project(); require(p.videoTracks.size < 7) { "Limite de sete faixas sobrepostas" }
                    val clip = result.getOrThrow(); val track = VideoTrack(clips = listOf(TimedVideoClip(at, clip)))
                    apply(p.copy(videoTracks = p.videoTracks + track,
                        trackStates = p.trackStates + (track.id to TrackState("Video ${p.videoTracks.size + 2}"))))
                    select(track.id, clip.id); seek(at); open(track.id, clip.id)
                }
            }
        }
    }
    fun menu() = with(activity) {
        pause(); val p = project(); val body = column(); lateinit var dialog: Dialog
        body.addView(sectionTitle("Video sobre video", "Toque em uma faixa para editar. A faixa mais alta aparece por cima."))
        body.addView(action("Adicionar video / facecam", true) { dialog.dismiss(); pick() })
        p.videoTracks.asReversed().forEach { track ->
            val name = p.trackState(track.id).name.ifBlank { "Video sobreposto" }
            body.addView(action("$name  /  ${track.clips.size} clipes") {
                dialog.dismiss(); val item = track.activeAt(position()) ?: track.clips.firstOrNull()
                if (item != null) { select(track.id, item.clip.id); open(track.id, item.clip.id) } else controls(track.id, name)
            })
        }
        body.addView(action("Faixa principal") { dialog.dismiss(); controls(MAIN_TRACK, "Video principal") })
        if (p.texts.isNotEmpty()) body.addView(action("Textos e legendas") { dialog.dismiss(); controls(TEXT_TRACK, "Textos") })
        if (p.stickers.isNotEmpty()) body.addView(action("Imagens e stickers") { dialog.dismiss(); controls(STICKER_TRACK, "Imagens") })
        p.audio.forEach { audio -> body.addView(action("Audio: ${audio.name}") { dialog.dismiss(); controls("audio:${audio.id}", audio.name) }) }
        dialog = sheet("Faixas", body)
    }
    fun controls(id: String, fallback: String) = with(activity) {
        pause(); val p = project(); val state = p.trackState(id); val body = column()
        val name = EditText(this).apply { setText(state.name.ifBlank { fallback }); isSingleLine = true }; body.addView(name)
        fun check(title: String, value: Boolean) = CheckBox(this).apply { text = title; isChecked = value; body.addView(this) }
        val visual = !id.startsWith("audio:")
        val visible = if (visual) check("Faixa visivel", state.visible) else null
        val mute = if (id != TEXT_TRACK && id != STICKER_TRACK) check("Silenciar audio", state.muted) else null
        val solo = check("Isolar esta faixa", state.solo)
        val locked = check("Bloquear edicao", state.locked)
        lateinit var dialog: Dialog
        body.addView(action("Aplicar controles", true) {
            attempt { apply(project().copy(trackStates = project().trackStates + (id to TrackState(name.text.toString().take(100), visible?.isChecked ?: state.visible,
                locked.isChecked, mute?.isChecked ?: state.muted, solo.isChecked)))); dialog.dismiss() }
        })
        val index = p.videoTracks.indexOfFirst { it.id == id }
        if (index >= 0 && !state.locked) {
            if (index < p.videoTracks.lastIndex) body.addView(action("Trazer uma faixa para frente") { attempt { apply(TrackEditing.reorder(project(), id, index + 1)); dialog.dismiss() } })
            if (index > 0) body.addView(action("Enviar uma faixa para tras") { attempt { apply(TrackEditing.reorder(project(), id, index - 1)); dialog.dismiss() } })
        }
        dialog = sheet("Controles da faixa", body)
    }
    fun update(trackId: String, clip: VideoClip) = attempt {
        apply(TrackEditing.update(project(), trackId, clip.id) { it.copy(clip = clip) })
    }
    fun move(trackId: String, clipId: String, timeUs: Long) = attempt {
        apply(TrackEditing.move(project(), trackId, clipId, timeUs)); select(trackId, clipId)
    }
    fun open(trackId: String, clipId: String) = with(activity) {
        pause(); val p = project()
        val track = p.videoTracks.firstOrNull { it.id == trackId } ?: return@with
        val item = track.clips.firstOrNull { it.clip.id == clipId } ?: return@with
        val clip = item.clip; select(trackId, clipId)
        if (p.trackState(trackId).locked) { controls(trackId, clip.name); return@with }
        val body = column(); lateinit var dialog: Dialog
        body.addView(sectionTitle(clip.name, "Arraste, pince ou gire a camada selecionada na previa."))
        fun field(title: String, time: Long): EditText {
            body.addView(label(title, color = EditorStyle.MUTED))
            return EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(String.format(java.util.Locale.ROOT, "%.3f", time / SECOND.toDouble())); body.addView(this) }
        }
        val start = field("Inicio na timeline (segundos)", item.startUs)
        val trimIn = field("Corte inicial no arquivo", clip.inUs); val trimOut = field("Corte final no arquivo", clip.outUs)
        var changed = clip
        if (clip.keyframes.isEmpty()) {
        slider(body, "Tamanho (%)", (clip.zoom * 100).toInt(), 400, 25) { changed = changed.copy(zoom = it / 100f) }
        slider(body, "Horizontal", (clip.offsetX * 100).toInt(), 50, -50) { changed = changed.copy(offsetX = it / 100f) }
        slider(body, "Vertical", (clip.offsetY * 100).toInt(), 50, -50) { changed = changed.copy(offsetY = it / 100f) }
        slider(body, "Girar (graus)", clip.fineRotation.toInt(), 180, -180) { changed = changed.copy(fineRotation = it.toFloat()) }
        slider(body, "Opacidade (%)", (clip.opacity * 100).toInt(), 100) { changed = changed.copy(opacity = it / 100f) }
        } else body.addView(label("Esta camada tem animacao. Use Keyframe no cursor para ajustar tamanho, posicao, giro e opacidade.", 13f, EditorStyle.MUTED))
        slider(body, "Volume (%)", (clip.volume * 100).toInt(), 200) { changed = changed.copy(volume = it / 100f) }
        fun us(field: EditText) = ((field.text.toString().replace(',', '.').toDoubleOrNull()
            ?: throw IllegalArgumentException("Digite um tempo valido")) * SECOND).toLong()
        body.addView(action("Aplicar tempo e transformacao", true) { attempt {
            val updated = changed.copy(inUs = us(trimIn), outUs = us(trimOut))
            apply(TrackEditing.update(project(), trackId, clipId) { TimedVideoClip(us(start), updated) }); dialog.dismiss()
        } })
        body.addView(action("Dividir no cursor") { attempt {
            val next = TrackEditing.split(project(), trackId, clipId, position())
            require(next != project()) { "Posicione o cursor dentro desta camada" }; apply(next); dialog.dismiss()
        } })
        body.addView(action("Filtros") { dialog.dismiss(); FilterGallery.show(this, clip, allowApplyAll = false) { filter, strength, _ -> update(trackId, clip.copy(filter = filter, filterStrength = strength)) } })
        body.addView(action("Pilha de efeitos") { dialog.dismiss(); effects(clipId) })
        body.addView(action("Remover fundo com IA") { dialog.dismiss(); backgroundRemoval(clipId) })
        body.addView(action("Animacoes de entrada, saida e loop") { dialog.dismiss(); animations(clipId) })
        body.addView(action("Velocidade e curvas") { dialog.dismiss(); SpeedTools.show(this, clip) { next ->
            apply(TrackEditing.update(project(), trackId, clipId) { it.copy(clip = next) })
        } })
        body.addView(action("Cor e LUT") {
            dialog.dismiss()
            StudioPanels.grade(
                this, clip, { update(trackId, it) }, { pickLut(clipId) },
                preview = { changed -> this@VideoTrackTools.preview(TrackEditing.update(project(), trackId, clipId) { it.copy(clip = changed) }) },
                restore = { this@VideoTrackTools.preview(project()) },
                lutDirectory = File(filesDir, "editor-luts"),
                timestampUs = RenderPlan.sourceTime(clip, item.startUs, position()),
            )
        })
        body.addView(action("Mascara e chroma key") { dialog.dismiss(); StudioPanels.masks(this, clip, apply = { update(trackId, it) }) })
        body.addView(action("Mascara profissional") {
            dialog.dismiss()
            val sourceUs = RenderPlan.sourceTime(clip, item.startUs, position())
            MaskTools.show(
                activity = this,
                initial = clip.mask,
                localTimeUs = sourceUs,
                trackingTracks = clip.trackingTracks,
                preview = { mask ->
                    val current = project()
                    preview(TrackEditing.update(current, trackId, clipId) { it.copy(clip = it.clip.copy(mask = mask)) })
                },
                apply = { mask ->
                    val current = project()
                    apply(TrackEditing.update(current, trackId, clipId) { it.copy(clip = it.clip.copy(mask = mask)) })
                },
                restore = { preview(project()) },
            )
        })
        body.addView(action("Keyframe no cursor") { dialog.dismiss(); StudioPanels.keyframe(this, clip,
            RenderPlan.sourceTime(clip, item.startUs, position()), { source -> seek(item.startUs + clip.timeMap.timelineAt(source)) }) { update(trackId, it) } })
        body.addView(action("Duplicar em nova faixa") { attempt {
            val current = project(); require(current.videoTracks.size < 7) { "Limite de sete faixas" }
            val copy = VideoTrack(clips = listOf(item.copy(clip = clip.copy(id = newId()))))
            apply(current.copy(videoTracks = current.videoTracks + copy)); dialog.dismiss()
        } })
        body.addView(action("Controles da faixa") { dialog.dismiss(); controls(trackId, clip.name) })
        body.addView(action("Excluir esta camada") {
            AlertDialog.Builder(this).setTitle("Excluir camada?").setMessage("O arquivo original continua no aparelho.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Excluir") { _, _ -> attempt {
                    val current = project(); val tracks = current.videoTracks.map { t -> if (t.id == trackId) t.copy(clips = t.clips.filter { it.clip.id != clipId }) else t }.filter { it.clips.isNotEmpty() }
                    apply(current.copy(videoTracks = tracks)); dialog.dismiss()
                } }.show()
        })
        dialog = sheet("Video sobreposto", body)
    }
    override fun close() { closed = true; worker.shutdownNow() }
}
