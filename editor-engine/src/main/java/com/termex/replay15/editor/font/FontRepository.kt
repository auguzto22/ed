package com.termex.replay15.editor.font

import android.content.Context
import android.graphics.Typeface
import com.termex.replay15.editor.domain.Project

/**
 * Main facade for the Recly font system.
 *
 * Provides unified access to built-in, bundled, Recly Original, and on-demand downloadable
 * fonts through a single API. Manages [FontCache] for disk/memory caching, [FontDownloader]
 * for network fetching, and variable-font weight resolution.
 */
class FontRepository(private val context: Context) {

    private val fontCache: FontCache = TypefaceCache.fontCache(context)
    private val downloader: FontDownloader = FontDownloader(fontCache)

    // ── Catalog access ──────────────────────────────────────────────────────────

    fun all(): List<FontAsset> = FontCatalog.all()
    fun builtIns(): List<FontAsset> = FontCatalog.builtIns
    fun remotes(): List<FontMetadata> = FontCatalog.remotes()
    fun find(id: String): FontAsset? = FontCatalog.find(id)
    fun resolve(id: String): FontAsset = FontCatalog.requireOrDefault(id)
    fun findRemote(id: String): FontMetadata? = FontCatalog.findRemote(id)

    // ── Typeface resolution ─────────────────────────────────────────────────────

    /** Resolve a [Typeface] for [id] with the given Android text [style]. */
    fun typeface(id: String, style: Int = Typeface.NORMAL): Typeface =
        TypefaceCache.get(context.applicationContext, resolve(id), style)

    /** Resolve a [Typeface] with an explicit [weight] and [italic] flag (for variable fonts). */
    fun typefaceWithWeight(id: String, weight: Int, italic: Boolean, style: Int = Typeface.NORMAL): Typeface =
        TypefaceCache.getWithWeight(context.applicationContext, resolve(id), weight, italic, style)

    /** Whether [id] is available immediately (built-in or cached on disk). */
    fun isAvailable(id: String): Boolean {
        if (FontCatalog.find(id)?.source != FontSource.DOWNLOADED) return true
        return fontCache.isDownloaded(id)
    }

    // ── Download ────────────────────────────────────────────────────────────────

    /**
     * Ensure [fontId] is ready to render. Downloads if necessary.
     *
     * @param onProgress called with [0.0, 1.0] during download.
     * @param onComplete called with the resolved [Typeface] on success, or the error on failure.
     */
    fun ensureFont(
        fontId: String,
        onProgress: (Float) -> Unit = {},
        onComplete: (Result<Typeface>) -> Unit,
    ) {
        // Already available?
        if (isAvailable(fontId)) {
            onProgress(1f)
            onComplete(Result.success(typeface(fontId)))
            return
        }

        val metadata = FontCatalog.findRemote(fontId)
        if (metadata == null) {
            onComplete(Result.failure(IllegalArgumentException("Font $fontId not found in catalog")))
            return
        }

        Thread {
            val result = downloader.download(metadata, onProgress)
            when (result) {
                is FontDownloader.DownloadResult.Success -> {
                    FontCatalog.registerDownloaded(metadata, result.file.absolutePath)
                    onComplete(Result.success(typeface(fontId)))
                }
                is FontDownloader.DownloadResult.Failure -> {
                    onComplete(Result.failure(RuntimeException("${result.error}: ${result.message}")))
                }
            }
        }.start()
    }

    // ── Project restoration ─────────────────────────────────────────────────────

    /**
     * Restore all fonts referenced by [project] that are not yet available.
     *
     * @param onMissingFontWarning called for each font that could not be restored, with
     *                             the font ID as argument. The UI should alert the user.
     */
    fun restoreProjectFonts(
        project: Project,
        onMissingFontWarning: (String) -> Unit = {},
    ) {
        val fontIds = (project.texts.map { it.fontId } + project.captionGlobalFontId +
            project.texts.mapNotNull { it.captionFontOverride }).distinct()
        for (id in fontIds) {
            if (isAvailable(id)) continue
            val metadata = FontCatalog.findRemote(id)
            if (metadata == null) {
                onMissingFontWarning(id)
                continue
            }
            val result = downloader.download(metadata)
            when (result) {
                is FontDownloader.DownloadResult.Success -> {
                    FontCatalog.registerDownloaded(metadata, result.file.absolutePath)
                }
                is FontDownloader.DownloadResult.Failure -> {
                    onMissingFontWarning(id)
                }
            }
        }
    }

    // ── Cache management ────────────────────────────────────────────────────────

    fun clearMemoryCache() = TypefaceCache.clearMemory()
    fun diskUsageBytes(): Long = fontCache.diskUsageBytes()

    fun pruneCache(pinnedIds: Set<String> = emptySet(), maxBytes: Long = FontCache.MAX_DISK_BYTES) =
        fontCache.pruneUnusedFonts(pinnedIds, maxBytes)
}
