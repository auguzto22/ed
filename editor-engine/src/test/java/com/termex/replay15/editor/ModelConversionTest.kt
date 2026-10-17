package com.termex.replay15.editor

import com.termex.replay15.editor.scene3d.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import javax.imageio.ImageIO
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelConversionTest {
    private fun bundle(vararg files: Pair<String, ByteArray>) = ModelBundle(files.associate { (name, bytes) -> name to { bytes } })

    private class Glb(val json: JSONObject, val bin: ByteArray)

    private fun read(glb: ByteArray): Glb {
        val b = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(GlbWriter.MAGIC, b.getInt(0))
        assertEquals(2, b.getInt(4)); assertEquals(glb.size, b.getInt(8))
        var p = 12; var json: JSONObject? = null; var bin = ByteArray(0)
        while (p < glb.size) {
            val length = b.getInt(p); val type = b.getInt(p + 4)
            assertEquals("chunks are 4-byte aligned", 0, length % 4)
            if (type == 0x4E4F534A) json = JSONObject(String(glb, p + 8, length, Charsets.UTF_8))
            if (type == 0x004E4942) bin = glb.copyOfRange(p + 8, p + 8 + length)
            p += 8 + length
        }
        return Glb(requireNotNull(json), bin)
    }

    /** Every accessor must lie inside its buffer view, and every view inside the buffer. */
    private fun assertStructurallyValid(g: Glb) {
        val views = g.json.optJSONArray("bufferViews")
        val total = g.json.getJSONArray("buffers").getJSONObject(0).getInt("byteLength")
        assertTrue(total <= g.bin.size)
        for (i in 0 until (views?.length() ?: 0)) {
            val v = views!!.getJSONObject(i)
            assertTrue(v.optInt("byteOffset", 0) + v.getInt("byteLength") <= total)
        }
        val accessors = g.json.optJSONArray("accessors")
        for (i in 0 until (accessors?.length() ?: 0)) {
            val a = accessors!!.getJSONObject(i)
            val v = views!!.getJSONObject(a.getInt("bufferView"))
            val width = when (a.getString("type")) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; else -> 16 }
            val component = when (a.getInt("componentType")) { 5126, 5125 -> 4; 5123 -> 2; else -> 1 }
            assertTrue(a.optInt("byteOffset", 0) + a.getInt("count") * width * component <= v.getInt("byteLength"))
        }
        val meshes = g.json.optJSONArray("meshes")
        for (m in 0 until (meshes?.length() ?: 0)) {
            val primitives = meshes!!.getJSONObject(m).getJSONArray("primitives")
            for (k in 0 until primitives.length()) {
                val prim = primitives.getJSONObject(k)
                val count = accessors!!.getJSONObject(prim.getJSONObject("attributes").getInt("POSITION")).getInt("count")
                val indices = accessors.getJSONObject(prim.getInt("indices"))
                assertEquals(0, indices.getInt("count") % 3)
                // Highest index must be a valid vertex.
                val view = views!!.getJSONObject(indices.getInt("bufferView"))
                val buf = ByteBuffer.wrap(g.bin).order(ByteOrder.LITTLE_ENDIAN)
                val base = view.optInt("byteOffset", 0) + indices.optInt("byteOffset", 0)
                for (n in 0 until indices.getInt("count")) {
                    val index = if (indices.getInt("componentType") == 5125) buf.getInt(base + n * 4) else buf.getShort(base + n * 2).toInt() and 0xFFFF
                    assertTrue("index $index of $count", index in 0 until count)
                }
            }
        }
    }

    private fun png(width: Int, height: Int, color: Int): ByteArray =
        TextureImages.encodePng(TextureImages.Pixels(width, height, IntArray(width * height) { color }))

    // ---- OBJ --------------------------------------------------------------------------------
    @Test fun objWithMaterialTextureQuadsAndNegativeIndices() {
        val obj = """
            # a quad and a triangle
            mtllib scene.mtl
            v 0 0 0
            v 1 0 0
            v 1 1 0
            v 0 1 0
            v 2 0 0
            vt 0 0
            vt 1 0
            vt 1 1
            vt 0 1
            usemtl red
            f 1/1 2/2 3/3 4/4
            usemtl blue
            f -4 -1 5
        """.trimIndent()
        val mtl = "newmtl red\nKd 1 0 0\nmap_Kd tex/red.png\nnewmtl blue\nKd 0 0 1\nd 0.5\n"
        val result = ObjConverter.convert(obj, bundle("scene.mtl" to mtl.toByteArray(), "tex/red.png" to png(2, 2, 0xFFFF0000.toInt())), "scene")
        assertTrue(result.warnings.toString(), result.warnings.isEmpty())
        val g = read(result.glb); assertStructurallyValid(g)
        val materials = g.json.getJSONArray("materials")
        assertEquals(2, materials.length())
        val red = (0 until 2).map { materials.getJSONObject(it) }.first { it.getString("name") == "red" }
        assertEquals(1.0, red.getJSONObject("pbrMetallicRoughness").getJSONArray("baseColorFactor").getDouble(0), 1e-6)
        assertTrue(red.getJSONObject("pbrMetallicRoughness").has("baseColorTexture"))
        val blue = (0 until 2).map { materials.getJSONObject(it) }.first { it.getString("name") == "blue" }
        assertEquals("BLEND", blue.getString("alphaMode"))
        assertEquals(1, g.json.getJSONArray("images").length())
        assertEquals("image/png", g.json.getJSONArray("images").getJSONObject(0).getString("mimeType"))
        // Quad -> 2 triangles (6 indices) + 1 triangle.
        val prims = g.json.getJSONArray("meshes").getJSONObject(0).getJSONArray("primitives")
        val counts = (0 until prims.length()).map { g.json.getJSONArray("accessors").getJSONObject(prims.getJSONObject(it).getInt("indices")).getInt("count") }.sorted()
        assertEquals(listOf(3, 6), counts)
    }

    @Test fun objMissingFilesWarnInsteadOfFailing() {
        val obj = "mtllib gone.mtl\nv 0 0 0\nv 1 0 0\nv 0 1 0\nusemtl m\nf 1 2 3\n"
        val withMtl = ObjConverter.convert(obj, bundle(), "x")
        assertTrue(withMtl.warnings.any { it.contains("gone.mtl") })
        read(withMtl.glb).also(::assertStructurallyValid)
        val textured = ObjConverter.convert("mtllib a.mtl\nv 0 0 0\nv 1 0 0\nv 0 1 0\nusemtl m\nf 1 2 3\n",
            bundle("a.mtl" to "newmtl m\nmap_Kd missing.png\n".toByteArray()), "x")
        assertTrue(textured.warnings.any { it.contains("missing.png") })
        assertFalse(read(textured.glb).json.has("images"))
        assertTrue(runCatching { ObjConverter.convert("v 0 0 0\nv 1 0 0\n", bundle(), "x") }.isFailure) // no faces
        assertTrue(runCatching { ObjConverter.convert("v 0 0 0\nv 1 0 0\nf 1 2 9\n", bundle(), "x") }.isFailure) // bad index
    }

    @Test fun objAcceptsBomUtf16AndLooseNumbers() {
        val text = "v 0 0 0\nv 1.5e0 0 0\nv 0 +1 0\nf 1 2 3\n"
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        assertEquals(text, ObjConverter.decodeText(utf16))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray()
        assertEquals(text, ObjConverter.decodeText(bom))
        read(ObjConverter.convert(ObjConverter.decodeText(utf16), bundle(), "x").glb).also(::assertStructurallyValid)
    }

    @Test fun triangulatorHandlesConcavePolygons() {
        // An L shape (concave), counter-clockwise.
        val l = floatArrayOf(0f,0f,0f, 2f,0f,0f, 2f,1f,0f, 1f,1f,0f, 1f,2f,0f, 0f,2f,0f)
        val tris = Triangulator.triangulate(l)
        assertEquals(4 * 3, tris.size)
        var area = 0f
        for (t in tris.toList().chunked(3)) {
            val (a, b, c) = t
            area += ((l[b * 3] - l[a * 3]) * (l[c * 3 + 1] - l[a * 3 + 1]) - (l[b * 3 + 1] - l[a * 3 + 1]) * (l[c * 3] - l[a * 3])) / 2f // signed: all must be counter-clockwise
        }
        assertEquals(3f, area, 1e-4f) // area of the L
    }

    // ---- textures ---------------------------------------------------------------------------
    @Test fun pngEncoderRoundTripsThroughImageIo() {
        val pixels = IntArray(3 * 2) { 0xFF000000.toInt() or (it * 40 shl 16) or (255 - it * 30 shl 8) or it }
        pixels[4] = 0x80336699.toInt()
        val bytes = TextureImages.encodePng(TextureImages.Pixels(3, 2, pixels))
        val image = ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(3, image.width); assertEquals(2, image.height)
        for (y in 0 until 2) for (x in 0 until 3) assertEquals("($x,$y)", pixels[y * 3 + x], image.getRGB(x, y))
    }

    @Test fun tgaAndBmpDecodeToArgb() {
        // 2x2 uncompressed 24-bit TGA, bottom-left origin: rows stored bottom first, BGR.
        val header = ByteArray(18).also { it[2] = 2; it[12] = 2; it[14] = 2; it[16] = 24 }
        val body = byteArrayOf(
            0, 0, 255.toByte(), 0, 255.toByte(), 0,         // bottom row: red, green
            255.toByte(), 0, 0, 255.toByte(), 255.toByte(), 255.toByte(), // top row: blue, white
        )
        val tga = TextureImages.decodeTga(header + body)
        assertEquals(2, tga.width); assertEquals(2, tga.height)
        assertEquals(listOf(0xFF0000FF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt()), tga.argb.toList())
        // Run-length encoded 1x3 32-bit TGA with the top-left origin flag: one run of 3 pixels.
        val rle = ByteArray(18).also { it[2] = 10; it[12] = 1; it[14] = 3; it[16] = 32; it[17] = 0x28 } +
            byteArrayOf((0x80 or 2).toByte(), 10, 20, 30, 128.toByte())
        val decoded = TextureImages.decodeTga(rle)
        assertEquals(List(3) { (128 shl 24) or (30 shl 16) or (20 shl 8) or 10 }, decoded.argb.toList())
        // 2x2 24-bit BMP, bottom-up, rows padded to 4 bytes.
        val bmp = ByteBuffer.allocate(54 + 16).order(ByteOrder.LITTLE_ENDIAN).apply {
            put('B'.code.toByte()); put('M'.code.toByte()); putInt(70); putInt(0); putInt(54)
            putInt(40); putInt(2); putInt(2); putShort(1); putShort(24); putInt(0); putInt(16); putInt(0); putInt(0); putInt(0); putInt(0)
            put(byteArrayOf(0, 0, 255.toByte(), 0, 255.toByte(), 0, 0, 0)) // bottom: red, green
            put(byteArrayOf(255.toByte(), 0, 0, 255.toByte(), 255.toByte(), 255.toByte(), 0, 0)) // top: blue, white
        }.array()
        assertEquals(tga.argb.toList(), TextureImages.decodeBmp(bmp).argb.toList().let { it })
        assertNull(TextureImages.toGltf("x.webp", byteArrayOf(1, 2, 3, 4)))
        assertEquals("image/png", TextureImages.toGltf("a.tga", header + body)!!.mimeType)
        assertEquals("image/png", TextureImages.toGltf("a.png", png(1, 1, -1))!!.mimeType)
    }

    // ---- FBX --------------------------------------------------------------------------------
    private val asciiFbx = """
        ; FBX 7.4.0 project file
        FBXHeaderExtension:  {
            FBXVersion: 7400
        }
        GlobalSettings:  {
            Version: 1000
            Properties70:  {
                P: "UpAxis", "int", "Integer", "",1
                P: "UpAxisSign", "int", "Integer", "",1
                P: "FrontAxis", "int", "Integer", "",2
                P: "FrontAxisSign", "int", "Integer", "",1
                P: "CoordAxis", "int", "Integer", "",0
                P: "CoordAxisSign", "int", "Integer", "",1
                P: "UnitScaleFactor", "double", "Number", "",1
            }
        }
        Objects:  {
            Geometry: 100, "Geometry::tri", "Mesh" {
                Vertices: *9 {
                    a: 0,0,0,2,0,0,0,2,0
                }
                PolygonVertexIndex: *3 {
                    a: 0,1,-3
                }
            }
            Material: 300, "Material::red", "" {
                Properties70:  {
                    P: "DiffuseColor", "Color", "", "A",1,0,0
                }
            }
            Model: 200, "Model::Tri", "Mesh" {
                Version: 232
                Properties70:  {
                    P: "Lcl Translation", "Lcl Translation", "", "A",5,0,0
                    P: "Lcl Scaling", "Lcl Scaling", "", "A",2,2,2
                }
            }
        }
        Connections:  {
            C: "OO",100,200
            C: "OO",300,200
            C: "OO",200,0
        }
    """.trimIndent()

    private fun positions(g: Glb): Pair<DoubleArray, DoubleArray> {
        val prim = g.json.getJSONArray("meshes").getJSONObject(0).getJSONArray("primitives").getJSONObject(0)
        val a = g.json.getJSONArray("accessors").getJSONObject(prim.getJSONObject("attributes").getInt("POSITION"))
        fun v(key: String) = DoubleArray(3) { a.getJSONArray(key).getDouble(it) }
        return v("min") to v("max")
    }

    @Test fun asciiFbxKeepsGeometryTransformAndMaterial() {
        val result = FbxConverter.convert(asciiFbx.toByteArray(), bundle(), "tri")
        val g = read(result.glb); assertStructurallyValid(g)
        val (min, max) = positions(g)
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 0.0), min, 1e-6); assertArrayEquals(doubleArrayOf(2.0, 2.0, 0.0), max, 1e-6)
        val node = g.json.getJSONArray("nodes").let { nodes -> (0 until nodes.length()).map { nodes.getJSONObject(it) }.first { it.has("mesh") } }
        val m = node.optJSONArray("matrix")
        val translation = if (m != null) doubleArrayOf(m.getDouble(12), m.getDouble(13), m.getDouble(14)) else DoubleArray(3) { node.getJSONArray("translation").getDouble(it) }
        assertArrayEquals(doubleArrayOf(5.0, 0.0, 0.0), translation, 1e-6)
        val scaleX = if (m != null) m.getDouble(0) else node.getJSONArray("scale").getDouble(0)
        assertEquals(2.0, scaleX, 1e-6)
        assertEquals(1.0, g.json.getJSONArray("materials").getJSONObject(0).getJSONObject("pbrMetallicRoughness").getJSONArray("baseColorFactor").getDouble(0), 1e-6)
    }

    // A tiny binary FBX writer: version 7400, 32-bit offsets.
    private class FbxWriter {
        val out = ByteArrayOutputStream()
        init {
            out.write("Kaydara FBX Binary  ".toByteArray(Charsets.US_ASCII)); out.write(byteArrayOf(0, 0x1A, 0))
            out.write(le(7400))
        }
        fun le(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        sealed class P { class I(val v: Int) : P(); class L(val v: Long) : P(); class S(val v: String) : P(); class D(val v: DoubleArray, val zip: Boolean) : P(); class Ints(val v: IntArray, val zip: Boolean) : P() }
        class N(val name: String, val props: List<P> = emptyList(), val children: List<N> = emptyList())

        private fun props(n: N): ByteArray {
            val b = ByteArrayOutputStream()
            n.props.forEach { p ->
                when (p) {
                    is P.I -> { b.write('I'.code); b.write(le(p.v)) }
                    is P.L -> { b.write('L'.code); b.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(p.v).array()) }
                    is P.S -> { b.write('S'.code); val s = p.v.toByteArray(); b.write(le(s.size)); b.write(s) }
                    is P.D -> { b.write('d'.code); array(b, ByteBuffer.allocate(p.v.size * 8).order(ByteOrder.LITTLE_ENDIAN).also { buf -> p.v.forEach { buf.putDouble(it) } }.array(), p.v.size, p.zip) }
                    is P.Ints -> { b.write('i'.code); array(b, ByteBuffer.allocate(p.v.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { buf -> p.v.forEach { buf.putInt(it) } }.array(), p.v.size, p.zip) }
                }
            }
            return b.toByteArray()
        }
        private fun array(b: ByteArrayOutputStream, raw: ByteArray, count: Int, zip: Boolean) {
            b.write(le(count))
            if (zip) {
                val deflater = Deflater(); deflater.setInput(raw); deflater.finish()
                val tmp = ByteArray(raw.size + 64); val n = deflater.deflate(tmp); deflater.end()
                b.write(le(1)); b.write(le(n)); b.write(tmp, 0, n)
            } else { b.write(le(0)); b.write(le(raw.size)); b.write(raw) }
        }
        fun node(n: N) {
            val pb = props(n); val name = n.name.toByteArray()
            val header = 13 + name.size
            val bodyStart = out.size() + header
            // Children are written to a temporary writer positioned at the right absolute offset.
            var childBytes = ByteArray(0)
            if (n.children.isNotEmpty()) {
                val sub = FbxWriter.Sub(bodyStart + pb.size)
                n.children.forEach { sub.node(it) }
                sub.nullRecord()
                childBytes = sub.out.toByteArray()
            }
            val end = bodyStart + pb.size + childBytes.size
            out.write(le(end)); out.write(le(n.props.size)); out.write(le(pb.size)); out.write(name.size); out.write(name)
            out.write(pb); out.write(childBytes)
        }
        class Sub(val base: Int) {
            val out = ByteArrayOutputStream()
            fun le(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
            fun nullRecord() { out.write(ByteArray(13)) }
            fun node(n: N) {
                val w = FbxWriter()
                // Reuse the property encoder through a throwaway writer.
                val pb = w.propsOf(n); val name = n.name.toByteArray()
                val bodyStart = base + out.size() + 13 + name.size
                var childBytes = ByteArray(0)
                if (n.children.isNotEmpty()) {
                    val sub = Sub(bodyStart + pb.size)
                    n.children.forEach { sub.node(it) }
                    sub.nullRecord(); childBytes = sub.out.toByteArray()
                }
                val end = bodyStart + pb.size + childBytes.size
                out.write(le(end)); out.write(le(n.props.size)); out.write(le(pb.size)); out.write(name.size); out.write(name)
                out.write(pb); out.write(childBytes)
            }
        }
        fun propsOf(n: N) = props(n)
        fun finish(): ByteArray { out.write(ByteArray(13)); out.write(ByteArray(64)); return out.toByteArray() }
    }

    @Test fun binaryFbxWithCompressedArraysParses() {
        val w = FbxWriter()
        w.node(FbxWriter.N("GlobalSettings", children = listOf(FbxWriter.N("Version", listOf(FbxWriter.P.I(1000))))))
        w.node(FbxWriter.N("Objects", children = listOf(
            FbxWriter.N("Geometry", listOf(FbxWriter.P.L(100), FbxWriter.P.S("tri\u0000\u0001Geometry"), FbxWriter.P.S("Mesh")), listOf(
                FbxWriter.N("Vertices", listOf(FbxWriter.P.D(doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0), zip = true))),
                FbxWriter.N("PolygonVertexIndex", listOf(FbxWriter.P.Ints(intArrayOf(0, 1, 2, -4), zip = false))),
            )),
            FbxWriter.N("Model", listOf(FbxWriter.P.L(200), FbxWriter.P.S("Quad\u0000\u0001Model"), FbxWriter.P.S("Mesh"))),
        )))
        w.node(FbxWriter.N("Connections", children = listOf(
            FbxWriter.N("C", listOf(FbxWriter.P.S("OO"), FbxWriter.P.L(100), FbxWriter.P.L(200))),
            FbxWriter.N("C", listOf(FbxWriter.P.S("OO"), FbxWriter.P.L(200), FbxWriter.P.L(0))),
        )))
        val bytes = w.finish()
        val document = FbxParser.parse(bytes)
        assertEquals(7400, document.version)
        val vertices = document.node("Objects")!!.child("Geometry")!!.child("Vertices")!!.doubles()!!
        assertEquals(12, vertices.size); assertEquals(1.0, vertices[10], 0.0)
        val g = read(FbxConverter.convert(bytes, bundle(), "quad").glb); assertStructurallyValid(g)
        val (min, max) = positions(g)
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 0.0), min, 1e-6); assertArrayEquals(doubleArrayOf(1.0, 1.0, 0.0), max, 1e-6)
        // The quad became two triangles.
        val prim = g.json.getJSONArray("meshes").getJSONObject(0).getJSONArray("primitives").getJSONObject(0)
        assertEquals(6, g.json.getJSONArray("accessors").getJSONObject(prim.getInt("indices")).getInt("count"))
    }

    @Test fun fbxErrorsAreClearAndNeverCrash() {
        assertTrue(runCatching { FbxParser.parse("; FBX 6.1.0 project file\nFBXHeaderExtension:  {\n FBXVersion: 6100\n}\n".toByteArray()) }
            .exceptionOrNull()!!.message!!.contains("FBX"))
        val whole = FbxWriter().also { it.node(FbxWriter.N("Objects", listOf(FbxWriter.P.S("some text")))) }.finish()
        assertTrue(runCatching { FbxParser.parse(whole.copyOf(45)) }.isFailure) // cut in the middle of a node
        assertTrue(runCatching { FbxConverter.convert(whole.copyOf(40), bundle(), "x") }.isFailure) // nothing left to convert
        assertTrue(runCatching { FbxConverter.convert("garbage".toByteArray(), bundle(), "x") }.isFailure)
    }

    // ---- glTF / GLB / selection ------------------------------------------------------------
    private fun triangleGlb(): ByteArray = ObjConverter.convert("v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n", bundle(), "t").glb

    @Test fun externalBuffersAndImagesArePackedIntoOneGlb() {
        val glb = read(triangleGlb())
        val bin = glb.bin.copyOf(glb.json.getJSONArray("buffers").getJSONObject(0).getInt("byteLength"))
        val json = JSONObject(glb.json.toString())
        json.getJSONArray("buffers").getJSONObject(0).put("uri", "geometry/data.bin")
        json.put("images", org.json.JSONArray().put(JSONObject().put("uri", "tex%20a.png")))
        json.put("textures", org.json.JSONArray().put(JSONObject().put("source", 0)))
        json.getJSONArray("materials").getJSONObject(0).getJSONObject("pbrMetallicRoughness").put("baseColorTexture", JSONObject().put("index", 0))
        val gltf = json.toString().toByteArray()
        val result = ModelConversion.pack(gltf, bundle("geometry/data.bin" to bin, "tex a.png" to png(2, 2, -1)))
        assertTrue(result.warnings.isEmpty())
        val packed = read(result.glb); assertStructurallyValid(packed)
        assertFalse(packed.json.getJSONArray("buffers").getJSONObject(0).has("uri"))
        assertTrue(packed.json.getJSONArray("images").getJSONObject(0).has("bufferView"))
        assertTrue(ModelConversion.selfContained(result.glb))
        assertFalse(ModelConversion.selfContained(gltf))
        // Missing .bin names the file; a missing texture is only dropped.
        val missingBin = runCatching { ModelConversion.pack(gltf, bundle("tex a.png" to png(1, 1, -1))) }.exceptionOrNull()
        assertTrue(missingBin!!.message!!.contains("data.bin"))
        val noTexture = ModelConversion.pack(gltf, bundle("geometry/data.bin" to bin))
        assertTrue(noTexture.warnings.single().contains("tex"))
        val dropped = read(noTexture.glb); assertStructurallyValid(dropped)
        assertEquals(0, dropped.json.getJSONArray("images").length())
        assertFalse(dropped.json.getJSONArray("materials").getJSONObject(0).getJSONObject("pbrMetallicRoughness").has("baseColorTexture"))
    }

    @Test fun selfContainedGlbIsUsedAsIs() {
        val glb = triangleGlb()
        assertTrue(ModelConversion.selfContained(glb))
        assertSame(glb, ModelConversion.convert("model.glb", glb, bundle()).glb)
    }

    @Test fun mainFileIsPickedByFormatThenDepthIgnoringMacJunk() {
        assertEquals("a/b.glb", ModelConversion.pickMain(listOf("x.obj", "a/b.glb", "c.fbx")))
        assertEquals("top.gltf", ModelConversion.pickMain(listOf("deep/er/m.gltf", "top.gltf", "x.obj")))
        assertEquals("m.fbx", ModelConversion.pickMain(listOf("m.obj", "m.fbx")))
        assertEquals("m.obj", ModelConversion.pickMain(listOf("__MACOSX/._m.obj", "m.obj", "m.mtl")))
        assertNull(ModelConversion.pickMain(listOf("__MACOSX/._m.obj", "readme.txt", "tex.png")))
        assertTrue(runCatching { ModelConversion.convert("a.stl", byteArrayOf(1, 2, 3), bundle()) }.isFailure)
    }

    @Test fun bundleFindsFilesByRelativePathOrName() {
        val b = bundle("models/tex/a.png" to byteArrayOf(1), "Other.JPG" to byteArrayOf(2))
        assertNotNull(b.find("tex/a.png")); assertNotNull(b.find("a.png")); assertNotNull(b.find("C:\\work\\tex\\a.png"))
        assertNotNull(b.find("other.jpg")); assertNotNull(b.find("./models/tex/a.png"))
        assertNull(b.find("nope.png"))
    }
}
