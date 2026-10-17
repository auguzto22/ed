package com.termex.replay15.editor.preview.engine

import android.opengl.GLES20
import com.termex.replay15.editor.speed.LayerMotion
import com.termex.replay15.editor.speed.NativeSpeed
import com.termex.replay15.editor.speed.SynthesisMethod

/**
 * Passe de movimento da prévia (thread GL do [GpuPreviewRenderer]).
 *
 * Para cada camada de vídeo que precisa (câmera lenta com mistura/optical flow, ou blur por
 * vetores) mantém um pipeline do motor em Rust e uma textura de saída do tamanho do quadro
 * decodificado. Recebe os dois quadros que envolvem o instante pedido (o decodificador entrega
 * em ordem e o renderizador guarda o anterior) e devolve a textura sintetizada, que segue para
 * remoção de fundo, efeitos e máscara como se fosse o quadro decodificado.
 *
 * Nenhum arquivo temporário: tudo acontece por quadro, na GPU. Se o motor não carregar ou o
 * pipeline não puder ser criado neste aparelho, a camada passa direto (quadro original) e o
 * motivo vai para o diagnóstico — a prévia nunca fica preta por causa disto.
 */
internal class PreviewMotionStage(private val event: (String) -> Unit) {
    private class State(
        val handle: Long, val width: Int, val height: Int, val flowWidth: Int, val flowHeight: Int,
        val levels: Int, val iterations: Int, val texture: Int, val fbo: Int,
    )

    private val states = HashMap<ActiveClipResolver.ClipKey, State>()
    private val failed = HashSet<String>()

    /** Textura sintetizada para o instante [targetUs], ou `null` para usar o quadro original. */
    fun apply(
        key: ActiveClipResolver.ClipKey, motion: LayerMotion, width: Int, height: Int,
        textureA: Int, ptsA: Long, textureB: Int, ptsB: Long, targetUs: Long,
    ): Int? {
        if (ptsB <= ptsA) return null
        val (flowWidth, flowHeight) = flowSize(width, height, motion.flowShortSide)
        val levels = motion.flowLevels.coerceAtLeast(1)
        val iterations = motion.flowIterations.coerceAtLeast(1)
        val signature = "${width}x$height/${flowWidth}x$flowHeight/$levels/$iterations"
        if (signature in failed) return null
        var state = states[key]
        if (state != null && (state.width != width || state.height != height || state.flowWidth != flowWidth ||
                state.flowHeight != flowHeight || state.levels != levels || state.iterations != iterations)) {
            release(key); state = null
        }
        if (state == null) {
            state = runCatching { create(width, height, flowWidth, flowHeight, levels, iterations) }
                .onFailure { failed += signature; event("MOTION_STAGE_UNAVAILABLE $signature ${it.message}") }
                .getOrNull() ?: return null
            states[key] = state
        }
        val phase = ((targetUs - ptsA).toDouble() / (ptsB - ptsA)).toFloat().coerceIn(0f, 1f)
        return runCatching {
            val stats = NativeSpeed.motionAnalyze(state.handle, textureA, textureB, ptsA, ptsB, motion.needsFlow)
            val synthesis = when (motion.synthesis) {
                SynthesisMethod.REPEAT -> 0
                SynthesisMethod.BLEND -> 1
                SynthesisMethod.OPTICAL_FLOW -> 2
            }
            // O par está a (ptsB − ptsA) µs de mídia; a exposição já está em µs de mídia.
            val blurScale = if (motion.blurSamples > 0) motion.exposureSourceUs / (ptsB - ptsA) else 0f
            NativeSpeed.motionRender(state.handle, textureA, textureB, phase, synthesis, motion.ghostControl,
                blurScale, motion.blurSamples, state.fbo, 0, 0, width, height)
            if (stats[2] > .5f) event("MOTION_SCENE_CUT key=$key pair=$ptsA..$ptsB")
            state.texture
        }.onFailure { event("MOTION_STAGE_FAILED key=$key ${it.message}") }.getOrNull()
    }

    private fun create(width: Int, height: Int, flowWidth: Int, flowHeight: Int, levels: Int, iterations: Int): State {
        val handle = NativeSpeed.motionCreate(width, height, flowWidth, flowHeight, levels, iterations)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val texture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glGenFramebuffers(1, ids, 0)
        val fbo = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0)
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            NativeSpeed.motionRelease(handle)
            error("framebuffer de movimento incompleto 0x${Integer.toHexString(status)}")
        }
        return State(handle, width, height, flowWidth, flowHeight, levels, iterations, texture, fbo)
    }

    fun release(key: ActiveClipResolver.ClipKey) {
        val state = states.remove(key) ?: return
        runCatching { NativeSpeed.motionRelease(state.handle) }
        GLES20.glDeleteFramebuffers(1, intArrayOf(state.fbo), 0)
        GLES20.glDeleteTextures(1, intArrayOf(state.texture), 0)
    }

    /** O par em cache deixou de valer (busca, troca de mídia). */
    fun invalidate(key: ActiveClipResolver.ClipKey) {
        states[key]?.let { runCatching { NativeSpeed.motionInvalidate(it.handle) } }
    }

    fun releaseAll() {
        states.keys.toList().forEach(::release)
        failed.clear()
    }

    companion object {
        fun flowSize(width: Int, height: Int, shortSide: Int): Pair<Int, Int> {
            val scale = shortSide.toFloat() / minOf(width, height).coerceAtLeast(1)
            fun even(value: Float) = ((value / 2f).toInt() * 2).coerceAtLeast(8)
            return if (scale >= 1f) even(width.toFloat()) to even(height.toFloat()) else even(width * scale) to even(height * scale)
        }

        /** Dois quadros mais distantes que isto não formam um par (salto do decodificador). */
        const val MAX_PAIR_GAP_US = 300_000L
    }
}
