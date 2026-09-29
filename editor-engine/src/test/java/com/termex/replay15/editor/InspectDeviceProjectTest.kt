package com.termex.replay15.editor

import com.termex.replay15.editor.project.ProjectCodec
import com.termex.replay15.editor.domain.allVideos
import org.junit.Test
import java.io.File
import java.io.FileInputStream

class InspectDeviceProjectTest {
    @Test
    fun inspect() {
        val file = File("/tmp/latest_project.r15")
        if (!file.exists()) return
        val project = ProjectCodec.read(FileInputStream(file))
        println("PROJECT ID: ${project.id}")
        println("MAIN VIDEOS: ${project.videos.size}")
        project.videos.forEachIndexed { i, v ->
            println("  MAIN V$i: id=${v.id} uri=${v.uri} isImage=${v.image} kfCount=${v.keyframes.size}")
            v.keyframes.forEach { k -> println("    KF sourceUs=${k.sourceUs} x=${k.x} y=${k.y} zoom=${k.zoom} rot=${k.rotation}") }
        }
        println("VIDEO TRACKS: ${project.videoTracks.size}")
        project.videoTracks.forEachIndexed { i, t ->
            println("  TRACK $i: id=${t.id} clips=${t.clips.size}")
            t.clips.forEach { c ->
                println("    CLIP id=${c.clip.id} uri=${c.clip.uri} isImage=${c.clip.image} startUs=${c.startUs} kfCount=${c.clip.keyframes.size}")
                c.clip.keyframes.forEach { k -> println("      KF sourceUs=${k.sourceUs} x=${k.x} y=${k.y} zoom=${k.zoom} rot=${k.rotation}") }
            }
        }
    }
}
