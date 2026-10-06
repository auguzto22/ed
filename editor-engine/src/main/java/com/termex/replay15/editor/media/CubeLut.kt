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

    /** Trilinear lookup used by LUT-browser thumbnails; it matches the GPU's RGB volume lookup. */
    fun apply(red: Float, green: Float, blue: Float, strength: Float = 1f): FloatArray {
        require(red.isFinite() && green.isFinite() && blue.isFinite())
        require(strength.isFinite() && strength in 0f..1f)
        val rp = coordinate(red); val gp = coordinate(green); val bp = coordinate(blue)
        val r = red.coerceIn(0f, 1f); val g = green.coerceIn(0f, 1f); val b = blue.coerceIn(0f, 1f)
        return floatArrayOf(
            mix(r, lookup(rp.first, rp.second, gp.first, gp.second, bp.first, bp.second, 0), strength),
            mix(g, lookup(rp.first, rp.second, gp.first, gp.second, bp.first, bp.second, 1), strength),
            mix(b, lookup(rp.first, rp.second, gp.first, gp.second, bp.first, bp.second, 2), strength),
        )
    }

    /** Allocation-free pixel variant for small, on-demand LUT preview thumbnails. */
    fun applyPixel(argb: Int, strength: Float = 1f): Int {
        require(strength.isFinite() && strength in 0f..1f)
        val red = ((argb ushr 16) and 255) / 255f
        val green = ((argb ushr 8) and 255) / 255f
        val blue = (argb and 255) / 255f
        val rPos = red * (size - 1); val r0 = rPos.toInt().coerceAtMost(size - 2); val rf = (rPos - r0).coerceIn(0f, 1f)
        val gPos = green * (size - 1); val g0 = gPos.toInt().coerceAtMost(size - 2); val gf = (gPos - g0).coerceIn(0f, 1f)
        val bPos = blue * (size - 1); val b0 = bPos.toInt().coerceAtMost(size - 2); val bf = (bPos - b0).coerceIn(0f, 1f)
        val r = toByte(mix(red, lookup(r0, rf, g0, gf, b0, bf, 0), strength))
        val g = toByte(mix(green, lookup(r0, rf, g0, gf, b0, bf, 1), strength))
        val b = toByte(mix(blue, lookup(r0, rf, g0, gf, b0, bf, 2), strength))
        return (argb and -0x1000000) or (r shl 16) or (g shl 8) or b
    }

    private fun coordinate(value: Float): Pair<Int, Float> {
        val position = value.coerceIn(0f, 1f) * (size - 1)
        val low = position.toInt().coerceAtMost(size - 2)
        return low to (position - low).coerceIn(0f, 1f)
    }

    private fun lookup(r0: Int, rf: Float, g0: Int, gf: Float, b0: Int, bf: Float, channel: Int): Float {
        val b0g0r0 = values[r0 + size * (g0 + size * b0)][channel]
        val b0g0r1 = values[r0 + 1 + size * (g0 + size * b0)][channel]
        val b0g1r0 = values[r0 + size * (g0 + 1 + size * b0)][channel]
        val b0g1r1 = values[r0 + 1 + size * (g0 + 1 + size * b0)][channel]
        val b1g0r0 = values[r0 + size * (g0 + size * (b0 + 1))][channel]
        val b1g0r1 = values[r0 + 1 + size * (g0 + size * (b0 + 1))][channel]
        val b1g1r0 = values[r0 + size * (g0 + 1 + size * (b0 + 1))][channel]
        val b1g1r1 = values[r0 + 1 + size * (g0 + 1 + size * (b0 + 1))][channel]
        val b00 = b0g0r0 * (1 - rf) + b0g0r1 * rf
        val b10 = b0g1r0 * (1 - rf) + b0g1r1 * rf
        val b01 = b1g0r0 * (1 - rf) + b1g0r1 * rf
        val b11 = b1g1r0 * (1 - rf) + b1g1r1 * rf
        val gLow = b00 * (1 - gf) + b10 * gf
        val gHigh = b01 * (1 - gf) + b11 * gf
        return (gLow * (1 - bf) + gHigh * bf).coerceIn(0f, 1f)
    }

    private fun mix(original: Float, mapped: Float, strength: Float) = (original + (mapped - original) * strength).coerceIn(0f, 1f)
    private fun toByte(value: Float) = (value * 255 + .5f).toInt().coerceIn(0, 255)

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
