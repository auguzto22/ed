package com.termex.replay15.editor.scene3d

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Writes a self-contained binary glTF 2.0 (.glb) from meshes converted out of OBJ or FBX.
 * Output follows the spec rules Filament's loader (cgltf) validates: 4-byte aligned buffer
 * views, POSITION min/max, index component type sized to the vertex count, embedded images.
 */
class GltfBuilder {
    class Image(val bytes: ByteArray, val mimeType: String)

    class Material(
        val name: String,
        val baseColor: FloatArray = floatArrayOf(.8f, .8f, .8f, 1f),
        val metallic: Float = 0f,
        val roughness: Float = .6f,
        val emissive: FloatArray = floatArrayOf(0f, 0f, 0f),
        val baseColorTexture: Int? = null,
        val normalTexture: Int? = null,
        val emissiveTexture: Int? = null,
        val blend: Boolean = false,
        val doubleSided: Boolean = true,
    )

    /** One draw call: triangles of a single material. Arrays are flat (3 per position/normal, 2 per uv, 4 per color). */
    class Primitive(
        val positions: FloatArray,
        val normals: FloatArray?,
        val uvs: FloatArray?,
        val colors: FloatArray?,
        val indices: IntArray,
        val material: Int,
    ) {
        init {
            require(positions.size % 3 == 0)
            val count = positions.size / 3
            require(normals == null || normals.size == count * 3)
            require(uvs == null || uvs.size == count * 2)
            require(colors == null || colors.size == count * 4)
            require(indices.size % 3 == 0 && indices.all { it in 0 until count })
        }
        val vertexCount: Int get() = positions.size / 3
    }

    class Mesh(val name: String, val primitives: List<Primitive>)

    /** [matrix] is column-major local transform (null = identity). */
    class Node(val name: String, val matrix: FloatArray?, val mesh: Int?, val children: List<Int>)

    val images = ArrayList<Image>()
    val materials = ArrayList<Material>()
    val meshes = ArrayList<Mesh>()
    val nodes = ArrayList<Node>()
    val roots = ArrayList<Int>()

    fun addImage(image: Image): Int { images += image; return images.lastIndex }
    fun addMaterial(material: Material): Int { materials += material; return materials.lastIndex }
    fun addMesh(mesh: Mesh): Int { meshes += mesh; return meshes.lastIndex }
    fun addNode(node: Node): Int { nodes += node; return nodes.lastIndex }

    fun build(generator: String = "Recly"): ByteArray {
        require(nodes.isNotEmpty() && roots.isNotEmpty()) { "O modelo não tem nenhum objeto" }
        require(meshes.any { m -> m.primitives.any { it.indices.isNotEmpty() } }) { "O modelo não tem nenhuma face" }
        if (materials.isEmpty()) materials += Material("default")
        val bin = ByteArrayOutputStream()
        val bufferViews = ArrayList<String>()
        val accessors = ArrayList<String>()

        fun pad() { while (bin.size() % 4 != 0) bin.write(0) }
        fun view(bytes: ByteArray, target: Int?): Int {
            pad()
            val offset = bin.size()
            bin.write(bytes)
            bufferViews += buildString {
                append("{\"buffer\":0,\"byteOffset\":").append(offset).append(",\"byteLength\":").append(bytes.size)
                if (target != null) append(",\"target\":").append(target)
                append('}')
            }
            return bufferViews.lastIndex
        }
        fun floats(values: FloatArray): ByteArray {
            val b = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach { b.putFloat(if (it.isFinite()) it else 0f) }
            return b.array()
        }
        fun accessor(viewIndex: Int, componentType: Int, count: Int, type: String, minMax: Pair<FloatArray, FloatArray>? = null): Int {
            accessors += buildString {
                append("{\"bufferView\":").append(viewIndex).append(",\"componentType\":").append(componentType)
                append(",\"count\":").append(count).append(",\"type\":\"").append(type).append('"')
                if (minMax != null) {
                    append(",\"min\":[").append(minMax.first.joinToString(",") { number(it) }).append(']')
                    append(",\"max\":[").append(minMax.second.joinToString(",") { number(it) }).append(']')
                }
                append('}')
            }
            return accessors.lastIndex
        }

        val meshJson = meshes.map { mesh ->
            val primitives = mesh.primitives.filter { it.indices.isNotEmpty() }.map { p ->
                val count = p.vertexCount
                val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
                val max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
                for (i in 0 until count) for (c in 0 until 3) {
                    val v = p.positions[i * 3 + c].let { if (it.isFinite()) it else 0f }
                    min[c] = min(min[c], v); max[c] = max(max[c], v)
                }
                val attributes = StringBuilder()
                attributes.append("\"POSITION\":").append(accessor(view(floats(p.positions), ARRAY_BUFFER), FLOAT, count, "VEC3", min to max))
                p.normals?.let { attributes.append(",\"NORMAL\":").append(accessor(view(floats(normalized(it)), ARRAY_BUFFER), FLOAT, count, "VEC3")) }
                p.uvs?.let { attributes.append(",\"TEXCOORD_0\":").append(accessor(view(floats(it), ARRAY_BUFFER), FLOAT, count, "VEC2")) }
                p.colors?.let { attributes.append(",\"COLOR_0\":").append(accessor(view(floats(it.map { c -> c.coerceIn(0f, 1f) }.toFloatArray()), ARRAY_BUFFER), FLOAT, count, "VEC4")) }
                val indices = if (count <= 65_535) {
                    val b = ByteBuffer.allocate(p.indices.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    p.indices.forEach { b.putShort(it.toShort()) }
                    accessor(view(b.array(), ELEMENT_ARRAY_BUFFER), UNSIGNED_SHORT, p.indices.size, "SCALAR")
                } else {
                    val b = ByteBuffer.allocate(p.indices.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                    p.indices.forEach { b.putInt(it) }
                    accessor(view(b.array(), ELEMENT_ARRAY_BUFFER), UNSIGNED_INT, p.indices.size, "SCALAR")
                }
                "{\"attributes\":{$attributes},\"indices\":$indices,\"material\":${p.material.coerceIn(0, materials.lastIndex)},\"mode\":4}"
            }
            "{\"name\":${quote(mesh.name)},\"primitives\":[${primitives.joinToString(",")}]}"
        }
        val imageJson = images.map { image -> "{\"bufferView\":${view(image.bytes, null)},\"mimeType\":${quote(image.mimeType)}}" }
        pad()

        val json = StringBuilder()
        json.append("{\"asset\":{\"version\":\"2.0\",\"generator\":").append(quote(generator)).append('}')
        json.append(",\"scene\":0,\"scenes\":[{\"nodes\":[").append(roots.joinToString(",")).append("]}]")
        json.append(",\"nodes\":[").append(nodes.joinToString(",") { node ->
            buildString {
                append("{\"name\":").append(quote(node.name))
                node.mesh?.let { append(",\"mesh\":").append(it) }
                if (node.children.isNotEmpty()) append(",\"children\":[").append(node.children.joinToString(",")).append(']')
                node.matrix?.takeUnless(::isIdentity)?.let { m -> append(",\"matrix\":[").append(m.joinToString(",") { number(it) }).append(']') }
                append('}')
            }
        }).append(']')
        val usedMeshes = meshJson.withIndex().filter { (index, _) -> meshes[index].primitives.any { it.indices.isNotEmpty() } }
        require(usedMeshes.size == meshes.size) { "Malha vazia não deve ser adicionada" }
        json.append(",\"meshes\":[").append(meshJson.joinToString(",")).append(']')
        json.append(",\"materials\":[").append(materials.joinToString(",") { m ->
            buildString {
                append("{\"name\":").append(quote(m.name)).append(",\"pbrMetallicRoughness\":{\"baseColorFactor\":[")
                append(m.baseColor.joinToString(",") { number(it.coerceIn(0f, 1f)) }).append(']')
                append(",\"metallicFactor\":").append(number(m.metallic.coerceIn(0f, 1f)))
                append(",\"roughnessFactor\":").append(number(m.roughness.coerceIn(0f, 1f)))
                m.baseColorTexture?.let { append(",\"baseColorTexture\":{\"index\":").append(it).append('}') }
                append('}')
                m.normalTexture?.let { append(",\"normalTexture\":{\"index\":").append(it).append('}') }
                m.emissiveTexture?.let { append(",\"emissiveTexture\":{\"index\":").append(it).append('}') }
                if (m.emissive.any { it > 0f } || m.emissiveTexture != null) {
                    val e = if (m.emissiveTexture != null && m.emissive.all { it <= 0f }) floatArrayOf(1f, 1f, 1f) else m.emissive
                    append(",\"emissiveFactor\":[").append(e.joinToString(",") { number(it.coerceIn(0f, 1f)) }).append(']')
                }
                if (m.blend) append(",\"alphaMode\":\"BLEND\"")
                append(",\"doubleSided\":").append(m.doubleSided)
                append('}')
            }
        }).append(']')
        if (images.isNotEmpty()) {
            json.append(",\"images\":[").append(imageJson.joinToString(",")).append(']')
            json.append(",\"samplers\":[{\"magFilter\":9729,\"minFilter\":9987,\"wrapS\":10497,\"wrapT\":10497}]")
            json.append(",\"textures\":[").append(images.indices.joinToString(",") { "{\"sampler\":0,\"source\":$it}" }).append(']')
        }
        json.append(",\"accessors\":[").append(accessors.joinToString(",")).append(']')
        json.append(",\"bufferViews\":[").append(bufferViews.joinToString(",")).append(']')
        json.append(",\"buffers\":[{\"byteLength\":").append(bin.size()).append("}]}")
        return GlbWriter.write(json.toString(), bin.toByteArray())
    }

    private fun normalized(n: FloatArray): FloatArray {
        val out = FloatArray(n.size)
        for (i in 0 until n.size / 3) {
            val x = n[i * 3]; val y = n[i * 3 + 1]; val z = n[i * 3 + 2]
            val l = kotlin.math.sqrt(x * x + y * y + z * z)
            if (l > 1e-12f && l.isFinite()) { out[i * 3] = x / l; out[i * 3 + 1] = y / l; out[i * 3 + 2] = z / l }
            else { out[i * 3 + 1] = 1f }
        }
        return out
    }

    companion object {
        const val FLOAT = 5126
        const val UNSIGNED_SHORT = 5123
        const val UNSIGNED_INT = 5125
        const val ARRAY_BUFFER = 34962
        const val ELEMENT_ARRAY_BUFFER = 34963

        fun number(value: Float): String = if (!value.isFinite()) "0" else
            java.math.BigDecimal(value.toDouble()).round(java.math.MathContext(9)).stripTrailingZeros().toPlainString()

        fun quote(value: String): String = buildString {
            append('"')
            value.forEach { c ->
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c < ' ' -> append(String.format(java.util.Locale.ROOT, "\\u%04x", c.code))
                    else -> append(c)
                }
            }
            append('"')
        }

        fun isIdentity(m: FloatArray): Boolean = m.indices.all { i -> kotlin.math.abs(m[i] - if (i % 5 == 0) 1f else 0f) < 1e-7f }

        /** Smooth normals: each triangle's normal is added to its corners, weighted by area. */
        fun smoothNormals(positions: FloatArray, indices: IntArray): FloatArray {
            val normals = FloatArray(positions.size)
            for (t in 0 until indices.size / 3) {
                val a = indices[t * 3]; val b = indices[t * 3 + 1]; val c = indices[t * 3 + 2]
                val ux = positions[b * 3] - positions[a * 3]; val uy = positions[b * 3 + 1] - positions[a * 3 + 1]; val uz = positions[b * 3 + 2] - positions[a * 3 + 2]
                val vx = positions[c * 3] - positions[a * 3]; val vy = positions[c * 3 + 1] - positions[a * 3 + 1]; val vz = positions[c * 3 + 2] - positions[a * 3 + 2]
                val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
                for (v in intArrayOf(a, b, c)) { normals[v * 3] += nx; normals[v * 3 + 1] += ny; normals[v * 3 + 2] += nz }
            }
            return normals
        }
    }
}

/** Collects triangle corners into indexed vertices, merging identical (position, uv, normal, color) corners. */
class VertexWelder(private val hasUv: Boolean, private val hasNormal: Boolean, private val hasColor: Boolean) {
    private data class Key(val position: Int, val uv: Int, val normal: Int, val color: Int)
    private val lookup = HashMap<Key, Int>()
    private val positions = FloatList(); private val normals = FloatList(); private val uvs = FloatList(); private val colors = FloatList()
    private val indices = IntList()

    /**
     * Adds one corner. [positionKey]/[uvKey]/[normalKey]/[colorKey] identify the source data for
     * merging; the float values are copied the first time a key combination appears.
     */
    fun corner(positionKey: Int, uvKey: Int, normalKey: Int, colorKey: Int,
        position: () -> FloatArray, uv: () -> FloatArray, normal: () -> FloatArray, color: () -> FloatArray) {
        val key = Key(positionKey, if (hasUv) uvKey else 0, if (hasNormal) normalKey else 0, if (hasColor) colorKey else 0)
        val index = lookup.getOrPut(key) {
            positions.addAll(position())
            if (hasUv) uvs.addAll(uv())
            if (hasNormal) normals.addAll(normal())
            if (hasColor) colors.addAll(color())
            positions.size / 3 - 1
        }
        indices.add(index)
    }

    val isEmpty: Boolean get() = indices.size == 0

    fun primitive(material: Int, computeNormals: Boolean): GltfBuilder.Primitive {
        val p = positions.toArray(); val i = indices.toArray()
        val n = if (hasNormal) normals.toArray() else if (computeNormals) GltfBuilder.smoothNormals(p, i) else null
        return GltfBuilder.Primitive(p, n, if (hasUv) uvs.toArray() else null, if (hasColor) colors.toArray() else null, i, material)
    }
}

class FloatList {
    private var data = FloatArray(1024); var size = 0; private set
    fun add(v: Float) { if (size == data.size) data = data.copyOf(max(16, size * 2)); data[size++] = v }
    fun addAll(v: FloatArray) { v.forEach(::add) }
    operator fun get(i: Int): Float { require(i in 0 until size); return data[i] }
    fun toArray(): FloatArray = data.copyOf(size)
}

class IntList {
    private var data = IntArray(1024); var size = 0; private set
    fun add(v: Int) { if (size == data.size) data = data.copyOf(max(16, size * 2)); data[size++] = v }
    operator fun get(i: Int): Int { require(i in 0 until size); return data[i] }
    fun toArray(): IntArray = data.copyOf(size)
}
