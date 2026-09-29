package com.termex.replay15.editor.backgroundremoval

/** Bounded timestamp cache. Values are low-resolution confidence masks, never full video frames. */
class SegmentationMaskCache(private val maxEntries: Int = 24) {
    init { require(maxEntries > 0) }

    private val entries = object : LinkedHashMap<Long, SegmentationResult>(maxEntries, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, SegmentationResult>?): Boolean = size > maxEntries
    }

    @Synchronized
    fun put(result: SegmentationResult) {
        entries[result.timestampUs] = result
    }

    /** Returns the closest known mask, preferring the latest mask on equal distance. */
    @Synchronized
    fun nearest(timestampUs: Long): SegmentationResult? = entries.entries.minWithOrNull(
        compareBy<Map.Entry<Long, SegmentationResult>> { kotlin.math.abs(it.key - timestampUs) }
            .thenByDescending { it.key }
    )?.value

    @Synchronized fun clear() = entries.clear()
    @Synchronized fun size(): Int = entries.size
}
