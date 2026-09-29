package com.termex.replay15.editor.core

import android.content.ComponentCallbacks2
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Central performance coordinator for Recly Preview.
 *
 * Implements graceful degradation across 6 quality tiers (0..5),
 * asymmetrical hysteresis with separate cooldown timers to prevent oscillation,
 * interactive gesture mode scaling, thermal state throttling, and memory trim eviction.
 */
object PreviewPerformanceController {
    private const val TAG = "ReclyPerfController"

    enum class QualityTier(val level: Int, val maxShortSide: Int, val effectDownsample: Int, val description: String) {
        TIER_0_PRISTINE(0, 1080, 1, "Pristine 1080p, 1x FX, full cache"),
        TIER_1_LIGHT(1, 1080, 1, "Light 1080p, gesture FX bypass"),
        TIER_2_MODERATE(2, 720, 2, "Moderate 720p, 2x FX downsample"),
        TIER_3_HEAVY(3, 540, 2, "Heavy 540p, 2x FX downsample, culling"),
        TIER_4_CRITICAL(4, 480, 4, "Critical 480p, 4x FX downsample, scrub bypass"),
        TIER_5_FALLBACK(5, 360, 4, "Fallback 360p / Proxy Media");

        companion object {
            fun fromLevel(lvl: Int): QualityTier =
                entries.firstOrNull { it.level == lvl.coerceIn(0, 5) } ?: TIER_2_MODERATE
        }
    }

    // Cooldown and threshold constants
    const val DOWNGRADE_COOLDOWN_MS = 1_500L
    const val RECOVERY_COOLDOWN_MS = 4_000L
    const val WINDOW_SIZE = 30

    private const val DOWNGRADE_P95_MS = 25.0
    private const val DOWNGRADE_DROP_RATIO = 0.12f
    private const val RECOVERY_P95_MS = 14.5
    private const val RECOVERY_DROP_RATIO = 0.02f

    private val currentTierRef = AtomicReference(QualityTier.TIER_0_PRISTINE)
    @Volatile private var isInteracting = false
    @Volatile private var thermalStatus = 0
    @Volatile private var memoryPressureLevel = 0

    // Circular frame time buffer for moving percentiles (nanoseconds)
    private val frameTimesNs = LongArray(WINDOW_SIZE)
    private var frameTimeHead = 0
    private var frameTimeCount = 0

    private var windowDroppedFrames = 0
    private var windowTotalFrames = 0

    private var lastQualityChangeMs = 0L
    private var lastDegradeMs = 0L
    private var lastRecoveryMs = 0L
    private var degradationReason: String = "Normal"

    // Diagnostics & listeners
    var onTierChangedListener: ((QualityTier, String) -> Unit)? = null
    var onTrimMemoryListener: ((Int) -> Unit)? = null

    var timeProvider: () -> Long = { System.currentTimeMillis() }
    fun now(): Long = timeProvider()

    val currentTier: QualityTier get() = currentTierRef.get()
    val isInteractive: Boolean get() = isInteracting

    fun reset(currentTime: Long = now()) {
        currentTierRef.set(QualityTier.TIER_0_PRISTINE)
        isInteracting = false
        thermalStatus = 0
        memoryPressureLevel = 0
        synchronized(frameTimesNs) {
            frameTimesNs.fill(0L)
            frameTimeHead = 0
            frameTimeCount = 0
        }
        windowDroppedFrames = 0
        windowTotalFrames = 0
        lastQualityChangeMs = currentTime - DOWNGRADE_COOLDOWN_MS
        lastDegradeMs = currentTime - RECOVERY_COOLDOWN_MS
        lastRecoveryMs = currentTime - RECOVERY_COOLDOWN_MS
        degradationReason = "Reset"
    }

    /**
     * Sets interactive mode (e.g. scrubbing, pinch-to-zoom, rotate, slider drags).
     * During gestures, latency is prioritized over rendering fidelity.
     */
    fun setInteractionMode(interacting: Boolean) {
        if (isInteracting == interacting) return
        isInteracting = interacting
    }

    /**
     * Resolves the target preview short side taking into account the current tier and
     * active gesture state (stepping down 1 tier during active gestures for responsiveness).
     */
    fun resolvePreviewShortSide(baseShortSide: Int): Int {
        val tier = currentTier
        val effectiveMax = if (isInteracting) {
            when (tier) {
                QualityTier.TIER_0_PRISTINE -> QualityTier.TIER_1_LIGHT.maxShortSide
                QualityTier.TIER_1_LIGHT -> QualityTier.TIER_2_MODERATE.maxShortSide
                QualityTier.TIER_2_MODERATE -> QualityTier.TIER_3_HEAVY.maxShortSide
                QualityTier.TIER_3_HEAVY, QualityTier.TIER_4_CRITICAL, QualityTier.TIER_5_FALLBACK ->
                    QualityTier.TIER_4_CRITICAL.maxShortSide
            }
        } else {
            tier.maxShortSide
        }
        return minOf(baseShortSide, effectiveMax)
    }

    /**
     * Returns the downsampling factor (1, 2, or 4) for heavy spatial effects like blur/glow.
     */
    fun effectDownsampleFactor(): Int {
        val tier = currentTier
        return if (isInteracting && tier.level >= QualityTier.TIER_1_LIGHT.level) {
            maxOf(2, tier.effectDownsample)
        } else {
            tier.effectDownsample
        }
    }

    /**
     * Returns true if high-cost multi-pass shaders should be temporarily bypassed
     * to preserve smooth 60fps tracking during gestures.
     */
    fun shouldBypassHeavyEffectsDuringGesture(): Boolean =
        isInteracting && currentTier.level >= QualityTier.TIER_1_LIGHT.level

    /**
     * Records a rendered video frame and computes rolling frame metrics.
     */
    fun recordFrameTime(frameDeltaNs: Long) {
        if (frameDeltaNs <= 0) return
        val now = now()
        synchronized(frameTimesNs) {
            frameTimesNs[frameTimeHead] = frameDeltaNs
            frameTimeHead = (frameTimeHead + 1) % WINDOW_SIZE
            if (frameTimeCount < WINDOW_SIZE) frameTimeCount++
        }
        windowTotalFrames++
        if (windowTotalFrames >= WINDOW_SIZE) {
            evaluatePerformance(now)
        }
    }

    /**
     * Records dropped frames reported by player analytics.
     */
    fun recordDroppedFrames(count: Int) {
        if (count <= 0) return
        windowDroppedFrames += count
        val now = now()
        if (windowDroppedFrames >= 4) {
            evaluatePerformance(now)
        }
    }

    /**
     * Handles thermal status updates on Android 10+ (API 29+).
     */
    fun onThermalStatusChanged(status: Int) {
        thermalStatus = status
        val now = now()
        when {
            status >= 3 /* THERMAL_STATUS_SEVERE */ -> {
                forceDegrade(QualityTier.TIER_4_CRITICAL, "Thermal Severe (Level $status)", now)
            }
            status >= 2 /* THERMAL_STATUS_MODERATE */ -> {
                if (currentTier.level < QualityTier.TIER_2_MODERATE.level) {
                    forceDegrade(QualityTier.TIER_2_MODERATE, "Thermal Moderate (Level $status)", now)
                }
            }
        }
    }

    /**
     * Handles Android memory pressure callbacks (ComponentCallbacks2.onTrimMemory).
     */
    fun onTrimMemory(level: Int) {
        memoryPressureLevel = level
        val now = now()
        onTrimMemoryListener?.invoke(level)
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                forceDegrade(QualityTier.TIER_4_CRITICAL, "Memory Trim Critical ($level)", now)
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                if (currentTier.level < QualityTier.TIER_3_HEAVY.level) {
                    forceDegrade(QualityTier.TIER_3_HEAVY, "Memory Trim Moderate ($level)", now)
                }
            }
        }
    }

    /**
     * Calculates rolling percentiles and triggers graceful degradation or recovery.
     */
    private fun evaluatePerformance(now: Long) {
        val samples: List<Long>
        synchronized(frameTimesNs) {
            if (frameTimeCount == 0) return
            samples = (0 until frameTimeCount).map { frameTimesNs[it] }.sorted()
        }

        val p95Ms = (samples[((samples.size - 1) * 0.95).toInt()] / 1_000_000.0)
        val meanMs = (samples.average() / 1_000_000.0)
        val total = windowTotalFrames.coerceAtLeast(1)
        val dropRatio = windowDroppedFrames.toFloat() / total

        // Reset window counters
        windowTotalFrames = 0
        windowDroppedFrames = 0

        val current = currentTier

        // 1. Check for Downgrade Condition
        val needsDowngrade = dropRatio > DOWNGRADE_DROP_RATIO || p95Ms > DOWNGRADE_P95_MS ||
            (thermalStatus >= 2 && current.level < QualityTier.TIER_2_MODERATE.level)
        if (needsDowngrade && current.level < 5) {
            if (now - lastQualityChangeMs >= DOWNGRADE_COOLDOWN_MS) {
                val next = QualityTier.fromLevel(current.level + 1)
                val reason = when {
                    dropRatio > DOWNGRADE_DROP_RATIO -> "Dropped frames ${(dropRatio * 100).toInt()}%"
                    p95Ms > DOWNGRADE_P95_MS -> "High p95 frameTime %.1fms".format(p95Ms)
                    else -> "Thermal / System pressure"
                }
                transitionTier(next, reason, now)
                lastDegradeMs = now
                return
            }
        }

        // 2. Check for Recovery Condition (requires sustained stability across RECOVERY_COOLDOWN_MS)
        val canRecover = dropRatio <= RECOVERY_DROP_RATIO && p95Ms <= RECOVERY_P95_MS &&
            thermalStatus < 2 && memoryPressureLevel < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        if (canRecover && current.level > 0) {
            if (now - lastQualityChangeMs >= RECOVERY_COOLDOWN_MS && now - lastDegradeMs >= RECOVERY_COOLDOWN_MS) {
                val next = QualityTier.fromLevel(current.level - 1)
                transitionTier(next, "Stable p95=%.1fms, 0 drops".format(p95Ms), now)
                lastRecoveryMs = now
            }
        }
    }

    private fun forceDegrade(targetTier: QualityTier, reason: String, now: Long) {
        if (currentTier.level < targetTier.level) {
            transitionTier(targetTier, reason, now)
            lastDegradeMs = now
        }
    }

    private fun transitionTier(next: QualityTier, reason: String, now: Long) {
        val prev = currentTierRef.getAndSet(next)
        if (prev != next) {
            lastQualityChangeMs = now
            degradationReason = reason
            Log.i(TAG, "PREVIEW_TIER_TRANSITION from=${prev.name} to=${next.name} reason='$reason'")
            onTierChangedListener?.invoke(next, reason)
        }
    }

    /**
     * Determines whether an imported video or composition configuration recommends a proxy.
     */
    fun shouldRecommendProxy(
        width: Int,
        height: Int,
        fps: Float,
        isHevcOrAv1: Boolean,
        videoLayerCount: Int,
        isLowRam: Boolean,
    ): Boolean {
        val totalPixels = width.toLong() * height
        val is4k = totalPixels >= 3840L * 2160L || (width >= 3840 || height >= 3840)
        val isHighFps = fps >= 55f

        return when {
            isLowRam && is4k -> true
            isLowRam && isHevcOrAv1 && isHighFps -> true
            is4k && (isHevcOrAv1 || isHighFps || videoLayerCount >= 2) -> true
            videoLayerCount >= 4 -> true
            currentTier.level >= QualityTier.TIER_4_CRITICAL.level && totalPixels > 1920L * 1080L -> true
            else -> false
        }
    }

    fun metricsSnapshot(): String {
        val tier = currentTier
        return "tier=${tier.level}(${tier.name}) interacting=$isInteracting thermal=$thermalStatus memTrim=$memoryPressureLevel reason='$degradationReason'"
    }
}
