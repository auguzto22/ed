package com.termex.replay15.editor

import com.termex.replay15.editor.captions.GeminiResponseParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GeminiResponseParserTest {
    @Test fun parsesWordInfoDurationStrings() {
        val response = JSONObject("""
            {"steps":[{"content":[{"annotations":[
              {"type":"word_info","word":"hoje","start_offset":"1.25s","end_offset":"1.53s"}
            ]}]}]}
        """.trimIndent())
        val words = GeminiResponseParser.parseWordTimestamps(response)
        assertEquals(1_250_000L, words.single().startUs)
        assertEquals(1_530_000L, words.single().endUs)
    }

    @Test fun rejectsIncompleteCorrectionIds() {
        val response = JSONObject().put("output_text", "[{\"id\":\"one\",\"text\":\"ok\"}]")
        val corrections = GeminiResponseParser.parseCorrections(response)
        assertEquals("one", corrections.single().first)
    }

    @Test fun rejectsNonJsonCorrectionResponse() {
        assertThrows(RuntimeException::class.java) {
            GeminiResponseParser.parseCorrections(JSONObject().put("output_text", "não sei"))
        }
    }
}
