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
    @JvmStatic external fun schedulerCreate(outputFps: Float, exposureFraction: Float): Long
    @JvmStatic external fun schedulerInput(handle: Long, ptsUs: Long): LongArray
    @JvmStatic external fun schedulerEnd(handle: Long): LongArray
    @JvmStatic external fun schedulerReset(handle: Long)
    @JvmStatic external fun schedulerRelease(handle: Long)
}
