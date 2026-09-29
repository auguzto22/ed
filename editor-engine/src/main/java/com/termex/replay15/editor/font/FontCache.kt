package com.termex.replay15.editor.font

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Two-tier font cache: persistent disk storage backed by a bounded in-memory LRU for [Typeface]
 * instances.
 *
 * ### Disk cache
 * Font files are stored under `context.filesDir/fonts/downloaded/`. Each file is validated on
 * write (magic bytes + optional SHA-256). A configurable disk budget ([maxDiskBytes]) triggers
 * eviction of the least-recently-used files that are **not** pinned by the active project.
 *
 * ### Memory LRU
 * Parsed [Typeface] objects are kept in a size-bounded concurrent LRU. When the limit is
 * reached, the least-recently-used entry is evicted from RAM — the file remains on disk and
 * can be re-parsed cheaply.
 */
class FontCache(context: Context, private val maxMemoryEntries: Int = MAX_MEMORY_ENTRIES) {

    private val diskDir: File = File(context.filesDir, "fonts/downloaded").also { it.mkdirs() }

    data class MemKey(val fontId: String, val weight: Int, val italic: Boolean, val style: Int)

    // ── Memory LRU ──────────────────────────────────────────────────────────────
    private val memoryCache = ConcurrentHashMap<MemKey, Typeface>()
    private val accessOrder = ConcurrentLinkedDeque<MemKey>()

    fun getTypeface(key: MemKey): Typeface? {
        val tf = memoryCache[key] ?: return null
        promoteAccess(key)
        return tf
    }

    fun putTypeface(key: MemKey, typeface: Typeface) {
        memoryCache[key] = typeface
        promoteAccess(key)
        evictIfNeeded()
    }

    fun memoryEntryCount(): Int = memoryCache.size

    private fun promoteAccess(key: MemKey) {
        accessOrder.remove(key)
        accessOrder.addFirst(key)
    }

    private fun evictIfNeeded() {
        while (memoryCache.size > maxMemoryEntries) {
            val victim = accessOrder.pollLast() ?: break
            memoryCache.remove(victim)
        }
    }

    // ── Disk cache ──────────────────────────────────────────────────────────────

    /** Return the local [File] for [fontId], or null if not cached. */
    fun diskFile(fontId: String): File? {
        val file = File(diskDir, "$fontId.ttf")
        return if (file.isFile && file.length() > 0) file else null
    }

    /** Whether [fontId] is available on disk. */
    fun isDownloaded(fontId: String): Boolean = diskFile(fontId) != null

    /**
     * Persist [tempFile] as the cached copy for [fontId] after validating magic bytes and
     * optional SHA-256 hash.
     *
     * @return the final [File] on success, or `null` if validation failed (the temp file is
     *         deleted).
     */
    fun commitDownload(fontId: String, tempFile: File, expectedSha256: String = ""): File? {
        if (!validateMagicBytes(tempFile)) {
            Log.w(TAG, "Font $fontId failed magic-byte validation")
            tempFile.delete()
            return null
        }
        if (expectedSha256.isNotBlank() && sha256(tempFile) != expectedSha256.lowercase()) {
            Log.w(TAG, "Font $fontId failed SHA-256 validation")
            tempFile.delete()
            return null
        }
        val target = File(diskDir, "$fontId.ttf")
        if (!tempFile.renameTo(target)) {
            tempFile.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            tempFile.delete()
        }
        return target
    }

    /** Total bytes currently used by cached font files. */
    fun diskUsageBytes(): Long = diskDir.listFiles()?.sumOf { it.length() } ?: 0

    /**
     * Remove downloaded fonts that are not in [pinnedIds] until disk usage drops below
     * [maxDiskBytes].
     */
    fun pruneUnusedFonts(pinnedIds: Set<String>, maxDiskBytes: Long = MAX_DISK_BYTES) {
        if (diskUsageBytes() <= maxDiskBytes) return
        val files = diskDir.listFiles()?.toMutableList() ?: return
        files.sortBy { it.lastModified() } // oldest first
        for (file in files) {
            if (diskUsageBytes() <= maxDiskBytes) break
            val id = file.nameWithoutExtension
            if (id in pinnedIds) continue
            file.delete()
            // Also evict from memory
            memoryCache.keys.filter { it.fontId == id }.forEach { key ->
                memoryCache.remove(key)
                accessOrder.remove(key)
            }
        }
    }

    /** Remove all cached data for [fontId] from disk and memory. */
    fun evict(fontId: String) {
        File(diskDir, "$fontId.ttf").delete()
        memoryCache.keys.filter { it.fontId == fontId }.forEach { key ->
            memoryCache.remove(key)
            accessOrder.remove(key)
        }
    }

    /** Clear the entire memory cache (disk untouched). */
    fun clearMemory() {
        memoryCache.clear()
        accessOrder.clear()
    }

    companion object {
        private const val TAG = "FontCache"
        const val MAX_MEMORY_ENTRIES = 48
        const val MAX_DISK_BYTES = 100L * 1024 * 1024 // 100 MB

        /**
         * Validate that [file] starts with a recognized TrueType/OpenType magic number.
         *
         * Accepted signatures:
         * - `0x00010000` (TrueType)
         * - `OTTO` (OpenType CFF)
         * - `true` (Apple TrueType)
         * - `typ1` (legacy PostScript)
         */
        fun validateMagicBytes(file: File): Boolean {
            if (file.length() < 4) return false
            val header = ByteArray(4)
            file.inputStream().use { it.read(header) }
            val sig = header.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
            return sig == 0x00010000 ||              // TrueType
                sig == 0x4F54544F ||                  // OTTO
                sig == 0x74727565 ||                  // true
                sig == 0x74797031                     // typ1
        }

        /** SHA-256 hex digest of [file]. */
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
