package com.termex.replay15.editor

import com.termex.replay15.editor.captions.AnimatedCaptionStyles
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.project.ProjectCodec
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedCaptionStylesTest {
    private val second = 1_000_000L

    @Test fun everyStyleProducesAValidCaption() {
        val base = TextClip(text = "isso mudou tudo", startUs = 0L, endUs = 2 * second, isCaption = true)
        val ids = AnimatedCaptionStyles.all.map { it.id }
        assertEquals("Ids repetidos", ids.size, ids.distinct().size)
        for (style in AnimatedCaptionStyles.all) {
            val styled = style.patch().applyTo(AnimatedCaptionStyles.withWordTiming(base))
            assertEquals(style.fontId, styled.fontId)
            assertEquals(style.wordStyle, styled.wordStyle)
        }
    }

    @Test fun animatedCaptionsNeverUseReclyOriginalFonts() {
        val reclyFonts = AnimatedCaptionStyles.all.filter { it.fontId.startsWith("recly_") }.map { it.id }
        assertTrue("Legendas animadas com fonte Recly: $reclyFonts", reclyFonts.isEmpty())
    }

    @Test fun estimatedTimingCoversTheWholeCaptionInOrder() {
        val clip = TextClip(text = "uma frase com várias palavras aqui", startUs = 3 * second, endUs = 5 * second, isCaption = true)
        val cues = AnimatedCaptionStyles.withWordTiming(clip).wordCues
        assertEquals(6, cues.size)
        assertEquals(clip.startUs, cues.first().startUs)
        assertEquals(clip.endUs, cues.last().endUs)
        cues.zipWithNext().forEach { (a, b) -> assertEquals(a.endUs, b.startUs) }
        assertTrue(cues.all { it.endUs > it.startUs })
    }

    @Test fun measuredTimingIsNeverReplaced() {
        val measured = listOf(SubtitleWordCue("oi", 0L, 400_000L))
        val clip = TextClip(text = "oi gente", startUs = 0L, endUs = second, isCaption = true, wordCues = measured)
        assertEquals(measured, AnimatedCaptionStyles.withWordTiming(clip).wordCues)
    }

    @Test fun newWordEffectsSurviveSaveAndLoad() {
        val style = SubtitleWordStyle(activeColor = 0xFF2BE07A.toInt(), activeScale = 1.2f, highlightColor = 0xFFC50022.toInt(),
            reveal = true, uppercase = true, pop = true, activePalette = listOf(0xFFFF4FA3.toInt(), 0xFF38E1FF.toInt()))
        val caption = AnimatedCaptionStyles.withWordTiming(
            TextClip(text = "salva os efeitos", startUs = 0L, endUs = second, isCaption = true, wordStyle = style))
        val project = Project(name = "Legenda animada", texts = listOf(caption),
            videos = listOf(VideoClip(uri = "content://video/1", name = "Clip", sourceUs = 2 * second, width = 1080, height = 1920)))
        val bytes = ByteArrayOutputStream().also { ProjectCodec.write(project, it) }.toByteArray()
        val restored = ProjectCodec.read(bytes.inputStream())
        assertEquals(style, restored.texts.single().wordStyle)
        assertEquals(caption.wordCues, restored.texts.single().wordCues)
    }

    @Test fun aCaptionShorterThanItsWordCountKeepsItsTextInsteadOfCrashing() {
        val clip = TextClip(text = "uma duas tres quatro", startUs = 0L, endUs = 3L, isCaption = true)
        assertEquals(clip, AnimatedCaptionStyles.withWordTiming(clip))
    }
}
