package com.termex.replay15.editor.autoedit

import android.content.Context
import com.termex.replay15.editor.captions.AnimatedCaptionStyles
import com.termex.replay15.editor.captions.AutoCaptionRunner
import com.termex.replay15.editor.domain.MAX_TEXTS
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.TextClip
import kotlin.coroutines.cancellation.CancellationException

/**
 * Auto Edit and Reference Style only reused captions already on the clip, so on a clip without
 * them no caption was ever made and the punch-ins had no phrases to land on. This generates them
 * first, with the same engine as the caption button.
 */
object CaptionPrep {
    data class Result(
        /** The project with the new captions (or unchanged). */
        val project: Project,
        /** Only the caption layers that were added, to merge into the live project later. */
        val added: List<TextClip>,
        /** Why captions could not be made, in plain language; null when they were made or already existed. */
        val note: String? = null,
    )

    fun hasCaptions(project: Project, clipIndex: Int): Boolean {
        val clip = project.videos.getOrNull(clipIndex) ?: return false
        val start = project.startOf(clipIndex)
        return project.texts.any { ClipAnalyzer.isCaptionOf(it, start, start + clip.durationUs) }
    }

    /** Never throws, except for cancellation. */
    fun ensure(
        context: Context,
        project: Project,
        clipIndex: Int,
        styleId: String = DEFAULT_STYLE_ID,
        progress: (String) -> Unit,
        checkCancelled: () -> Unit,
    ): Result {
        val clip = project.videos.getOrNull(clipIndex) ?: return Result(project, emptyList())
        if (clip.image || hasCaptions(project, clipIndex)) return Result(project, emptyList())
        if (project.texts.size >= MAX_TEXTS) return Result(project, emptyList(), "Legendas: o projeto ja tem o maximo de textos.")
        return try {
            progress("Gerando legendas (${AutoCaptionRunner.engineLabel()})")
            val (generated, _) = AutoCaptionRunner.generateBlocking(context, project, clipIndex, progress = progress, checkCancelled = checkCancelled)
            val known = project.texts.mapTo(HashSet()) { it.id }
            val style = AnimatedCaptionStyles.byId(styleId)
            val added = generated.texts.filter { it.id !in known }.map { text ->
                if (style == null) text else style.patch().applyTo(AnimatedCaptionStyles.withWordTiming(text))
            }
            if (added.isEmpty()) Result(project, emptyList(), "Legendas: nenhuma fala foi encontrada no clipe.")
            else Result(project.copy(texts = (project.texts + added).take(MAX_TEXTS), captionLanguage = generated.captionLanguage), added)
        } catch (error: InterruptedException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result(project, emptyList(), "Legendas: ${error.message?.takeIf(String::isNotBlank) ?: "nao foi possivel gerar"}")
        }
    }

    /** Adds [added] captions to the project as it is now (the user may have kept editing). */
    fun merge(current: Project, added: List<TextClip>): Project {
        if (added.isEmpty()) return current
        val present = current.texts.mapTo(HashSet()) { it.id }
        return current.copy(texts = (current.texts + added.filter { it.id !in present }).sortedBy { it.startUs }.take(MAX_TEXTS))
    }

    const val DEFAULT_STYLE_ID = "karaoke"
}
