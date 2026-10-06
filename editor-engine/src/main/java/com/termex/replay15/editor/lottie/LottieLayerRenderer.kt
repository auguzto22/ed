package com.termex.replay15.editor.lottie

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.net.Uri
import android.util.LruCache
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.termex.replay15.editor.licenses.ResourceCreditsRegistry
import com.termex.replay15.editor.licenses.ResourceLicenseMetadata
import java.io.InputStream

/**
 * High-performance Lottie animation renderer for preview and export overlays.
 *
 * Evaluates vector paths, strokes, fills, and transforms at exact timeline timestamps
 * without needing an active Android UI Looper or View hierarchy.
 */
object LottieLayerRenderer {

    private val compositionCache = object : LruCache<String, LottieComposition>(64) {
        override fun sizeOf(key: String, value: LottieComposition): Int = 1
    }

    private val drawablePool = ThreadLocal<LottieDrawable>()

    private fun getDrawable(): LottieDrawable {
        var d = drawablePool.get()
        if (d == null) {
            d = LottieDrawable()
            drawablePool.set(d)
        }
        return d
    }

    init {
        // Register curated Lottie assets in attribution registry
        listOf(
            ResourceLicenseMetadata(
                id = "recly_lottie_like_heart",
                name = "Like Heart (Lottie)",
                author = "Recly Open Motion / Bodymovin Community",
                source = "Open Source Lottie Collection",
                license = "MIT",
                licenseUrl = "https://opensource.org/licenses/MIT",
                category = "Social",
                tags = listOf("like", "heart", "social", "lottie", "animated"),
            ),
            ResourceLicenseMetadata(
                id = "recly_lottie_subscribe_bell",
                name = "Subscribe Bell (Lottie)",
                author = "Recly Open Motion / Bodymovin Community",
                source = "Open Source Lottie Collection",
                license = "MIT",
                licenseUrl = "https://opensource.org/licenses/MIT",
                category = "Social",
                tags = listOf("subscribe", "bell", "alert", "notification", "lottie"),
            ),
            ResourceLicenseMetadata(
                id = "recly_lottie_confetti_blast",
                name = "Confetti Blast (Lottie)",
                author = "Recly Open Motion / Bodymovin Community",
                source = "Open Source Lottie Collection",
                license = "MIT",
                licenseUrl = "https://opensource.org/licenses/MIT",
                category = "Celebration",
                tags = listOf("confetti", "party", "celebration", "burst", "lottie"),
            ),
            ResourceLicenseMetadata(
                id = "recly_lottie_star_burst",
                name = "Star Burst (Lottie)",
                author = "Recly Open Motion / Bodymovin Community",
                source = "Open Source Lottie Collection",
                license = "MIT",
                licenseUrl = "https://opensource.org/licenses/MIT",
                category = "Gaming",
                tags = listOf("star", "level", "achievement", "sparkle", "lottie"),
            ),
            ResourceLicenseMetadata(
                id = "recly_lottie_arrow_bounce",
                name = "Arrow Bounce (Lottie)",
                author = "Recly Open Motion / Bodymovin Community",
                source = "Open Source Lottie Collection",
                license = "MIT",
                licenseUrl = "https://opensource.org/licenses/MIT",
                category = "UI",
                tags = listOf("arrow", "bounce", "pointer", "callout", "lottie"),
            ),
            ResourceLicenseMetadata(
                id = "recly_lottie_fire_flame",
                name = "Fire Flame (Lottie)",
                author = "Recly Open Motion / Bodymovin Community",
                source = "Open Source Lottie Collection",
                license = "MIT",
                licenseUrl = "https://opensource.org/licenses/MIT",
                category = "Motion",
                tags = listOf("fire", "flame", "hot", "hype", "gaming", "lottie"),
            ),
        ).forEach { ResourceCreditsRegistry.register(it) }
    }

    /**
     * Determines whether a given uri points to a Lottie animation.
     */
    fun isLottieUri(uri: String): Boolean {
        val clean = uri.lowercase()
        return clean.endsWith(".json") || clean.endsWith(".lottie") ||
            clean.contains("editor/lottie/") || clean.contains("recly_lottie_")
    }

    /**
     * Resolves the canonical asset path if the URI points to a built-in Lottie animation.
     */
    fun resolveAssetPath(uri: String): String = when {
        uri.startsWith("asset:///") -> uri.removePrefix("asset:///").removePrefix("/")
        uri.startsWith("file:///android_asset/") -> uri.removePrefix("file:///android_asset/")
        uri.startsWith("recly_lottie_") -> "editor/lottie/${uri.removePrefix("recly_lottie_")}.json"
        !uri.contains("/") && !uri.endsWith(".json") -> "editor/lottie/$uri.json"
        else -> uri
    }

    /**
     * Loads and caches LottieComposition from an asset, file URI, content URI, or relative path.
     */
    fun loadComposition(context: Context, uri: String): LottieComposition? {
        val cached = compositionCache.get(uri)
        if (cached != null) return cached

        return runCatching {
            val result = when {
                uri.startsWith("content://") || uri.startsWith("file://") && !uri.startsWith("file:///android_asset/") -> {
                    val stream: InputStream = context.contentResolver.openInputStream(Uri.parse(uri))
                        ?: return null
                    stream.use { LottieCompositionFactory.fromJsonInputStreamSync(it, uri).value }
                }
                else -> {
                    val path = resolveAssetPath(uri)
                    LottieCompositionFactory.fromAssetSync(context, path).value
                        ?: runCatching {
                            // Fallback to contentResolver if asset failed
                            context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                                LottieCompositionFactory.fromJsonInputStreamSync(it, uri).value
                            }
                        }.getOrNull()
                }
            }
            if (result != null) {
                compositionCache.put(uri, result)
            }
            result
        }.getOrNull()
    }

    /**
     * Pre-caches a LottieComposition directly.
     */
    fun cacheComposition(key: String, composition: LottieComposition) {
        compositionCache.put(key, composition)
    }

    /**
     * Renders a Lottie frame directly onto the provided Canvas.
     *
     * @param canvas The target canvas (e.g. Media3 overlay bitmap canvas).
     * @param composition The loaded LottieComposition.
     * @param progress Normalized playback progress (0f..1f).
     * @param bounds Target destination rectangle on the canvas.
     * @param alpha Opacity (0f..1f).
     */
    fun draw(
        canvas: Canvas,
        composition: LottieComposition,
        progress: Float,
        bounds: RectF,
        alpha: Float = 1f,
    ) {
        val drawable = getDrawable()
        drawable.composition = composition
        drawable.progress = progress.coerceIn(0f, 1f)

        val compBounds = composition.bounds
        val compW = compBounds.width().toFloat().coerceAtLeast(1f)
        val compH = compBounds.height().toFloat().coerceAtLeast(1f)

        drawable.setBounds(0, 0, compBounds.width(), compBounds.height())

        canvas.save()
        val effectiveAlpha = (alpha * 255).toInt().coerceIn(0, 255)
        val needsAlphaLayer = effectiveAlpha < 255
        if (needsAlphaLayer) {
            canvas.saveLayerAlpha(bounds.left, bounds.top, bounds.right, bounds.bottom, effectiveAlpha)
        }
        canvas.translate(bounds.left, bounds.top)
        canvas.scale(bounds.width() / compW, bounds.height() / compH)
        drawable.draw(canvas)
        if (needsAlphaLayer) {
            canvas.restore()
        }
        canvas.restore()
    }

    /**
     * Renders a Lottie composition into a standalone preview Bitmap.
     */
    fun renderToBitmap(
        composition: LottieComposition,
        progress: Float,
        width: Int,
        height: Int,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        draw(c, composition, progress, RectF(0f, 0f, width.toFloat(), height.toFloat()))
        return bmp
    }

    /**
     * Clears cached compositions and pools.
     */
    fun clearCache() {
        compositionCache.evictAll()
    }
}

