package com.termex.replay15.editor.autoedit

import com.termex.replay15.editor.captions.AnimatedCaptionStyles
import com.termex.replay15.editor.domain.SubtitleWordCue
import com.termex.replay15.editor.domain.TextClip

/**
 * Captions in the look of podcast cuts: one to three words at a time, CAIXA ALTA, white with a
 * black outline, and the word being spoken in yellow (karaoke). Lines are cut from measured word
 * timing only; no word is invented or moved.
 */
object PodcastCaptions {
    const val STYLE_ID = "podcast"
    const val MAX_WORDS = 3
    /** Characters per line (spaces included) that still fit one line of a 9:16 frame. */
    const val MAX_CHARS = 16
    /** A pause longer than this starts a new line, like the reference. */
    const val MAX_GAP_US = 280_000L
    /** Keeps the last word on screen a moment after it is spoken. */
    const val TAIL_US = 60_000L
    const val MIN_DURATION_US = 200_000L
    const val SIZE = .044f
    const val Y = .66f

    private val edgePunctuation = Regex("^[\\p{Punct}\"“”‘’«»…]+|[\\p{Punct}\"“”‘’«»…]+$")

    /** Word as shown in the reference: no leading/trailing punctuation. Blank when nothing is left. */
    fun displayWord(raw: String): String = raw.trim().replace(edgePunctuation, "").trim()

    /**
     * Splits timed words (clip-local) into short lines. A line closes at [MAX_WORDS] words,
     * [MAX_CHARS] characters, a pause longer than [MAX_GAP_US] or sentence punctuation.
     * Consecutive lines never overlap and every word cue stays inside its line.
     */
    fun chunk(words: List<TimedWord>, durationUs: Long, importance: (String) -> Int = { 0 }): List<CaptionDecision> {
        if (durationUs <= 0L) return emptyList()
        val clean = words.asSequence()
            .filter { it.startUs < durationUs }
            .mapNotNull { word ->
                val text = displayWord(word.text)
                val end = minOf(word.endUs, durationUs)
                if (text.isEmpty() || end <= word.startUs) null
                else Triple(word, text, end)
            }
            .sortedBy { it.first.startUs }
            .toList()
        if (clean.isEmpty()) return emptyList()

        val groups = mutableListOf<List<Triple<TimedWord, String, Long>>>()
        var group = mutableListOf<Triple<TimedWord, String, Long>>()
        clean.forEach { item ->
            val previous = group.lastOrNull()
            val chars = group.sumOf { it.second.length } + group.size + item.second.length
            val gap = previous?.let { item.first.startUs - it.third } ?: 0L
            if (group.isNotEmpty() && (group.size >= MAX_WORDS || chars > MAX_CHARS || gap > MAX_GAP_US)) {
                groups += group; group = mutableListOf()
            }
            group += item
            if (item.first.text.trimEnd().lastOrNull() in SENTENCE_END) { groups += group; group = mutableListOf() }
        }
        if (group.isNotEmpty()) groups += group

        return groups.mapIndexedNotNull { index, items ->
            val start = items.first().first.startUs
            val nextStart = groups.getOrNull(index + 1)?.first()?.first?.startUs ?: durationUs
            val limit = minOf(nextStart, durationUs)
            var end = minOf(items.maxOf { it.third } + TAIL_US, limit)
            if (end - start < MIN_DURATION_US) end = minOf(start + MIN_DURATION_US, limit)
            if (end <= start) return@mapIndexedNotNull null
            val cues = items.mapNotNull { (word, text, wordEnd) ->
                val cueStart = word.startUs.coerceIn(start, end)
                val cueEnd = wordEnd.coerceIn(start, end)
                if (cueEnd > cueStart) TimedWord(text, cueStart, cueEnd, word.confidence) else null
            }
            if (cues.isEmpty()) return@mapIndexedNotNull null
            val emphasized = cues.maxByOrNull { importance(it.text) }?.takeIf { importance(it.text) >= 2 }?.text
            CaptionDecision(start, end, cues.joinToString(" ") { it.text }, emphasized,
                cues.map { it.confidence }.average().toFloat(), "legenda curta estilo podcast", cues)
        }
    }

    /** Builds the editable caption layer for one planned line, already placed on the timeline. */
    fun layer(text: String, startUs: Long, endUs: Long, cues: List<SubtitleWordCue>): TextClip {
        val base = TextClip(text = text, startUs = startUs, endUs = endUs, isCaption = true,
            wordCues = cues.filter { it.startUs >= startUs && it.endUs <= endUs }.sortedBy { it.startUs })
        return style(base)
    }

    /** The reference look as a normal caption style, so the caption panel can still change it. */
    fun style(caption: TextClip): TextClip {
        val look = AnimatedCaptionStyles.byId(STYLE_ID) ?: error("Estilo de legenda podcast ausente")
        return look.patch().copy(size = SIZE, y = Y, x = .5f).applyTo(AnimatedCaptionStyles.withWordTiming(caption))
    }

    private val SENTENCE_END = setOf('.', '!', '?', ':', ';')
}
