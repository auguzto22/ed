package com.termex.replay15.editor.captions

/** Per-generation counters. Only the worker running generation mutates this instance. */
internal class CaptionPerf {
    private val started = System.nanoTime()
    var asrCalls = 0
    var vadCalls = 0
    var alignmentCalls = 0
    var fullTranscriptions = 0
    var localRetranscriptions = 0
    var retries = 0
    var dirtyRegions = 0
    var cacheHits = 0
    var cacheMisses = 0

    inline fun <T> measure(stage: String, block: () -> T): T {
        val start = System.nanoTime()
        try { return block() }
        finally { CaptionDebugLog.d("CaptionPerf", "$stage = ${(System.nanoTime() - start) / 1_000_000}ms") }
    }

    fun finish() {
        CaptionDebugLog.d("CaptionPerf", "TOTAL = ${(System.nanoTime() - started) / 1_000_000}ms " +
            "asrCalls=$asrCalls vadCalls=$vadCalls alignmentCalls=$alignmentCalls " +
            "fullTranscriptions=$fullTranscriptions localRetranscriptions=$localRetranscriptions " +
            "retries=$retries dirtyRegions=$dirtyRegions cacheHits=$cacheHits cacheMisses=$cacheMisses")
    }
}
