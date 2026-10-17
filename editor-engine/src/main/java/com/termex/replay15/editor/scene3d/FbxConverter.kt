package com.termex.replay15.editor.scene3d

import com.termex.replay15.editor.domain.Matrix4

/**
 * FBX 7.x (binary or ASCII) to glTF: the model hierarchy with full FBX transforms (pivots,
 * offsets, pre/post rotation, every rotation order, geometric transforms), meshes with
 * normals/UVs/vertex colors in any mapping, per-polygon materials, Phong and common PBR
 * material properties, and textures embedded in the file or shipped beside it. Axes and
 * handedness follow GlobalSettings. Skinning, blend shapes and animation are not converted
 * (the mesh is kept in its bind pose).
 */
object FbxConverter {
    class Result(val glb: ByteArray, val warnings: List<String>)

    private class FbxObject(val id: Long, val name: String, val type: String, val node: FbxNode)

    fun convert(bytes: ByteArray, bundle: ModelBundle, fallbackName: String = "model"): Result {
        val document = FbxParser.parse(bytes)
        val warnings = ArrayList<String>()
        val objects = HashMap<Long, FbxObject>()
        document.node("Objects")?.children?.forEach { node ->
            val id = node.long(0) ?: return@forEach
            val (name, _) = className(node.string(1) ?: "")
            objects[id] = FbxObject(id, name, node.string(2) ?: "", node)
        }
        require(objects.isNotEmpty()) { "O FBX não tem objetos" }
        // child -> parents (object-object) and child -> (parent, property) (object-property).
        val parentsOf = HashMap<Long, MutableList<Long>>()
        val childrenOf = LinkedHashMap<Long, MutableList<Long>>()
        val propertyLinks = ArrayList<Triple<Long, Long, String>>()
        document.node("Connections")?.all("C")?.forEach { c ->
            val kind = c.string(0) ?: return@forEach
            val child = c.long(1) ?: return@forEach
            val parent = c.long(2) ?: return@forEach
            when (kind) {
                "OO" -> { parentsOf.getOrPut(child) { ArrayList() } += parent; childrenOf.getOrPut(parent) { ArrayList() } += child }
                "OP" -> propertyLinks += Triple(child, parent, c.string(3) ?: "")
            }
        }
        val models = objects.values.filter { it.node.name == "Model" }
        if (models.isEmpty()) throw IllegalArgumentException("O FBX não tem modelos")
        if (objects.values.any { it.node.name == "Deformer" && it.type == "Skin" }) {
            warnings += "Esqueleto e animação do FBX não são importados; o modelo fica na pose original."
        }

        val builder = GltfBuilder()
        val imageCache = HashMap<Long, Int?>()
        fun textureImage(textureId: Long): Int? = imageCache.getOrPut(textureId) {
            val texture = objects[textureId] ?: return@getOrPut null
            val videoId = childrenOf[textureId]?.firstOrNull { objects[it]?.node?.name == "Video" }
            val video = videoId?.let { objects[it] }
            val embedded = video?.node?.child("Content")?.let { content ->
                val raw = content.properties.firstOrNull { it is ByteArray } as? ByteArray
                raw?.takeIf { it.isNotEmpty() } ?: decodeAsciiContent(content.properties.filterIsInstance<String>())
            }
            val names = listOfNotNull(
                texture.node.child("RelativeFilename")?.string(0), texture.node.child("FileName")?.string(0),
                video?.node?.child("RelativeFilename")?.string(0), video?.node?.child("Filename")?.string(0),
            ).filter { it.isNotBlank() }
            val label = names.firstOrNull() ?: texture.name
            val data = embedded?.takeIf { it.isNotEmpty() } ?: names.firstNotNullOfOrNull { bundle.find(it) }
            if (data == null) { warnings += "Textura \"$label\" não veio junto."; return@getOrPut null }
            val image = TextureImages.toGltf(label, data)
            if (image == null) { warnings += "Textura \"$label\" em formato não suportado (use PNG, JPG, TGA ou BMP)."; null }
            else builder.addImage(image)
        }
        fun textureFor(materialId: Long, vararg properties: String): Int? {
            val link = propertyLinks.firstOrNull { (child, parent, property) ->
                parent == materialId && objects[child]?.node?.name == "Texture" && properties.any { property.equals(it, ignoreCase = true) || property.endsWith("|$it", ignoreCase = true) }
            } ?: return null
            return textureImage(link.first)
        }

        val materialIndex = HashMap<Long, Int>()
        fun material(id: Long?): Int {
            if (id == null) return materialIndex.getOrPut(-1L) { builder.addMaterial(GltfBuilder.Material("default")) }
            return materialIndex.getOrPut(id) {
                val o = objects[id] ?: return@getOrPut material(null)
                val p = o.node.properties70()
                fun color(vararg names: String): FloatArray? = names.firstNotNullOfOrNull { n ->
                    p.entries.firstOrNull { it.key.equals(n, true) || it.key.endsWith("|$n", true) }?.value?.let { v ->
                        val a = v.double(4); val b = v.double(5); val c = v.double(6)
                        if (a != null && b != null && c != null) floatArrayOf(a.toFloat(), b.toFloat(), c.toFloat()) else null
                    }
                }
                fun scalar(vararg names: String): Float? = names.firstNotNullOfOrNull { n ->
                    p.entries.firstOrNull { it.key.equals(n, true) || it.key.endsWith("|$n", true) }?.value?.double(4)?.toFloat()
                }
                val pbrColor = color("base_color", "basecolor", "baseColor")
                val diffuse = pbrColor ?: color("DiffuseColor", "Diffuse")?.let { c ->
                    val factor = scalar("DiffuseFactor") ?: 1f
                    floatArrayOf(c[0] * factor, c[1] * factor, c[2] * factor)
                } ?: floatArrayOf(.8f, .8f, .8f)
                val opacity = scalar("Opacity")
                    ?: (1f - (scalar("TransparencyFactor") ?: 0f) * (color("TransparentColor")?.maxOrNull() ?: 1f))
                val alpha = opacity.coerceIn(0f, 1f).let { if (it < .01f) 1f else it } // fully transparent is a common exporter mistake
                val metallic = scalar("metalness", "metallic") ?: 0f
                val roughness = scalar("roughness", "specularRoughness") ?: scalar("ShininessExponent", "Shininess")?.let {
                    kotlin.math.sqrt(2f / (it.coerceAtLeast(0f) + 2f))
                } ?: .6f
                val emissive = color("EmissiveColor", "emission_color", "emission")?.let { c ->
                    val factor = scalar("EmissiveFactor", "emission") ?: 1f
                    floatArrayOf(c[0] * factor, c[1] * factor, c[2] * factor)
                } ?: floatArrayOf(0f, 0f, 0f)
                val base = textureFor(id, "DiffuseColor", "base_color_map", "baseColor", "base_color", "Diffuse")
                val normal = textureFor(id, "NormalMap", "normalCamera", "bump_map", "norm_map", "normal_map")
                val emission = textureFor(id, "EmissiveColor", "emission_map", "emission")
                val transparencyMap = propertyLinks.any { (child, parent, property) ->
                    parent == id && objects[child]?.node?.name == "Texture" && (property.equals("TransparentColor", true) || property.endsWith("opacity_map", true))
                }
                builder.addMaterial(GltfBuilder.Material(
                    name = o.name.ifBlank { "material" },
                    baseColor = floatArrayOf(diffuse[0].coerceIn(0f, 1f), diffuse[1].coerceIn(0f, 1f), diffuse[2].coerceIn(0f, 1f), alpha),
                    metallic = metallic.coerceIn(0f, 1f), roughness = roughness.coerceIn(.04f, 1f),
                    emissive = emissive.map { it.coerceIn(0f, 1f) }.toFloatArray(),
                    baseColorTexture = base, normalTexture = normal, emissiveTexture = emission,
                    blend = alpha < .999f || transparencyMap,
                ))
            }
        }

        // glTF node per FBX model, keeping the hierarchy.
        val nodeOf = HashMap<Long, Int>()
        fun modelChildren(id: Long) = childrenOf[id].orEmpty().filter { objects[it]?.node?.name == "Model" }
        fun buildNode(model: FbxObject, depth: Int): Int {
            require(depth < 256) { "Hierarquia do FBX profunda demais" }
            val p = model.node.properties70()
            val local = localTransform(p)
            val geometryIds = childrenOf[model.id].orEmpty().filter { objects[it]?.node?.name == "Geometry" && objects[it]?.type == "Mesh" }
            val materialIds = childrenOf[model.id].orEmpty().filter { objects[it]?.node?.name == "Material" }
            val primitives = ArrayList<GltfBuilder.Primitive>()
            geometryIds.forEach { gid ->
                primitives += meshPrimitives(objects.getValue(gid).node, geometricTransform(p), materialIds, ::material, warnings)
            }
            val mesh = if (primitives.isNotEmpty()) builder.addMesh(GltfBuilder.Mesh(model.name, primitives)) else null
            val children = modelChildren(model.id).mapNotNull { objects[it] }.map { buildNode(it, depth + 1) }
            return builder.addNode(GltfBuilder.Node(model.name.ifBlank { "node" }, local, mesh, children)).also { nodeOf[model.id] = it }
        }
        val rootModels = models.filter { m -> parentsOf[m.id].orEmpty().none { objects[it]?.node?.name == "Model" } }
        val roots = rootModels.map { buildNode(it, 0) }
        require(builder.meshes.isNotEmpty()) { "O FBX não tem malhas (só esqueleto, câmeras ou luzes)" }
        builder.roots += builder.addNode(GltfBuilder.Node(fallbackName, axisConversion(document), null, roots))
        return Result(builder.build("Recly FBX"), warnings)
    }

    /**
     * ASCII FBX writes embedded files as base64, sometimes split into several strings. Writers
     * either split one base64 text or encode each piece on its own, so both readings are tried.
     */
    fun decodeAsciiContent(parts: List<String>): ByteArray? {
        val pieces = parts.map { it.filterNot(Char::isWhitespace) }.filter { it.isNotEmpty() }
        if (pieces.isEmpty()) return null
        val decoder = java.util.Base64.getDecoder()
        val joined = runCatching { decoder.decode(pieces.joinToString("")) }.getOrNull()
        if (joined != null) return joined
        return runCatching {
            val out = java.io.ByteArrayOutputStream()
            pieces.forEach { out.write(decoder.decode(it)) }
            out.toByteArray()
        }.getOrNull()
    }

    /** "Name\u0000\u0001Class" (binary) or "Class::Name" (ASCII) to name and class. */
    fun className(raw: String): Pair<String, String> {
        val binary = raw.indexOf("\u0000\u0001")
        if (binary >= 0) return raw.substring(0, binary) to raw.substring(binary + 2)
        val ascii = raw.indexOf("::")
        if (ascii >= 0) return raw.substring(ascii + 2) to raw.substring(0, ascii)
        return raw to ""
    }

    private fun vector(p: Map<String, FbxNode>, name: String, default: Float): FloatArray {
        val n = p[name] ?: return floatArrayOf(default, default, default)
        return floatArrayOf((n.double(4) ?: default.toDouble()).toFloat(), (n.double(5) ?: default.toDouble()).toFloat(), (n.double(6) ?: default.toDouble()).toFloat())
    }

    /** FBX rotation orders: XYZ, XZY, YZX, YXZ, ZXY, ZYX (letters in the order they are applied). */
    fun rotation(degrees: FloatArray, order: Int, out: FloatArray = FloatArray(16)): FloatArray {
        Matrix4.setIdentity(out)
        val sequence = when (order) { 1 -> "XZY"; 2 -> "YZX"; 3 -> "YXZ"; 4 -> "ZXY"; 5 -> "ZYX"; else -> "XYZ" }
        // Applied first = rightmost factor: M = R(last) * R(middle) * R(first).
        for (axis in sequence.reversed()) when (axis) {
            'X' -> Matrix4.rotateX(out, degrees[0])
            'Y' -> Matrix4.rotateY(out, degrees[1])
            else -> Matrix4.rotateZ(out, degrees[2])
        }
        return out
    }

    private fun translation(v: FloatArray) = FloatArray(16).also { Matrix4.setIdentity(it); Matrix4.translate(it, v[0], v[1], v[2]) }
    private fun scaling(v: FloatArray) = FloatArray(16).also { Matrix4.setIdentity(it); Matrix4.scale(it, v[0], v[1], v[2]) }

    private fun product(vararg matrices: FloatArray): FloatArray {
        val out = FloatArray(16).also(Matrix4::setIdentity)
        matrices.forEach { Matrix4.multiply(out, out, it) }
        return out
    }

    /** FBX SDK local matrix: T * Roff * Rp * Rpre * R * Rpost^-1 * Rp^-1 * Soff * Sp * S * Sp^-1. */
    fun localTransform(p: Map<String, FbxNode>): FloatArray {
        val order = p["RotationOrder"]?.double(4)?.toInt() ?: 0
        val t = translation(vector(p, "Lcl Translation", 0f))
        val r = rotation(vector(p, "Lcl Rotation", 0f), order)
        val s = scaling(vector(p, "Lcl Scaling", 1f))
        val pre = rotation(vector(p, "PreRotation", 0f), 0)
        val post = rotation(vector(p, "PostRotation", 0f), 0)
        val postInverse = SceneProjection.invert(post) ?: FloatArray(16).also(Matrix4::setIdentity)
        val rotationPivot = vector(p, "RotationPivot", 0f)
        val scalingPivot = vector(p, "ScalingPivot", 0f)
        val rp = translation(rotationPivot); val rpInverse = translation(floatArrayOf(-rotationPivot[0], -rotationPivot[1], -rotationPivot[2]))
        val sp = translation(scalingPivot); val spInverse = translation(floatArrayOf(-scalingPivot[0], -scalingPivot[1], -scalingPivot[2]))
        val rotationOffset = translation(vector(p, "RotationOffset", 0f))
        val scalingOffset = translation(vector(p, "ScalingOffset", 0f))
        return product(t, rotationOffset, rp, pre, r, postInverse, rpInverse, scalingOffset, sp, s, spInverse)
    }

    /** Geometric transform: applies to this model's mesh only, never to its children. */
    fun geometricTransform(p: Map<String, FbxNode>): FloatArray = product(
        translation(vector(p, "GeometricTranslation", 0f)),
        rotation(vector(p, "GeometricRotation", 0f), 0),
        scaling(vector(p, "GeometricScaling", 1f)),
    )

    /** Converts FBX axes (GlobalSettings) to glTF's Y-up, right-handed, +Z front. */
    fun axisConversion(document: FbxParser.Document): FloatArray {
        val p = document.node("GlobalSettings")?.properties70() ?: emptyMap()
        fun int(name: String, default: Int) = p[name]?.double(4)?.toInt() ?: default
        val up = int("UpAxis", 1); val upSign = int("UpAxisSign", 1).let { if (it < 0) -1f else 1f }
        val front = int("FrontAxis", 2); val frontSign = int("FrontAxisSign", 1).let { if (it < 0) -1f else 1f }
        val coord = int("CoordAxis", 0); val coordSign = int("CoordAxisSign", 1).let { if (it < 0) -1f else 1f }
        if (setOf(up, front, coord) != setOf(0, 1, 2)) return FloatArray(16).also(Matrix4::setIdentity)
        // Row i of the 3x3 picks the FBX component that becomes glTF axis i.
        val rows = arrayOf(coord to coordSign, up to upSign, front to frontSign)
        val m = FloatArray(16)
        m[15] = 1f
        rows.forEachIndexed { row, (axis, sign) -> m[axis * 4 + row] = sign } // column-major: element (row, column=axis)
        return m
    }

    private fun meshPrimitives(geometry: FbxNode, geometric: FloatArray, materialIds: List<Long>, material: (Long?) -> Int,
        warnings: MutableList<String>): List<GltfBuilder.Primitive> {
        val vertices = geometry.child("Vertices")?.doubles() ?: return emptyList()
        val polygonIndex = geometry.child("PolygonVertexIndex")?.ints() ?: return emptyList()
        val controlCount = vertices.size / 3
        if (controlCount == 0 || polygonIndex.isEmpty()) return emptyList()

        class Layer(val values: DoubleArray, val indices: IntArray?, val mapping: String, val reference: String, val width: Int)
        fun layer(elementName: String, valuesName: String, indexName: String, width: Int): Layer? {
            val element = geometry.all(elementName).firstOrNull() ?: return null
            val values = element.child(valuesName)?.doubles() ?: return null
            if (values.isEmpty()) return null
            return Layer(values, element.child(indexName)?.ints(),
                element.child("MappingInformationType")?.string(0) ?: "ByPolygonVertex",
                element.child("ReferenceInformationType")?.string(0) ?: "Direct", width)
        }
        val normals = layer("LayerElementNormal", "Normals", "NormalsIndex", 3)
        val uvs = layer("LayerElementUV", "UV", "UVIndex", 2)
        val colors = layer("LayerElementColor", "Colors", "ColorIndex", 4)
        val materialLayer = geometry.all("LayerElementMaterial").firstOrNull()
        val materialMapping = materialLayer?.child("MappingInformationType")?.string(0) ?: "AllSame"
        val materialPerPolygon = materialLayer?.child("Materials")?.ints() ?: IntArray(0)

        /** Index into a layer's values for one polygon corner; -1 when unavailable. */
        fun resolve(layer: Layer?, polygonVertex: Int, controlPoint: Int, polygon: Int): Int {
            if (layer == null) return -1
            val direct = when (layer.mapping) {
                "ByPolygonVertex" -> polygonVertex
                "ByVertice", "ByVertex", "ByControlPoint" -> controlPoint
                "ByPolygon" -> polygon
                "AllSame" -> 0
                else -> polygonVertex
            }
            // "IndexToDirect" without an index array is read as direct, like other FBX readers do.
            val indexed = (layer.reference == "IndexToDirect" || layer.reference == "Index") && layer.indices != null
            val index = if (indexed) layer.indices!!.getOrNull(direct) ?: -1 else direct
            return if (index >= 0 && (index + 1) * layer.width <= layer.values.size) index else -1
        }

        // Corners of every polygon (a negative index, bitwise-not, closes a polygon).
        val polygons = ArrayList<IntRange>()
        var start = 0
        polygonIndex.forEachIndexed { i, raw -> if (raw < 0) { polygons += start..i; start = i + 1 } }
        if (start < polygonIndex.size) polygons += start until polygonIndex.size
        fun control(i: Int) = polygonIndex[i].let { if (it < 0) it.inv() else it }
        if (polygonIndex.indices.any { control(it) !in 0 until controlCount }) {
            warnings += "Uma malha do FBX tem índices inválidos e foi ignorada."
            return emptyList()
        }

        val normalMatrix = SceneProjection.invert(geometric)?.let { inverse ->
            FloatArray(16) { i -> inverse[(i % 4) * 4 + i / 4] } // transpose of the inverse
        } ?: geometric
        fun position(cp: Int): FloatArray {
            val v = Matrix4.transformPoint(geometric, vertices[cp * 3].toFloat(), vertices[cp * 3 + 1].toFloat(), vertices[cp * 3 + 2].toFloat(), 1f)
            return floatArrayOf(v[0], v[1], v[2])
        }
        fun layerValue(layer: Layer, index: Int) = FloatArray(layer.width) { layer.values[index * layer.width + it].toFloat() }

        val hasUv = uvs != null; val hasNormal = normals != null; val hasColor = colors != null
        val welders = LinkedHashMap<Int, VertexWelder>()
        var missingData = false
        polygons.forEachIndexed { polygon, range ->
            if (range.count() < 3) return@forEachIndexed
            val slot = when (materialMapping) {
                "ByPolygon" -> materialPerPolygon.getOrNull(polygon) ?: 0
                else -> materialPerPolygon.firstOrNull() ?: 0
            }
            val welder = welders.getOrPut(slot) { VertexWelder(hasUv, hasNormal, hasColor) }
            val cps = range.map(::control)
            val flat = FloatArray(cps.size * 3) { vertices[cps[it / 3] * 3 + it % 3].toFloat() }
            Triangulator.triangulate(flat).forEach { k ->
                val pv = range.first + k; val cp = cps[k]
                val n = resolve(normals, pv, cp, polygon); val t = resolve(uvs, pv, cp, polygon); val c = resolve(colors, pv, cp, polygon)
                if ((hasNormal && n < 0) || (hasUv && t < 0) || (hasColor && c < 0)) missingData = true
                welder.corner(cp, t, n, c,
                    { position(cp) },
                    { if (t < 0) floatArrayOf(0f, 0f) else layerValue(uvs!!, t).let { floatArrayOf(it[0], 1f - it[1]) } },
                    {
                        if (n < 0) floatArrayOf(0f, 1f, 0f) else layerValue(normals!!, n).let {
                            val m = Matrix4.transformPoint(normalMatrix, it[0], it[1], it[2], 0f); floatArrayOf(m[0], m[1], m[2])
                        }
                    },
                    { if (c < 0) floatArrayOf(1f, 1f, 1f, 1f) else layerValue(colors!!, c) })
            }
        }
        if (missingData) warnings += "Parte dos dados de normais/UV/cores do FBX está incompleta; valores padrão foram usados."
        return welders.filterValues { !it.isEmpty }.map { (slot, welder) ->
            welder.primitive(material(materialIds.getOrNull(slot)), computeNormals = true)
        }
    }
}
