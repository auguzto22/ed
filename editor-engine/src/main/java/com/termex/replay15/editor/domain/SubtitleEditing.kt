package com.termex.replay15.editor.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** The temporary UI operation used to decide which caption clips receive a patch. */
enum class SubtitleApplyScope(val label: String) {
    CURRENT("Esta"),
    NEXT_N("Próximas"),
    UNTIL_END("Fim"),
    ALL("Todas"),
    CUSTOM_RANGE("Intervalo"),
}

data class ApplyRange(
    val scope: SubtitleApplyScope = SubtitleApplyScope.CURRENT,
    val nextCount: Int = 5,
    val firstIndex: Int? = null,
    val lastIndex: Int? = null,
) {
    init {
        require(nextCount in 0..MAX_TEXTS)
        require((firstIndex == null) == (lastIndex == null))
        if (firstIndex != null && lastIndex != null) require(firstIndex >= 0 && lastIndex >= firstIndex)
    }

    companion object {
        fun current() = ApplyRange(SubtitleApplyScope.CURRENT)
        fun next(count: Int) = ApplyRange(SubtitleApplyScope.NEXT_N, count.coerceIn(0, MAX_TEXTS))
        fun untilEnd() = ApplyRange(SubtitleApplyScope.UNTIL_END)
        fun all() = ApplyRange(SubtitleApplyScope.ALL)
        fun custom(first: Int, last: Int) = ApplyRange(SubtitleApplyScope.CUSTOM_RANGE, firstIndex = first, lastIndex = last)
    }
}

/**
 * Nullable values are intentional: a patch changes only the properties supplied by the UI.
 * This prevents a font operation from accidentally copying a caption's color or position.
 */
data class SubtitleStylePatch(
    val text: String? = null,
    val fontId: String? = null,
    val fontWeight: Int? = null,
    val size: Float? = null,
    val color: Int? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val alignment: TextAlignment? = null,
    val opacity: Float? = null,
    val letterSpacing: Float? = null,
    val lineSpacing: Float? = null,
    val boxWidth: Float? = null,
    val rotation: Float? = null,
    val x: Float? = null,
    val y: Float? = null,
    val scale: Float? = null,
    val backgroundColor: Int? = null,
    val outlineColor: Int? = null,
    val outlineWidth: Float? = null,
    val shadow: Boolean? = null,
    val animation: TextAnimation? = null,
    val enterAnimation: TextAnimation? = null,
    val duringAnimation: TextAnimation? = null,
    val exitAnimation: TextAnimation? = null,
    val wordStyle: SubtitleWordStyle? = null,
    val wordCues: List<SubtitleWordCue>? = null,
) {
    init {
        fontId?.let { require(it.matches(Regex("[a-z0-9_]{1,80}"))) }
        fontWeight?.let { require(it in 1..1000) }
        size?.let { require(it in .02f.. .25f) }
        opacity?.let { require(it in .1f..1f) }
        letterSpacing?.let { require(it in -.05f.. .3f) }
        lineSpacing?.let { require(it in .7f..2f) }
        boxWidth?.let { require(it == 0f || it in .2f.. .95f) }
        rotation?.let { require(it in -180f..180f) }
        x?.let { require(it in 0f..1f) }
        y?.let { require(it in 0f..1f) }
        scale?.let { require(it in .05f..20f) }
        outlineWidth?.let { require(it in 0f.. .04f) }
        wordCues?.let { require(it.size <= 300) }
    }

    fun plus(other: SubtitleStylePatch): SubtitleStylePatch = SubtitleStylePatch(
        text = other.text ?: text,
        fontId = other.fontId ?: fontId,
        fontWeight = other.fontWeight ?: fontWeight,
        size = other.size ?: size,
        color = other.color ?: color,
        bold = other.bold ?: bold,
        italic = other.italic ?: italic,
        underline = other.underline ?: underline,
        alignment = other.alignment ?: alignment,
        opacity = other.opacity ?: opacity,
        letterSpacing = other.letterSpacing ?: letterSpacing,
        lineSpacing = other.lineSpacing ?: lineSpacing,
        boxWidth = other.boxWidth ?: boxWidth,
        rotation = other.rotation ?: rotation,
        x = other.x ?: x,
        y = other.y ?: y,
        scale = other.scale ?: scale,
        backgroundColor = other.backgroundColor ?: backgroundColor,
        outlineColor = other.outlineColor ?: outlineColor,
        outlineWidth = other.outlineWidth ?: outlineWidth,
        shadow = other.shadow ?: shadow,
        animation = other.animation ?: animation,
        enterAnimation = other.enterAnimation ?: enterAnimation,
        duringAnimation = other.duringAnimation ?: duringAnimation,
        exitAnimation = other.exitAnimation ?: exitAnimation,
        wordStyle = other.wordStyle ?: wordStyle,
        wordCues = other.wordCues ?: wordCues,
    )

    fun applyTo(source: TextClip): TextClip {
        val changedFont = fontId != null
        return source.copy(
            text = text ?: source.text,
            fontId = fontId ?: source.fontId,
            fontWeight = fontWeight ?: source.fontWeight,
            size = size ?: source.size,
            color = color ?: source.color,
            bold = bold ?: source.bold,
            italic = italic ?: source.italic,
            underline = underline ?: source.underline,
            alignment = alignment ?: source.alignment,
            opacity = opacity ?: source.opacity,
            letterSpacing = letterSpacing ?: source.letterSpacing,
            lineSpacing = lineSpacing ?: source.lineSpacing,
            boxWidth = boxWidth ?: source.boxWidth,
            rotation = rotation ?: source.rotation,
            x = x ?: source.x,
            y = y ?: source.y,
            scale = scale ?: source.scale,
            backgroundColor = backgroundColor ?: source.backgroundColor,
            outlineColor = outlineColor ?: source.outlineColor,
            outlineWidth = outlineWidth ?: source.outlineWidth,
            shadow = shadow ?: source.shadow,
            animation = animation ?: source.animation,
            enterAnimation = enterAnimation ?: source.enterAnimation,
            duringAnimation = duringAnimation ?: source.duringAnimation,
            exitAnimation = exitAnimation ?: source.exitAnimation,
            wordStyle = wordStyle ?: source.wordStyle,
            wordCues = wordCues ?: source.wordCues,
            // CaptionStyleResolver intentionally uses this override at render time.
            captionFontOverride = if (changedFont && source.isCaption) fontId else source.captionFontOverride,
        )
    }
}

object SubtitleApplyRangeResolver {
    /** Returns stable positions in the sorted caption list, not positions in all text layers. */
    fun indices(captions: List<TextClip>, currentId: String, range: ApplyRange): List<Int> {
        if (captions.isEmpty()) return emptyList()
        val current = captions.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: return emptyList()
        return when (range.scope) {
            SubtitleApplyScope.CURRENT -> listOf(current)
            // +N means current plus N following captions.
            SubtitleApplyScope.NEXT_N -> (current..(current + range.nextCount).coerceAtMost(captions.lastIndex)).toList()
            SubtitleApplyScope.UNTIL_END -> (current..captions.lastIndex).toList()
            SubtitleApplyScope.ALL -> captions.indices.toList()
            SubtitleApplyScope.CUSTOM_RANGE -> {
                val first = range.firstIndex ?: current
                val last = range.lastIndex ?: current
                (first.coerceIn(0, captions.lastIndex)..last.coerceIn(0, captions.lastIndex)).toList()
            }
        }
    }

    fun apply(project: Project, currentId: String, range: ApplyRange, patch: SubtitleStylePatch): Project {
        val captions = project.texts.filter { it.isCaption }.sortedBy { it.startUs }
        val selectedIds = indices(captions, currentId, range).map { captions[it].id }.toSet()
        if (selectedIds.isEmpty()) return project
        return project.copy(texts = project.texts.map { if (it.id in selectedIds) patch.applyTo(it) else it })
    }
}

/** Rebuilds caption blocks from measured word boundaries without inventing timestamps. */
object SubtitleRegrouper {
    /** null means automatic/current grouping and therefore leaves the project unchanged. */
    fun regroup(project: Project, wordsPerBlock: Int?): Project {
        if (wordsPerBlock == null) return project
        require(wordsPerBlock in 1..12)
        val captions = project.texts.filter { it.isCaption }.sortedBy { it.startUs }
        val timed = captions.flatMap { caption -> caption.wordCues.map { cue -> caption to cue } }
        if (timed.isEmpty()) return project
        val rebuilt = timed.chunked(wordsPerBlock).mapIndexed { index, chunk ->
            val firstSource = chunk.first().first
            val firstCue = chunk.first().second
            val lastCue = chunk.last().second
            firstSource.copy(
                id = if (index == 0) captions.first().id else newId(),
                text = chunk.joinToString(" ") { it.second.text },
                startUs = firstCue.startUs,
                endUs = lastCue.endUs,
                wordCues = chunk.map { it.second },
            )
        }
        val captionIds = captions.map { it.id }.toSet()
        return project.copy(texts = (project.texts.filterNot { it.id in captionIds } + rebuilt).sortedBy { it.startUs })
    }
}

/** Shared time-based evaluator used by preview and export overlays. */
object SubtitleAnimationEvaluator {
    // alpha, scale, x offset in px, y offset in px
    fun evaluate(text: TextClip, timeUs: Long, width: Int, height: Int): FloatArray {
        val legacy = if (text.enterAnimation == TextAnimation.NONE &&
            text.duringAnimation == TextAnimation.NONE && text.exitAnimation == TextAnimation.NONE) text.animation else TextAnimation.NONE
        val enter = if (legacy != TextAnimation.NONE) legacy else text.enterAnimation
        val during = if (legacy != TextAnimation.NONE) TextAnimation.NONE else text.duringAnimation
        val exit = if (legacy != TextAnimation.NONE) legacy else text.exitAnimation
        val enterProgress = ((timeUs - text.startUs) / 350_000f).coerceIn(0f, 1f)
        val exitProgress = ((text.endUs - timeUs) / 250_000f).coerceIn(0f, 1f)
        val activeAnimation = when {
            enterProgress < 1f -> enter
            exitProgress < 1f -> exit
            else -> during
        }
        val progress = when {
            enterProgress < 1f -> enterProgress
            exitProgress < 1f -> exitProgress
            else -> ((timeUs - text.startUs) / text.durationUs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
        }
        val alpha = when (activeAnimation) {
            TextAnimation.NONE, TextAnimation.PULSE, TextAnimation.FLOAT, TextAnimation.SHAKE,
            TextAnimation.WORD_POP, TextAnimation.KARAOKE, TextAnimation.CURRENT_WORD_HIGHLIGHT,
            TextAnimation.TYPEWRITER -> 1f
            TextAnimation.FADE, TextAnimation.FADE_OUT, TextAnimation.BLUR_IN, TextAnimation.BLUR_OUT -> progress
            else -> 1f
        }
        val overshoot = overshoot(progress)
        val scale = when (activeAnimation) {
            TextAnimation.POP, TextAnimation.BOUNCE, TextAnimation.SOFT_BOUNCE -> .72f + .28f * overshoot
            TextAnimation.ZOOM, TextAnimation.SCALE -> .82f + .18f * progress
            TextAnimation.ZOOM_OUT -> .82f + .18f * progress
            TextAnimation.PULSE -> 1f + .04f * sin(progress * Math.PI * 2.0).toFloat()
            TextAnimation.ELASTIC -> 1f + sin(progress * Math.PI * 4.0).toFloat() * (1f - progress) * 0.25f
            TextAnimation.WAVE -> 1f + sin(progress * Math.PI * 2.0).toFloat() * 0.06f
            else -> 1f
        }
        val x = when (activeAnimation) {
            TextAnimation.SLIDE_LEFT -> (1f - progress) * width * .09f
            TextAnimation.SLIDE_RIGHT -> -(1f - progress) * width * .09f
            TextAnimation.SHAKE -> sin(progress * Math.PI * 12.0).toFloat() * width * .012f
            TextAnimation.GLITCH -> if (sin(progress * 30.0) > 0.4) sin(progress * 70.0).toFloat() * width * 0.015f else 0f
            TextAnimation.TRACKING -> (1f - progress) * width * 0.02f
            else -> 0f
        }
        val y = when (activeAnimation) {
            TextAnimation.SLIDE_UP -> (1f - progress) * height * .07f
            TextAnimation.SLIDE_DOWN -> -(1f - progress) * height * .07f
            TextAnimation.FLOAT -> sin(progress * Math.PI * 2.0).toFloat() * height * .012f
            TextAnimation.WAVE -> sin(progress * Math.PI * 3.0).toFloat() * height * .014f
            else -> 0f
        }
        return floatArrayOf((alpha * text.opacity).coerceIn(0f, 1f), scale, x, y)
    }

    private fun overshoot(value: Float): Float {
        val shifted = value - 1f
        return shifted * shifted * (2.2f * shifted + 2.2f) + 1f
    }
}
