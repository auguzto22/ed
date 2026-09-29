package com.termex.replay15.editor.preview.engine

/** Applies a hard budget before any decoder is created. Does not assume MIME implies compatibility. */
class DecoderDemandPlanner(private val maxDecoders: Int) {
    init { require(maxDecoders > 0) }
    data class Demand(
        val active: List<ActiveClipResolver.Video>,
        val prewarm: List<ActiveClipResolver.Video>,
        val omitted: List<ActiveClipResolver.ClipKey>,
    )

    fun plan(snapshot: ActiveClipResolver.Snapshot, upcoming: List<ActiveClipResolver.Video>): Demand {
        val videos = snapshot.videos.filterNot { it.clip.image }
        // Preserve main/transition inputs first. The caller must expose omitted layers in diagnostics.
        val active = videos.take(maxDecoders)
        val activeKeys = active.map { it.key }.toSet()
        val prewarm = upcoming.filterNot { it.key in activeKeys }.distinctBy { it.key }
            .take(maxDecoders - active.size)
        return Demand(active, prewarm, videos.drop(maxDecoders).map { it.key })
    }
}
