package com.termex.replay15.editor.render

import android.content.Context
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.termex.replay15.editor.speed.BlurMethod
import com.termex.replay15.editor.speed.MotionPlan
import com.termex.replay15.editor.speed.NativeSpeed
import com.termex.replay15.editor.speed.PreviewMotionPlanner
import com.termex.replay15.editor.speed.SynthesisMethod
import java.util.concurrent.Executor

/**
 * Câmera lenta suave, motion blur e acumulação temporal na exportação.
 *
 * Fica logo no começo da cadeia de efeitos do clipe, então recebe os quadros decodificados com
 * os carimbos de tempo já ajustados pela velocidade (o Media3 aplica o `SpeedProvider` na fonte).
 * O agendador do motor em Rust decide os quadros de saída na grade da exportação; o pipeline de
 * GPU do mesmo motor sintetiza cada um. Os efeitos seguintes (máscara, cor, keyframes, textos)
 * recebem os quadros novos com seus próprios instantes, então as animações também ficam suaves.
 *
 * [plan] vem da política do motor no contexto de exportação; [exposureFraction] é a fração do
 * obturador para a acumulação (0 quando o blur é pela animação da camada, que é outro efeito).
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class MotionRetimeEffect(
    private val plan: MotionPlan,
    private val outputFps: Int,
    private val exposureFraction: Float,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        MotionRetimeProgram(plan, outputFps, exposureFraction, useHdr)
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class MotionRetimeProgram(
    private val plan: MotionPlan,
    private val outputFps: Int,
    private val exposureFraction: Float,
    private val useHdr: Boolean,
) : GlShaderProgram {
    private var inputListener: GlShaderProgram.InputListener = object : GlShaderProgram.InputListener {}
    private var outputListener: GlShaderProgram.OutputListener = object : GlShaderProgram.OutputListener {}
    private var errorListener: GlShaderProgram.ErrorListener = GlShaderProgram.ErrorListener {}
    private var errorExecutor: Executor = Executor { it.run() }

    private var scheduler = 0L
    private var pipeline = 0L
    private var width = 0
    private var height = 0
    private var synthesis = 0
    private var vectorBlur = false
    private val history = arrayOfNulls<GlTextureInfo>(SLOTS)
    private val historyPts = LongArray(SLOTS) { Long.MIN_VALUE }
    private val outputs = ArrayList<GlTextureInfo>()
    private val free = ArrayDeque<GlTextureInfo>()
    /** Ações do agendador ainda não executadas (8 `long` cada). */
    private val pending = ArrayDeque<LongArray>()
    private var input: GlTextureInfo? = null
    private var waitingForInput = true
    private var endOfStream = false
    private var released = false

    override fun setInputListener(listener: GlShaderProgram.InputListener) {
        inputListener = listener
        if (waitingForInput) listener.onReadyToAcceptInputFrame()
    }

    override fun setOutputListener(listener: GlShaderProgram.OutputListener) {
        outputListener = listener
    }

    override fun setErrorListener(executor: Executor, listener: GlShaderProgram.ErrorListener) {
        errorExecutor = executor
        errorListener = listener
    }

    override fun queueInputFrame(glObjectsProvider: GlObjectsProvider, inputTexture: GlTextureInfo, presentationTimeUs: Long) {
        try {
            check(!released)
            waitingForInput = false
            ensureSetup(glObjectsProvider, inputTexture.width, inputTexture.height)
            input = inputTexture
            enqueue(NativeSpeed.schedulerInput(scheduler, presentationTimeUs))
            drain()
        } catch (failure: Exception) {
            report(failure, presentationTimeUs)
        }
    }

    override fun releaseOutputFrame(outputTexture: GlTextureInfo) {
        if (released) return
        if (outputs.any { it === outputTexture } && free.none { it === outputTexture }) free.addLast(outputTexture)
        try { drain() } catch (failure: Exception) { report(failure, 0L) }
    }

    override fun signalEndOfCurrentInputStream() {
        try {
            if (scheduler != 0L) enqueue(NativeSpeed.schedulerEnd(scheduler))
            endOfStream = true
            drain()
        } catch (failure: Exception) {
            report(failure, 0L)
        }
    }

    override fun flush() {
        pending.clear()
        input = null
        endOfStream = false
        free.clear(); free.addAll(outputs)
        historyPts.fill(Long.MIN_VALUE)
        if (scheduler != 0L) NativeSpeed.schedulerReset(scheduler)
        if (pipeline != 0L) NativeSpeed.motionInvalidate(pipeline)
        inputListener.onFlush()
        waitingForInput = true
        inputListener.onReadyToAcceptInputFrame()
    }

    override fun release() {
        if (released) return
        released = true
        try {
            if (pipeline != 0L) NativeSpeed.motionRelease(pipeline)
            if (scheduler != 0L) NativeSpeed.schedulerRelease(scheduler)
            pipeline = 0L; scheduler = 0L
            history.forEach { it?.release() }
            outputs.forEach { it.release() }
            outputs.clear(); free.clear(); pending.clear()
        } catch (failure: GlUtil.GlException) {
            throw VideoFrameProcessingException(failure)
        }
    }

    private fun ensureSetup(provider: GlObjectsProvider, frameWidth: Int, frameHeight: Int) {
        if (scheduler == 0L) scheduler = NativeSpeed.schedulerCreate(outputFps.toFloat(), exposureFraction)
        if (pipeline != 0L && frameWidth == width && frameHeight == height) return
        // Tamanho novo (primeiro quadro ou troca de resolução): recria texturas e pipeline.
        if (pipeline != 0L) NativeSpeed.motionRelease(pipeline)
        history.forEach { it?.release() }
        outputs.forEach { it.release() }
        outputs.clear(); free.clear()
        width = frameWidth; height = frameHeight
        // Sem highp de verdade o optical flow e os vetores não são confiáveis: cai para mistura.
        val highp = runCatching { NativeSpeed.motionProbe()[0] == 1 }.getOrDefault(false)
        synthesis = when {
            plan.synthesis == SynthesisMethod.OPTICAL_FLOW && highp -> 2
            plan.synthesis == SynthesisMethod.REPEAT -> 0
            else -> 1
        }
        vectorBlur = plan.blur == BlurMethod.MOTION_VECTORS && highp
        val (flowWidth, flowHeight) = flowSize(width, height, plan.flowShortSide.coerceAtLeast(64))
        pipeline = NativeSpeed.motionCreate(width, height, flowWidth, flowHeight, plan.flowLevels.coerceAtLeast(1), plan.flowIterations.coerceAtLeast(1))
        for (slot in 0 until SLOTS) history[slot] = texture(provider)
        repeat(OUTPUT_CAPACITY) { outputs += texture(provider) }
        free.addAll(outputs)
    }

    private fun texture(provider: GlObjectsProvider): GlTextureInfo {
        val id = GlUtil.createTexture(width, height, useHdr)
        return provider.createBuffersForTexture(id, width, height)
    }

    private fun enqueue(rows: LongArray) {
        var index = 0
        while (index < rows.size) {
            pending.addLast(rows.copyOfRange(index, index + 8))
            index += 8
        }
    }

    /** Executa as ações na ordem; para quando falta textura de saída e retoma no release. */
    private fun drain() {
        while (pending.isNotEmpty()) {
            val action = pending.first()
            when (action[0].toInt()) {
                STORE -> {
                    val slot = action[1].toInt()
                    val frame = checkNotNull(input) { "quadro de entrada ausente" }
                    val target = checkNotNull(history[slot])
                    NativeSpeed.motionCopy(pipeline, frame.texId, target.fboId, 0, 0, width, height)
                    historyPts[slot] = action[2]
                    input = null
                    inputListener.onInputFrameProcessed(frame)
                }
                ACCUMULATE -> {
                    val sample = checkNotNull(history[action[1].toInt()]).texId
                    val next = action[3].toInt()
                    // Com blur por vetores cada amostra cobre o intervalo até a seguinte: as cópias
                    // do que se move viram um rastro contínuo, como numa exposição longa.
                    if (next >= 0 && vectorBlur) NativeSpeed.motionAccumulatePair(pipeline, sample, checkNotNull(history[next]).texId,
                        action[4], action[5], plan.blurSamples.coerceAtLeast(1), action[2])
                    else NativeSpeed.motionAccumulate(pipeline, sample, action[2])
                }
                EMIT_ACCUMULATED -> {
                    val output = free.removeFirstOrNull() ?: return
                    NativeSpeed.motionEmitAccumulated(pipeline, output.fboId, 0, 0, width, height)
                    outputListener.onOutputFrameAvailable(output, action[1])
                }
                EMIT_SYNTHESIZED -> {
                    val output = free.removeFirstOrNull() ?: return
                    emitSynthesized(action, output)
                    outputListener.onOutputFrameAvailable(output, action[1])
                }
                else -> error("ação desconhecida ${action[0]}")
            }
            pending.removeFirst()
        }
        if (input == null && !waitingForInput && !endOfStream) {
            waitingForInput = true
            inputListener.onReadyToAcceptInputFrame()
        }
        if (endOfStream) {
            endOfStream = false
            if (scheduler != 0L) NativeSpeed.schedulerReset(scheduler)
            if (pipeline != 0L) NativeSpeed.motionInvalidate(pipeline)
            outputListener.onCurrentOutputStreamEnded()
        }
    }

    private fun emitSynthesized(action: LongArray, output: GlTextureInfo) {
        var fromSlot = action[2].toInt()
        var toSlot = action[3].toInt()
        var fromUs = action[4]
        var toUs = action[5]
        var phase = Float.fromBits(action[6].toInt())
        val exposureUs = action[7]
        if (fromSlot == toSlot && vectorBlur && exposureUs > 0) {
            // Quadro original exato: o blur por vetores precisa do movimento até ele, então usa
            // o quadro anterior mais recente como par e mostra o próprio quadro (fase 1).
            val previous = (0 until SLOTS).filter { it != toSlot && historyPts[it] != Long.MIN_VALUE && historyPts[it] < toUs }
                .maxByOrNull { historyPts[it] }
            if (previous != null && toUs - historyPts[previous] <= MAX_PAIR_GAP_US) {
                fromSlot = previous; fromUs = historyPts[previous]; phase = 1f
            }
        }
        val a = checkNotNull(history[fromSlot]).texId
        val b = checkNotNull(history[toSlot]).texId
        if (fromSlot == toSlot) {
            NativeSpeed.motionCopy(pipeline, a, output.fboId, 0, 0, width, height)
            return
        }
        val needsFlow = synthesis == 2 || vectorBlur
        NativeSpeed.motionAnalyze(pipeline, a, b, fromUs, toUs, needsFlow)
        val blurScale = if (vectorBlur && exposureUs > 0) exposureUs.toFloat() / (toUs - fromUs) * plan.blurIntensity else 0f
        NativeSpeed.motionRender(pipeline, a, b, phase, synthesis, PreviewMotionPlanner.GHOST_CONTROL,
            blurScale, if (blurScale > 0f) plan.blurSamples else 0, output.fboId, 0, 0, width, height)
    }

    private fun report(failure: Exception, presentationTimeUs: Long) {
        val exception = failure as? VideoFrameProcessingException ?: VideoFrameProcessingException(failure, presentationTimeUs)
        errorExecutor.execute { errorListener.onError(exception) }
    }

    private companion object {
        const val SLOTS = 3
        const val OUTPUT_CAPACITY = 3
        const val STORE = 0
        const val ACCUMULATE = 1
        const val EMIT_ACCUMULATED = 2
        const val EMIT_SYNTHESIZED = 3
        const val MAX_PAIR_GAP_US = 500_000L

        fun flowSize(width: Int, height: Int, shortSide: Int): Pair<Int, Int> {
            val scale = shortSide.toFloat() / minOf(width, height).coerceAtLeast(1)
            fun even(value: Float) = ((value / 2f).toInt() * 2).coerceAtLeast(8)
            return if (scale >= 1f) even(width.toFloat()) to even(height.toFloat()) else even(width * scale) to even(height * scale)
        }
    }
}
