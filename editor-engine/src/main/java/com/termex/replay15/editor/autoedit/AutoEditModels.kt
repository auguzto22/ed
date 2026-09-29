package com.termex.replay15.editor.autoedit

import com.termex.replay15.editor.domain.SECOND

const val AUTO_EDIT_ALGORITHM_VERSION = 1

enum class AutoEditStyle(val label: String) {
    CLEAN("Clean"), DYNAMIC("Dynamic"), GAMING("Gaming"), CINEMATIC("Cinematic"), PODCAST("Podcast"), SHORTS("Shorts")
}

enum class AutoEditIntensity(val label: String, val factor: Float) {
    SUBTLE("Subtle", .72f), BALANCED("Balanced", 1f), STRONG("Strong", 1.28f)
}

enum class SpeechCleanup(val label: String) {
    NATURAL("Natural"), BALANCED("Balanced"), FAST("Fast")
}

data class AutoEditOptions(
    val style: AutoEditStyle = AutoEditStyle.DYNAMIC,
    val intensity: AutoEditIntensity = AutoEditIntensity.BALANCED,
    val cleanup: SpeechCleanup = SpeechCleanup.BALANCED,
    val captions: Boolean = true,
    val cleanPauses: Boolean = true,
    val smartZoom: Boolean = true,
    val autoReframe: Boolean = true,
    val audioEnhancement: Boolean = true,
    val variation: Int = 0,
)

data class TimedWord(val text: String, val startUs: Long, val endUs: Long, val confidence: Float = 1f) {
    init { require(text.isNotBlank() && startUs >= 0 && endUs > startUs && confidence in 0f..1f) }
}

data class AudioSample(val startUs: Long, val endUs: Long, val rms: Float, val peak: Float) {
    init { require(startUs >= 0 && endUs > startUs && rms >= 0f && peak >= 0f) }
}

data class MotionSample(val timeUs: Long, val amount: Float, val focusX: Float = .5f, val focusY: Float = .5f) {
    init { require(timeUs >= 0 && amount >= 0f && focusX in 0f..1f && focusY in 0f..1f) }
}

data class ClipAnalysis(
    val clipId: String,
    val durationUs: Long,
    val audio: List<AudioSample> = emptyList(),
    val motion: List<MotionSample> = emptyList(),
    val words: List<TimedWord> = emptyList(),
    val hasAudio: Boolean = false,
    val warnings: List<String> = emptyList(),
) {
    init {
        require(durationUs > 0)
        require(audio.zipWithNext().all { (a, b) -> a.startUs <= b.startUs })
        require(motion.zipWithNext().all { (a, b) -> a.timeUs <= b.timeUs })
        require(words.zipWithNext().all { (a, b) -> a.startUs <= b.startUs })
    }
}

enum class EditDecisionType { CUT, CAPTION, ZOOM, REFRAME, EFFECT, AUDIO }

data class CutDecision(
    val startUs: Long, val endUs: Long, val confidence: Float, val reason: String,
) { init { require(startUs >= 0 && endUs > startUs && confidence in 0f..1f && reason.isNotBlank()) } }

data class CaptionDecision(
    val startUs: Long, val endUs: Long, val text: String, val emphasizedWord: String? = null,
    val confidence: Float = 1f, val reason: String = "transcricao temporizada",
) { init { require(startUs >= 0 && endUs > startUs && text.isNotBlank() && confidence in 0f..1f) } }

data class ZoomDecision(
    val timeUs: Long, val durationUs: Long, val scale: Float, val confidence: Float, val reason: String,
) { init { require(timeUs >= 0 && durationUs in 80_000L..900_000L && scale in 1.03f..1.25f && confidence in 0f..1f) } }

data class ReframeDecision(
    val timeUs: Long, val x: Float, val y: Float, val confidence: Float, val reason: String,
) { init { require(timeUs >= 0 && x in -.5f.. .5f && y in -.5f.. .5f && confidence in 0f..1f) } }

data class EffectDecision(
    val startUs: Long, val endUs: Long, val assetId: String, val intensity: Float,
    val values: Map<String, Float>, val confidence: Float, val reason: String,
) { init { require(startUs >= 0 && endUs > startUs && assetId.isNotBlank() && intensity in 0f..1f && confidence in 0f..1f) } }

data class AudioDecision(
    val normalizeVolume: Float, val fadeInUs: Long, val fadeOutUs: Long, val reason: String,
) { init { require(normalizeVolume in 0f..2f && fadeInUs in 0L..SECOND && fadeOutUs in 0L..SECOND) } }

data class AutoEditPlan(
    val clipId: String,
    val sourceDurationUs: Long,
    val options: AutoEditOptions,
    val cuts: List<CutDecision> = emptyList(),
    val captions: List<CaptionDecision> = emptyList(),
    val zooms: List<ZoomDecision> = emptyList(),
    val reframes: List<ReframeDecision> = emptyList(),
    val effects: List<EffectDecision> = emptyList(),
    val audio: AudioDecision? = null,
    val warnings: List<String> = emptyList(),
) {
    fun summary(): String = buildList {
        if (cuts.isNotEmpty()) add("${cuts.size} pausas encurtadas")
        if (captions.isNotEmpty()) add("${captions.size} legendas ajustadas")
        if (zooms.isNotEmpty()) add("${zooms.size} zooms de enfase")
        if (reframes.isNotEmpty()) add("Auto Reframe com ${reframes.size} pontos")
        if (effects.isNotEmpty()) add("${effects.size} efeitos de impacto")
        if (audio != null) add("Audio equilibrado")
        if (isEmpty()) add("O clipe ja esta bem ritmado; nenhuma mudanca necessaria")
    }.joinToString("\n")

    fun isEmpty() = cuts.isEmpty() && captions.isEmpty() && zooms.isEmpty() && reframes.isEmpty() && effects.isEmpty() && audio == null
}

sealed class AutoEditProgress(val label: String) {
    data object Inspecting : AutoEditProgress("Inspecionando clipe")
    data object Audio : AutoEditProgress("Analisando audio")
    data object Visuals : AutoEditProgress("Analisando movimento")
    data object Planning : AutoEditProgress("Planejando edicao")
    data object Validating : AutoEditProgress("Validando plano")
    data object Applying : AutoEditProgress("Criando preview editavel")
}
