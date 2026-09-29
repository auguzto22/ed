package com.termex.replay15.editor

import com.termex.replay15.editor.project.ProjectCodec
import com.termex.replay15.editor.domain.allVideos
import org.junit.Test
import java.io.File
import java.io.FileInputStream

class InspectAllProjectsTest {
    @Test
    fun inspectAll() {
        val dir = File("/tmp/all_projects")
        dir.listFiles()?.filter { it.name.endsWith(".r15") }?.forEach { file ->
            try {
                val project = ProjectCodec.read(FileInputStream(file))
                val totalKfs = project.allVideos.sumOf { it.keyframes.size } + project.stickers.sumOf { it.transformKeyframes.size }
                if (totalKfs > 0 || project.stickers.isNotEmpty() || project.videoTracks.isNotEmpty()) {
                    println("FILE: ${file.name} - ${project.name} (id=${project.id}, schema=${project.schemaVersion})")
                    println("  stickers: ${project.stickers.size}, videoTracks: ${project.videoTracks.size}")
                    project.stickers.forEach { s ->
                        println("    STICKER id=${s.id} uri=${s.uri} kfs=${s.transformKeyframes.size}")
                        s.transformKeyframes.forEach { println("       KF: ${it.timeUs} -> ${it.transform}") }
                    }
                    project.allVideos.forEach { v ->
                        if (v.keyframes.isNotEmpty()) {
                            println("    VIDEO id=${v.id} isImage=${v.image} kfs=${v.keyframes.size}")
                            v.keyframes.forEach { println("       KF: ${it.sourceUs} -> (${it.x}, ${it.y}, zoom=${it.zoom})") }
                        }
                    }
                }
            } catch (e: Exception) {
                println("FILE ERROR: ${file.name}: ${e.message}")
            }
        }
    }
}
