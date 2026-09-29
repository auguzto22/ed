package com.termex.replay15.editor.transform

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory transient state for gestures and direct manipulation.
 *
 * During active gestures (dragging, pinch-scaling, rotating), temporary transforms
 * are stored here so renderers can immediately display changes without modifying the
 * immutable Project snapshot, rebuilding compositions, or generating Undo steps.
 */
data class TimingOverride(val startUs: Long, val endUs: Long)
typealias TextTimingOverride = TimingOverride

object RealtimeProjectState {
    private val layerTransforms = ConcurrentHashMap<String, TransformState>()
    private val layerTimings = ConcurrentHashMap<String, TimingOverride>()

    fun updateTransform(clipId: String, transform: TransformState) {
        layerTransforms[clipId] = transform
    }

    fun transform(clipId: String): TransformState? {
        return layerTransforms[clipId]
    }

    fun updateTiming(clipId: String, startUs: Long, endUs: Long) {
        layerTimings[clipId] = TimingOverride(startUs, endUs)
    }

    fun timing(clipId: String): TimingOverride? {
        return layerTimings[clipId]
    }

    fun clearTiming(clipId: String) {
        layerTimings.remove(clipId)
    }

    fun clear(clipId: String) {
        layerTransforms.remove(clipId)
        layerTimings.remove(clipId)
    }

    fun clearAll() {
        layerTransforms.clear()
        layerTimings.clear()
    }
}
