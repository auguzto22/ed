package com.termex.replay15.editor.highlights

import com.termex.replay15.editor.autoedit.AudioSample
import com.termex.replay15.editor.captions.CaptionDebugLog
import com.termex.replay15.editor.captions.GeminiHttpClient
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.TextClip
import com.recly.editor.engine.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Orchestrates highlight detection by combining local analysis with optional Gemini
 * semantic analysis.
 *
 * Flow:
 *  1. Extract transcript from existing captions (never generates captions)
 *  2. Collect audio energy data if available
 *  3. Try Gemini analysis (when configured and online)
 *  4. Fall back to local analysis if Gemini fails
 *  5. Merge and deduplicate results
 *
 * The detector NEVER modifies the project. It returns suggestions that the UI
 * presents to the user for explicit confirmation.
 */
class HighlightDetector(
    private val geminiAnalyzer: HighlightAnalyzer? = createGeminiAnalyzer(),
    private val localAnalyzer: HighlightAnalyzer = LocalHighlightAnalyzer(),
) {
    /**
     * Detects highlights in the project.
     *
     * @param project Current project state (read-only)
     * @param audioSamples Optional audio analysis data from ClipAnalyzer
     * @param maxHighlights Maximum number of highlights to return
     * @param checkCancelled Cancellation check callback
     * @param progress Progress reporting callback
     */
    suspend fun detect(
        project: Project,
        audioSamples: List<AudioSample> = emptyList(),
        maxHighlights: Int = 10,
        checkCancelled: () -> Unit = {},
        progress: (stage: String, completed: Int, total: Int) -> Unit = { _, _, _ -> },
    ): HighlightResult = withContext(Dispatchers.Default) {
        progress("Preparando análise...", 0, 3)
        checkCancelled()

        // Step 1: Extract transcript from existing captions
        val transcriptSegments = extractTranscript(project)
        val audioEnergy = audioSamples.mapIndexed { index, sample ->
            AudioEnergyPoint(
                timeMs = sample.startUs / 1_000L,
                rms = sample.rms,
                peak = sample.peak,
            )
        }

        val request = HighlightRequest(
            projectId = project.id,
            projectDurationMs = project.durationUs / 1_000L,
            transcriptSegments = transcriptSegments,
            audioEnergy = audioEnergy,
            languageCode = project.captionLanguage,
            maxHighlights = maxHighlights,
        )

        checkCancelled()
        progress("Analisando conteúdo...", 1, 3)

        // Step 2: Try Gemini analysis (semantic)
        var geminiResult: HighlightResult? = null
        if (geminiAnalyzer != null && transcriptSegments.isNotEmpty()) {
            geminiResult = try {
                geminiAnalyzer.analyze(request, checkCancelled)
            } catch (error: HighlightNoTranscript) {
                CaptionDebugLog.d("Highlight", "Skipping Gemini: no transcript")
                null
            } catch (error: HighlightGeminiUnavailable) {
                CaptionDebugLog.d("Highlight", "Gemini unavailable: ${error.message}")
                null
            } catch (error: HighlightDetectionException) {
                CaptionDebugLog.d("Highlight", "Gemini highlight failed: ${error.message}")
                null
            } catch (error: Throwable) {
                CaptionDebugLog.d("Highlight", "Gemini highlight error: ${error.javaClass.simpleName}")
                null
            }
        }

        checkCancelled()
        progress("Finalizando...", 2, 3)

        // Step 3: Local analysis (always runs as fallback/supplement)
        val localResult = try {
            localAnalyzer.analyze(request, checkCancelled)
        } catch (error: Throwable) {
            HighlightResult(emptyList(), HighlightSource.LOCAL, listOf("Análise local falhou: ${error.message}"))
        }

        checkCancelled()
        progress("Concluído", 3, 3)

        // Step 4: Combine results
        combineResults(geminiResult, localResult, maxHighlights)
    }

    private fun extractTranscript(project: Project): List<TranscriptSegment> {
        val captions = project.texts
            .filter { it.isCaption }
            .sortedBy { it.startUs }
        if (captions.isEmpty()) return emptyList()

        return captions.mapIndexed { index, clip ->
            TranscriptSegment(
                id = index + 1,
                text = clip.text,
                startMs = clip.startUs / 1_000L,
                endMs = clip.endUs / 1_000L,
            )
        }
    }

    private fun combineResults(
        gemini: HighlightResult?,
        local: HighlightResult,
        maxHighlights: Int,
    ): HighlightResult {
        if (gemini == null || gemini.highlights.isEmpty()) {
            return local
        }
        if (local.highlights.isEmpty()) {
            return gemini
        }

        // Gemini results are generally higher quality; prioritize them
        // Local results supplement gaps
        val combined = mutableListOf<Highlight>()

        // Add all Gemini highlights first
        combined += gemini.highlights

        // Add local highlights that don't overlap significantly with Gemini ones
        for (localHighlight in local.highlights) {
            val overlaps = combined.any { existing ->
                val overlapStart = maxOf(existing.startMs, localHighlight.startMs)
                val overlapEnd = minOf(existing.endMs, localHighlight.endMs)
                if (overlapStart >= overlapEnd) false
                else {
                    val overlapDuration = overlapEnd - overlapStart
                    val minDuration = minOf(existing.durationMs, localHighlight.durationMs)
                    overlapDuration.toFloat() / minDuration > 0.5f
                }
            }
            if (!overlaps) {
                // Slightly lower score for local-only results when combined
                combined += localHighlight.copy(
                    score = localHighlight.score * 0.8f,
                    source = HighlightSource.COMBINED,
                )
            }
        }

        val final = combined
            .sortedByDescending { it.score }
            .take(maxHighlights)

        return HighlightResult(
            highlights = final,
            source = HighlightSource.COMBINED,
            warnings = gemini.warnings + local.warnings,
        )
    }

    companion object {
        private fun createGeminiAnalyzer(): HighlightAnalyzer? {
            val key = BuildConfig.GEMINI_API_KEY.takeIf(String::isNotBlank)
                ?: com.termex.replay15.editor.ai.GeminiApiKeyConfig.getApiKey()
            return if (key.isNotBlank()) GeminiHighlightAnalyzer(key) else null
        }
    }
}

