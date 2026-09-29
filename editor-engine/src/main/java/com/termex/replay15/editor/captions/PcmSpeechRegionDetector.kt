package com.termex.replay15.editor.captions

import java.io.File
import java.io.FileInputStream
import kotlin.math.max
import kotlin.math.sqrt

/** Conservative energy gate. It is only a candidate detector; music and noise remain ambiguous. */
class PcmSpeechRegionDetector(private val pcmFile: File, private val sampleRate: Int = 16_000,
    private val sensitive: Boolean = false, private val paddingMs: Int = 300) : SpeechRegionDetector {
    override suspend fun detect(): List<SpeechRegion> {
        require(paddingMs in 0..1_000)
        val paddingFrames = paddingMs / 10
        val frameSamples = sampleRate / 100 // 10 ms
        val bytes = ByteArray(frameSamples * 2)
        val energies = mutableListOf<Double>()
        var previousSample = 0.0
        FileInputStream(pcmFile).use { input ->
            while (true) {
                var read = 0
                while (read < bytes.size) {
                    val n = input.read(bytes, read, bytes.size - read)
                    if (n < 0) break
                    read += n
                }
                if (read < bytes.size) break
                var power = 0.0
                for (i in 0 until frameSamples) {
                    val value = (bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)
                    val sample = value.toShort().toInt() / 32768.0
                    val filtered = sample - previousSample * .97
                    previousSample = sample
                    power += filtered * filtered
                }
                energies += sqrt(power / frameSamples)
            }
        }
        if (energies.isEmpty()) return emptyList()
        val sorted = energies.sorted()
        val noiseFloor = sorted[(sorted.size * .2).toInt().coerceAtMost(sorted.lastIndex)]
        val threshold = max(if (sensitive) .003 else .008, noiseFloor * if (sensitive) 2.0 else 3.0)
        val active = energies.map { it >= threshold }
        val regions = mutableListOf<SpeechRegion>()
        var start = -1
        var last = -1
        for (i in active.indices) {
            if (active[i]) { if (start < 0) start = i; last = i }
            if (start >= 0 && (i - last > 25 || i == active.lastIndex)) {
                if (last - start >= 9) {
                    val from = ((start - paddingFrames).coerceAtLeast(0) * 10).toLong()
                    val to = ((last + paddingFrames).coerceAtMost(active.size) * 10).toLong()
                    val rms = energies.subList(start, last + 1).average()
                    val confidence = (rms / (threshold * 2)).toFloat().coerceIn(0f, 1f)
                    if (regions.isNotEmpty() && from - regions.last().endMs < 300) {
                        val previous = regions.removeAt(regions.lastIndex)
                        regions += SpeechRegion(previous.startMs, to, max(previous.confidence, confidence))
                    } else regions += SpeechRegion(from, to, confidence)
                }
                start = -1
            }
        }
        return regions
    }
}
