package com.termex.replay15.editor.preview.engine

import android.content.Context
import android.os.Handler
import android.view.Surface
import kotlin.math.roundToInt
import java.util.concurrent.atomic.AtomicBoolean

class VideoDecoderManager(
    private val context: Context,
    private val renderer: GpuPreviewRenderer,
    private val mailbox: SeekMailbox,
    private val diagnostics: PreviewDiagnostics,
    private val onError: (PreviewMediaFailure) -> Unit,
    private val maxDecoders: Int,
) : AutoCloseable {
    private data class Record(
        @Volatile var video: ActiveClipResolver.Video,
        val generation: Long,
        val sourceUri: String,
        var decoder: VideoDecoderSession? = null,
        var preparing: Boolean = true,
        val alive: AtomicBoolean = AtomicBoolean(true),
        var target: VideoDecoderSession.Target? = null,
    )
    private val main=Handler(context.mainLooper)
    private val records=LinkedHashMap<ActiveClipResolver.ClipKey,Record>()
    private val planner=DecoderDemandPlanner(maxDecoders)
    private var closed=false
    private var onClosed: (() -> Unit)? = null
    private val retiring = HashSet<ActiveClipResolver.ClipKey>()
    private data class Demand(val snapshot: ActiveClipResolver.Snapshot, val upcoming: List<ActiveClipResolver.Video>, val generation: Long, val mode: SeekMailbox.Mode)
    private var latestDemand: Demand? = null
    private var nextRecordGeneration = 0L
    private val recoveryAttempts=HashMap<ActiveClipResolver.ClipKey,Int>()
    var previewShortSide=720

    fun update(snapshot:ActiveClipResolver.Snapshot, upcoming:List<ActiveClipResolver.Video>, generation:Long, mode:SeekMailbox.Mode){
        if(closed)return
        latestDemand = Demand(snapshot, upcoming, generation, mode)
        val demand=planner.plan(snapshot,upcoming)
        val desired=(demand.active+demand.prewarm).associateBy{it.key}
        val toRemove = records.keys.filter { it !in desired }.toMutableList()
        val needed = desired.values.filter { records[it.key] == null && it.key !in retiring }
        for (video in needed) {
            val candidateKey = toRemove.firstOrNull { oldKey ->
                val old = records[oldKey]
                old != null && !old.preparing && old.decoder != null && old.alive.get() &&
                    old.sourceUri == video.clip.effectivePreviewUri &&
                    old.video.clip.width == video.clip.width &&
                    old.video.clip.height == video.clip.height &&
                    old.video.clip.mimeType == video.clip.mimeType
            }
            if (candidateKey != null) {
                toRemove.remove(candidateKey)
                val oldRecord = records.remove(candidateKey)!!
                oldRecord.video = video
                records[video.key] = oldRecord
                renderer.transferInput(candidateKey, video.key)
                oldRecord.decoder?.resetForReuse(video.clip.id)
                diagnostics.event("DECODER_REUSED from=$candidateKey to=${video.key}")
            }
        }
        toRemove.forEach(::remove)
        desired.values.forEach{video->
            val old=records[video.key]
            if (old != null && (old.video.clip.effectivePreviewUri != video.clip.effectivePreviewUri ||
                    old.video.clip.width != video.clip.width || old.video.clip.height != video.clip.height ||
                    old.video.clip.mimeType != video.clip.mimeType)) remove(video.key)
            if (records[video.key] == null && video.key !in retiring && records.size + retiring.size < maxDecoders) { recoveryAttempts.remove(video.key); create(video) }
            records[video.key]?.video = video
        }
        demand.active.forEach{video->request(video.key, VideoDecoderSession.Target(video.sourceTimeUs(snapshot.projectTimeUs),generation,mode,snapshot.projectTimeUs))}
        demand.prewarm.forEach{video->request(video.key, VideoDecoderSession.Target(video.clip.inUs,generation,SeekMailbox.Mode.FAST_SCRUB,video.startUs))}
        if(demand.omitted.isNotEmpty())diagnostics.event("DECODER_BUDGET_OMIT keys=${demand.omitted}")
    }

    private fun request(key: ActiveClipResolver.ClipKey, target: VideoDecoderSession.Target) {
        val record = records[key] ?: return
        // Preserve the newest request while createInput is crossing GL -> main.
        record.target = target
        record.decoder?.request(target)
    }

    private fun create(video:ActiveClipResolver.Video, sourceUri:String=video.clip.effectivePreviewUri){
        val gen=++nextRecordGeneration
        val record=Record(video,gen,sourceUri);records[video.key]=record
        diagnostics.event("DECODER_PREPARING key=${video.key} generation=$gen")
        val (w,h)=scaled(video.clip.width,video.clip.height,previewShortSide)
        renderer.createInput(video.key,w,h){target->
            if(closed || !record.alive.get()) return@createInput
            val decoder=VideoDecoderSession(context,sourceUri,video.clip.id,
                {target.surface},gen,{record.alive.get()},mailbox::isCurrent,{record.video.projectTimeUs(it)},
                {frame->renderer.submit(record.video.key,frame)},
                {failure->main.post{handleFailure(record.video.key,gen,failure)}},diagnostics::decoderEvent)
            record.decoder=decoder;record.preparing=false
            diagnostics.event("DECODER_READY key=${video.key} generation=$gen pending=${record.target != null}")
            record.target?.let(decoder::request)
        }
    }

    private fun handleFailure(key:ActiveClipResolver.ClipKey, recordGeneration:Long, failure:PreviewMediaFailure){
        if(closed)return
        val record=records[key]
        if(record==null||record.generation!=recordGeneration){diagnostics.staleMediaErrorDropped(failure);return}
        diagnostics.mediaFailure(failure)
        val clip=record.video.clip
        if(record.sourceUri==clip.proxyUri && clip.proxyUri!=clip.uri &&
            failure is PreviewMediaFailure.MissingSource){
            diagnostics.event("MEDIA_PROXY_FALLBACK clip=${clip.id} proxy=${record.sourceUri} original=${clip.uri}")
            remove(key){if(!closed&&records[key]==null)create(record.video,clip.uri)}
            return
        }
        val recoverable=failure is PreviewMediaFailure.DecoderFailure||failure is PreviewMediaFailure.SurfaceFailure||failure is PreviewMediaFailure.SeekFailure
        val attempt=recoveryAttempts[key]?:0
        if(recoverable&&attempt<1){
            recoveryAttempts[key]=attempt+1
            diagnostics.event("MEDIA_RECOVERY clip=${clip.id} generation=$recordGeneration cause=${failure.javaClass.simpleName}")
            remove(key){if(!closed&&records[key]==null)create(record.video,record.sourceUri)}
            return
        }
        onError(failure)
    }

    private fun remove(key:ActiveClipResolver.ClipKey, after:()->Unit={}){
        val record=records.remove(key)?:return
        record.alive.set(false)
        retiring.add(key)
        val released: () -> Unit = {
            retiring.remove(key)
            if (!closed) {
                if (latestDemand != null) after()
                latestDemand?.let { update(it.snapshot, it.upcoming, it.generation, it.mode) }
            } else if (retiring.isEmpty()) {
                onClosed?.invoke(); onClosed = null
            }
        }
        val decoder=record.decoder
        if(decoder==null)renderer.removeInput(key,released) else decoder.close{renderer.removeInput(key,released)}
    }

    /** Attempts to rebind a decoder to a new surface without full recreation. */
    fun rebindDecoder(key: ActiveClipResolver.ClipKey, newSurface: Surface): Boolean {
        val record = records[key] ?: return false
        return record.decoder?.rebindSurface(newSurface) ?: false
    }

    fun hasReadyDecoders(): Boolean = records.values.any { it.decoder != null && !it.preparing }

    fun clear(){latestDemand=null;records.keys.toList().forEach(::remove);recoveryAttempts.clear()}
    fun close(after: () -> Unit) {
        if (closed) return
        closed = true
        onClosed = after
        clear()
        if (retiring.isEmpty()) { onClosed?.invoke(); onClosed = null }
    }
    override fun close() = close {}
    private fun scaled(w:Int,h:Int,short:Int):Pair<Int,Int>{val sourceShort=minOf(w,h);if(sourceShort<=short)return w to h;val scale=short/sourceShort.toFloat();return ((w*scale).roundToInt().coerceAtLeast(2)/2*2) to ((h*scale).roundToInt().coerceAtLeast(2)/2*2)}
}
