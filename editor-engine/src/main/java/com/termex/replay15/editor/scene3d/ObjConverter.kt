package com.termex.replay15.editor.scene3d

import kotlin.math.sqrt

/**
 * Files that came with a model (picked together, or inside a .zip). Paths written inside model
 * files (Windows separators, "./", URL escapes, other folders) are matched by relative path
 * first and then by file name, ignoring case.
 */
class ModelBundle(private val files: Map<String, () -> ByteArray>) {
    private val byPath = files.keys.associateBy { normalize(it) }
    private val byName = files.keys.groupBy { normalize(it).substringAfterLast('/') }

    fun find(reference: String): ByteArray? {
        val clean = normalize(reference)
        if (clean.isBlank()) return null
        byPath[clean]?.let { return files.getValue(it)() }
        byPath.entries.firstOrNull { (path, _) -> path.endsWith("/$clean") }?.let { return files.getValue(it.value)() }
        val candidates = byName[clean.substringAfterLast('/')] ?: return null
        return files.getValue(candidates.first())()
    }

    val names: Set<String> get() = files.keys

    companion object {
        fun normalize(path: String): String {
            var p = path.trim().trim('"').replace('\\', '/')
            p = runCatching { java.net.URLDecoder.decode(p.replace("+", "%2B"), "UTF-8") }.getOrDefault(p)
            while (p.startsWith("./")) p = p.substring(2)
            p = p.substringAfter("file://").trimStart('/')
            // Absolute Windows paths keep only what can exist in the bundle.
            if (p.length > 2 && p[1] == ':') p = p.substring(2).trimStart('/')
            return p.lowercase(java.util.Locale.ROOT)
        }
    }
}

/**
 * Wavefront OBJ (+ MTL) to glTF: polygons (triangulated, concave ones by ear clipping),
 * negative indices, groups/objects, materials with diffuse/specular/emissive/opacity and their
 * texture maps, the PBR extension (Pr/Pm), per-vertex colors, and smooth normals when the file
 * has none. Lines and points are skipped (they are not surfaces).
 */
object ObjConverter {
    class Result(val glb: ByteArray, val warnings: List<String>)

    private class MtlMaterial(val name: String) {
        var diffuse = floatArrayOf(.8f, .8f, .8f)
        var specular = floatArrayOf(0f, 0f, 0f)
        var emissive = floatArrayOf(0f, 0f, 0f)
        var shininess = -1f
        var opacity = 1f
        var roughness: Float? = null
        var metallic: Float? = null
        var diffuseMap: String? = null
        var normalMap: String? = null
        var emissiveMap: String? = null
        var alphaMap: String? = null
    }

    private class Group(val name: String, val material: String?) {
        // Each face: corners as (position, uv, normal) absolute zero-based indices, -1 when absent.
        val faces = ArrayList<IntArray>()
    }

    fun convert(text: String, bundle: ModelBundle, name: String = "model"): Result {
        val warnings = ArrayList<String>()
        val positions = FloatList(); val colors = FloatList(); val uvs = FloatList(); val normals = FloatList()
        var colorCount = 0
        val groups = ArrayList<Group>()
        val materials = LinkedHashMap<String, MtlMaterial>()
        var currentName = name
        var currentMaterial: String? = null
        var current: Group? = null
        fun group(): Group = current ?: Group(currentName, currentMaterial).also { groups += it; current = it }

        logicalLines(text).forEach { line ->
            val tokens = line.split(WHITESPACE).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return@forEach
            when (tokens[0]) {
                "v" -> {
                    require(tokens.size >= 4) { "Vértice inválido: $line" }
                    for (i in 1..3) positions.add(number(tokens[i]))
                    if (tokens.size >= 7) { for (i in 4..6) colors.add(number(tokens[i])); colorCount++ }
                    else if (tokens.size == 5 || tokens.size == 4) { colors.add(Float.NaN); colors.add(Float.NaN); colors.add(Float.NaN) }
                    else { colors.add(Float.NaN); colors.add(Float.NaN); colors.add(Float.NaN) }
                }
                "vt" -> { require(tokens.size >= 2) { "UV inválida: $line" }; uvs.add(number(tokens[1])); uvs.add(if (tokens.size >= 3) number(tokens[2]) else 0f) }
                "vn" -> { require(tokens.size >= 4) { "Normal inválida: $line" }; for (i in 1..3) normals.add(number(tokens[i])) }
                "f" -> {
                    if (tokens.size < 4) return@forEach
                    val vCount = positions.size / 3; val tCount = uvs.size / 2; val nCount = normals.size / 3
                    val corners = IntArray((tokens.size - 1) * 3)
                    for (i in 1 until tokens.size) {
                        val parts = tokens[i].split('/')
                        fun index(text: String?, count: Int): Int {
                            if (text.isNullOrEmpty()) return -1
                            val raw = text.toInt()
                            val resolved = if (raw < 0) count + raw else raw - 1
                            require(resolved in 0 until count) { "Índice fora do modelo: ${tokens[i]}" }
                            return resolved
                        }
                        corners[(i - 1) * 3] = index(parts[0], vCount)
                        corners[(i - 1) * 3 + 1] = index(parts.getOrNull(1), tCount)
                        corners[(i - 1) * 3 + 2] = index(parts.getOrNull(2), nCount)
                    }
                    group().faces += corners
                }
                "o", "g" -> { currentName = tokens.drop(1).joinToString(" ").ifBlank { name }; current = null }
                "usemtl" -> { currentMaterial = tokens.drop(1).joinToString(" ").ifBlank { null }; current = null }
                "mtllib" -> {
                    val reference = line.substringAfter("mtllib").trim()
                    val mtl = bundle.find(reference) ?: reference.split(WHITESPACE).firstNotNullOfOrNull { bundle.find(it) }
                    if (mtl == null) warnings += "Arquivo de materiais \"$reference\" não veio junto (cores padrão)."
                    else parseMtl(decodeText(mtl), materials)
                }
                "l", "p" -> Unit // not surfaces
                else -> Unit // s, vp, cstype and comments are irrelevant here
            }
        }
        if (positions.size == 0) throw IllegalArgumentException("O OBJ não tem vértices")
        val hasColors = colorCount > 0

        val builder = GltfBuilder()
        val imageCache = HashMap<String, Int?>()
        fun texture(reference: String?): Int? {
            if (reference == null) return null
            return imageCache.getOrPut(reference) {
                val bytes = bundle.find(reference)
                if (bytes == null) { warnings += "Textura \"$reference\" não veio junto."; return@getOrPut null }
                val image = TextureImages.toGltf(reference, bytes)
                if (image == null) { warnings += "Textura \"$reference\" em formato não suportado (use PNG, JPG, TGA ou BMP)."; null }
                else builder.addImage(image)
            }
        }
        val materialIndex = HashMap<String?, Int>()
        fun material(nameOrNull: String?): Int = materialIndex.getOrPut(nameOrNull) {
            val m = nameOrNull?.let { materials[it] }
            if (m == null) return@getOrPut builder.addMaterial(GltfBuilder.Material(nameOrNull ?: "default"))
            val roughness = m.roughness ?: if (m.shininess >= 0f) sqrt(2f / (m.shininess + 2f)).coerceIn(.04f, 1f) else .6f
            val metallic = m.metallic ?: 0f
            val alpha = m.opacity.coerceIn(0f, 1f)
            builder.addMaterial(GltfBuilder.Material(
                name = m.name,
                baseColor = floatArrayOf(m.diffuse[0], m.diffuse[1], m.diffuse[2], alpha),
                metallic = metallic, roughness = roughness, emissive = m.emissive,
                baseColorTexture = texture(m.diffuseMap), normalTexture = texture(m.normalMap), emissiveTexture = texture(m.emissiveMap),
                blend = alpha < .999f || m.alphaMap != null,
            ))
        }

        val nodeChildren = ArrayList<Int>()
        // One mesh per OBJ object/group, one primitive per material inside it.
        groups.groupBy { it.name }.forEach { (groupName, parts) ->
            val primitives = ArrayList<GltfBuilder.Primitive>()
            parts.groupBy { it.material }.forEach { (materialName, sameMaterial) ->
                val faces = sameMaterial.flatMap { it.faces }
                if (faces.isEmpty()) return@forEach
                val hasUv = faces.all { f -> (0 until f.size / 3).all { f[it * 3 + 1] >= 0 } }
                val hasNormal = faces.all { f -> (0 until f.size / 3).all { f[it * 3 + 2] >= 0 } }
                val welder = VertexWelder(hasUv, hasNormal, hasColors)
                faces.forEach { f ->
                    val count = f.size / 3
                    val polygon = FloatArray(count * 3) { positions[f[(it / 3) * 3] * 3 + it % 3] }
                    Triangulator.triangulate(polygon).forEach { corner ->
                        val p = f[corner * 3]; val t = f[corner * 3 + 1]; val n = f[corner * 3 + 2]
                        welder.corner(p, t, n, p,
                            { floatArrayOf(positions[p * 3], positions[p * 3 + 1], positions[p * 3 + 2]) },
                            // glTF puts the texture origin at the top-left; OBJ at the bottom-left.
                            { floatArrayOf(uvs[t * 2], 1f - uvs[t * 2 + 1]) },
                            { floatArrayOf(normals[n * 3], normals[n * 3 + 1], normals[n * 3 + 2]) },
                            {
                                val r = colors[p * 3]
                                if (r.isNaN()) floatArrayOf(1f, 1f, 1f, 1f)
                                else floatArrayOf(r, colors[p * 3 + 1], colors[p * 3 + 2], 1f)
                            })
                    }
                }
                if (!welder.isEmpty) primitives += welder.primitive(material(materialName), computeNormals = true)
            }
            if (primitives.isNotEmpty()) {
                val mesh = builder.addMesh(GltfBuilder.Mesh(groupName, primitives))
                nodeChildren += builder.addNode(GltfBuilder.Node(groupName, null, mesh, emptyList()))
            }
        }
        require(nodeChildren.isNotEmpty()) { "O OBJ não tem faces (só pontos ou linhas)" }
        builder.roots += builder.addNode(GltfBuilder.Node(name, null, null, nodeChildren))
        return Result(builder.build(), warnings)
    }

    private fun parseMtl(text: String, into: MutableMap<String, MtlMaterial>) {
        var current: MtlMaterial? = null
        logicalLines(text).forEach { line ->
            val tokens = line.split(WHITESPACE).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return@forEach
            val m = current
            when (tokens[0].lowercase()) {
                "newmtl" -> { val n = tokens.drop(1).joinToString(" "); current = MtlMaterial(n).also { into[n] = it } }
                "kd" -> m?.diffuse = color(tokens)
                "ks" -> m?.specular = color(tokens)
                "ke" -> m?.emissive = color(tokens)
                "ns" -> m?.shininess = tokens.getOrNull(1)?.let(::number) ?: -1f
                "d" -> m?.opacity = tokens.lastOrNull()?.let(::number)?.takeIf { it.isFinite() } ?: 1f
                "tr" -> m?.opacity = 1f - (tokens.lastOrNull()?.let(::number)?.takeIf { it.isFinite() } ?: 0f)
                "pr" -> m?.roughness = tokens.getOrNull(1)?.let(::number)
                "pm" -> m?.metallic = tokens.getOrNull(1)?.let(::number)
                "map_kd" -> m?.diffuseMap = mapFile(tokens)
                "map_ke" -> m?.emissiveMap = mapFile(tokens)
                "map_d" -> m?.alphaMap = mapFile(tokens)
                "norm", "map_bump", "bump", "map_kn" -> m?.normalMap = mapFile(tokens)
            }
        }
    }

    private fun color(tokens: List<String>): FloatArray {
        val values = tokens.drop(1).mapNotNull { it.toFloatOrNull() }
        return when {
            values.size >= 3 -> floatArrayOf(values[0], values[1], values[2])
            values.size == 1 -> floatArrayOf(values[0], values[0], values[0])
            else -> floatArrayOf(.8f, .8f, .8f)
        }.map { it.coerceIn(0f, 1f) }.toFloatArray()
    }

    /** File name of a map statement, skipping its options (-s 1 1 1, -bm 0.5, -clamp on, ...). */
    private fun mapFile(tokens: List<String>): String? {
        val counts = mapOf("-blendu" to 1, "-blendv" to 1, "-boost" to 1, "-mm" to 2, "-o" to 3, "-s" to 3, "-t" to 3,
            "-texres" to 1, "-clamp" to 1, "-bm" to 1, "-imfchan" to 1, "-type" to 1, "-cc" to 1)
        var i = 1
        while (i < tokens.size) {
            val option = counts[tokens[i].lowercase()] ?: break
            i++
            var taken = 0
            while (taken < option && i < tokens.size && (tokens[i].toFloatOrNull() != null || option == 1)) { i++; taken++ }
        }
        return tokens.drop(i).joinToString(" ").ifBlank { null }
    }

    private val WHITESPACE = Regex("\\s+")

    private val LEADING_NUMBER = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")

    /** Tolerant like common OBJ readers: "1,5" and "3.1+e2"-style typos read their valid prefix. */
    private fun number(text: String): Float = text.toFloatOrNull() ?: text.replace(',', '.').toFloatOrNull()
        ?: LEADING_NUMBER.find(text.replace(',', '.'))?.value?.toFloatOrNull()
        ?: throw IllegalArgumentException("Número inválido no modelo: $text")

    /** Lines without comments, with "\" continuations joined. */
    private fun logicalLines(text: String): Sequence<String> = sequence {
        val pending = StringBuilder()
        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trimEnd()
            if (line.endsWith("\\")) { pending.append(line.dropLast(1)).append(' '); return@forEach }
            pending.append(line)
            val full = pending.toString().trim()
            pending.setLength(0)
            if (full.isNotEmpty()) yield(full)
        }
        if (pending.isNotBlank()) yield(pending.toString().trim())
    }

    /** UTF-8 by default; UTF-16 files (with a byte order mark) are decoded too. */
    fun decodeText(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        else -> String(bytes, Charsets.UTF_8)
    }
}

/**
 * Triangulates a planar polygon given as flat xyz corners; returns corner indices.
 * Convex polygons are fanned; others are ear-clipped in their best-fit plane.
 */
object Triangulator {
    fun triangulate(polygon: FloatArray): IntArray {
        val n = polygon.size / 3
        if (n < 3) return IntArray(0)
        if (n == 3) return intArrayOf(0, 1, 2)
        // Newell normal of the polygon.
        var nx = 0f; var ny = 0f; var nz = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            val xi = polygon[i * 3]; val yi = polygon[i * 3 + 1]; val zi = polygon[i * 3 + 2]
            val xj = polygon[j * 3]; val yj = polygon[j * 3 + 1]; val zj = polygon[j * 3 + 2]
            nx += (yi - yj) * (zi + zj); ny += (zi - zj) * (xi + xj); nz += (xi - xj) * (yi + yj)
        }
        val ax = kotlin.math.abs(nx); val ay = kotlin.math.abs(ny); val az = kotlin.math.abs(nz)
        if (ax + ay + az < 1e-20f) return fan(n)
        // Project onto the plane most facing the normal, keeping counter-clockwise orientation.
        val u = FloatArray(n); val v = FloatArray(n)
        for (i in 0 until n) {
            val x = polygon[i * 3]; val y = polygon[i * 3 + 1]; val z = polygon[i * 3 + 2]
            when {
                ax >= ay && ax >= az -> { u[i] = y; v[i] = z; if (nx < 0) u[i] = -u[i] }
                ay >= ax && ay >= az -> { u[i] = z; v[i] = x; if (ny < 0) u[i] = -u[i] }
                else -> { u[i] = x; v[i] = y; if (nz < 0) u[i] = -u[i] }
            }
        }
        fun cross(a: Int, b: Int, c: Int) = (u[b] - u[a]) * (v[c] - v[a]) - (v[b] - v[a]) * (u[c] - u[a])
        val convex = (0 until n).all { cross(it, (it + 1) % n, (it + 2) % n) >= -1e-12f }
        if (convex) return fan(n)
        val remaining = (0 until n).toMutableList()
        val out = IntList()
        var guard = 0
        while (remaining.size > 3 && guard++ < n * n) {
            var clipped = false
            for (k in remaining.indices) {
                val a = remaining[(k + remaining.size - 1) % remaining.size]; val b = remaining[k]; val c = remaining[(k + 1) % remaining.size]
                if (cross(a, b, c) <= 1e-12f) continue
                val inside = remaining.any { p -> p != a && p != b && p != c && inTriangle(u, v, p, a, b, c) }
                if (inside) continue
                out.add(a); out.add(b); out.add(c)
                remaining.removeAt(k)
                clipped = true
                break
            }
            if (!clipped) break // degenerate polygon: finish with a fan below
        }
        if (remaining.size == 3) { out.add(remaining[0]); out.add(remaining[1]); out.add(remaining[2]) }
        else if (remaining.size > 3) for (k in 1 until remaining.size - 1) { out.add(remaining[0]); out.add(remaining[k]); out.add(remaining[k + 1]) }
        return out.toArray()
    }

    private fun fan(n: Int): IntArray = IntArray((n - 2) * 3) { i -> val t = i / 3; when (i % 3) { 0 -> 0; 1 -> t + 1; else -> t + 2 } }

    private fun inTriangle(u: FloatArray, v: FloatArray, p: Int, a: Int, b: Int, c: Int): Boolean {
        fun side(x: Int, y: Int) = (u[y] - u[x]) * (v[p] - v[x]) - (v[y] - v[x]) * (u[p] - u[x])
        val s1 = side(a, b); val s2 = side(b, c); val s3 = side(c, a)
        return s1 >= 0f && s2 >= 0f && s3 >= 0f
    }
}
