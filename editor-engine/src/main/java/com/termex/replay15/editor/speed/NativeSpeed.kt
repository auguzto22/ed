package com.termex.replay15.editor.speed

/**
 * Ponte JNI para o motor de velocidade em Rust (`blitz-speed/`, biblioteca `libblitz_speed.so`).
 *
 * Esta classe só declara as funções; toda a lógica (curvas, integração, mapa de tempo,
 * presets, reajuste, política de qualidade, agendador de quadros, áudio e GPU) está no Rust.
 * Os mapas de tempo são tabelas `LongArray` guardadas pelo Kotlin, então não há objeto nativo
 * a liberar para eles; agendadores, processadores de áudio e pipelines de GPU têm handle e
 * `release` explícito.
 *
 * Na JVM dos testes a biblioteca do host é encontrada por `java.library.path` (o Gradle compila
 * com cargo antes dos testes) ou pela propriedade `blitz.speed.library` com o caminho do arquivo.
 */
object NativeSpeed {
    init {
        val explicit = System.getProperty("blitz.speed.library")
        if (!explicit.isNullOrBlank()) System.load(explicit) else System.loadLibrary("blitz_speed")
    }

    // ---- Mapa de tempo -------------------------------------------------------------------
    @JvmStatic external fun buildTimeMap(sourceUs: Long, inUs: Long, outUs: Long, speed: Float, reverse: Boolean, points: LongArray): LongArray
    @JvmStatic external fun duration(table: LongArray): Long
    @JvmStatic external fun segments(table: LongArray): LongArray
    @JvmStatic external fun sourceAt(table: LongArray, timelineUs: Long): Long
    @JvmStatic external fun timelineAt(table: LongArray, sourceUs: Long): Long
    @JvmStatic external fun extendedSourceAt(table: LongArray, timelineUs: Long): Long
    @JvmStatic external fun speedAtInput(table: LongArray, relativeSourceUs: Long): Float
    /** Próxima mudança relativa ao início do trecho, ou -1. */
    @JvmStatic external fun nextInputChange(table: LongArray, relativeSourceUs: Long): Long
    @JvmStatic external fun curveSpeedAtSource(table: LongArray, sourceUs: Long): Float
    @JvmStatic external fun curveSpeedAtTimeline(table: LongArray, timelineUs: Long): Float
    /** `[mais lenta, mais rápida]` na janela visível. */
    @JvmStatic external fun speedRange(table: LongArray): FloatArray
    @JvmStatic external fun sampleCurve(table: LongArray, fromSourceUs: Long, toSourceUs: Long, count: Int): FloatArray

    // ---- Presets -------------------------------------------------------------------------
    @JvmStatic external fun presetCount(): Int
    @JvmStatic external fun presetId(index: Int): String
    @JvmStatic external fun presetLabel(index: Int): String
    @JvmStatic external fun presetDescription(index: Int): String
    @JvmStatic external fun buildPreset(index: Int, inUs: Long, outUs: Long, intensity: Float): LongArray

    // ---- Reajuste do projeto ---------------------------------------------------------------
    @JvmStatic external fun warpMap(before: LongArray, after: LongArray, oldStartUs: Long, newStartUs: Long, ripple: Boolean, times: LongArray): LongArray

    // ---- Política de qualidade -------------------------------------------------------------
    @JvmStatic external fun motionPlan(ints: IntArray, floats: FloatArray): FloatArray
    @JvmStatic external fun noticeText(code: Int): String

    // ---- Agendador de quadros (exportação) ------------------------------------------------
    /** [endUs]: fim do clipe na base dos carimbos (−1 se desconhecido); a grade nunca passa dele. */
    @JvmStatic external fun schedulerCreate(outputFps: Float, exposureFraction: Float, endUs: Long): Long
    @JvmStatic external fun schedulerInput(handle: Long, ptsUs: Long): LongArray
    @JvmStatic external fun schedulerEnd(handle: Long): LongArray
    @JvmStatic external fun schedulerReset(handle: Long)
    @JvmStatic external fun schedulerRelease(handle: Long)

    // ---- Pipeline de movimento na GPU (sempre na thread com o contexto GL atual) ----------
    /** `[highp (0/1), tamanho máximo de textura]` do contexto atual. */
    @JvmStatic external fun motionProbe(): IntArray
    @JvmStatic external fun motionCreate(outWidth: Int, outHeight: Int, flowWidth: Int, flowHeight: Int, levels: Int, iterations: Int): Long
    /** `[confiança média, correlação, corte (0/1), tem flow (0/1)]`. */
    @JvmStatic external fun motionAnalyze(handle: Long, textureA: Int, textureB: Int, keyA: Long, keyB: Long, flow: Boolean): FloatArray
    /** [synthesis]: 0 repetir, 1 misturar, 2 optical flow; [blurSamples] 0 = sem blur. */
    @JvmStatic external fun motionRender(handle: Long, textureA: Int, textureB: Int, phase: Float, synthesis: Int, ghostControl: Float,
        blurScale: Float, blurSamples: Int, fbo: Int, x: Int, y: Int, width: Int, height: Int)
    /** Soma [texture] à média do quadro de saída [outputUs] (outro instante recomeça a média). */
    @JvmStatic external fun motionAccumulate(handle: Long, texture: Int, outputUs: Long)
    /** Soma A borrada pelo movimento até B (cobre o intervalo entre as duas amostras). */
    @JvmStatic external fun motionAccumulatePair(handle: Long, textureA: Int, textureB: Int, keyA: Long, keyB: Long, samples: Int, outputUs: Long)
    @JvmStatic external fun motionEmitAccumulated(handle: Long, fbo: Int, x: Int, y: Int, width: Int, height: Int)
    @JvmStatic external fun motionCopy(handle: Long, texture: Int, fbo: Int, x: Int, y: Int, width: Int, height: Int)
    @JvmStatic external fun motionInvalidate(handle: Long)
    @JvmStatic external fun motionRelease(handle: Long)

    // ---- Áudio retemporizado da prévia (um handle por sessão) ------------------------------
    @JvmStatic external fun audioCreate(): Long
    @JvmStatic external fun audioInputNeeded(handle: Long, frames: Int, speed: Float, preservePitch: Boolean): Int
    /** Empurra [frames] quadros estéreo de [input]; devolve os quadros de timeline produzidos. */
    @JvmStatic external fun audioPush(handle: Long, input: FloatArray, frames: Int, speed: Float, preservePitch: Boolean, target: Int): Int
    @JvmStatic external fun audioPull(handle: Long, output: FloatArray, frames: Int): Int
    @JvmStatic external fun audioAvailable(handle: Long): Int
    /** Fim da mídia: solta o que ficou nos buffers; devolve os quadros de timeline produzidos. */
    @JvmStatic external fun audioFinish(handle: Long, speed: Float): Int
    @JvmStatic external fun audioReset(handle: Long)
    @JvmStatic external fun audioRelease(handle: Long)

    // ---- Conferência da exportação --------------------------------------------------------
    /** `[veredito (0 = ok), diferença em µs]`; [audioUs] < 0 quando o arquivo não tem áudio. */
    @JvmStatic external fun validateExport(expectedUs: Long, videoUs: Long, audioUs: Long, expectAudio: Boolean, fps: Int): LongArray
    @JvmStatic external fun exportVerdictText(code: Int, differenceUs: Long): String
}
