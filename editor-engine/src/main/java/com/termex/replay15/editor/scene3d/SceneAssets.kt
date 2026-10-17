package com.termex.replay15.editor.scene3d

import com.termex.replay15.editor.domain.Matrix4
import com.termex.replay15.editor.domain.ModelPose
import com.termex.replay15.editor.domain.OrbitCamera
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Builds the studio floor as a tiny binary glTF, so it is drawn by gltfio's own PBR materials
 * (no custom compiled material is needed). The disc lies on y = 0 facing up; its per-vertex alpha
 * lets the model's mirrored reflection show near the centre and fades the rim into the backdrop.
 */
object FloorMesh {
    const val RADIUS = 1f
    private val RINGS = floatArrayOf(.015f, .04f, .08f, .13f, .2f, .3f, .45f, .6f, .75f, .88f, 1f)
    const val SEGMENTS = 72

    /** Opacity of the floor at normalized radius [r] (0 centre, 1 rim). */
    fun alphaAt(r: Float, reflection: Float): Float {
        val centre = 1f - reflection.coerceIn(0f, 1f) * .85f
        val solid = lerp(centre, 1f, smoothstep(.05f, .22f, r))
        return solid * (1f - smoothstep(.62f, 1f, r))
    }

    fun glb(color: FloatArray, roughness: Float, reflection: Float): ByteArray {
        require(color.size == 3)
        val vertexCount = 1 + RINGS.size * SEGMENTS
        val positions = FloatArray(vertexCount * 3)
        val normals = FloatArray(vertexCount * 3)
        val colors = FloatArray(vertexCount * 4)
        fun put(index: Int, x: Float, z: Float, alpha: Float) {
            positions[index * 3] = x; positions[index * 3 + 1] = 0f; positions[index * 3 + 2] = z
            normals[index * 3 + 1] = 1f
            colors[index * 4] = 1f; colors[index * 4 + 1] = 1f; colors[index * 4 + 2] = 1f; colors[index * 4 + 3] = alpha
        }
        put(0, 0f, 0f, alphaAt(0f, reflection))
        RINGS.forEachIndexed { ring, radius ->
            val alpha = alphaAt(radius, reflection)
            for (segment in 0 until SEGMENTS) {
                val theta = 2.0 * Math.PI * segment / SEGMENTS
                put(1 + ring * SEGMENTS + segment, (radius * cos(theta)).toFloat(), (radius * sin(theta)).toFloat(), alpha)
            }
        }
        val indices = ArrayList<Int>()
        fun vertex(ring: Int, segment: Int) = 1 + ring * SEGMENTS + (segment % SEGMENTS)
        // Counter-clockwise seen from +y: (centre, next, current) because the ring runs from +x to +z.
        for (segment in 0 until SEGMENTS) { indices += 0; indices += vertex(0, segment + 1); indices += vertex(0, segment) }
        for (ring in 0 until RINGS.size - 1) for (segment in 0 until SEGMENTS) {
            val a = vertex(ring, segment); val b = vertex(ring, segment + 1)
            val c = vertex(ring + 1, segment); val d = vertex(ring + 1, segment + 1)
            indices += a; indices += b; indices += c
            indices += b; indices += d; indices += c
        }
        require(vertexCount <= 65_535)

        val positionBytes = positions.size * 4; val normalBytes = normals.size * 4; val colorBytes = colors.size * 4
        val indexBytes = indices.size * 2
        val binLength = align4(positionBytes + normalBytes + colorBytes + indexBytes)
        val bin = ByteBuffer.allocate(binLength).order(ByteOrder.LITTLE_ENDIAN)
        positions.forEach(bin::putFloat); normals.forEach(bin::putFloat); colors.forEach(bin::putFloat)
        indices.forEach { bin.putShort(it.toShort()) }
        val r = RINGS.last()
        val json = """{"asset":{"version":"2.0","generator":"Recly"},"scene":0,"scenes":[{"nodes":[0]}],""" +
            """"nodes":[{"mesh":0,"name":"recly-floor"}],""" +
            """"meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"COLOR_0":2},"indices":3,"material":0}]}],""" +
            """"materials":[{"pbrMetallicRoughness":{"baseColorFactor":[${f(color[0])},${f(color[1])},${f(color[2])},1.0],""" +
            """"metallicFactor":0.0,"roughnessFactor":${f(roughness.coerceIn(.05f, 1f))}},"alphaMode":"BLEND","doubleSided":false}],""" +
            """"buffers":[{"byteLength":$binLength}],""" +
            """"bufferViews":[""" +
            """{"buffer":0,"byteOffset":0,"byteLength":$positionBytes,"target":34962},""" +
            """{"buffer":0,"byteOffset":$positionBytes,"byteLength":$normalBytes,"target":34962},""" +
            """{"buffer":0,"byteOffset":${positionBytes + normalBytes},"byteLength":$colorBytes,"target":34962},""" +
            """{"buffer":0,"byteOffset":${positionBytes + normalBytes + colorBytes},"byteLength":$indexBytes,"target":34963}],""" +
            """"accessors":[""" +
            """{"bufferView":0,"componentType":5126,"count":$vertexCount,"type":"VEC3","min":[${f(-r)},0.0,${f(-r)}],"max":[${f(r)},0.0,${f(r)}]},""" +
            """{"bufferView":1,"componentType":5126,"count":$vertexCount,"type":"VEC3"},""" +
            """{"bufferView":2,"componentType":5126,"count":$vertexCount,"type":"VEC4"},""" +
            """{"bufferView":3,"componentType":5123,"count":${indices.size},"type":"SCALAR"}]}"""
        return GlbWriter.write(json, bin.array())
    }

    private fun f(value: Float) = String.format(java.util.Locale.ROOT, "%.5f", value)
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun smoothstep(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
}

internal fun align4(value: Int) = (value + 3) and 3.inv()

object GlbWriter {
    const val MAGIC = 0x46546C67 // "glTF"
    private const val JSON_CHUNK = 0x4E4F534A
    private const val BIN_CHUNK = 0x004E4942

    fun write(json: String, bin: ByteArray): ByteArray {
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val jsonLength = align4(jsonBytes.size)
        val binLength = align4(bin.size)
        val total = 12 + 8 + jsonLength + 8 + binLength
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(MAGIC); out.putInt(2); out.putInt(total)
        out.putInt(jsonLength); out.putInt(JSON_CHUNK); out.put(jsonBytes)
        repeat(jsonLength - jsonBytes.size) { out.put(' '.code.toByte()) }
        out.putInt(binLength); out.putInt(BIN_CHUNK); out.put(bin)
        repeat(binLength - bin.size) { out.put(0) }
        return out.array()
    }
}

/** Checks an imported model before it is copied into the project. */
object ModelFileCheck {
    const val MAX_BYTES = 250L * 1024 * 1024
    const val MAX_GLTF_TEXT_BYTES = 64L * 1024 * 1024

    sealed class Result {
        object Glb : Result()
        object Gltf : Result()
        class Rejected(val reason: String) : Result()
    }

    /**
     * [head] is the start of the file (at least 12 bytes for GLB). For .gltf files [text] is the
     * whole JSON; external buffers or images cannot be resolved from a single picked file.
     */
    fun check(fileName: String, size: Long, head: ByteArray, text: () -> String): Result {
        if (size <= 0L) return Result.Rejected("Arquivo vazio")
        if (size > MAX_BYTES) return Result.Rejected("Modelo maior que 250 MB")
        val lower = fileName.lowercase(java.util.Locale.ROOT)
        val isGlbHeader = head.size >= 12 && ByteBuffer.wrap(head, 0, 12).order(ByteOrder.LITTLE_ENDIAN).let {
            it.getInt(0) == GlbWriter.MAGIC && it.getInt(4) == 2
        }
        if (isGlbHeader) {
            val declared = ByteBuffer.wrap(head, 8, 4).order(ByteOrder.LITTLE_ENDIAN).getInt().toLong() and 0xFFFFFFFFL
            if (declared != size) return Result.Rejected("Arquivo .glb incompleto ou corrompido")
            return Result.Glb
        }
        if (lower.endsWith(".glb")) return Result.Rejected("Este .glb não é glTF 2.0 válido")
        if (lower.endsWith(".gltf")) {
            if (size > MAX_GLTF_TEXT_BYTES) return Result.Rejected("Este .gltf é grande demais para texto. Exporte como .glb.")
            val json = text()
            if (!json.trimStart().startsWith("{") || !json.contains("\"asset\"")) return Result.Rejected("Este .gltf não é glTF válido")
            val external = Regex("\"uri\"\\s*:\\s*\"([^\"]*)\"").findAll(json).map { it.groupValues[1] }.filterNot { it.startsWith("data:") }.toList()
            if (external.isNotEmpty()) {
                return Result.Rejected("Este .gltf usa arquivos separados (${external.first()}). Exporte como .glb ou com dados embutidos.")
            }
            return Result.Gltf
        }
        if (lower.endsWith(".fbx") || lower.endsWith(".obj")) {
            return Result.Rejected("FBX e OBJ ainda não são suportados. Converta para .glb (Blender: Exportar > glTF 2.0).")
        }
        return Result.Rejected("Escolha um modelo .glb ou .gltf")
    }
}

/** Matrices that place a model, its floor reflection and the camera in the studio. */
object SceneLayout {
    /** Normalizes a model's box: largest side 2 units, centred on x/z, standing on y = 0. */
    fun normalization(center: FloatArray, halfExtent: FloatArray, out: FloatArray = FloatArray(16)): FloatArray {
        Matrix4.setIdentity(out)
        val largest = max(halfExtent[0], max(halfExtent[1], halfExtent[2]))
        if (!largest.isFinite() || largest <= 1e-6f) return out
        val s = 1f / largest
        Matrix4.scale(out, s, s, s)
        Matrix4.translate(out, -center[0], -(center[1] - halfExtent[1]), -center[2])
        return out
    }

    /**
     * World matrix: Ry(stage) * pose * normalization. Turning the light is implemented by turning
     * the whole stage (model, floor and camera) inside a fixed environment, which keeps the
     * backdrop, the reflections and the key light's shadows in agreement.
     */
    fun world(objectMatrix: FloatArray, normalization: FloatArray, stageDegrees: Float, mirror: Boolean, out: FloatArray = FloatArray(16)): FloatArray {
        Matrix4.setIdentity(out)
        Matrix4.rotateY(out, stageDegrees)
        if (mirror) Matrix4.scale(out, 1f, -1f, 1f)
        Matrix4.multiply(out, out, objectMatrix)
        Matrix4.multiply(out, out, normalization)
        return out
    }

    /** Convenience for a model without parents. */
    fun world(pose: ModelPose, normalization: FloatArray, stageDegrees: Float, mirror: Boolean, out: FloatArray = FloatArray(16)): FloatArray =
        world(pose.toMatrix(), normalization, stageDegrees, mirror, out)

    fun floor(radius: Float, out: FloatArray = FloatArray(16)): FloatArray {
        Matrix4.setIdentity(out)
        Matrix4.scale(out, radius, 1f, radius)
        return out
    }

    /** Camera eye/target/up for [camera], turned with the stage. */
    fun camera(camera: OrbitCamera, stageDegrees: Float): Array<DoubleArray> = camera(camera.basis(), stageDegrees)

    /** Camera eye/target/up for a scene-space [basis], turned with the stage. */
    fun camera(basis: com.termex.replay15.editor.domain.CameraBasis, stageDegrees: Float): Array<DoubleArray> {
        val rotation = FloatArray(16).also { Matrix4.setIdentity(it); Matrix4.rotateY(it, stageDegrees) }
        fun turn(v: FloatArray, w: Float): DoubleArray {
            val p = Matrix4.transformPoint(rotation, v[0], v[1], v[2], w)
            return doubleArrayOf(p[0].toDouble(), p[1].toDouble(), p[2].toDouble())
        }
        return arrayOf(turn(basis.eye, 1f), turn(basis.target, 1f), turn(basis.up, 0f))
    }

    /** Loops an animation of [duration] seconds; 0 when the model has none. */
    fun animationTime(seconds: Float, duration: Float): Float =
        if (duration <= 1e-4f || !seconds.isFinite()) 0f else (seconds % duration).let { if (it < 0f) it + duration else it }
}
