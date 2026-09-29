package com.termex.replay15.editor.project

import com.termex.replay15.editor.domain.*
import java.io.*

/** Reuses the versioned video payload. Only one level of envelopes is accepted. */
internal object TrackCodec {
    fun write(project: Project, out: DataOutputStream) {
        out.writeLong(project.createdAt); out.writeLong(project.modifiedAt)
        out.writeInt(project.trackStates.size)
        project.trackStates.forEach { (id, state) ->
            out.writeUTF(id); out.writeUTF(state.name); out.writeBoolean(state.visible)
            out.writeBoolean(state.locked); out.writeBoolean(state.muted); out.writeBoolean(state.solo)
        }
        out.writeInt(project.videoTracks.size)
        project.videoTracks.forEach { track ->
            out.writeUTF(track.id); out.writeInt(track.clips.size)
            track.clips.forEach { out.writeLong(it.startUs) }
            val bytes = ByteArrayOutputStream()
            ProjectCodec.write(Project(id = "track", videos = track.clips.map { it.clip }), bytes)
            require(bytes.size() <= MAX_PAYLOAD)
            out.writeInt(bytes.size()); out.write(bytes.toByteArray())
        }
    }
    fun read(project: Project, input: DataInputStream, nested: Boolean): Project {
        fun count(max: Int) = input.readInt().also { require(it in 0..max) { "Projeto incompativel" } }
        val created = input.readLong(); val modified = input.readLong()
        val states = linkedMapOf<String, TrackState>()
        repeat(count(64)) {
            val id = input.readUTF(); require(id !in states)
            states[id] = TrackState(input.readUTF(), input.readBoolean(), input.readBoolean(), input.readBoolean(), input.readBoolean())
        }
        val tracks = List(count(if (nested) 0 else 7)) {
            val id = input.readUTF(); val starts = List(count(200)) { input.readLong() }
            val length = count(MAX_PAYLOAD); val bytes = ByteArray(length); input.readFully(bytes)
            val clips = ProjectCodec.read(bytes.inputStream(), nested = true).videos
            require(starts.size == clips.size)
            VideoTrack(id, clips.mapIndexed { index, clip -> TimedVideoClip(starts[index], clip) })
        }
        return project.copy(videoTracks = tracks, trackStates = states, createdAt = created, modifiedAt = modified)
    }
    private const val MAX_PAYLOAD = 4 * 1024 * 1024
}
