package com.termex.replay15.editor.core

import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.history.ProjectHistory

class EditorSessionController(var history: ProjectHistory = ProjectHistory(Project())) {
    val project get() = history.current
    fun apply(next: Project): Boolean {
        val current = project
        if (next == current) return false
        require(!current.trackState(MAIN_TRACK).locked || current.videos == next.videos) { "Desbloqueie a faixa principal" }
        current.videoTracks.filter { current.trackState(it.id).locked }.forEach { track ->
            require(next.videoTracks.firstOrNull { it.id == track.id } == track) { "Desbloqueie a faixa para editar" }
        }
        require(!current.trackState(TEXT_TRACK).locked || current.texts == next.texts) { "Desbloqueie os textos" }
        require(!current.trackState(STICKER_TRACK).locked || current.stickers == next.stickers) { "Desbloqueie as imagens" }
        current.audio.filter { current.trackState("audio:${it.id}").locked }.forEach { audio ->
            require(next.audio.firstOrNull { it.id == audio.id } == audio) { "Desbloqueie o audio" }
        }
        val now = System.currentTimeMillis()
        return history.apply(next.copy(createdAt = current.createdAt.takeIf { it > 0 } ?: now, modifiedAt = now))
    }
}
