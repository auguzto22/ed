package com.termex.replay15.editor.core

import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Trace
import android.util.Log
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.Window
import java.util.concurrent.atomic.AtomicLong

/** Temporary opt-in diagnostics. Never changes playback/quality or writes per-frame logcat. */
object PreviewPacingProbe {
    private const val TAG = "ReclyPacing"
    @Volatile var enabled = false
        private set
    @Volatile private var playing = false
    private var window: Window? = null
    private var position: (() -> Long)? = null
    private val vsync by lazy { PacingSamples() }
    private val callback by lazy { PacingSamples() }
    private val pts by lazy { PacingSamples() }
    private val scheduled by lazy { PacingSamples() }
    private val ui by lazy { PacingSamples() }
    private val gpuUi by lazy { PacingSamples() }
    private val stages = java.util.concurrent.ConcurrentHashMap<String, PacingSamples>()
    private val operations = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()
    private var lastVsync = 0L
    private var lastCallback = 0L
    private var lastPts = Long.MIN_VALUE
    private var lastScheduled = 0L
    private var gcStart = 0L
    private var startedNs = 0L
    private val jankyUi = AtomicLong()
    private val lostUiReports = AtomicLong()
    private val dropped = AtomicLong()
    private var displayBudgetNs = 16_666_667L
    private fun gcCount() = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: -1L
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!enabled) return
            if (playing) {
                if (lastVsync != 0L) vsync.add(frameTimeNanos - lastVsync)
                lastVsync = frameTimeNanos
                Trace.setCounter("Recly.vsyncNs", frameTimeNanos)
                Trace.setCounter("Recly.playerPositionUs", position?.invoke() ?: -1L)
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private val frames = Window.OnFrameMetricsAvailableListener { _, metrics, lost ->
        if (enabled && playing) {
            val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            ui.add(duration)
            lostUiReports.addAndGet(lost.toLong())
            val deadline = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else displayBudgetNs
            if (duration > deadline && deadline > 0) jankyUi.incrementAndGet()
            if (Build.VERSION.SDK_INT >= 31) gpuUi.add(metrics.getMetric(FrameMetrics.GPU_DURATION))
        }
    }
    fun attach(target: Window, positionUs: () -> Long) {
        detach()
        window = target
        position = positionUs
        displayBudgetNs = (1e9 / (target.decorView.display?.refreshRate ?: 60f)).toLong()
        enabled = true
        target.addOnFrameMetricsAvailableListener(frames, Handler(Looper.getMainLooper()))
        Choreographer.getInstance().postFrameCallback(frame)
        Log.i(TAG, "PROBE_ATTACHED presentation=unavailable_use_SurfaceFlinger decoderOutput=unavailable_use_Perfetto")
    }
    @Synchronized fun playback(active: Boolean) {
        if (!enabled || active == playing) return
        playing = false
        if (active) {
            listOf(vsync, callback, pts, scheduled, ui, gpuUi).forEach { it.clear() }
            stages.clear(); operations.clear()
            jankyUi.set(0); lostUiReports.set(0); dropped.set(0)
            lastVsync = 0; lastCallback = 0; lastPts = Long.MIN_VALUE; lastScheduled = 0
            gcStart = gcCount(); startedNs = System.nanoTime()
            playing = true
            Log.i(TAG, "PREVIEW_PLAYBACK beginNs=$startedNs")
        } else report()
    }
    @Synchronized fun metadata(presentationTimeUs: Long, releaseTimeNs: Long) {
        if (!enabled || !playing) return
        val now = System.nanoTime()
        if (lastCallback != 0L) callback.add(now - lastCallback)
        if (lastPts != Long.MIN_VALUE) pts.add((presentationTimeUs - lastPts) * 1000)
        if (lastScheduled != 0L && releaseTimeNs > 0) scheduled.add(releaseTimeNs - lastScheduled)
        lastCallback = now; lastPts = presentationTimeUs; lastScheduled = releaseTimeNs
        Trace.setCounter("Recly.mediaPtsUs", presentationTimeUs)
        Trace.setCounter("Recly.scheduledReleaseNs", releaseTimeNs)
    }
    fun operation(name: String, reason: String) {
        if (!enabled) return
        if (playing) operations.getOrPut(name) { AtomicLong() }.incrementAndGet()
        Log.i(TAG, "$name ns=${System.nanoTime()} playing=$playing reason=$reason")
    }
    fun dropped(count: Int) { if (enabled && playing) dropped.addAndGet(count.toLong()) }
    fun begin(name: String): Long {
        if (!enabled || !playing) return 0L
        Trace.beginSection(name)
        return System.nanoTime()
    }
    fun end(name: String, beginNs: Long) {
        if (beginNs == 0L) return
        Trace.endSection()
        if (playing) stages.getOrPut(name) { PacingSamples(8192) }.add(System.nanoTime() - beginNs)
    }
    private fun report() {
        val gcEnd = gcCount()
        Log.i(TAG, "PREVIEW_PLAYBACK durationMs=${(System.nanoTime() - startedNs) / 1e6} " +
            "gcDelta=${if (gcStart >= 0 && gcEnd >= 0) gcEnd - gcStart else -1} " +
            "droppedDecoder=${dropped.get()} jankyUI=${jankyUi.get()} lostUiReports=${lostUiReports.get()} " +
            "javaUsedBytes=${Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()} " +
            "nativeBytes=${Debug.getNativeHeapAllocatedSize()} operations=$operations")
        listOf("mainVsyncCallbackInterval" to vsync, "videoCallbackInterval" to callback,
            "mediaPtsInterval" to pts, "scheduledReleaseInterval_NOT_PRESENTED" to scheduled,
            "uiTotalDuration" to ui, "uiGpuDuration_NOT_VIDEO_GPU" to gpuUi).forEach { (name, data) ->
            Log.i(TAG, "$name ${data.summary()}")
        }
        stages.forEach { (name, data) -> Log.i(TAG, "$name ${data.summary()}") }
    }
    fun detach() {
        if (!enabled) return
        playback(false)
        enabled = false
        window?.removeOnFrameMetricsAvailableListener(frames)
        Choreographer.getInstance().removeFrameCallback(frame)
        window = null; position = null
    }
}
