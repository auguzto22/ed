package com.termex.replay15.editor.captions

/** Cache keys include the exact signal identity, window, model revision and recognition settings. */
class CaptionRecognitionCache(private val capacity: Int = 256) {
    data class Key(
        val audioFingerprint: String, val startMs: Long, val endMs: Long,
        val modelVersion: String, val variant: AudioVariant, val language: String?,
        val vocabularyFingerprint: Int, val mode: CaptionMode, val contextFingerprint: Int = 0
    )
    private val entries = object : LinkedHashMap<Key, TranscriptionHypothesis>(capacity, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, TranscriptionHypothesis>?): Boolean = size > capacity
    }
    @Synchronized fun get(key: Key): TranscriptionHypothesis? = entries[key]
    @Synchronized fun put(key: Key, result: TranscriptionHypothesis) { entries[key] = result }
    @Synchronized fun invalidate(audioFingerprint: String, startMs: Long, endMs: Long) {
        entries.keys.removeAll { it.audioFingerprint == audioFingerprint && it.startMs < endMs && it.endMs > startMs }
    }
    @Synchronized fun clear() = entries.clear()
}

class CachingCaptionTranscriber(
    private val delegate: CaptionTranscriber, private val cache: CaptionRecognitionCache,
    private val audioFingerprint: String, private val modelVersion: String
) : CaptionTranscriber {
    override suspend fun transcribe(request: RecognitionRequest): TranscriptionHypothesis? {
        val key = CaptionRecognitionCache.Key(audioFingerprint, request.region.startMs, request.region.endMs,
            modelVersion, request.variant, request.language, request.vocabulary.sorted().hashCode(), request.mode,
            listOf(request.context.previousText, request.context.followingText,
                request.context.entities.entities, request.context.pronunciationHints).hashCode())
        cache.get(key)?.let { return it }
        return delegate.transcribe(request)?.also { cache.put(key, it) }
    }
}
