package com.termex.replay15.editor.media

import java.io.Reader

/** .cube uses red-fastest ordering; the renderer's HALD texture uses blue-fastest. */
data class CubeLut(val size: Int, val values: List<FloatArray>) {
    init {
        require(size in 2..64 && values.size == size * size * size)
        require(values.all { row -> row.size == 3 && row.all { it.isFinite() } })
    }

    fun pixels(strength: Float): IntArray {
        require(strength.isFinite() && strength in 0f..1f)
        return IntArray(size * size * size) { index ->
        val b = index % size; val g = index / size % size; val r = index / (size * size)
        val color = values[r + size * (g + size * b)]
        fun channel(c: Int, original: Int): Int = ((original.toFloat() / (size - 1) * (1 - strength) + color[c] * strength)
            .coerceIn(0f, 1f) * 255 + .5f).toInt()
        (255 shl 24) or (channel(0, r) shl 16) or (channel(1, g) shl 8) or channel(2, b)
        }
    }
    companion object {
        fun parse(reader: Reader): CubeLut {
            var size = 0
            var total = 0
            val values = ArrayList<FloatArray>()
            reader.buffered().useLines { lines -> lines.forEach { raw ->
                total += raw.length
                require(total <= 12_000_000) { "LUT maior que 12 MB" }
                val line = raw.substringBefore('#').trim()
                if (line.isNotEmpty()) {
                    val tokens = line.split(Regex("\\s+"))
                    when (tokens[0]) {
                        "TITLE" -> Unit
                        "LUT_3D_SIZE" -> { require(size == 0 && values.isEmpty()); size = tokens[1].toInt(); require(size in 2..64) { "Use LUT 3D de 2 a 64 pontos" } }
                        "DOMAIN_MIN", "DOMAIN_MAX" -> {
                            val expected = if (tokens[0] == "DOMAIN_MIN") 0f else 1f
                            require(tokens.size == 4 && tokens.drop(1).all { it.toFloat() == expected }) { "Use LUT com dominio RGB de 0 a 1" }
                        }
                        else -> {
                            require(size > 0 && values.size < size * size * size && tokens.size == 3) { "Arquivo LUT 3D invalido" }
                            values += tokens.map { it.toFloat().also { n -> require(n.isFinite()) } }.toFloatArray()
                        }
                    }
                }
            } }
            require(size > 0 && values.size == size * size * size) { "LUT incompleta" }
            return CubeLut(size, values)
        }
    }
}
