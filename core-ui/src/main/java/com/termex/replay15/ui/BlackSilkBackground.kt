package com.termex.replay15.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * Fundo de seda preta / vidro líquido escuro gerado inteiramente em código nativo.
 *
 * Características Visuais:
 * - Fundo preto profundo e luxuoso (#08080B).
 * - Ondas curvas Bézier cúbicas amplas, elegantes e fluidas, simulando dobras de seda negra acetinada.
 * - Gradientes monocromáticos de alto requinte que vão de pretos ricos (#0D0E12) a cinzas carvão
 *   e grafite prateado acetinado (#282A34, #3E4250, #55596B).
 * - Zero tonalidades azuladas, arroxeadas ou coloridas.
 * - Brilhos especulares duplos nas cristas das dobras (traço central nítido + aura difusa).
 * - Sombras de oclusão suaves sob cada dobra para profundidade tridimensional escultural.
 * - Cache em Bitmap para renderização ultrarrápida a 60-120fps e alimentação direta do Backdrop Blur.
 */
class BlackSilkBackground @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var cachedBitmap: Bitmap? = null
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            renderSilkToCache(w, h)
        }
    }

    fun getRenderedBitmap(): Bitmap? = cachedBitmap

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = cachedBitmap
        if (bmp != null && !bmp.isRecycled) {
            canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
        } else {
            canvas.drawColor(0xFF070709.toInt())
        }
    }

    private fun renderSilkToCache(w: Int, h: Int) {
        cachedBitmap?.recycle()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val fw = w.toFloat()
        val fh = h.toFloat()

        // 1. Fundo base: Preto profundo com leve nuance diagonal
        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0f, 0f, fw, fh,
                intArrayOf(0xFF080808.toInt(), 0xFF0D0D0F.toInt(), 0xFF080808.toInt()),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, fw, fh, basePaint)

        // 2. Ondas de seda Bézier esculpidas (ordem de profundidade: trás para frente)
        drawWave1(c, fw, fh)
        drawWave2(c, fw, fh)
        drawWave3(c, fw, fh)
        drawWave4(c, fw, fh)
        drawWave5(c, fw, fh)

        // 3. Reflexos especulares acetinados nas cristas das dobras
        drawSilkSheenHighlights(c, fw, fh)

        // 4. Vinheta periférica sutil para manter legibilidade sem apagar as ondas
        drawVignette(c, fw, fh)

        cachedBitmap = bmp
        invalidate()
    }

    /**
     * Dobra 1: Grande manto superior fluido. Começa no topo esquerdo e ondula até o lado direito.
     */
    private fun drawWave1(c: Canvas, w: Float, h: Float) {
        val path = Path().apply {
            moveTo(-0.1f * w, -0.05f * h)
            cubicTo(
                0.20f * w, 0.18f * h,
                0.55f * w, 0.04f * h,
                1.15f * w, 0.32f * h
            )
            lineTo(1.15f * w, -0.05f * h)
            close()
        }

        // Sombra de profundidade abaixo da dobra 1
        drawDropShadow(c, path, w, h, 0.02f * h)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0f, 0f, 0.9f * w, 0.35f * h,
                intArrayOf(
                    0xFF3E3E44.toInt(), // Grafite prateado luminoso (crista da seda)
                    0xFF242428.toInt(), // Seda carvão intermediária
                    0xFF141416.toInt()  // Superfície profunda da dobra
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawPath(path, paint)
    }

    /**
     * Dobra 2: Onda intermediária sinuosa cruzando a porção superior média (atrás dos títulos e cartões).
     */
    private fun drawWave2(c: Canvas, w: Float, h: Float) {
        val path = Path().apply {
            moveTo(-0.15f * w, 0.28f * h)
            cubicTo(
                0.30f * w, 0.16f * h,
                0.60f * w, 0.46f * h,
                1.15f * w, 0.22f * h
            )
            lineTo(1.15f * w, 0.56f * h)
            cubicTo(
                0.70f * w, 0.65f * h,
                0.25f * w, 0.48f * h,
                -0.15f * w, 0.52f * h
            )
            close()
        }

        drawDropShadow(c, path, w, h, 0.025f * h)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0.1f * w, 0.18f * h, 0.85f * w, 0.55f * h,
                intArrayOf(
                    0xFF323238.toInt(),
                    0xFF1E1E22.toInt(),
                    0xFF0D0D0F.toInt()
                ),
                floatArrayOf(0f, 0.50f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawPath(path, paint)
    }

    /**
     * Dobra 3: Grande manto diagonal atravessando o centro e terço inferior, lembrando tecido líquido dobrado.
     */
    private fun drawWave3(c: Canvas, w: Float, h: Float) {
        val path = Path().apply {
            moveTo(-0.1f * w, 0.48f * h)
            cubicTo(
                0.35f * w, 0.35f * h,
                0.50f * w, 0.72f * h,
                1.15f * w, 0.56f * h
            )
            lineTo(1.15f * w, 0.88f * h)
            cubicTo(
                0.68f * w, 0.96f * h,
                0.25f * w, 0.74f * h,
                -0.1f * w, 0.78f * h
            )
            close()
        }

        drawDropShadow(c, path, w, h, 0.03f * h)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                -0.05f * w, 0.40f * h, 0.95f * w, 0.86f * h,
                intArrayOf(
                    0xFF44444C.toInt(), // Ponto mais brilhante de seda dobrada
                    0xFF2A2A30.toInt(),
                    0xFF141416.toInt()
                ),
                floatArrayOf(0f, 0.42f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawPath(path, paint)
    }

    /**
     * Dobra 4: Onda inferior ampla que emoldura a base e a dock flutuante.
     */
    private fun drawWave4(c: Canvas, w: Float, h: Float) {
        val path = Path().apply {
            moveTo(-0.1f * w, 0.70f * h)
            cubicTo(
                0.30f * w, 0.65f * h,
                0.62f * w, 0.88f * h,
                1.15f * w, 0.72f * h
            )
            lineTo(1.15f * w, 1.15f * h)
            lineTo(-0.1f * w, 1.15f * h)
            close()
        }

        drawDropShadow(c, path, w, h, 0.025f * h)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0.15f * w, 0.66f * h, 0.85f * w, 1.05f * h,
                intArrayOf(
                    0xFF38383E.toInt(),
                    0xFF222226.toInt(),
                    0xFF0D0D0F.toInt()
                ),
                floatArrayOf(0f, 0.48f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawPath(path, paint)
    }

    /**
     * Dobra 5: Camada orgânica no canto inferior direito, completando o fluxo dinâmico da seda.
     */
    private fun drawWave5(c: Canvas, w: Float, h: Float) {
        val path = Path().apply {
            moveTo(0.35f * w, 1.12f * h)
            cubicTo(
                0.55f * w, 0.88f * h,
                0.80f * w, 0.90f * h,
                1.15f * w, 0.82f * h
            )
            lineTo(1.15f * w, 1.15f * h)
            close()
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0.40f * w, 0.85f * h, 1.0f * w, 1.10f * h,
                intArrayOf(
                    0xFF2E2E34.toInt(),
                    0xFF1A1A1E.toInt(),
                    0xFF080808.toInt()
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawPath(path, paint)
    }

    /**
     * Desenha uma sombra suave de oclusão abaixo do caminho para dar profundidade 3D escultural.
     */
    private fun drawDropShadow(c: Canvas, path: Path, w: Float, h: Float, dy: Float) {
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xAA000000.toInt()
            maskFilter = BlurMaskFilter(max(16f, w * 0.035f), BlurMaskFilter.Blur.NORMAL)
        }
        val save = c.save()
        c.translate(0f, dy)
        c.drawPath(path, shadowPaint)
        c.restoreToCount(save)
    }

    /**
     * Reflexos de seda acetinada: cristas iluminadas com duplo traço (aura difusa + núcleo nítido).
     */
    private fun drawSilkSheenHighlights(c: Canvas, w: Float, h: Float) {
        // --- Crista 1: Borda da dobra superior 1 ---
        val ridge1 = Path().apply {
            moveTo(-0.05f * w, -0.03f * h)
            cubicTo(
                0.20f * w, 0.18f * h,
                0.55f * w, 0.04f * h,
                1.12f * w, 0.32f * h
            )
        }
        drawSheenLine(c, ridge1, w, 0f, 0f, w, 0.32f * h, 0.70f)

        // --- Crista 2: Borda da dobra 2 ---
        val ridge2 = Path().apply {
            moveTo(-0.10f * w, 0.28f * h)
            cubicTo(
                0.30f * w, 0.16f * h,
                0.60f * w, 0.46f * h,
                1.12f * w, 0.22f * h
            )
        }
        drawSheenLine(c, ridge2, w, 0f, 0.16f * h, w, 0.46f * h, 0.60f)

        // --- Crista 3: Crista principal da grande dobra central 3 ---
        val ridge3 = Path().apply {
            moveTo(-0.08f * w, 0.48f * h)
            cubicTo(
                0.35f * w, 0.35f * h,
                0.50f * w, 0.72f * h,
                1.12f * w, 0.56f * h
            )
        }
        drawSheenLine(c, ridge3, w, 0f, 0.35f * h, w, 0.72f * h, 0.85f)

        // --- Crista 4: Dobra inferior 4 ---
        val ridge4 = Path().apply {
            moveTo(-0.08f * w, 0.70f * h)
            cubicTo(
                0.30f * w, 0.65f * h,
                0.62f * w, 0.88f * h,
                1.12f * w, 0.72f * h
            )
        }
        drawSheenLine(c, ridge4, w, 0.1f * w, 0.65f * h, 0.9f * w, 0.88f * h, 0.65f)
    }

    /**
     * Traço duplo de reflexo acetinado:
     * 1. Aura difusa translúcida simulando dispersão da luz na trama da seda.
     * 2. Crista nítida luminosa.
     */
    private fun drawSheenLine(
        c: Canvas,
        path: Path,
        w: Float,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        intensity: Float
    ) {
        val peakAlpha = (0x75 * intensity).toInt().coerceIn(0x20, 0x85)
        val midAlpha = (0x38 * intensity).toInt().coerceIn(0x10, 0x45)

        // 1. Aura difusa externa
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = max(8f, w * 0.022f)
            shader = LinearGradient(
                x0, y0, x1, y1,
                intArrayOf(0x00FFFFFF, (midAlpha shl 24) or 0x00FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.50f, 1f),
                Shader.TileMode.CLAMP
            )
            maskFilter = BlurMaskFilter(max(10f, w * 0.025f), BlurMaskFilter.Blur.NORMAL)
        }
        c.drawPath(path, glowPaint)

        // 2. Linha de crista nítida
        val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = max(2.5f, w * 0.006f)
            shader = LinearGradient(
                x0, y0, x1, y1,
                intArrayOf(0x00FFFFFF, (peakAlpha shl 24) or 0x00FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.50f, 1f),
                Shader.TileMode.CLAMP
            )
            maskFilter = BlurMaskFilter(max(2f, w * 0.005f), BlurMaskFilter.Blur.NORMAL)
        }
        c.drawPath(path, corePaint)
    }

    /**
     * Vinheta periférica sutil: escurece apenas as bordas extremas sem cobrir as ondas centrais.
     */
    private fun drawVignette(c: Canvas, w: Float, h: Float) {
        val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(
                    0x30000000.toInt(), // Leve proteção no topo
                    0x00000000,         // 100% livre e vívido no centro
                    0x38000000.toInt()  // Leve proteção na base
                ),
                floatArrayOf(0f, 0.40f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, w, h, vignettePaint)
    }
}
