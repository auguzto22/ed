package com.termex.replay15.editor.preview.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.SurfaceView
import com.termex.replay15.editor.core.EditorChangeClassifier
import com.termex.replay15.editor.core.PreviewQualityPolicy
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.allVideos
import com.termex.replay15.editor.preview.engine.audio.PreviewTransportClock

/** The only interactive preview engine used by the editor. Export remains a separate pipeline. */
class EditorPreviewEngine(
    private val context:Context,
    surfaceView:SurfaceView,
    private val error:(PreviewMediaFailure)->Unit,
):AutoCloseable{
    enum class State{EMPTY,PREPARING_SOURCE,FRAME_READY,PLAYING,PAUSED,SEEKING,ERROR}
    private val main=Handler(Looper.getMainLooper())
    private val mailbox=SeekMailbox()
    private val choreographer = Choreographer.getInstance()
    private var seekScheduled = false
    private var redrawScheduled = false
    private val redrawFrame = Choreographer.FrameCallback {
        redrawScheduled = false
        if (!closed) renderState(clock.positionUs())
    }
    private val seekFrame = Choreographer.FrameCallback {
        seekScheduled = false
        if (!closed) request(clock.positionUs(), if (scrubbing) SeekMailbox.Mode.FAST_SCRUB else SeekMailbox.Mode.PRECISE, !scrubbing)
    }
    private fun cancelScheduledSeek() {
        choreographer.removeFrameCallback(seekFrame)
        seekScheduled = false
    }
    private fun cancelScheduledRedraw() {
        choreographer.removeFrameCallback(redrawFrame)
        redrawScheduled = false
    }
    private val diagnostics=PreviewDiagnostics(context)
    private val audio=AudioPreviewEngine(context){failure->
        PreviewMediaFailure.DecoderFailure(null,null,"audio",generation,failure).also(diagnostics::mediaFailure).let(::onFailure)
    }
    private val clock=PreviewTransportClock(audioPositionProvider = { audio.getPlaybackPositionUs() })
    private val renderer=GpuPreviewRenderer(context,surfaceView,mailbox::isCurrent,clock::positionUs,diagnostics,clock::isPlaying,::onFramePresented){failure->
        PreviewMediaFailure.SurfaceFailure(null,null,generation,failure).also(diagnostics::mediaFailure).let(::onFailure)
    }
    private val maxDecoders=run{val am=context.getSystemService(ActivityManager::class.java);if(am?.isLowRamDevice==true)2 else 4}
    private val decoders=VideoDecoderManager(context,renderer,mailbox,diagnostics,::onFailure,maxDecoders)
    private var project=Project();private var resolver=ActiveClipResolver(project)
    private var projectRevision=0L
    private var projectSnapshot=ProjectSnapshot(projectRevision,project)
    private var generation=0L;private var closed=false;private var scrubbing=false
    @Volatile var state:State=State.EMPTY;private set
    val isPlaying:Boolean get()=clock.isPlaying()
    val playWhenReady:Boolean get()=isPlaying
    val isScrubbing:Boolean get()=scrubbing
    val positionUs:Long get()=clock.positionUs()
    val runtimeStatus: PreviewRuntimeStatus get() = PreviewRuntimeStatus(
        decoderReady = decoders.hasReadyDecoders(),
        decoderSurfaceReady = renderer.hasAnyDecoderSurface(),
        displaySurfaceAttached = renderer.isDisplayAttached,
        glReady = renderer.isGlReady,
        frameAvailable = hasPresentedFrame,
        playing = isPlaying,
    )
    var quality:Int=0
        set(value) {
            checkMain()
            field = value
            if (!closed && project.allVideos.isNotEmpty()) {
                val resolved = chosenQuality()
                if (decoders.previewShortSide != resolved) {
                    decoders.previewShortSide = resolved
                    decoders.clear()
                    request(positionUs, SeekMailbox.Mode.PRECISE, true)
                }
            }
        }

    private val tick=object:Runnable{override fun run(){
        if(closed||!clock.isPlaying())return
        val position=clock.positionUs()
        if(position>=project.durationUs){pause();return}
        if (!seekScheduled) renderAt(position,SeekMailbox.Mode.PRECISE,false)
        main.postDelayed(this,FRAME_TICK_MS)
    }}

    fun load(project:Project,positionUs:Long,playWhenReady:Boolean=false){
        checkMain();if(closed)return
        cancelScheduledSeek()
        this.project=project;resolver=ActiveClipResolver(project);projectSnapshot=ProjectSnapshot(++projectRevision,project);audio.updateTimeline(projectSnapshot,resolver)
        clock.setDuration(project.durationUs);clock.seek(positionUs.coerceIn(0,project.durationUs));decoders.previewShortSide=chosenQuality()
        diagnostics.opened();if(project.allVideos.isEmpty()){state=State.EMPTY;decoders.clear();renderState(clock.positionUs());return}
        state=State.PREPARING_SOURCE;request(clock.positionUs(),SeekMailbox.Mode.PRECISE,true);if(playWhenReady)play() else pause(keepPreparing=true)
    }

    /**
     * Resumes preview after returning from background/pause without destroying decoders or Surfaces.
     * Only re-requests the current frame. Decoders that are still alive will respond immediately.
     * If decoders were suspended (e.g., during export), falls back to load().
     */
    fun resume(positionUs:Long,playWhenReady:Boolean=false){
        checkMain();if(closed)return
        if(project.allVideos.isEmpty()){load(project,positionUs,playWhenReady);return}
        clock.seek(positionUs.coerceIn(0,project.durationUs))
        // If decoders are already alive, just re-request current frame
        request(clock.positionUs(),SeekMailbox.Mode.PRECISE,true)
        if(playWhenReady)play() else{if(state!=State.EMPTY&&state!=State.ERROR)state=if(hasPresentedFrame)State.PAUSED else State.PREPARING_SOURCE}
    }

    fun update(next:Project,redrawIfPaused:Boolean=true){
        checkMain();if(closed)return
        if (project.copy(camera = next.camera) == next) {
            project = next
            projectSnapshot = ProjectSnapshot(++projectRevision, next)
            renderState(clock.positionUs())
            return
        }
        val kind=EditorChangeClassifier.classify(project,next);project=next;resolver=ActiveClipResolver(next)
        diagnostics.event("PROJECT_UPDATE kind=$kind generation=$generation")
        projectSnapshot=ProjectSnapshot(++projectRevision,next);audio.updateTimeline(projectSnapshot,resolver);clock.setDuration(next.durationUs)
        when(kind){
            EditorChangeClassifier.ChangeKind.RENDER_ONLY->{redraw()}
            EditorChangeClassifier.ChangeKind.TIMELINE_MAPPING,EditorChangeClassifier.ChangeKind.DECODER_SEEK,EditorChangeClassifier.ChangeKind.ACTIVE_SOURCE,EditorChangeClassifier.ChangeKind.STRUCTURAL_LAYER->request(clock.positionUs(),SeekMailbox.Mode.PRECISE,true)
        }
    }
    fun seek(targetUs:Long){
        checkMain(); if (closed) return
        clock.seek(targetUs.coerceIn(0,project.durationUs))
        if (!seekScheduled) { seekScheduled = true; choreographer.postFrameCallback(seekFrame) }
    }
    fun beginScrub(){checkMain();if(scrubbing)return;scrubbing=true;pause();audio.beginScrub()}
    fun endScrub(finalPositionUs:Long,resumePlayback:Boolean?=null){checkMain();scrubbing=false;val target=finalPositionUs.coerceIn(0,project.durationUs);clock.seek(target);audio.endScrub();request(clock.positionUs(),SeekMailbox.Mode.PRECISE,true);if(resumePlayback==true)play() else if(resumePlayback==false)pause()}
    fun play(){checkMain();if(closed||project.allVideos.isEmpty())return;if(clock.positionUs()>=project.durationUs)clock.seek(0);diagnostics.playRequested();audio.play(clock.positionUs());clock.start();state=State.PLAYING;main.removeCallbacks(tick);main.post(tick)}
    fun pause(){pause(false)}
    private fun pause(keepPreparing:Boolean){checkMain();clock.pause();audio.pause();main.removeCallbacks(tick);if(!keepPreparing&&state!=State.EMPTY&&state!=State.ERROR)state=if(hasPresentedFrame)State.PAUSED else State.PREPARING_SOURCE}
    fun toggle(durationUs:Long){if(isPlaying)pause()else{if(positionUs>=durationUs-50_000)seek(0);play()}}
    fun redraw(){
        checkMain();if(closed || redrawScheduled)return
        redrawScheduled = true
        choreographer.postFrameCallback(redrawFrame)
    }
    /** Suspends decoders and audio. Use when export is active or memory pressure requires it. */
    fun suspendSources(){checkMain();cancelScheduledSeek();pause();decoders.clear();if(project.allVideos.isNotEmpty())state=State.PREPARING_SOURCE}
    fun metrics():String="${diagnostics.summary()} ${audio.diagnostics.summary()}"

    private var hasPresentedFrame=false
    private fun request(positionUs:Long,mode:SeekMailbox.Mode,newGeneration:Boolean){
        if (closed) return
        if (newGeneration) cancelScheduledSeek()
        if(project.allVideos.isEmpty()){decoders.clear();pause();state=State.EMPTY;renderState(positionUs);return}
        val request=if(newGeneration)mailbox.submit(positionUs,mode,clock.nextGeneration())else SeekMailbox.Request(generation,positionUs,mode,System.nanoTime())
        if(newGeneration){mailbox.take();generation=request.generation;diagnostics.seekRequested(positionUs);if(mode==SeekMailbox.Mode.FAST_SCRUB)audio.scrubTo(positionUs,generation)else audio.seek(positionUs,generation);if(hasPresentedFrame)state=State.SEEKING}
        renderAt(positionUs,mode,newGeneration)
    }
    private fun renderAt(positionUs:Long,mode:SeekMailbox.Mode,forceAudio:Boolean){
        val snapshot=resolver.resolve(positionUs);renderState(snapshot)
        decoders.update(snapshot,resolver.upcoming(positionUs,PREWARM_US),generation,mode)
        audio.updateProjectPosition(snapshot.projectTimeUs)
    }
    private fun renderState(positionUs:Long)=renderState(resolver.resolve(positionUs))
    private fun renderState(snapshot:ActiveClipResolver.Snapshot){renderer.update(RenderStateEvaluator.evaluate(snapshot,project,generation,projectRevision,renderer.width,renderer.height))}
    private fun onFramePresented(timeUs:Long){
        if(closed||!mailbox.isCurrent(generation))return
        hasPresentedFrame=true
        state=if(clock.isPlaying())State.PLAYING else State.FRAME_READY
        val curAudio=audio.getPlaybackPositionUs()?:clock.positionUs()
        audio.diagnostics.syncSample(clock.positionUs(),curAudio,timeUs,timeUs-curAudio,audio.diagnostics.audioQueuedDurationUs.get()/1000L)
    }
    private fun onFailure(failure:PreviewMediaFailure){
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { onFailure(failure) }; return }
        if(closed)return
        diagnostics.event("PREVIEW_FAILURE type=${failure.javaClass.simpleName} clip=${failure.clipId} generation=${failure.sourceGeneration}")
        state=State.ERROR
        error(failure)
    }
    private fun chosenQuality():Int{if(quality!=0)return quality;val am=context.getSystemService(ActivityManager::class.java);return PreviewQualityPolicy.choose(PreviewQualityPolicy.Inputs(am?.memoryClass?:256,am?.isLowRamDevice?:false,minOf(renderer.width,renderer.height),resolver.resolve(clock.positionUs()).videos.size,project.export.fps))}
    private fun checkMain(){check(Looper.myLooper()==Looper.getMainLooper()){ "Preview API must run on main thread" }}
    override fun close(){checkMain();if(closed)return;closed=true;cancelScheduledSeek();cancelScheduledRedraw();main.removeCallbacks(tick);clock.pause();mailbox.close();audio.close();decoders.close { renderer.close() };state=State.EMPTY;diagnostics.event("PREVIEW_RELEASE ${diagnostics.summary()}")}
    fun release()=close()
    companion object{private const val FRAME_TICK_MS=16L;private const val PREWARM_US=800_000L}
}
