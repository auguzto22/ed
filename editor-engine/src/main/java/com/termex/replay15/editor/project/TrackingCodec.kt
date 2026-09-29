package com.termex.replay15.editor.project

import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.TrackingPoint
import com.termex.replay15.editor.domain.TrackingTrack
import java.io.DataInputStream
import java.io.DataOutputStream

/** Schema-24 appendix for bounded internal motion tracks. */
internal object TrackingCodec {
    fun write(project: Project, out: DataOutputStream) {
        out.writeInt(project.videos.size)
        project.videos.forEach { video ->
            out.writeInt(video.trackingTracks.size)
            video.trackingTracks.forEach { track ->
                out.writeUTF(track.id)
                out.writeInt(track.points.size)
                track.points.forEach { point ->
                    out.writeLong(point.timeUs)
                    out.writeFloat(point.centerX); out.writeFloat(point.centerY)
                    out.writeFloat(point.width); out.writeFloat(point.height); out.writeFloat(point.confidence)
                }
            }
        }
    }

    fun read(project: Project, input: DataInputStream): Project {
        fun count(max: Int) = input.readInt().also { require(it in 0..max) { "Projeto incompativel" } }
        val videoCount = count(200)
        require(videoCount == project.videos.size)
        val tracksByVideo = List(videoCount) {
            List(count(8)) {
                val id = input.readUTF()
                val points = List(count(TrackingTrack.MAX_POINTS)) {
                    TrackingPoint(input.readLong(), input.readFloat(), input.readFloat(), input.readFloat(),
                        input.readFloat(), input.readFloat())
                }
                TrackingTrack(id, points)
            }
        }
        val restored = project.copy(videos = project.videos.mapIndexed { index, video ->
            video.copy(trackingTracks = tracksByVideo[index])
        })
        require(restored.videos.all { video ->
            video.mask?.trackingTrackId == null || video.trackingTracks.any { it.id == video.mask.trackingTrackId }
        }) { "Mascara referencia track inexistente" }
        return restored
    }
}
