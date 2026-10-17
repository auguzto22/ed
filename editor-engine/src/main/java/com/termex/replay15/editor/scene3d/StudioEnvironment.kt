package com.termex.replay15.editor.scene3d

import com.termex.replay15.editor.domain.StudioPreset
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Procedural, physically plausible studio environments (no image assets are shipped).
 *
 * Images are equirectangular in Filament's convention (see iblprefilter equirectToCube.mat):
 * u = (atan2(x, z) / PI + 1) / 2 and v = (1 - asin(y) * 2 / PI) / 2, with v = 0 at the zenith.
 * Every light is placed either on the x = 0 plane or symmetrically in x, so the image reads the
 * same whether or not the cubemap conversion mirrors the x axis.
 */
object StudioEnvironment {
    const val WIDTH = 1024
    const val HEIGHT = 512

    /** Environment light intensity (lux-ish) for a pixel value of 1, before the user multiplier. */
    const val IBL_INTENSITY = 30_000f

    class Look(
        /** Unit vector pointing from the scene towards the key light. */
        val keyDirection: FloatArray,
        /** Illuminance of the key (sun) light in lux, before the user multiplier. */
        val keyIntensity: Float,
        /** Linear albedo of the studio floor. */
        val floorColor: FloatArray,
        val floorRoughness: Float,
    )

    fun look(preset: StudioPreset): Look = when (preset) {
        StudioPreset.NEUTRAL -> Look(direction(0f, 68f), 55_000f, floatArrayOf(.32f, .32f, .33f), .32f)
        StudioPreset.DARK -> Look(direction(0f, 72f), 42_000f, floatArrayOf(.025f, .025f, .028f), .22f)
        StudioPreset.PRODUCT -> Look(direction(0f, 70f), 50_000f, floatArrayOf(.82f, .82f, .83f), .45f)
        StudioPreset.SKY -> Look(direction(0f, SUN_ELEVATION), 105_000f, floatArrayOf(.28f, .27f, .26f), .55f)
    }

    /** Key light for user HDR images: straight overhead, so it agrees with any panorama. */
    val overheadKey = Look(floatArrayOf(0f, 1f, 0f), 35_000f, floatArrayOf(.3f, .3f, .3f), .4f)

    /** Linear RGB pixels, row-major from the zenith row down, 3 floats per pixel. */
    fun pixels(preset: StudioPreset, width: Int = WIDTH, height: Int = HEIGHT): FloatArray {
        require(width >= 8 && height >= 4)
        val out = FloatArray(width * height * 3)
        val rgb = FloatArray(3)
        val dir = FloatArray(3)
        for (row in 0 until height) {
            val v = (row + .5f) / height
            val elevation = (1f - 2f * v) * (PI.toFloat() / 2f)
            for (column in 0 until width) {
                val u = (column + .5f) / width
                val azimuth = (u * 2f - 1f) * PI.toFloat()
                dir[0] = sin(azimuth) * cos(elevation)
                dir[1] = sin(elevation)
                dir[2] = cos(azimuth) * cos(elevation)
                shade(preset, dir, rgb)
                val index = (row * width + column) * 3
                out[index] = rgb[0]; out[index + 1] = rgb[1]; out[index + 2] = rgb[2]
            }
        }
        return out
    }

    /** Direction (unit vector) of a pixel centre, for tests and for the renderer. */
    fun directionOf(column: Int, row: Int, width: Int = WIDTH, height: Int = HEIGHT): FloatArray {
        val azimuth = ((column + .5f) / width * 2f - 1f) * PI.toFloat()
        val elevation = (1f - 2f * (row + .5f) / height) * (PI.toFloat() / 2f)
        return floatArrayOf(sin(azimuth) * cos(elevation), sin(elevation), cos(azimuth) * cos(elevation))
    }

    private const val SUN_ELEVATION = 42f

    private fun shade(preset: StudioPreset, d: FloatArray, out: FloatArray) {
        when (preset) {
            StudioPreset.NEUTRAL -> {
                backdrop(d, top = .30f, horizon = .22f, ground = .12f, warm = .015f, out = out)
                add(out, softbox(d, direction(0f, 68f), 24f, 9f) * 6.5f)
                add(out, strip(d, 70f) * 4f + strip(d, -70f) * 4f)
                add(out, strip(d, 180f, width = 10f) * 1.6f)
            }
            StudioPreset.DARK -> {
                backdrop(d, top = .018f, horizon = .012f, ground = .006f, warm = 0f, out = out)
                add(out, softbox(d, direction(0f, 72f), 20f, 7f) * 3.5f)
                add(out, strip(d, 80f) * 6f + strip(d, -80f) * 6f)
                add(out, strip(d, 180f, width = 7f) * 2.2f)
            }
            StudioPreset.PRODUCT -> {
                backdrop(d, top = .95f, horizon = .85f, ground = .6f, warm = .01f, out = out)
                add(out, softbox(d, direction(0f, 70f), 30f, 12f) * 5f)
                add(out, strip(d, 60f, width = 12f) * 2.2f + strip(d, -60f, width = 12f) * 2.2f)
            }
            StudioPreset.SKY -> {
                val elevation = Math.toDegrees(kotlin.math.asin(d[1].coerceIn(-1f, 1f).toDouble())).toFloat()
                if (elevation >= 0f) {
                    val t = (elevation / 90f).coerceIn(0f, 1f)
                    val k = sqrt(t)
                    out[0] = lerp(.78f, .16f, k); out[1] = lerp(.84f, .33f, k); out[2] = lerp(.95f, .80f, k)
                    val scale = 1.25f
                    out[0] *= scale; out[1] *= scale; out[2] *= scale
                } else {
                    val t = (-elevation / 12f).coerceIn(0f, 1f)
                    out[0] = lerp(.55f, .22f, t); out[1] = lerp(.56f, .20f, t); out[2] = lerp(.58f, .18f, t)
                }
                val sun = direction(0f, SUN_ELEVATION)
                val angle = angleBetween(d, sun)
                // Small bright disc plus a soft glow around it.
                val disc = 1f - smoothstep(.9f, 1.3f, angle)
                val glow = exp(-angle / 9f) * .9f
                add(out, disc * 450f + glow)
            }
        }
    }

    private fun backdrop(d: FloatArray, top: Float, horizon: Float, ground: Float, warm: Float, out: FloatArray) {
        val y = d[1]
        val value = if (y >= 0f) lerp(horizon, top, smoothstep(0f, .8f, y)) else lerp(horizon, ground, smoothstep(0f, .25f, -y))
        out[0] = value + warm; out[1] = value; out[2] = value - warm * .6f
    }

    /** Rectangular soft light: [halfWidth] across azimuth and [halfHeight] along elevation, degrees. */
    private fun softbox(d: FloatArray, centre: FloatArray, halfWidth: Float, halfHeight: Float): Float {
        // Local frame of the light: right is horizontal, up follows the elevation.
        val right = normalize(cross(floatArrayOf(0f, 1f, 0f), centre).let { if (length(it) < 1e-4f) floatArrayOf(1f, 0f, 0f) else it })
        val up = cross(centre, right)
        val forward = dot(d, centre)
        if (forward <= 0f) return 0f
        val x = Math.toDegrees(kotlin.math.atan2(dot(d, right).toDouble(), forward.toDouble())).toFloat()
        val y = Math.toDegrees(kotlin.math.atan2(dot(d, up).toDouble(), forward.toDouble())).toFloat()
        return edge(kotlin.math.abs(x), halfWidth) * edge(kotlin.math.abs(y), halfHeight)
    }

    /** Vertical strip light at [azimuthDeg] (0 = +z, the front), from just above the floor upwards. */
    private fun strip(d: FloatArray, azimuthDeg: Float, width: Float = 5f): Float {
        val azimuth = Math.toDegrees(kotlin.math.atan2(d[0].toDouble(), d[2].toDouble())).toFloat()
        var delta = kotlin.math.abs(azimuth - azimuthDeg) % 360f
        if (delta > 180f) delta = 360f - delta
        val elevation = Math.toDegrees(kotlin.math.asin(d[1].coerceIn(-1f, 1f).toDouble())).toFloat()
        return edge(delta, width) * edge(kotlin.math.abs(elevation - 22f), 24f)
    }

    private fun edge(distance: Float, half: Float): Float = 1f - smoothstep(half * .8f, half * 1.1f, distance)

    /** Unit vector at azimuth (0 = +z, towards the default camera) and elevation, in degrees. */
    fun direction(azimuthDeg: Float, elevationDeg: Float): FloatArray {
        val a = Math.toRadians(azimuthDeg.toDouble()); val e = Math.toRadians(elevationDeg.toDouble())
        return floatArrayOf((sin(a) * cos(e)).toFloat(), sin(e).toFloat(), (cos(a) * cos(e)).toFloat())
    }

    private fun add(out: FloatArray, value: Float) { out[0] += value; out[1] += value; out[2] += value }
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun smoothstep(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun length(a: FloatArray) = sqrt(dot(a, a))
    private fun normalize(a: FloatArray): FloatArray { val l = max(length(a), 1e-9f); return floatArrayOf(a[0] / l, a[1] / l, a[2] / l) }
    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun angleBetween(a: FloatArray, b: FloatArray) = Math.toDegrees(acos(dot(a, b).coerceIn(-1f, 1f).toDouble())).toFloat()
}

/** IEEE 754 binary16 conversion for uploading HDR pixels as RGB16F. */
object HalfFloat {
    fun fromFloat(value: Float): Short {
        val bits = java.lang.Float.floatToRawIntBits(value)
        val sign = (bits ushr 16) and 0x8000
        val exponent = (bits ushr 23) and 0xFF
        val mantissa = bits and 0x7FFFFF
        if (exponent == 0xFF) return (sign or 0x7C00 or if (mantissa != 0) 0x200 else 0).toShort() // Inf / NaN
        val half = exponent - 127 + 15
        if (half >= 0x1F) return (sign or 0x7C00).toShort() // overflow -> Inf
        if (half <= 0) {
            if (half < -10) return sign.toShort() // underflow -> signed zero
            val m = mantissa or 0x800000
            val shift = 14 - half
            var result = m ushr shift
            if ((m ushr (shift - 1)) and 1 != 0) result += 1 // round half up
            return (sign or result).toShort()
        }
        var result = (half shl 10) or (mantissa ushr 13)
        if (mantissa and 0x1000 != 0) result += 1 // round half up (may carry into the exponent, which is correct)
        return (sign or result).toShort()
    }

    fun toFloat(half: Short): Float {
        val h = half.toInt() and 0xFFFF
        val sign = if (h and 0x8000 != 0) -1f else 1f
        val exponent = (h ushr 10) and 0x1F
        val mantissa = h and 0x3FF
        return when (exponent) {
            0 -> sign * mantissa / 1024f * Math.pow(2.0, -14.0).toFloat()
            0x1F -> if (mantissa == 0) sign * Float.POSITIVE_INFINITY else Float.NaN
            else -> sign * (1f + mantissa / 1024f) * Math.pow(2.0, (exponent - 15).toDouble()).toFloat()
        }
    }

    /** Clamps to the largest finite half (65504) so bright lights never become infinity. */
    fun encode(pixels: FloatArray, out: java.nio.ShortBuffer) {
        for (value in pixels) out.put(fromFloat(if (value.isFinite()) value.coerceIn(0f, 65_000f) else 0f))
    }
}
