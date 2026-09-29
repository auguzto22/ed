package com.termex.replay15.editor.export

import android.media.MediaCodecList
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.termex.replay15.editor.domain.Project

object EncoderSupport {
    fun supports(project: Project): Boolean = encoders(project).isNotEmpty()

    /** Resolution/FPS availability must not depend on an arbitrary probe bitrate. */
    fun supportsSizeAndRate(project: Project): Boolean = encoders(project, checkBitrate = false).isNotEmpty()

    fun encoders(project: Project): List<MediaCodecInfo> = encoders(project, checkBitrate = true)

    private fun encoders(project: Project, checkBitrate: Boolean): List<MediaCodecInfo> {
        val (w, h) = project.dimensions()
        if ((project.texts.isNotEmpty() || project.stickers.isNotEmpty()) &&
            (w > 4096 || h > 4096 || w.toLong() * h > 9_000_000)) return emptyList()
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && !info.isAlias && info.isHardwareAccelerated && !info.isSoftwareOnly &&
                info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } &&
                runCatching {
                    val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities ?: return@runCatching false
                    caps.areSizeAndRateSupported(w, h, project.export.fps.toDouble()) &&
                        (!checkBitrate || caps.bitrateRange.contains(project.export.bitrate))
                }.getOrDefault(false)
        }
    }
}
