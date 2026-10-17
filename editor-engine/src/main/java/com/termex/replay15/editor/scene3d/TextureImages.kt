package com.termex.replay15.editor.scene3d

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Makes model textures usable by glTF (which only allows PNG and JPEG): PNG/JPEG pass through,
 * TGA and BMP (very common next to OBJ/FBX files) are decoded and re-encoded as PNG.
 */
object TextureImages {
    class Pixels(val width: Int, val height: Int, /** ARGB, top row first. */ val argb: IntArray)

    fun mimeOf(bytes: ByteArray): String? = when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
        else -> null
    }

    /** glTF-ready image for [bytes] named [name], or null when the format is not supported. */
    fun toGltf(name: String, bytes: ByteArray): GltfBuilder.Image? {
        mimeOf(bytes)?.let { return GltfBuilder.Image(bytes, it) }
        val lower = name.lowercase()
        val pixels = runCatching {
            when {
                bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte() -> decodeBmp(bytes)
                lower.endsWith(".tga") || lower.endsWith(".targa") -> decodeTga(bytes)
                else -> null
            }
        }.getOrNull() ?: return null
        return GltfBuilder.Image(encodePng(pixels), "image/png")
    }

    fun encodePng(pixels: Pixels): ByteArray {
        val w = pixels.width; val h = pixels.height
        require(w > 0 && h > 0 && pixels.argb.size == w * h)
        val raw = ByteArray((w * 4 + 1) * h)
        var o = 0
        for (y in 0 until h) {
            raw[o++] = 0 // filter: none
            for (x in 0 until w) {
                val c = pixels.argb[y * w + x]
                raw[o++] = (c shr 16).toByte(); raw[o++] = (c shr 8).toByte(); raw[o++] = c.toByte(); raw[o++] = (c ushr 24).toByte()
            }
        }
        val deflater = Deflater(6)
        deflater.setInput(raw); deflater.finish()
        val compressed = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (!deflater.finished()) { val n = deflater.deflate(chunk); compressed.write(chunk, 0, n) }
        deflater.end()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            out.write(ByteBuffer.allocate(4).putInt(data.size).array())
            val typeBytes = type.toByteArray(Charsets.US_ASCII)
            out.write(typeBytes); out.write(data)
            val crc = CRC32(); crc.update(typeBytes); crc.update(data)
            out.write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
        }
        chunk("IHDR", ByteBuffer.allocate(13).putInt(w).putInt(h).put(8).put(6).put(0).put(0).put(0).array())
        chunk("IDAT", compressed.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    /** Uncompressed and RLE TGA: 8-bit grey, 24-bit and 32-bit, either row order. */
    fun decodeTga(bytes: ByteArray): Pixels {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val idLength = bytes[0].toInt() and 0xFF
        val colorMapType = bytes[1].toInt() and 0xFF
        val type = bytes[2].toInt() and 0xFF
        val colorMapLength = b.getShort(5).toInt() and 0xFFFF
        val colorMapDepth = bytes[7].toInt() and 0xFF
        val width = b.getShort(12).toInt() and 0xFFFF
        val height = b.getShort(14).toInt() and 0xFFFF
        val depth = bytes[16].toInt() and 0xFF
        val descriptor = bytes[17].toInt() and 0xFF
        require(width in 1..16_384 && height in 1..16_384) { "TGA inválido" }
        require(type in setOf(2, 3, 10, 11)) { "TGA de tipo $type não suportado" }
        require(depth in setOf(8, 24, 32)) { "TGA de $depth bits não suportado" }
        var p = 18 + idLength + if (colorMapType == 1) colorMapLength * ((colorMapDepth + 7) / 8) else 0
        val bpp = depth / 8
        val out = IntArray(width * height)
        fun read(): Int {
            require(p + bpp <= bytes.size) { "TGA incompleto" }
            val c = when (bpp) {
                1 -> { val g = bytes[p].toInt() and 0xFF; (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
                3 -> (0xFF shl 24) or ((bytes[p + 2].toInt() and 0xFF) shl 16) or ((bytes[p + 1].toInt() and 0xFF) shl 8) or (bytes[p].toInt() and 0xFF)
                else -> ((bytes[p + 3].toInt() and 0xFF) shl 24) or ((bytes[p + 2].toInt() and 0xFF) shl 16) or ((bytes[p + 1].toInt() and 0xFF) shl 8) or (bytes[p].toInt() and 0xFF)
            }
            p += bpp
            return c
        }
        val total = width * height
        var i = 0
        if (type == 2 || type == 3) {
            while (i < total) out[i++] = read()
        } else {
            while (i < total) {
                require(p < bytes.size) { "TGA incompleto" }
                val header = bytes[p++].toInt() and 0xFF
                val count = (header and 0x7F) + 1
                if (header and 0x80 != 0) {
                    val c = read()
                    repeat(count) { if (i < total) out[i++] = c }
                } else {
                    repeat(count) { if (i < total) out[i++] = read() }
                }
            }
        }
        val topFirst = descriptor and 0x20 != 0
        val rightToLeft = descriptor and 0x10 != 0
        val result = IntArray(total)
        for (y in 0 until height) for (x in 0 until width) {
            val sy = if (topFirst) y else height - 1 - y
            val sx = if (rightToLeft) width - 1 - x else x
            result[y * width + x] = out[sy * width + sx]
        }
        return Pixels(width, height, result)
    }

    /** Uncompressed BMP: 24 and 32 bits (BI_RGB / BI_BITFIELDS with the usual masks). */
    fun decodeBmp(bytes: ByteArray): Pixels {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val dataOffset = b.getInt(10)
        val width = b.getInt(18)
        val rawHeight = b.getInt(22)
        val bits = b.getShort(28).toInt()
        val compression = b.getInt(30)
        require(width in 1..16_384 && rawHeight != 0 && kotlin.math.abs(rawHeight) <= 16_384) { "BMP inválido" }
        require(bits == 24 || bits == 32) { "BMP de $bits bits não suportado" }
        require(compression == 0 || compression == 3) { "BMP comprimido não suportado" }
        val height = kotlin.math.abs(rawHeight)
        val stride = ((width * bits + 31) / 32) * 4
        require(dataOffset >= 0 && dataOffset.toLong() + stride.toLong() * height <= bytes.size) { "BMP incompleto" }
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val row = if (rawHeight > 0) height - 1 - y else y
            var p = dataOffset + row * stride
            for (x in 0 until width) {
                val blue = bytes[p].toInt() and 0xFF; val green = bytes[p + 1].toInt() and 0xFF; val red = bytes[p + 2].toInt() and 0xFF
                val alpha = if (bits == 32 && compression == 3) bytes[p + 3].toInt() and 0xFF else 0xFF
                out[y * width + x] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                p += bits / 8
            }
        }
        return Pixels(width, height, out)
    }
}
