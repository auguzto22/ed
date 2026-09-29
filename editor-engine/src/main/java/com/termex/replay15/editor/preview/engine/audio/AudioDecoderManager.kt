package com.termex.replay15.editor.preview.engine.audio

import android.content.Context
import com.termex.replay15.editor.preview.engine.ActiveClipResolver
import com.termex.replay15.editor.preview.engine.ResolvedAudioSource
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the lifecycle of persistent AudioDecoderSession instances across project updates and seeks.
 */
class AudioDecoderManager(
    private val context: Context,
    private val diagnostics: AudioDiagnostics? = null
) : AutoCloseable {

    private val sessions = ConcurrentHashMap<ActiveClipResolver.ClipKey, AudioDecoderSession>()
    private val unavailable = ConcurrentHashMap<ActiveClipResolver.ClipKey, String>()
    @Volatile private var isClosed = false

    fun getOrCreateSession(key: ActiveClipResolver.ClipKey, uri: String): AudioDecoderSession? {
        if (isClosed) return null
        if (unavailable[key] == uri) return null
        val existing = sessions[key]
        if (existing != null && existing.uri == uri) {
            return existing
        }
        existing?.close()

        return runCatching {
            val session = AudioDecoderSession(context, uri, 48000, diagnostics)
            sessions[key] = session
            unavailable.remove(key)
            session
        }.onFailure { diagnostics?.event("AUDIO_SOURCE_UNAVAILABLE key=$key uri=$uri reason=${it.message}"); unavailable[key] = uri }.getOrNull()
    }

    fun pruneUnused(activeKeys: Set<ActiveClipResolver.ClipKey>) {
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in activeKeys) {
                entry.value.close()
                iterator.remove()
            }
        }
    }

    fun prepareAndSeek(demands: List<ResolvedAudioSource>, generation: Long) {
        for (demand in demands) {
            val session = getOrCreateSession(demand.key, demand.uri) ?: continue
            session.seekTo(demand.sourceTimeUs, generation)
        }
    }

    fun prewarm(demands: List<ResolvedAudioSource>, generation: Long) {
        demands.forEach { demand ->
            val session = getOrCreateSession(demand.key, demand.uri) ?: return@forEach
            session.seekTo(demand.sourceStartUs, generation)
        }
    }

    override fun close() {
        isClosed = true
        for ((_, session) in sessions) {
            runCatching { session.close() }
        }
        sessions.clear()
        unavailable.clear()
    }
}
