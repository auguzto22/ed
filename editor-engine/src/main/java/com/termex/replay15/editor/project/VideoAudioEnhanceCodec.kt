package com.termex.replay15.editor.project

import com.termex.replay15.editor.audio.AudioEnhance
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.VideoClip
import java.io.DataInputStream
import java.io.DataOutputStream

/** Schema-26 appendix for audio treatments on embedded video audio. */
internal object VideoAudioEnhanceCodec {
    private fun clips(project: Project): List<VideoClip> = project.videos +
        project.videoTracks.flatMap { track -> track.clips.map { it.clip } }

    fun write(project: Project, out: DataOutputStream) {
        val clips = clips(project)
        out.writeInt(clips.size)
        clips.forEach { clip ->
            out.writeFloat(clip.enhance.noiseReduction)
            out.writeFloat(clip.enhance.voiceEnhance)
            out.writeFloat(clip.enhance.compression)
            out.writeFloat(clip.enhance.normalize)
        }
    }

    fun read(project: Project, input: DataInputStream): Project {
        val clips = clips(project)
        require(input.readInt() == clips.size) { "Projeto incompativel" }
        val settings = List(clips.size) {
            fun amount() = input.readFloat().let { if (it.isNaN()) 0f else it.coerceIn(0f, 1f) }
            AudioEnhance(noiseReduction = amount(), voiceEnhance = amount(),
                compression = amount(), normalize = amount())
        }
        var index = 0
        val videos = project.videos.map { it.copy(enhance = settings[index++]) }
        val tracks = project.videoTracks.map { track ->
            track.copy(clips = track.clips.map { placed ->
                placed.copy(clip = placed.clip.copy(enhance = settings[index++]))
            })
        }
        return project.copy(videos = videos, videoTracks = tracks)
    }
}