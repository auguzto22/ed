package com.termex.replay15.editor.render

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.termex.replay15.editor.audio.AudioEnhance
import com.termex.replay15.editor.audio.AudioEnhanceProcessor
import java.nio.ByteBuffer

/**
 * Runs [AudioEnhance] inside a Media3 [AudioProcessor], so the exported file carries exactly the
 * treatment the preview was playing.
 *
 * Media3 hands processors interleaved 16-bit PCM, while the DSP works in floats. The conversion
 * happens in a scratch array that is grown once and reused for the whole export: allocating per
 * buffer here would hand the GC thousands of short-lived arrays during a long render.
 */
@UnstableApi
class AudioEnhanceProcessorEffect(settings: AudioEnhance) : BaseAudioProcessor() {
    private val settings = settings
    private var engine: AudioEnhanceProcessor? = null
    private var scratch = FloatArray(0)
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Requiring float keeps the conversion exact; asking for it when the source is 16-bit
        // lets Media3 insert its own resampler, which is cheaper than converting per block here.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) return AudioProcessor.AudioFormat(
            inputAudioFormat.sampleRate, inputAudioFormat.channelCount, C.ENCODING_PCM_FLOAT,
        )
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) { replaceOutputBuffer(size); return }
        if (settings.isNeutral) {
            replaceOutputBuffer(size).put(inputBuffer)
            return
        }
        val format = inputAudioFormat
        val samples = size / 4 * format.channelCount
        if (scratch.size < samples) scratch = FloatArray(samples)
        // asFloatBuffer honours each buffer's own byte order, so Media3's native-order
        // pipeline round-trips without a separate swap step.
        inputBuffer.asFloatBuffer().get(scratch, 0, samples)

        val processor = engine ?: AudioEnhanceProcessor(settings, format.sampleRate, format.channelCount)
            .also { engine = it }
        val frames = samples / format.channelCount
        processor.process(scratch, frames)

        replaceOutputBuffer(samples * 4).asFloatBuffer().put(scratch, 0, samples)
    }
}
