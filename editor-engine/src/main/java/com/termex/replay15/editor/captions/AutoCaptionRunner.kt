package com.termex.replay15.editor.captions

import android.content.Context
import com.termex.replay15.editor.domain.Project
import kotlinx.coroutines.runBlocking

/**
 * One way in for automatic captions, used by the caption button, Auto Edit and Reference Style.
 *
 * Gemini (debug key or the Recly backend) is used when configured. Without either, captions used
 * to fail outright with "Gemini API key não configurada" — the release build never carries a key.
 * They now fall back to the on-device Vosk recognizer (model downloaded once, ~50 MB), which is
 * less accurate but always available.
 */
object AutoCaptionRunner {
    /** Which engine will run, for labels in the UI. */
    fun engineLabel(): String = if (CaptionProviderFactory.isCloudConfigured()) "Gemini" else "no aparelho (Vosk)"

    /**
     * Captions for one clip. Returns the project with the new caption layers and how many were added.
     * Errors are the [CaptionGenerationException] family, as before.
     */
    suspend fun generate(
        context: Context,
        project: Project,
        clipIndex: Int,
        options: CaptionGenerationOptions,
        progress: (CaptionProgressEvent) -> Unit,
        checkCancelled: () -> Unit,
    ): Pair<Project, Int> {
        if (CaptionProviderFactory.isCloudConfigured()) {
            return GeminiCaptionGenerator(context).generate(project, clipIndex, options, progress, checkCancelled)
        }
        @Suppress("DEPRECATION")
        val offline = OfflineCaptionGenerator(context)
        // The offline engine reads the language from the project.
        val language = options.languageCode.takeIf { it in setOf("pt-BR", "en-US", "auto") } ?: "pt-BR"
        val (result, added) = offline.generate(project.copy(captionLanguage = language), clipIndex,
            { stage -> progress(CaptionProgressEvent(stage, 0, 1)) }, checkCancelled)
        return result.copy(captionLanguage = project.captionLanguage) to added
    }

    /** Blocking form for the editor's worker threads (Auto Edit / Reference Style). */
    fun generateBlocking(
        context: Context,
        project: Project,
        clipIndex: Int,
        options: CaptionGenerationOptions = CaptionGenerationOptions(languageCode = project.captionLanguage),
        progress: (String) -> Unit,
        checkCancelled: () -> Unit,
    ): Pair<Project, Int> = runBlocking {
        generate(context, project, clipIndex, options, { progress(it.stage) }, checkCancelled)
    }
}
