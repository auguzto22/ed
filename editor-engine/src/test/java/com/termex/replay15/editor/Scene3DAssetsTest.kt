package com.termex.replay15.editor

import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.scene3d.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Scene3DAssetsTest {
    @Test fun halfFloatRoundTripsWithinHalfPrecision() {
        listOf(0f, 1f, -1f, .5f, .1f, 3.14159f, 1000f, 65_000f, 6.1e-5f, 1e-6f, 2.5e-7f).forEach { value ->
            val back = HalfFloat.toFloat(HalfFloat.fromFloat(value))
            val tolerance = maxOf(abs(value) * 1e-3f, 6e-8f)
            assertEquals("value $value", value, back, tolerance)
        }
        assertEquals(Float.POSITIVE_INFINITY, HalfFloat.toFloat(HalfFloat.fromFloat(1e9f)), 0f)
        assertEquals(0f, HalfFloat.toFloat(HalfFloat.fromFloat(1e-12f)), 0f)
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
        HalfFloat.encode(floatArrayOf(1e9f, Float.NaN, -3f, 2f), buffer.asShortBuffer())
        val shorts = buffer.asShortBuffer()
        assertTrue(HalfFloat.toFloat(shorts.get(0)).isFinite()) // clamped, never infinity
        assertEquals(0f, HalfFloat.toFloat(shorts.get(1)), 0f)   // NaN becomes black
        assertEquals(0f, HalfFloat.toFloat(shorts.get(2)), 0f)   // no negative light
        assertEquals(2f, HalfFloat.toFloat(shorts.get(3)), 0f)
    }

    /** Filament's equirect lookup (iblprefilter equirectToCube.mat), used as the reference. */
    private fun filamentUv(d: FloatArray): Pair<Float, Float> {
        val xf = atan2(d[0], d[2]) / PI.toFloat()
        val yf = asin(d[1]) * (2f / PI.toFloat())
        return (xf + 1f) * .5f to (1f - yf) * .5f
    }

    @Test fun pixelDirectionsFollowFilamentsEquirectConvention() {
        val w = 64; val h = 32
        for (row in 0 until h step 3) for (column in 0 until w step 5) {
            val (u, v) = filamentUv(StudioEnvironment.directionOf(column, row, w, h))
            assertEquals((column + .5f) / w, u, 1e-4f)
            assertEquals((row + .5f) / h, v, 1e-4f)
        }
    }

    @Test fun presetsAreFiniteAndMirrorSymmetric() {
        val w = 128; val h = 64
        StudioPreset.entries.forEach { preset ->
            val pixels = StudioEnvironment.pixels(preset, w, h)
            assertEquals(w * h * 3, pixels.size)
            assertTrue("$preset", pixels.all { it.isFinite() && it >= 0f })
            // x -> -x leaves every preset unchanged, so a mirrored cubemap conversion cannot move a light.
            for (row in 0 until h) for (column in 0 until w) {
                val a = (row * w + column) * 3
                val b = (row * w + (w - 1 - column)) * 3
                for (c in 0 until 3) assertEquals("$preset r$row c$column", pixels[a + c], pixels[b + c], 1e-3f)
            }
        }
    }

    @Test fun keyLightSitsOnARealLightInTheImage() {
        val w = 512; val h = 256
        StudioPreset.entries.forEach { preset ->
            val pixels = StudioEnvironment.pixels(preset, w, h)
            fun luminance(row: Int, column: Int): Float {
                val i = (row * w + column) * 3
                return pixels[i] * .2126f + pixels[i + 1] * .7152f + pixels[i + 2] * .0722f
            }
            var best = -1f; var bestRow = 0; var bestColumn = 0
            for (row in 0 until h) for (column in 0 until w) {
                val l = luminance(row, column)
                if (l > best) { best = l; bestRow = row; bestColumn = column }
            }
            // Pixel that contains the key light's direction (Filament's equirect convention).
            val key = StudioEnvironment.look(preset).keyDirection
            val (u, v) = filamentUv(key)
            val keyLuminance = luminance((v * h).toInt().coerceIn(0, h - 1), (u * w).toInt().coerceIn(0, w - 1))
            // The shadow-casting key always comes from one of the image's main lights...
            assertTrue("$preset key light is in a dark area ($keyLuminance of $best)", keyLuminance >= best * .5f)
            // ...and in the sky it is exactly the sun disc, so shadows match the visible sun.
            if (preset == StudioPreset.SKY) {
                val brightest = StudioEnvironment.directionOf(bestColumn, bestRow, w, h)
                val angle = Math.toDegrees(acos((brightest[0] * key[0] + brightest[1] * key[1] + brightest[2] * key[2]).coerceIn(-1f, 1f).toDouble()))
                assertTrue("sun is $angle° from the key light", angle <= 1.5)
            }
        }
    }

    private class Glb(val json: JSONObject, val bin: ByteBuffer)

    private fun parse(bytes: ByteArray): Glb {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(GlbWriter.MAGIC, b.getInt(0)); assertEquals(2, b.getInt(4)); assertEquals(bytes.size, b.getInt(8))
        val jsonLength = b.getInt(12); assertEquals(0x4E4F534A, b.getInt(16))
        assertEquals(0, jsonLength % 4)
        val json = JSONObject(String(bytes, 20, jsonLength, Charsets.UTF_8).trim())
        val binLength = b.getInt(20 + jsonLength); assertEquals(0x004E4942, b.getInt(24 + jsonLength))
        val bin = ByteBuffer.wrap(bytes, 28 + jsonLength, binLength).slice().order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(bytes.size, 28 + jsonLength + binLength)
        return Glb(json, bin)
    }

    @Test fun floorIsAValidUpFacingGltfWithFadingAlpha() {
        val glb = parse(FloorMesh.glb(floatArrayOf(.3f, .3f, .3f), .3f, .5f))
        val json = glb.json
        assertEquals("2.0", json.getJSONObject("asset").getString("version"))
        val material = json.getJSONArray("materials").getJSONObject(0)
        assertEquals("BLEND", material.getString("alphaMode"))
        val accessors = json.getJSONArray("accessors"); val views = json.getJSONArray("bufferViews")
        assertEquals(json.getJSONArray("buffers").getJSONObject(0).getInt("byteLength"), glb.bin.capacity())
        val components = mapOf("VEC3" to 3, "VEC4" to 4, "SCALAR" to 1)
        val sizes = mapOf(5126 to 4, 5123 to 2)
        for (i in 0 until accessors.length()) {
            val accessor = accessors.getJSONObject(i)
            val view = views.getJSONObject(accessor.getInt("bufferView"))
            val expected = accessor.getInt("count") * components.getValue(accessor.getString("type")) * sizes.getValue(accessor.getInt("componentType"))
            assertEquals(expected, view.getInt("byteLength"))
            assertTrue(view.getInt("byteOffset") + view.getInt("byteLength") <= glb.bin.capacity())
            assertEquals(0, view.getInt("byteOffset") % 4)
        }
        val vertexCount = accessors.getJSONObject(0).getInt("count")
        fun position(index: Int) = FloatArray(3) { glb.bin.getFloat(views.getJSONObject(0).getInt("byteOffset") + (index * 3 + it) * 4) }
        fun alpha(index: Int) = glb.bin.getFloat(views.getJSONObject(2).getInt("byteOffset") + (index * 4 + 3) * 4)
        val indexOffset = views.getJSONObject(3).getInt("byteOffset")
        val indexCount = accessors.getJSONObject(3).getInt("count")
        assertEquals(0, indexCount % 3)
        for (t in 0 until indexCount / 3) {
            val (a, b, c) = (0 until 3).map { glb.bin.getShort(indexOffset + (t * 3 + it) * 2).toInt() and 0xFFFF }
            assertTrue(a < vertexCount && b < vertexCount && c < vertexCount)
            val pa = position(a); val pb = position(b); val pc = position(c)
            val e1 = floatArrayOf(pb[0] - pa[0], pb[1] - pa[1], pb[2] - pa[2])
            val e2 = floatArrayOf(pc[0] - pa[0], pc[1] - pa[1], pc[2] - pa[2])
            val normalY = e1[2] * e2[0] - e1[0] * e2[2]
            assertTrue("triangle $t faces down", normalY > 0f)
        }
        for (v in 0 until vertexCount) assertTrue(alpha(v) in 0f..1f)
        assertEquals(0f, alpha(vertexCount - 1), 1e-6f) // rim fades out
        assertTrue(FloorMesh.alphaAt(0f, 1f) < FloorMesh.alphaAt(0f, .2f))
        assertEquals(1f, FloorMesh.alphaAt(.4f, 1f), 1e-6f)
    }

    private fun glbHeader(length: Int) = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(GlbWriter.MAGIC).putInt(2).putInt(length).array()

    @Test fun modelFileCheckAcceptsUsableFilesOnly() {
        assertSame(ModelFileCheck.Result.Glb, ModelFileCheck.check("car.glb", 1000, glbHeader(1000)) { "" })
        assertSame(ModelFileCheck.Result.Glb, ModelFileCheck.check("named-wrong.bin", 64, glbHeader(64)) { "" })
        assertTrue(ModelFileCheck.check("car.glb", 999, glbHeader(1000)) { "" } is ModelFileCheck.Result.Rejected)
        assertTrue(ModelFileCheck.check("car.glb", 1000, ByteArray(12)) { "" } is ModelFileCheck.Result.Rejected)
        val embedded = """{"asset":{"version":"2.0"},"buffers":[{"uri":"data:application/octet-stream;base64,AAAA"}]}"""
        assertSame(ModelFileCheck.Result.Gltf, ModelFileCheck.check("car.gltf", embedded.length.toLong(), embedded.toByteArray().copyOf(12)) { embedded })
        val external = """{"asset":{"version":"2.0"},"buffers":[{"uri":"car.bin"}]}"""
        val rejected = ModelFileCheck.check("car.gltf", external.length.toLong(), external.toByteArray().copyOf(12)) { external }
        assertTrue(rejected is ModelFileCheck.Result.Rejected && rejected.reason.contains("car.bin"))
        assertTrue(ModelFileCheck.check("car.fbx", 10, ByteArray(12)) { "" } is ModelFileCheck.Result.Rejected)
        assertTrue(ModelFileCheck.check("car.glb", 0, ByteArray(0)) { "" } is ModelFileCheck.Result.Rejected)
        assertTrue(ModelFileCheck.check("big.gltf", ModelFileCheck.MAX_GLTF_TEXT_BYTES + 1, ByteArray(12)) { error("must not read") } is ModelFileCheck.Result.Rejected)
        assertTrue(ModelFileCheck.check("huge.glb", ModelFileCheck.MAX_BYTES + 1, glbHeader(0)) { "" } is ModelFileCheck.Result.Rejected)
    }

    @Test fun layoutNormalizesStandsOnTheFloorAndMirrors() {
        val n = SceneLayout.normalization(floatArrayOf(3f, 5f, 1f), floatArrayOf(2f, 4f, 1f))
        fun at(m: FloatArray, x: Float, y: Float, z: Float) = Matrix4.transformPoint(m, x, y, z).copyOf(3)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), at(n, 3f, 1f, 1f), 1e-5f)   // bottom centre -> origin
        assertArrayEquals(floatArrayOf(0f, 2f, 0f), at(n, 3f, 9f, 1f), 1e-5f)   // largest side becomes 2 units
        assertArrayEquals(floatArrayOf(.5f, 0f, 0f), at(n, 5f, 1f, 1f), 1e-5f)
        val pose = ModelPose(positionX = 1f, positionY = .5f, rotationY = 90f, scale = 2f)
        val world = SceneLayout.world(pose, n, 0f, false)
        val mirror = SceneLayout.world(pose, n, 0f, true)
        listOf(floatArrayOf(3f, 9f, 1f), floatArrayOf(5f, 1f, 2f)).forEach { p ->
            val a = at(world, p[0], p[1], p[2]); val b = at(mirror, p[0], p[1], p[2])
            assertEquals(a[0], b[0], 1e-5f); assertEquals(-a[1], b[1], 1e-5f); assertEquals(a[2], b[2], 1e-5f)
        }
        // Degenerate boxes fall back to identity instead of producing NaN.
        assertTrue(SceneLayout.normalization(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f)).all { it.isFinite() })
    }

    @Test fun stageRotationTurnsModelAndCameraTogether() {
        val n = SceneLayout.normalization(floatArrayOf(0f, 1f, 0f), floatArrayOf(1f, 1f, 1f))
        val camera = OrbitCamera(yaw = 20f, pitch = 15f, distance = 6f, targetY = .7f)
        val point = floatArrayOf(.3f, 1.2f, -.4f)
        fun view(stage: Float): FloatArray {
            val world = SceneLayout.world(ModelPose(rotationY = 33f), n, stage, false)
            val p = Matrix4.transformPoint(world, point[0], point[1], point[2])
            val (eye, target, up) = SceneLayout.camera(camera, stage)
            // Coordinates of the model point in the camera's own frame: unchanged by the stage turn.
            val f = doubleArrayOf(target[0] - eye[0], target[1] - eye[1], target[2] - eye[2])
            val d = doubleArrayOf(p[0] - eye[0], p[1] - eye[1], p[2] - eye[2])
            val r = doubleArrayOf(f[1] * up[2] - f[2] * up[1], f[2] * up[0] - f[0] * up[2], f[0] * up[1] - f[1] * up[0])
            fun dot(a: DoubleArray, b: DoubleArray) = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).toFloat()
            return floatArrayOf(dot(d, f), dot(d, up), dot(d, r))
        }
        val reference = view(0f)
        listOf(-180f, -45f, 90f, 135f).forEach { assertArrayEquals("stage $it", reference, view(it), 1e-3f) }
    }

    @Test fun animationTimeLoops() {
        assertEquals(.5f, SceneLayout.animationTime(2.5f, 2f), 1e-6f)
        assertEquals(0f, SceneLayout.animationTime(3f, 0f), 0f)
        assertEquals(1.5f, SceneLayout.animationTime(-.5f, 2f), 1e-6f)
        assertEquals(0f, SceneLayout.animationTime(Float.NaN, 2f), 0f)
    }
}
