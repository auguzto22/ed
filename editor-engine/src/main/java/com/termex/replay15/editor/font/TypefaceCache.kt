package com.termex.replay15.editor.font

import android.content.Context
import android.graphics.Typeface
import java.io.File

/**
 * Process-wide Typeface cache backed by [FontCache]'s bounded LRU.
 *
 * No font IO occurs in preview/export render loops — only cache hits.
 * When the LRU limit is reached the least-recently-used [Typeface] is evicted from memory
 * (the font file remains on disk and can be re-parsed cheaply).
 */
object TypefaceCache {
    @Volatile private var fontCache: FontCache? = null

    private fun cache(context: Context): FontCache =
        fontCache ?: synchronized(this) {
            fontCache ?: FontCache(context.applicationContext).also { fontCache = it }
        }

    fun get(context: Context, asset: FontAsset, style: Int = Typeface.NORMAL): Typeface {
        val fc = cache(context)
        val key = FontCache.MemKey(asset.id, asset.weight, asset.italic, style)
        fc.getTypeface(key)?.let { return it }

        val base = when {
            // Downloaded font — load from absolute path on disk.
            asset.source == FontSource.DOWNLOADED && asset.file.isNotBlank() && File(asset.file).isFile -> {
                Typeface.Builder(asset.file)
                    .setWeight(asset.weight)
                    .setItalic(asset.italic)
                    .build()
            }
            // Downloaded font — try FontCache disk directory.
            asset.source == FontSource.DOWNLOADED -> {
                fc.diskFile(asset.id)?.let { file ->
                    Typeface.Builder(file.absolutePath)
                        .setWeight(asset.weight)
                        .setItalic(asset.italic)
                        .build()
                }
            }
            // System font — no file, just family name.
            asset.file.isBlank() -> {
                Typeface.create(Typeface.create(asset.family, Typeface.NORMAL), asset.weight, asset.italic)
            }
            // Bundled font from APK assets.
            else -> {
                Typeface.Builder(context.assets, "fonts/${asset.file}")
                    .setWeight(asset.weight)
                    .setItalic(asset.italic)
                    .build()
            }
        } ?: Typeface.create(Typeface.create(asset.family, Typeface.NORMAL), asset.weight, asset.italic)

        val result = Typeface.create(base, style)
        fc.putTypeface(key, result)
        return result
    }

    /** Resolve a typeface with an explicit [weight] and [italic] flag, for variable fonts. */
    fun getWithWeight(
        context: Context, asset: FontAsset, weight: Int, italic: Boolean,
        style: Int = Typeface.NORMAL,
    ): Typeface {
        val adapted = FontAsset(
            id = asset.id, displayName = asset.displayName, family = asset.family,
            weight = weight, italic = italic, file = asset.file,
            source = asset.source, license = asset.license, categories = asset.categories,
            isOriginalRecly = asset.isOriginalRecly,
        )
        return get(context, adapted, style)
    }

    internal fun entryCount(): Int = fontCache?.memoryEntryCount() ?: 0
    internal fun clearMemory() { fontCache?.clearMemory() }
    internal fun fontCache(context: Context): FontCache = cache(context)
}
