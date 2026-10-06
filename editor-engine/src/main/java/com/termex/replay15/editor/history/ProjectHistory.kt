package com.termex.replay15.editor.history

import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.*
import java.util.ArrayDeque

class ProjectHistory(initial: Project, private val limit: Int = 50) {
    init { require(limit > 0) }

    var current = initial.detachedSnapshot(); private set
    private val past = ArrayDeque<Project>()
    private val future = ArrayDeque<Project>()
    private var editStart: Project? = null
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()
    val isEditing get() = editStart != null

    fun apply(project: Project): Boolean {
        check(!isEditing) { "Finalize ou cancele o gesto atual antes de aplicar outra edição" }
        val snapshot = project.detachedSnapshot()
        if (snapshot == current) return false
        past.addLast(current)
        while (past.size > limit) past.removeFirst()
        future.clear(); current = snapshot
        return true
    }

    /** Starts one user gesture. Draft updates are visible but do not create history entries. */
    fun beginEdit() {
        check(!isEditing) { "Já existe um gesto de edição em andamento" }
        editStart = current
    }

    fun updateEdit(project: Project) {
        check(isEditing) { "Nenhum gesto de edição foi iniciado" }
        current = project.detachedSnapshot()
    }

    /** Updates the current project snapshot without appending to undo history (e.g. proxy hot-swaps). */
    fun updateWithoutHistory(project: Project) {
        check(!isEditing) { "Finalize ou cancele o gesto atual antes de atualizar o projeto" }
        current = project.detachedSnapshot()
    }

    /** Commits at most one history entry; returning to the original state is a no-op. */
    fun commitEdit(): Boolean {
        val start = checkNotNull(editStart) { "Nenhum gesto de edição foi iniciado" }
        editStart = null
        if (current == start) {
            current = start
            return false
        }
        past.addLast(start)
        while (past.size > limit) past.removeFirst()
        future.clear()
        return true
    }

    fun cancelEdit(): Boolean {
        val start = editStart ?: return false
        current = start
        editStart = null
        return true
    }

    fun undo(): Boolean {
        check(!isEditing) { "Finalize ou cancele o gesto antes de desfazer" }
        if (!canUndo) return false
        future.addLast(current)
        current = past.removeLast()
        return true
    }

    fun redo(): Boolean {
        check(!isEditing) { "Finalize ou cancele o gesto antes de refazer" }
        if (!canRedo) return false
        past.addLast(current)
        current = future.removeLast()
        return true
    }
}

/**
 * History/export snapshots must not share caller-owned mutable collections. Media bytes and Android
 * resources are not part of Project, so detaching metadata is bounded by the model limits.
 */
internal fun Project.detachedSnapshot(): Project = copy(
    videos = videos.map(VideoClip::detachedSnapshot),
    audio = audio.map { it.copy() },
    texts = texts.map { it.copy(mask = it.mask?.detachedSnapshot()) },
    captionVocabulary = captionVocabulary.toSet(),
    compounds = compounds.map { it.copy(childIds = it.childIds.toList()) },
    export = export.copy(),
    stickers = stickers.map { it.copy(mask = it.mask?.detachedSnapshot()) },
    markers = markers.map { it.copy() },
    videoTracks = videoTracks.map { track ->
        track.copy(clips = track.clips.map { it.copy(clip = it.clip.detachedSnapshot()) })
    },
    trackStates = trackStates.mapValues { (_, state) -> state.copy() },
)

private fun VideoClip.detachedSnapshot(): VideoClip = copy(
    crop = crop.copy(),
    keyframes = keyframes.map { it.copy(bezier = it.bezier.copy()) },
    grade = grade.copy(
        curve = grade.curve.toList(),
        hsl = grade.hsl.map { it.copy() },
        channelCurves = grade.channelCurves.map { it.toList() },
    ),
    effects = effects.map { effect ->
        effect.copy(
            values = effect.values.toMap(),
            keyframes = effect.keyframes.mapValues { (_, keys) ->
                keys.map { it.copy(bezier = it.bezier.copy()) }
            },
        )
    },
    speedCurve = speedCurve.map { it.copy() },
    mask = mask?.detachedSnapshot(),
    trackingTracks = trackingTracks.map { track -> track.copy(points = track.points.map { it.copy() }) },
)

private fun MaskState.detachedSnapshot(): MaskState = copy(
    customPath = customPath.map { it.copy() },
    keyframes = keyframes.map { it.copy(bezier = it.bezier.copy()) },
)
