package com.termex.replay15.editor.preview.engine

import android.os.SystemClock
import android.util.Log
import com.termex.replay15.editor.core.isEditorDebuggable
import com.termex.replay15.editor.core.PreviewPerformanceController
import java.util.concurrent.atomic.AtomicLong

class PreviewDiagnostics(context: android.content.Context) {
    private val enabled = context.isEditorDebuggable()
    private val traceFrames = java.lang.Boolean.getBoolean("recly.preview.traceFrames")
    enum class TransitionDebugMode { NORMAL, INPUT_A, INPUT_B, CROSSFADE }
    val transitionDebugMode: TransitionDebugMode = if (enabled)
        TransitionDebugMode.entries.firstOrNull { it.name == System.getProperty("recly.preview.transitionMode") }
            ?: TransitionDebugMode.NORMAL else TransitionDebugMode.NORMAL
    private val traceTransitions = enabled && java.lang.Boolean.getBoolean("recly.preview.traceTransitions")
    private var lastTransitionLogMs = Long.MIN_VALUE
    fun traceTransitionAt(timeUs: Long): Boolean {
        if (!traceTransitions || timeUs < 0) return false
        val now = SystemClock.elapsedRealtime()
        if (lastTransitionLogMs != Long.MIN_VALUE && now - lastTransitionLogMs < 250L) return false
        lastTransitionLogMs = now
        return true
    }
    fun traceCameraAt(timeUs: Long) = enabled && timeUs in 3_900_000L..6_200_000L
    fun shaderFailure(asset: String, failure: Throwable) { Log.e(TAG, "SHADER_FAILURE asset=$asset: ${failure.message}", failure) }
    val decoderCreateCount=AtomicLong(); val decoderReleaseCount=AtomicLong(); val decoderFlushCount=AtomicLong()
    val activeDecoderCount=AtomicLong(); val surfaceCreateCount=AtomicLong(); val eglCreateCount=AtomicLong()
    val decodedFrameCount=AtomicLong(); val shaderCompileCount=AtomicLong(); val staleSeekDropCount=AtomicLong()
    
    val displaySurfaceAttachCount=AtomicLong(); val displaySurfaceDetachCount=AtomicLong()
    val decoderSurfaceCreateCount=AtomicLong(); val droppedByPacingCount=AtomicLong()
    val displayGenerationCurrent=AtomicLong()
    val activeTextCount=AtomicLong(); val textTextureCreateCount=AtomicLong(); val textTextureCacheHitCount=AtomicLong(); val textTextureReleaseCount=AtomicLong()
    val mediaSourceOpenCount=AtomicLong(); val mediaMissingCount=AtomicLong(); val mediaPermissionLostCount=AtomicLong()
    val decoderFailureCount=AtomicLong(); val surfaceFailureCount=AtomicLong(); val seekFailureCount=AtomicLong(); val staleMediaErrorDropCount=AtomicLong()
    
    private val renderCount=AtomicLong(); private val renderTotalNs=AtomicLong(); private val renderMaxNs=AtomicLong()
    private val openAt=AtomicLong(); private val playAt=AtomicLong(); private val seekAt=AtomicLong()
    private val firstFrameMs=AtomicLong(-1); private val playResumeLatencyMs=AtomicLong(-1); private val seekLatencyMs=AtomicLong(-1)
    fun event(value:String){ if(enabled && (traceFrames || !value.startsWith("FRAME_")))Log.d(TAG,"[${SystemClock.elapsedRealtime()}] $value") }
    fun opened(){openAt.set(SystemClock.elapsedRealtime());event("PREVIEW_OPEN")}
    fun playRequested(){playAt.set(SystemClock.elapsedRealtime());event("PLAY_REQUEST")}
    fun seekRequested(timeUs:Long){seekAt.set(SystemClock.elapsedRealtime());event("SEEK_REQUEST projectUs=$timeUs")}
    fun decoderEvent(value:String){when{value.startsWith("MEDIA_SOURCE_OPEN")->mediaSourceOpenCount.incrementAndGet();value.startsWith("DECODER_CREATE")->  {decoderCreateCount.incrementAndGet();activeDecoderCount.incrementAndGet()};value.startsWith("DECODER_RELEASE")-> {decoderReleaseCount.incrementAndGet();activeDecoderCount.updateAndGet{n->(n-1).coerceAtLeast(0)}};value.startsWith("DECODER_FLUSH")->decoderFlushCount.incrementAndGet()};event(value)}
    fun mediaFailure(failure:PreviewMediaFailure){
        when(failure){
            is PreviewMediaFailure.MissingSource->{mediaMissingCount.incrementAndGet();event("MEDIA_SOURCE_MISSING clip=${failure.clipId} uri=${failure.uri} generation=${failure.sourceGeneration}")}
            is PreviewMediaFailure.PermissionLost->{mediaPermissionLostCount.incrementAndGet();event("MEDIA_PERMISSION_LOST clip=${failure.clipId} uri=${failure.uri} generation=${failure.sourceGeneration}")}
            is PreviewMediaFailure.DecoderFailure->{decoderFailureCount.incrementAndGet();event("DECODER_ERROR clip=${failure.clipId} codec=${failure.codecName} generation=${failure.sourceGeneration} cause=${failure.cause.javaClass.simpleName}")}
            is PreviewMediaFailure.SurfaceFailure->{surfaceFailureCount.incrementAndGet();event("SURFACE_ERROR clip=${failure.clipId} generation=${failure.sourceGeneration} cause=${failure.cause.javaClass.simpleName}")}
            is PreviewMediaFailure.SeekFailure->{seekFailureCount.incrementAndGet();event("SEEK_ERROR clip=${failure.clipId} targetUs=${failure.targetUs} generation=${failure.sourceGeneration} cause=${failure.cause.javaClass.simpleName}")}
            is PreviewMediaFailure.UnsupportedFormat->event("UNSUPPORTED_FORMAT clip=${failure.clipId} mime=${failure.mime} generation=${failure.sourceGeneration}")
            is PreviewMediaFailure.SourceReadFailure->event("MEDIA_SOURCE_READ_ERROR clip=${failure.clipId} uri=${failure.uri} generation=${failure.sourceGeneration} cause=${failure.cause.javaClass.simpleName}")
        }
    }
    fun staleMediaErrorDropped(failure:PreviewMediaFailure){staleMediaErrorDropCount.incrementAndGet();event("MEDIA_ERROR_STALE_DROP clip=${failure.clipId} generation=${failure.sourceGeneration} cause=${failure.javaClass.simpleName}")}
    fun surfaceCreated(){surfaceCreateCount.incrementAndGet()}
    fun eglCreated(){eglCreateCount.incrementAndGet();event("EGL_CREATE")}; fun eglReleased(){event("EGL_RELEASE")}
    fun shaderCompiled(){shaderCompileCount.incrementAndGet();event("SHADER_COMPILE")}
    fun staleSeekDropped(){staleSeekDropCount.incrementAndGet();event("SEEK_STALE_DROP")}
    fun frameAvailable(ptsUs:Long){decodedFrameCount.incrementAndGet();event("FRAME_AVAILABLE ptsUs=$ptsUs")}
    
    fun displayAttached(generation:Long){displaySurfaceAttachCount.incrementAndGet();displayGenerationCurrent.set(generation);event("DISPLAY_SURFACE_ATTACH generation=$generation")}
    fun displayDetached(generation:Long){displaySurfaceDetachCount.incrementAndGet();event("DISPLAY_SURFACE_DETACH generation=$generation")}
    fun decoderSurfaceCreated(key:ActiveClipResolver.ClipKey){decoderSurfaceCreateCount.incrementAndGet();event("DECODER_SURFACE_CREATE key=$key")}
    fun frameDroppedByPacing(ptsUs:Long,latenessUs:Long){droppedByPacingCount.incrementAndGet();event("FRAME_DROP_PACING ptsUs=$ptsUs latenessUs=$latenessUs")}
    fun textActive(count:Int){activeTextCount.set(count.toLong())}
    fun textTextureCreated(id:String){textTextureCreateCount.incrementAndGet();event("TEXT_TEXTURE_CREATE id=$id")}
    fun textTextureCacheHit(){textTextureCacheHitCount.incrementAndGet()}
    fun textTextureReleased(id:String){textTextureReleaseCount.incrementAndGet();event("TEXT_TEXTURE_RELEASE id=$id")}
    fun textRendered(id:String){event("TEXT_RENDER id=$id")}
    
    fun frameRendered(ms:Double,timeUs:Long){val ns=(ms*1_000_000).toLong();val count=renderCount.incrementAndGet();renderTotalNs.addAndGet(ns);renderMaxNs.updateAndGet{maxOf(it,ns)};PreviewPerformanceController.recordFrameTime(ns);val now=SystemClock.elapsedRealtime();openAt.getAndSet(0).takeIf{it>0}?.let{firstFrameMs.set(now-it);event("FIRST_FRAME latencyMs=${now-it}")};playAt.getAndSet(0).takeIf{it>0}?.let{playResumeLatencyMs.set(now-it);event("PLAY_STARTED latencyMs=${now-it}")};seekAt.getAndSet(0).takeIf{it>0}?.let{seekLatencyMs.set(now-it);event("SEEK_FRAME_READY latencyMs=${now-it} projectUs=$timeUs")};if(count%300L==0L)event("PREVIEW_METRICS ${summary()}")}
    fun summary():String="firstFrameMs=${firstFrameMs.get()} playResumeLatencyMs=${playResumeLatencyMs.get()} seekLatencyMs=${seekLatencyMs.get()} decoderCreateCount=${decoderCreateCount.get()} decoderReleaseCount=${decoderReleaseCount.get()} activeDecoderCount=${activeDecoderCount.get()} surfaceCreateCount=${surfaceCreateCount.get()} eglCreateCount=${eglCreateCount.get()} shaderCompileCount=${shaderCompileCount.get()} renderAvgMs=${if(renderCount.get()==0L)0.0 else renderTotalNs.get()/renderCount.get()/1e6} renderMaxMs=${renderMaxNs.get()/1e6} staleSeekDropCount=${staleSeekDropCount.get()} displayAttachCount=${displaySurfaceAttachCount.get()} displayDetachCount=${displaySurfaceDetachCount.get()} decoderSurfaceCreateCount=${decoderSurfaceCreateCount.get()} droppedByPacingCount=${droppedByPacingCount.get()} mediaSourceOpenCount=${mediaSourceOpenCount.get()} mediaMissingCount=${mediaMissingCount.get()} mediaPermissionLostCount=${mediaPermissionLostCount.get()} decoderFailureCount=${decoderFailureCount.get()} surfaceFailureCount=${surfaceFailureCount.get()} seekFailureCount=${seekFailureCount.get()} staleMediaErrorDropCount=${staleMediaErrorDropCount.get()} activeTextCount=${activeTextCount.get()} textTextureCreateCount=${textTextureCreateCount.get()} textTextureCacheHit=${textTextureCacheHitCount.get()} textTextureReleaseCount=${textTextureReleaseCount.get()}"
    companion object{private const val TAG="ReclyPreview"}
}
