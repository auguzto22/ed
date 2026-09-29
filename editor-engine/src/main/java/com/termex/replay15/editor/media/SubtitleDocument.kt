package com.termex.replay15.editor.media

import com.termex.replay15.editor.domain.*
import java.util.Locale

object SubtitleDocument {
    private val stamp = Regex("(\\d{1,3}):(\\d{2}):(\\d{2})[,.](\\d{3})\\s*-->\\s*(\\d{1,3}):(\\d{2}):(\\d{2})[,.](\\d{3})(?:\\s+.*)?")
    fun parse(source: String, durationUs: Long, limit: Int = MAX_TEXTS): List<TextClip> {
        require(limit > 0 && source.length <= 2_000_000)
        val result = mutableListOf<TextClip>()
        source.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\\n\\s*\\n")).forEach { block ->
            val lines = block.lines().map(String::trim)
            val index = lines.indexOfFirst { stamp.matches(it) }
            if (index < 0 || index == lines.lastIndex) return@forEach
            val match = stamp.matchEntire(lines[index]) ?: return@forEach
            fun timestamp(offset: Int): Long {
                val h = match.groupValues[offset].toLong(); val m = match.groupValues[offset + 1].toLong()
                val s = match.groupValues[offset + 2].toLong(); val ms = match.groupValues[offset + 3].toLong()
                require(m in 0..59 && s in 0..59) { "Tempo de legenda invalido" }
                return (h * 3600 + m * 60 + s) * SECOND + ms * 1000
            }
            val start = timestamp(1); val end = minOf(timestamp(5), durationUs)
            val text = lines.drop(index + 1).joinToString("\n").replace(Regex("<[^>]+>"), "").trim()
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            if (text.isNotBlank() && start < end) {
                require(text.length <= 500) { "Uma legenda excede 500 caracteres" }
                require(result.size < limit) { "A importacao excede $limit legendas disponiveis. Nenhuma frase foi descartada." }
                result += TextClip(text = text, startUs = start, endUs = end, y = .84f, size = .048f,
                    color = -1, bold = true, boxWidth = .84f, backgroundColor = 0xB8000000.toInt(), shadow = true,
                    isCaption = true)
            }
        }
        require(result.isNotEmpty()) { "Nenhuma legenda SRT valida encontrada" }
        return result.sortedBy { it.startUs }
    }
    fun write(texts: List<TextClip>, durationUs: Long): String = texts.sortedBy { it.startUs }
        .filter { it.startUs < durationUs }.mapIndexed { index, text ->
            "${index + 1}\n${format(text.startUs)} --> ${format(minOf(text.endUs, durationUs))}\n${text.text}\n"
        }.joinToString("\n")
    private fun format(us: Long): String {
        val ms = us / 1000
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
    }
}
