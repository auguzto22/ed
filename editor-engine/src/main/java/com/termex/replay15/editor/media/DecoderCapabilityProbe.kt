package com.termex.replay15.editor.media

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

object DecoderCapabilityProbe {

    data class DecoderReport(
        val name: String,
        val mimeType: String,
        val isHardware: Boolean,
        val maxInstances: Int,
        val maxWidth: Int,
        val maxHeight: Int,
        val maxFps: Double,
    )

    fun isHardwareDecoderAvailable(mimeType: String): Boolean =
        hardwareDecodersFor(mimeType).isNotEmpty()

    fun hardwareDecodersFor(mimeType: String): List<MediaCodecInfo> = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && !info.isAlias && isHardware(info) &&
                info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
        }
    }.getOrDefault(emptyList())

    fun canDecodeRealtime(width: Int, height: Int, fps: Double, mimeType: String): Boolean {
        if (width <= 0 || height <= 0 || fps <= 0) return true
        val decoders = hardwareDecodersFor(mimeType)
        if (decoders.isEmpty()) {
            // Software decoder on 4K or 60fps is not viable for real-time preview
            return width.toLong() * height < 1920L * 1080L && fps <= 30.0
        }
        return decoders.any { info ->
            runCatching {
                val caps = info.getCapabilitiesForType(mimeType)
                val videoCaps = caps.videoCapabilities ?: return@runCatching false
                videoCaps.areSizeAndRateSupported(width, height, fps)
            }.getOrDefault(false)
        }
    }

    fun maxConcurrentDecoders(mimeType: String): Int {
        val decoders = hardwareDecodersFor(mimeType)
        if (decoders.isEmpty()) return 1
        return decoders.maxOfOrNull { info ->
            runCatching {
                val caps = info.getCapabilitiesForType(mimeType)
                caps.maxSupportedInstances
            }.getOrDefault(2)
        } ?: 2
    }

    private fun isHardware(info: MediaCodecInfo): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated && !info.isSoftwareOnly
        } else {
            val name = info.name.lowercase()
            !name.startsWith("omx.google.") &&
                !name.startsWith("c2.android.") &&
                !name.contains("sw") &&
                !name.contains("soft")
        }
    }
}
