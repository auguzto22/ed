package com.termex.replay15.editor.scene3d

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns any supported model (glTF, GLB, OBJ, FBX), with the files that came with it, into one
 * self-contained .glb that Filament can load on its own.
 */
object ModelConversion {
    class Result(val glb: ByteArray, val warnings: List<String>)

    private val MODEL_EXTENSIONS = listOf(".glb", ".gltf", ".fbx", ".obj")

    /** The main model among [names]: GLB, then glTF, FBX, OBJ; folders like __MACOSX are ignored. */
    fun pickMain(names: Collection<String>): String? {
        val candidates = names.filterNot { it.contains("__MACOSX") || it.substringAfterLast('/').startsWith("._") }
        for (extension in MODEL_EXTENSIONS) {
            candidates.filter { it.lowercase().endsWith(extension) }.minByOrNull { it.count { c -> c == '/' } }?.let { return it }
        }
        return null
    }

    fun convert(mainName: String, main: ByteArray, bundle: ModelBundle): Result {
        val lower = mainName.lowercase()
        val isGlb = main.size >= 12 && ByteBuffer.wrap(main, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int == GlbWriter.MAGIC
        return when {
            isGlb && selfContained(main) -> Result(main, emptyList())
            isGlb -> pack(main, bundle)
            lower.endsWith(".gltf") -> pack(main, bundle)
            lower.endsWith(".fbx") || FbxParser.isBinary(main) -> FbxConverter.convert(main, bundle, baseName(mainName)).let { Result(it.glb, it.warnings) }
            lower.endsWith(".obj") -> ObjConverter.convert(ObjConverter.decodeText(main), bundle, baseName(mainName)).let { Result(it.glb, it.warnings) }
            else -> throw IllegalArgumentException("Formato não suportado. Use .glb, .gltf, .fbx ou .obj (ou um .zip com eles).")
        }
    }

    /** A GLB that already holds every buffer and only PNG/JPEG images is used as it is. */
    fun selfContained(glb: ByteArray): Boolean = runCatching {
        val json = JSONObject(split(glb).first)
        val buffers = json.optJSONArray("buffers") ?: JSONArray()
        val buffersInside = (0 until buffers.length()).all { i ->
            val buffer = buffers.getJSONObject(i)
            (!buffer.has("uri") && i == 0) || buffer.optString("uri").startsWith("data:")
        }
        val images = json.optJSONArray("images") ?: JSONArray()
        val imagesInside = (0 until images.length()).all { i ->
            val image = images.getJSONObject(i)
            val uri = image.optString("uri", "")
            (image.has("bufferView") && image.optString("mimeType") in setOf("image/png", "image/jpeg")) ||
                uri.startsWith("data:image/png") || uri.startsWith("data:image/jpeg")
        }
        buffersInside && imagesInside
    }.getOrDefault(false)

    private fun baseName(name: String) = name.substringAfterLast('/').substringBeforeLast('.').ifBlank { "model" }

    /**
     * Rebuilds a glTF/GLB as one GLB with every buffer and image inside, resolving external
     * files from [bundle]. Images in formats glTF does not allow are converted to PNG.
     */
    fun pack(input: ByteArray, bundle: ModelBundle): Result {
        val warnings = ArrayList<String>()
        val (jsonText, embeddedBin) = split(input)
        val json = JSONObject(jsonText)
        val asset = json.optJSONObject("asset")
        require(asset != null && asset.optString("version").startsWith("2")) { "Só glTF 2.0 é suportado" }
        val buffers = json.optJSONArray("buffers") ?: JSONArray()
        val bin = ByteArrayOutputStream()
        fun pad() { while (bin.size() % 4 != 0) bin.write(0) }
        val bufferOffsets = IntArray(buffers.length())
        for (i in 0 until buffers.length()) {
            val buffer = buffers.getJSONObject(i)
            val length = buffer.getInt("byteLength")
            val data: ByteArray = when {
                !buffer.has("uri") -> requireNotNull(embeddedBin) { "glTF sem dados binários" }
                else -> {
                    val uri = buffer.getString("uri")
                    if (uri.startsWith("data:")) decodeDataUri(uri)
                    else bundle.find(uri) ?: throw IllegalArgumentException("Falta o arquivo \"$uri\" do modelo. Envie junto (vários arquivos ou um .zip).")
                }
            }
            require(data.size >= length) { "Arquivo de dados do modelo incompleto" }
            pad()
            bufferOffsets[i] = bin.size()
            bin.write(data, 0, length)
        }
        val views = json.optJSONArray("bufferViews") ?: JSONArray().also { json.put("bufferViews", it) }
        for (i in 0 until views.length()) {
            val view = views.getJSONObject(i)
            val buffer = view.getInt("buffer")
            require(buffer in bufferOffsets.indices) { "glTF inválido (bufferView)" }
            view.put("byteOffset", bufferOffsets[buffer] + view.optInt("byteOffset", 0))
            view.put("buffer", 0)
        }
        json.optJSONArray("images")?.let { images ->
            for (i in 0 until images.length()) {
                val image = images.getJSONObject(i)
                val label = image.optString("uri", image.optString("name", "imagem $i"))
                val bytes: ByteArray? = when {
                    image.has("bufferView") -> {
                        val view = views.getJSONObject(image.getInt("bufferView"))
                        val all = bin.toByteArray()
                        val offset = view.getInt("byteOffset"); val length = view.getInt("byteLength")
                        all.copyOfRange(offset, offset + length)
                    }
                    image.has("uri") && image.getString("uri").startsWith("data:") -> decodeDataUri(image.getString("uri"))
                    image.has("uri") -> bundle.find(image.getString("uri"))
                    else -> null
                }
                if (bytes == null) { warnings += "Textura \"$label\" não veio junto."; continue }
                if (image.has("bufferView") && TextureImages.mimeOf(bytes) != null) continue // already inside, already valid
                val converted = TextureImages.toGltf(label, bytes)
                if (converted == null) { warnings += "Textura \"$label\" em formato não suportado."; continue }
                pad()
                val offset = bin.size()
                bin.write(converted.bytes)
                views.put(JSONObject().put("buffer", 0).put("byteOffset", offset).put("byteLength", converted.bytes.size))
                image.remove("uri")
                image.put("bufferView", views.length() - 1)
                image.put("mimeType", converted.mimeType)
            }
            // Textures whose image could not be found would fail validation: drop their sources.
            val missing = (0 until images.length()).filter { i -> images.getJSONObject(i).let { !it.has("bufferView") } }
            if (missing.isNotEmpty()) removeImages(json, missing.toSet())
        }
        pad()
        json.put("buffers", JSONArray().put(JSONObject().put("byteLength", bin.size())))
        return Result(GlbWriter.write(json.toString(), bin.toByteArray()), warnings)
    }

    /** Removes images (and the textures using them, and their references in materials). */
    private fun removeImages(json: JSONObject, remove: Set<Int>) {
        val images = json.getJSONArray("images")
        val imageMap = IntArray(images.length()) { -1 }
        val keptImages = JSONArray()
        for (i in 0 until images.length()) if (i !in remove) { imageMap[i] = keptImages.length(); keptImages.put(images.get(i)) }
        json.put("images", keptImages)
        val textures = json.optJSONArray("textures") ?: return
        val textureMap = IntArray(textures.length()) { -1 }
        val keptTextures = JSONArray()
        for (i in 0 until textures.length()) {
            val texture = textures.getJSONObject(i)
            val source = texture.optInt("source", -1)
            if (source >= 0 && imageMap.getOrElse(source) { -1 } < 0) continue
            if (source >= 0) texture.put("source", imageMap[source])
            textureMap[i] = keptTextures.length(); keptTextures.put(texture)
        }
        json.put("textures", keptTextures)
        fun fix(holder: JSONObject, key: String) {
            val info = holder.optJSONObject(key) ?: return
            val mapped = textureMap.getOrElse(info.optInt("index", -1)) { -1 }
            if (mapped < 0) holder.remove(key) else info.put("index", mapped)
        }
        json.optJSONArray("materials")?.let { materials ->
            for (i in 0 until materials.length()) {
                val material = materials.getJSONObject(i)
                material.optJSONObject("pbrMetallicRoughness")?.let { pbr -> fix(pbr, "baseColorTexture"); fix(pbr, "metallicRoughnessTexture") }
                fix(material, "normalTexture"); fix(material, "occlusionTexture"); fix(material, "emissiveTexture")
            }
        }
    }

    private fun split(input: ByteArray): Pair<String, ByteArray?> {
        if (input.size >= 12 && ByteBuffer.wrap(input, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int == GlbWriter.MAGIC) {
            val b = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN)
            var p = 12
            var json: String? = null; var bin: ByteArray? = null
            while (p + 8 <= input.size) {
                val length = b.getInt(p); val type = b.getInt(p + 4)
                require(length >= 0 && p + 8 + length <= input.size) { "GLB corrompido" }
                when (type) {
                    0x4E4F534A -> json = String(input, p + 8, length, Charsets.UTF_8)
                    0x004E4942 -> bin = input.copyOfRange(p + 8, p + 8 + length)
                }
                p += 8 + length
            }
            return requireNotNull(json) { "GLB sem JSON" } to bin
        }
        return ObjConverter.decodeText(input) to null
    }

    private fun decodeDataUri(uri: String): ByteArray {
        val comma = uri.indexOf(',')
        require(comma > 0) { "Dados embutidos inválidos" }
        val header = uri.substring(0, comma)
        val payload = uri.substring(comma + 1)
        return if (header.endsWith(";base64")) java.util.Base64.getDecoder().decode(payload)
        else java.net.URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.ISO_8859_1)
    }
}
