package com.termex.replay15.editor.highlights

import com.termex.replay15.editor.domain.TimelineMarker
import com.termex.replay15.editor.domain.newId

/**
 * A detected highlight within the project timeline.
 *
 * Highlights are suggestions — they never modify the project automatically.
 * The user must explicitly confirm before any markers or cuts are applied.
 */
data class Highlight(
    val id: String = newId(),
    val startMs: Long,
    val endMs: Long,
    val reason: String,
    val score: Float,
    val source: HighlightSource = HighlightSource.LOCAL,
) {
    init {
        require(startMs >= 0 && endMs > startMs) { "Highlight range invalid" }
        require(reason.isNotBlank() && reason.length <= 500) { "Reason must be non-blank and <= 500 chars" }
        require(score in 0f..1f) { "Score must be in 0..1" }
    }

    val durationMs: Long get() = endMs - startMs

    fun toMarker(color: Int = HIGHLIGHT_MARKER_COLOR): TimelineMarker = TimelineMarker(
        id = newId(),
        timeUs = startMs * 1_000L,
        name = reason.take(100),
        color = color,
    )

    companion object {
        const val HIGHLIGHT_MARKER_COLOR = 0xFFFFC66D.toInt()
    }
}

/** Where the highlight was detected from. */
enum class HighlightSource {
    /** Local audio energy + silence analysis */
    LOCAL,
    /** Gemini semantic analysis */
    GEMINI,
    /** Combined local + Gemini */
    COMBINED,
}

/**
 * Request for highlight detection.
 *
 * The transcript is optional — when captions exist, their text is sent.
 * When no captions exist, only local audio/motion analysis is performed.
 */
data class HighlightRequest(
    val projectId: String,
    val projectDurationMs: Long,
    val transcriptSegments: List<TranscriptSegment> = emptyList(),
    val audioEnergy: List<AudioEnergyPoint> = emptyList(),
    val languageCode: String = "pt-BR",
    val maxHighlights: Int = 10,
    val minDurationMs: Long = 3_000L,
    val maxDurationMs: Long = 30_000L,
) {
    init {
        require(projectDurationMs > 0)
        require(maxHighlights in 1..50)
        require(minDurationMs in 1_000L..10_000L)
        require(maxDurationMs in minDurationMs..120_000L)
        require(transcriptSegments.size <= 5_000)
        require(audioEnergy.size <= 50_000)
    }
}

/** A transcript segment with timing. IDs are stable references — Gemini receives only these. */
data class TranscriptSegment(
    val id: Int,
    val text: String,
    val startMs: Long,
    val endMs: Long,
) {
    init {
        require(text.isNotBlank() && startMs >= 0 && endMs > startMs)
    }
}

/** Audio energy at a point in time (from ClipAnalyzer). */
data class AudioEnergyPoint(
    val timeMs: Long,
    val rms: Float,
    val peak: Float,
) {
    init {
        require(timeMs >= 0 && rms >= 0f && peak >= 0f)
    }
}

/** Result of highlight detection. */
data class HighlightResult(
    val highlights: List<Highlight>,
    val source: HighlightSource,
    val warnings: List<String> = emptyList(),
) {
    init {
        require(highlights.size <= 50)
    }
}

/** Provider boundary for highlight analysis. The editor does not know which backend was used. */
interface HighlightAnalyzer {
    suspend fun analyze(request: HighlightRequest, checkCancelled: () -> Unit): HighlightResult
}

/** Exceptions specific to highlight detection. */
open class HighlightDetectionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class HighlightNoTranscript(message: String = "Este projeto não tem legendas para analisar.") : HighlightDetectionException(message)
class HighlightGeminiUnavailable(message: String = "Não foi possível acessar o serviço de IA.", cause: Throwable? = null) : HighlightDetectionException(message, cause)

