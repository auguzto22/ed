package com.termex.replay15.editor.scene3d

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/** One FBX record: a name, its typed properties and nested records. */
class FbxNode(val name: String, val properties: List<Any>, val children: List<FbxNode>) {
    fun child(name: String): FbxNode? = children.firstOrNull { it.name == name }
    fun all(name: String): List<FbxNode> = children.filter { it.name == name }

    fun string(index: Int): String? = when (val p = properties.getOrNull(index)) {
        is String -> p
        is ByteArray -> String(p, Charsets.UTF_8)
        else -> null
    }

    fun long(index: Int): Long? = when (val p = properties.getOrNull(index)) {
        is Long -> p; is Int -> p.toLong(); is Short -> p.toLong(); is Double -> p.toLong(); is Float -> p.toLong()
        else -> null
    }

    fun double(index: Int): Double? = when (val p = properties.getOrNull(index)) {
        is Double -> p; is Float -> p.toDouble(); is Long -> p.toDouble(); is Int -> p.toDouble(); is Short -> p.toDouble()
        is Boolean -> if (p) 1.0 else 0.0
        else -> null
    }

    /** Numeric array of property [index] (binary arrays, or ASCII "*N { a: ... }" blocks). */
    fun doubles(index: Int = 0): DoubleArray? = when (val p = properties.getOrNull(index)) {
        is DoubleArray -> p
        is FloatArray -> DoubleArray(p.size) { p[it].toDouble() }
        is IntArray -> DoubleArray(p.size) { p[it].toDouble() }
        is LongArray -> DoubleArray(p.size) { p[it].toDouble() }
        is BooleanArray -> DoubleArray(p.size) { if (p[it]) 1.0 else 0.0 }
        null -> null
        else -> if (properties.all { it is Number }) DoubleArray(properties.size) { (properties[it] as Number).toDouble() } else null
    }

    fun ints(index: Int = 0): IntArray? = when (val p = properties.getOrNull(index)) {
        is IntArray -> p
        is LongArray -> IntArray(p.size) { p[it].toInt() }
        else -> doubles(index)?.let { d -> IntArray(d.size) { d[it].toInt() } }
    }

    /** Properties70 entries by name: P: "name", "type", "label", "flags", values... */
    fun properties70(): Map<String, FbxNode> = child("Properties70")?.all("P")?.mapNotNull { p -> p.string(0)?.let { it to p } }?.toMap()
        ?: emptyMap()
}

/**
 * Reads binary FBX (versions 7.x, 32- and 64-bit offsets, zlib-compressed arrays) and ASCII
 * FBX into [FbxNode] trees. FBX 6.x and older use a different object model and are refused.
 */
object FbxParser {
    class Document(val version: Int, val nodes: List<FbxNode>) {
        fun node(name: String): FbxNode? = nodes.firstOrNull { it.name == name }
    }

    private val MAGIC = "Kaydara FBX Binary  ".toByteArray(Charsets.US_ASCII)

    fun isBinary(bytes: ByteArray): Boolean = bytes.size > 27 && (0 until MAGIC.size).all { bytes[it] == MAGIC[it] }

    fun parse(bytes: ByteArray): Document {
        val document = if (isBinary(bytes)) parseBinary(bytes) else parseAscii(ObjConverter.decodeText(bytes))
        require(document.version >= 7000) { "FBX antigo (versão ${document.version / 1000}.${document.version % 1000 / 100}). Exporte de novo como FBX 2011 ou mais novo." }
        return document
    }

    // -------------------------------------------------------------------------------------
    // Binary
    // -------------------------------------------------------------------------------------
    private fun parseBinary(bytes: ByteArray): Document {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val version = buffer.getInt(23)
        val wide = version >= 7500
        buffer.position(27)
        val nodes = ArrayList<FbxNode>()
        while (buffer.remaining() > (if (wide) 25 else 13)) {
            val node = readNode(buffer, wide, bytes.size) ?: break
            nodes += node
        }
        return Document(version, nodes)
    }

    private fun readNode(b: ByteBuffer, wide: Boolean, size: Int): FbxNode? {
        val start = b.position()
        val endOffset = if (wide) b.long else b.int.toLong() and 0xFFFFFFFFL
        val propertyCount = if (wide) b.long else b.int.toLong() and 0xFFFFFFFFL
        val propertyLength = if (wide) b.long else b.int.toLong() and 0xFFFFFFFFL
        val nameLength = b.get().toInt() and 0xFF
        if (endOffset == 0L) return null // null record: end of a nested list
        require(endOffset in (start + 1).toLong()..size.toLong()) { "FBX corrompido" }
        require(propertyCount in 0..100_000_000L && propertyLength in 0..size.toLong()) { "FBX corrompido" }
        val nameBytes = ByteArray(nameLength).also { b.get(it) }
        val name = String(nameBytes, Charsets.UTF_8)
        val propertiesStart = b.position()
        val properties = ArrayList<Any>(propertyCount.toInt().coerceAtMost(64))
        repeat(propertyCount.toInt()) { properties += readProperty(b) }
        require(b.position().toLong() == propertiesStart + propertyLength) { "FBX corrompido (propriedades)" }
        val children = ArrayList<FbxNode>()
        while (b.position() < endOffset) {
            val child = readNode(b, wide, size) ?: break
            children += child
        }
        b.position(endOffset.toInt())
        return FbxNode(name, properties, children)
    }

    private fun readProperty(b: ByteBuffer): Any {
        return when (val type = b.get().toInt().toChar()) {
            'Y' -> b.short
            'C' -> b.get().toInt() != 0
            'I' -> b.int
            'F' -> b.float
            'D' -> b.double
            'L' -> b.long
            'S', 'R' -> {
                val length = b.int
                require(length in 0..b.remaining()) { "FBX corrompido (texto)" }
                val data = ByteArray(length).also { b.get(it) }
                if (type == 'S') String(data, Charsets.UTF_8) else data
            }
            'f', 'd', 'l', 'i', 'b', 'c' -> readArray(b, type)
            else -> throw IllegalArgumentException("FBX corrompido (tipo $type)")
        }
    }

    private fun readArray(b: ByteBuffer, type: Char): Any {
        val count = b.int
        val encoding = b.int
        val compressedLength = b.int
        require(count >= 0 && compressedLength in 0..b.remaining()) { "FBX corrompido (lista)" }
        val elementSize = when (type) { 'f', 'i' -> 4; 'd', 'l' -> 8; else -> 1 }
        require(count.toLong() * elementSize <= 1_000_000_000L) { "FBX grande demais" }
        val raw = ByteArray(compressedLength).also { b.get(it) }
        val data = when (encoding) {
            0 -> raw
            1 -> {
                val inflater = Inflater()
                inflater.setInput(raw)
                val out = ByteArray(count * elementSize)
                var done = 0
                while (done < out.size && !inflater.finished()) {
                    val n = inflater.inflate(out, done, out.size - done)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    done += n
                }
                inflater.end()
                require(done == out.size) { "FBX corrompido (lista comprimida)" }
                out
            }
            else -> throw IllegalArgumentException("FBX com compressão desconhecida")
        }
        require(data.size >= count * elementSize) { "FBX corrompido (tamanho da lista)" }
        val d = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        return when (type) {
            'f' -> FloatArray(count) { d.float }
            'd' -> DoubleArray(count) { d.double }
            'l' -> LongArray(count) { d.long }
            'i' -> IntArray(count) { d.int }
            'c' -> IntArray(count) { d.get().toInt() and 0xFF }
            else -> BooleanArray(count) { d.get().toInt() != 0 }
        }
    }

    // -------------------------------------------------------------------------------------
    // ASCII
    // -------------------------------------------------------------------------------------
    private class Tokens(private val text: String) {
        var i = 0
        private fun skip() {
            while (i < text.length) {
                val c = text[i]
                if (c == ';') { while (i < text.length && text[i] != '\n') i++ }
                else if (c.isWhitespace()) i++
                else break
            }
        }
        fun peek(): Char? { skip(); return if (i < text.length) text[i] else null }
        /** Next token: an identifier (with its trailing ':' kept), a number, a quoted string, or a symbol. */
        fun next(): String? {
            skip()
            if (i >= text.length) return null
            val c = text[i]
            if (c == '"') {
                val end = text.indexOf('"', i + 1).let { if (it < 0) text.length else it }
                val value = text.substring(i, end + 1)
                i = (end + 1).coerceAtMost(text.length)
                return value
            }
            if (c == '{' || c == '}' || c == ',') { i++; return c.toString() }
            val start = i
            while (i < text.length && !text[i].isWhitespace() && text[i] != ',' && text[i] != '{' && text[i] != '}' && text[i] != '"') {
                i++
                if (text[i - 1] == ':') break
            }
            return text.substring(start, i)
        }
    }

    private fun parseAscii(text: String): Document {
        val tokens = Tokens(text)
        val nodes = ArrayList<FbxNode>()
        while (tokens.peek() != null) {
            val node = readAsciiNode(tokens) ?: break
            nodes += node
        }
        val header = nodes.firstOrNull { it.name == "FBXHeaderExtension" }
        val version = header?.child("FBXVersion")?.long(0)?.toInt()
            ?: Regex("FBX\\s+(\\d)\\.(\\d)").find(text.take(400))?.let { it.groupValues[1].toInt() * 1000 + it.groupValues[2].toInt() * 100 }
            ?: 0
        return Document(version, nodes)
    }

    private fun readAsciiNode(tokens: Tokens): FbxNode? {
        val head = tokens.next() ?: return null
        if (head == "}") return null
        require(head.endsWith(":")) { "FBX de texto inválido perto de \"$head\"" }
        val name = head.dropLast(1)
        val properties = ArrayList<Any>()
        var arrayValues: DoubleArray? = null
        var isLongArray = false
        while (true) {
            val c = tokens.peek() ?: break
            if (c == '{' || c == '}') break
            // A new "Name:" on the next line ends this node's properties.
            val save = tokens.i
            val token = tokens.next() ?: break
            if (token == ",") continue
            if (token.endsWith(":") && !token.startsWith("\"")) { tokens.i = save; return FbxNode(name, properties, emptyList()) }
            properties += asciiValue(token)
        }
        val children = ArrayList<FbxNode>()
        if (tokens.peek() == '{') {
            tokens.next()
            val isArray = properties.size == 1 && (properties[0] as? String)?.startsWith("*") == true
            while (tokens.peek() != null && tokens.peek() != '}') {
                val child = readAsciiNode(tokens) ?: break
                if (isArray && child.name == "a") {
                    isLongArray = child.properties.all { it is Long }
                    arrayValues = DoubleArray(child.properties.size) { (child.properties[it] as Number).toDouble() }
                    if (isLongArray) {
                        val longs = LongArray(child.properties.size) { (child.properties[it] as Number).toLong() }
                        properties.clear(); properties += longs
                    }
                } else children += child
            }
            if (tokens.peek() == '}') tokens.next()
            if (isArray && !isLongArray) { properties.clear(); properties += (arrayValues ?: DoubleArray(0)) }
            else if (isArray && arrayValues == null) { properties.clear(); properties += DoubleArray(0) }
        }
        return FbxNode(name, properties, children)
    }

    private fun asciiValue(token: String): Any {
        if (token.startsWith("\"")) return token.trim('"').let { s ->
            // ASCII files write class-qualified names as "Model::Cube"; binary uses "Cube\u0000\u0001Model".
            s
        }
        token.toLongOrNull()?.let { return it }
        token.toDoubleOrNull()?.let { return it }
        return token // e.g. "*24", "T", "Y"
    }
}
