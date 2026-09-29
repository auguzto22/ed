package com.termex.replay15.editor

import com.termex.replay15.editor.captions.hasVoskModelFiles
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class VoskModelLayoutTest {
    @Test fun acceptsOfficialPortugueseLegacyLayout() {
        val dir = createTempDir(prefix = "vosk-layout-")
        try {
            listOf("final.mdl", "mfcc.conf", "Gr.fst", "HCLr.fst", "disambig_tid.int")
                .forEach { File(dir, it).writeText("fixture") }
            assertTrue(hasVoskModelFiles(dir))
            File(dir, "Gr.fst").delete()
            assertFalse(hasVoskModelFiles(dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun acceptsCurrentLayout() {
        val dir = createTempDir(prefix = "vosk-layout-")
        try {
            listOf("am/final.mdl", "conf/model.conf", "graph/words.txt").forEach {
                File(dir, it).apply { parentFile!!.mkdirs(); writeText("fixture") }
            }
            assertTrue(hasVoskModelFiles(dir))
        } finally { dir.deleteRecursively() }
    }
}
