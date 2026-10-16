package com.termex.replay15.editor.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import com.recly.editor.engine.R

/**
 * Sistema de iconografia de alta precisão do editor Recly.
 * Prioriza recursos vetoriais nativos (@drawable/ic_recly_*) e desenha
 * traços vetoriais anti-aliased proporcionais para ferramentas contextuais.
 */
class EditorGlyph(
    kindName: String,
    private val tint: Int = Color.WHITE,
    private val context: Context? = null,
) : Drawable() {

    /** Lookup key without accents, so "Máscaras" and "Mascaras" draw the same icon. */
    private val kind: String = if (kindName.startsWith("canvas:")) kindName else
        java.text.Normalizer.normalize(kindName, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private val delegate: Drawable? = context?.let { ctx ->
        val resId = when (kind) {
            "Fechar" -> R.drawable.ic_recly_close
            "Desfazer" -> R.drawable.ic_recly_undo
            "Refazer" -> R.drawable.ic_recly_redo
            "Reproduzir", "Play" -> R.drawable.ic_recly_play
            "Pausar", "Pause" -> R.drawable.ic_recly_pause
            "Quadro anterior" -> R.drawable.ic_recly_prev_frame
            "Proximo quadro" -> R.drawable.ic_recly_next_frame
            "Tela cheia" -> R.drawable.ic_recly_fullscreen
            "Cortar" -> R.drawable.ic_recly_cut
            "Dividir" -> R.drawable.ic_recly_split
            "Keyframe" -> R.drawable.ic_recly_keyframe
            "Encaixe" -> R.drawable.ic_recly_snap
            "Marcador" -> R.drawable.ic_recly_marker
            "Zoom mais" -> R.drawable.ic_recly_zoom_in
            "Zoom menos" -> R.drawable.ic_recly_zoom_out
            "-1f" -> R.drawable.ic_recly_step_back
            "+1f" -> R.drawable.ic_recly_step_forward
            "Velocidade" -> R.drawable.ic_recly_speed
            "Texto" -> R.drawable.ic_recly_text
            "Efeitos" -> R.drawable.ic_recly_effects
            "Buscar" -> R.drawable.ic_pro_search
            "Volume", "Audio", "Áudio" -> R.drawable.ic_pro_volume
            "Editar" -> R.drawable.ic_recly_cut
            "Projeto" -> R.drawable.ic_pro_folder
            "Favorito" -> R.drawable.ic_recly_star_filled
            "Replay" -> R.drawable.ic_recly_replay_badge
            "Economico" -> R.drawable.ic_recly_economy
            "Qualidade" -> R.drawable.ic_recly_quality
            "Facecam" -> R.drawable.ic_recly_facecam
            "+ Midia" -> R.drawable.ic_pro_plus
            else -> 0
        }
        if (resId != 0) {
            AppCompatResources.getDrawable(ctx, resId)?.mutate()?.also { d ->
                DrawableCompat.setTint(d, tint)
            }
        } else null
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint
        style = Paint.Style.FILL
    }

    /**
     * Ícones da barra de ferramentas (viewBox 24x24, traço 2.1). O nome começa com "canvas:".
     */
    private val canvasPaths: List<Path>? = CANVAS_ICONS[kind]?.mapNotNull { androidx.core.graphics.PathParser.createPathFromPathData(it) }

    private fun drawCanvasIcon(canvas: Canvas, paths: List<Path>) {
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        val stroke = Paint(paint).apply {
            strokeWidth = 2.1f
            style = if (kind in CANVAS_FILLED) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        }
        paths.forEach { canvas.drawPath(it, stroke) }
        canvas.restore()
    }

    override fun draw(canvas: Canvas) {
        if (canvasPaths != null) {
            drawCanvasIcon(canvas, canvasPaths)
            return
        }
        if (delegate != null) {
            delegate.bounds = bounds
            delegate.draw(canvas)
            return
        }

        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)

        fun line(x: Float, y: Float, xx: Float, yy: Float) = canvas.drawLine(x, y, xx, yy, paint)

        when (kind) {
            "Fechar" -> {
                line(6f, 6f, 18f, 18f)
                line(18f, 6f, 6f, 18f)
            }
            "Buscar" -> {
                canvas.drawCircle(10.5f, 10.5f, 5.5f, paint)
                line(14.5f, 14.5f, 19.5f, 19.5f)
            }
            "Editar" -> {
                canvas.drawCircle(7f, 16f, 2.5f, paint)
                canvas.drawCircle(17f, 16f, 2.5f, paint)
                line(8.5f, 14.5f, 17f, 5.5f)
                line(15.5f, 14.5f, 7f, 5.5f)
            }
            "Cortar" -> {
                line(5f, 5f, 5f, 19f)
                line(19f, 5f, 19f, 19f)
                line(5f, 12f, 19f, 12f)
                line(9f, 8f, 9f, 16f)
                line(15f, 8f, 15f, 16f)
            }
            "Dividir" -> {
                line(12f, 3f, 12f, 21f)
                val left = Path().apply { moveTo(8f, 8f); lineTo(4f, 12f); lineTo(8f, 16f) }
                val right = Path().apply { moveTo(16f, 8f); lineTo(20f, 12f); lineTo(16f, 16f) }
                canvas.drawPath(left, paint)
                canvas.drawPath(right, paint)
            }
            "Velocidade" -> {
                canvas.drawArc(3.5f, 4f, 20.5f, 21f, 180f, 180f, false, paint)
                line(12f, 14f, 17f, 9f)
                canvas.drawCircle(12f, 14f, 1.8f, fillPaint)
            }
            "Volume" -> {
                val speaker = Path().apply {
                    moveTo(4f, 9.5f)
                    lineTo(8f, 9.5f)
                    lineTo(12.5f, 5.5f)
                    lineTo(12.5f, 18.5f)
                    lineTo(8f, 14.5f)
                    lineTo(4f, 14.5f)
                    close()
                }
                canvas.drawPath(speaker, paint)
                canvas.drawArc(14.5f, 8f, 18.5f, 16f, -60f, 120f, false, paint)
            }
            "Recorte" -> {
                line(4.5f, 7.5f, 19.5f, 7.5f)
                line(19.5f, 7.5f, 19.5f, 20.5f)
                line(4.5f, 3.5f, 4.5f, 16.5f)
                line(4.5f, 16.5f, 16.5f, 16.5f)
            }
            "Girar" -> {
                canvas.drawArc(5f, 5f, 19f, 19f, -80f, 280f, false, paint)
                val arrow = Path().apply {
                    moveTo(18f, 4f)
                    lineTo(19f, 9f)
                    lineTo(14f, 8f)
                }
                canvas.drawPath(arrow, paint)
            }
            "Espelhar" -> {
                canvas.drawRoundRect(3f, 6f, 10f, 18f, 2f, 2f, paint)
                canvas.drawRoundRect(14f, 6f, 21f, 18f, 2f, 2f, paint)
                line(12f, 3.5f, 12f, 20.5f)
            }
            "Duplicar" -> {
                canvas.drawRoundRect(4f, 7f, 16f, 19f, 2.5f, 2.5f, paint)
                canvas.drawRoundRect(8f, 4f, 20f, 16f, 2.5f, 2.5f, paint)
            }
            "Mover" -> {
                line(4f, 12f, 20f, 12f)
                line(12f, 4f, 12f, 20f)
                line(4f, 12f, 7f, 9f); line(4f, 12f, 7f, 15f)
                line(20f, 12f, 17f, 9f); line(20f, 12f, 17f, 15f)
                line(12f, 4f, 9f, 7f); line(12f, 4f, 15f, 7f)
                line(12f, 20f, 9f, 17f); line(12f, 20f, 15f, 17f)
            }
            "Substituir" -> {
                canvas.drawRoundRect(3.5f, 4.5f, 20.5f, 19.5f, 3f, 3f, paint)
                val cycle = Path().apply {
                    moveTo(8f, 12f); lineTo(12f, 8f); lineTo(16f, 12f)
                }
                canvas.drawPath(cycle, paint)
                line(12f, 8f, 12f, 16f)
            }
            "Excluir" -> {
                canvas.drawRoundRect(6.5f, 7.5f, 17.5f, 20.5f, 2f, 2f, paint)
                line(4.5f, 7.5f, 19.5f, 7.5f)
                line(9f, 4.5f, 15f, 4.5f)
                line(10f, 10.5f, 10f, 17.5f)
                line(14f, 10.5f, 14f, 17.5f)
            }
            "Audio", "Áudio" -> {
                line(9.5f, 17f, 9.5f, 5.5f)
                line(9.5f, 5.5f, 18.5f, 7.5f)
                line(18.5f, 7.5f, 18.5f, 15.5f)
                canvas.drawOval(4.5f, 14.5f, 10f, 19.5f, fillPaint)
                canvas.drawOval(13.5f, 13f, 19f, 18f, fillPaint)
            }
            "Texto" -> {
                line(5f, 5.5f, 19f, 5.5f)
                line(12f, 5.5f, 12f, 19.5f)
                line(8f, 19.5f, 16f, 19.5f)
            }
            "Filtros", "Cor" -> {
                canvas.drawCircle(9.5f, 10.5f, 5.5f, paint)
                canvas.drawCircle(14.5f, 13.5f, 5.5f, paint)
            }
            "Fundo" -> {
                canvas.drawRoundRect(3f, 4f, 21f, 20f, 3f, 3f, paint)
                val hill = Path().apply {
                    moveTo(3f, 16f)
                    lineTo(9f, 10f)
                    lineTo(15f, 16f)
                    lineTo(18f, 13f)
                    lineTo(21f, 16f)
                }
                canvas.drawPath(hill, paint)
            }
            "Keyframe", "Marcador" -> {
                val path = Path().apply {
                    moveTo(12f, 3.5f)
                    lineTo(20.5f, 12f)
                    lineTo(12f, 20.5f)
                    lineTo(3.5f, 12f)
                    close()
                }
                canvas.drawPath(path, paint)
                canvas.drawCircle(12f, 12f, 1.8f, fillPaint)
            }
            "Camada", "Copiar estilo", "Colar estilo" -> {
                canvas.drawRoundRect(4f, 4f, 16f, 16f, 2.5f, 2.5f, paint)
                val edge = Path().apply {
                    moveTo(8f, 20f); lineTo(20f, 20f); lineTo(20f, 8f)
                }
                canvas.drawPath(edge, paint)
            }
            "Mascaras" -> {
                canvas.drawCircle(12f, 12f, 8.5f, paint)
                line(12f, 3.5f, 12f, 20.5f)
                line(3.5f, 12f, 20.5f, 12f)
            }
            "Legendas" -> {
                canvas.drawRoundRect(3f, 5f, 21f, 19f, 3f, 3f, paint)
                line(6.5f, 10f, 11f, 10f)
                line(13f, 10f, 17.5f, 10f)
                line(6.5f, 14f, 17.5f, 14f)
            }
            "Transicao", "Transição" -> {
                val waveA = Path().apply {
                    moveTo(4f, 16f)
                    cubicTo(8f, 16f, 10f, 8f, 14f, 8f)
                    lineTo(20f, 8f)
                }
                val waveB = Path().apply {
                    moveTo(4f, 8f)
                    lineTo(10f, 8f)
                    cubicTo(14f, 8f, 16f, 16f, 20f, 16f)
                }
                canvas.drawPath(waveA, paint)
                canvas.drawPath(waveB, paint)
            }
            "Transformar" -> {
                canvas.drawRoundRect(5f, 5f, 19f, 19f, 2.5f, 2.5f, paint)
                canvas.drawCircle(5f, 5f, 1.8f, fillPaint)
                canvas.drawCircle(19f, 5f, 1.8f, fillPaint)
                canvas.drawCircle(5f, 19f, 1.8f, fillPaint)
                canvas.drawCircle(19f, 19f, 1.8f, fillPaint)
            }
            "Congelar" -> {
                canvas.drawRoundRect(3.5f, 5.5f, 20.5f, 18.5f, 2.5f, 2.5f, paint)
                canvas.drawCircle(12f, 12f, 3f, paint)
                line(12f, 7.5f, 12f, 16.5f)
                line(7.5f, 12f, 16.5f, 12f)
            }
            "Zoom mais" -> {
                canvas.drawCircle(10.5f, 10.5f, 5.5f, paint)
                line(14.5f, 14.5f, 19.5f, 19.5f)
                line(7.5f, 10.5f, 13.5f, 10.5f)
                line(10.5f, 7.5f, 10.5f, 13.5f)
            }
            "Zoom menos" -> {
                canvas.drawCircle(10.5f, 10.5f, 5.5f, paint)
                line(14.5f, 14.5f, 19.5f, 19.5f)
                line(7.5f, 10.5f, 13.5f, 10.5f)
            }
            "Proporcao" -> {
                canvas.drawRoundRect(4f, 5f, 20f, 19f, 2.5f, 2.5f, paint)
                line(9f, 5f, 9f, 19f)
                line(15f, 5f, 15f, 19f)
            }
            "Projeto" -> {
                canvas.drawRoundRect(3.5f, 5.5f, 20.5f, 20.5f, 2.5f, 2.5f, paint)
                val tab = Path().apply {
                    moveTo(7f, 5.5f); lineTo(7f, 10.5f); lineTo(14f, 10.5f); lineTo(14f, 5.5f)
                }
                canvas.drawPath(tab, paint)
            }
            "+ Midia" -> {
                canvas.drawRoundRect(3f, 3f, 21f, 21f, 4f, 4f, paint)
                line(7f, 12f, 17f, 12f)
                line(12f, 7f, 12f, 17f)
            }
            "Efeitos" -> {
                // Varinha mágica de efeitos com estrelas de precisão
                line(4.5f, 19.5f, 14.5f, 9.5f)
                canvas.drawCircle(17f, 7f, 1.8f, fillPaint)
                canvas.drawCircle(13f, 5f, 1.2f, fillPaint)
                canvas.drawCircle(19f, 11f, 1.2f, fillPaint)
            }
            "Voz" -> {
                canvas.drawRoundRect(9f, 4f, 15f, 13f, 3f, 3f, paint)
                canvas.drawArc(6.5f, 7f, 17.5f, 15f, 0f, 180f, false, paint)
                line(12f, 15f, 12f, 19f)
                line(9f, 19f, 15f, 19f)
            }
            "Ajustar" -> {
                line(6f, 4f, 6f, 20f); canvas.drawCircle(6f, 9f, 2.2f, fillPaint)
                line(12f, 4f, 12f, 20f); canvas.drawCircle(12f, 15f, 2.2f, fillPaint)
                line(18f, 4f, 18f, 20f); canvas.drawCircle(18f, 8f, 2.2f, fillPaint)
            }
            "Mais" -> {
                canvas.drawCircle(6f, 12f, 2f, fillPaint)
                canvas.drawCircle(12f, 12f, 2f, fillPaint)
                canvas.drawCircle(18f, 12f, 2f, fillPaint)
            }
            else -> {
                canvas.drawRoundRect(3.5f, 3.5f, 20.5f, 20.5f, 3f, 3f, paint)
                line(7.5f, 12f, 16.5f, 12f)
                line(12f, 7.5f, 12f, 16.5f)
            }
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        fillPaint.alpha = alpha
        delegate?.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        fillPaint.colorFilter = colorFilter
        delegate?.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Drawable opacity")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}


/**
 * Ícones da barra de ferramentas no estilo Blitz: traço único (viewBox 24x24, traço 2.1,
 * pontas redondas), desenhados na cor do grupo da ferramenta (ver [toolColor]).
 */
private val CANVAS_ICONS: Map<String, List<String>> = mapOf(
    "canvas:adicionar" to listOf("M12 5v14M5 12h14"),
    // Dois clipes com a lâmina passando entre eles.
    "canvas:dividir" to listOf("M4 6h4v12H4z", "M16 6h4v12h-4z", "M12 3v18"),
    // Alças de aparar abraçando o clipe.
    "canvas:aparar" to listOf("M9 4H6v16h3", "M15 4h3v16h-3", "M10 12h4"),
    "canvas:texto" to listOf("M5 7V5h14v2", "M12 5v14", "M9 19h6"),
    // Onda sonora, igual à faixa verde de áudio da timeline.
    "canvas:audio" to listOf("M4 10v4", "M8 7v10", "M12 4v16", "M16 7v10", "M20 10v4"),
    "canvas:volume" to listOf("M11 5L6 9H3v6h3l5 4z", "M15.5 8.5a5 5 0 0 1 0 7", "M18.5 5.5a9 9 0 0 1 0 13"),
    "canvas:camadas" to listOf("M12 3L3 7.5l9 4.5 9-4.5z", "M3 12l9 4.5 9-4.5", "M3 16.5l9 4.5 9-4.5"),
    "canvas:stickers" to listOf(
        "M5 3h10l6 6v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
        "M15 3v6h6",
        "M8.5 14.5a4 4 0 0 0 7 0",
    ),
    // Dois clipes com o raio de corte entre eles: o mesmo raio do botão de transição da timeline.
    "canvas:transicao" to listOf("M3 6h4v12H3z", "M17 6h4v12h-4z", "M14 3l-4 8.5 4-1L10 21"),
    "canvas:filtros" to listOf(
        "M7 8a5 5 0 1 0 10 0a5 5 0 1 0-10 0",
        "M3.5 14a5 5 0 1 0 10 0a5 5 0 1 0-10 0",
        "M10.5 14a5 5 0 1 0 10 0a5 5 0 1 0-10 0",
    ),
    "canvas:efeitos" to listOf("M11 3l1.8 5.2L18 10l-5.2 1.8L11 17l-1.8-5.2L4 10l5.2-1.8z", "M19 15v5", "M16.5 17.5h5"),
    "canvas:velocidade" to listOf("M4.5 18A8.5 8.5 0 1 1 19.5 18", "M12 15l4-5"),
    "canvas:recorte" to listOf("M6 2v14a2 2 0 0 0 2 2h14", "M18 22V8a2 2 0 0 0-2-2H2"),
    "canvas:ajustar" to listOf("M4 7h16", "M4 17h16", "M9 4v6", "M15 14v6"),
    "canvas:animacao" to listOf("M13 12a4 4 0 1 0 8 0a4 4 0 1 0-8 0", "M3 8h7", "M2 12h8", "M3 16h7"),
    "canvas:mascara" to listOf(
        "M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z",
        "M8 12a4 4 0 1 0 8 0a4 4 0 1 0-8 0",
    ),
    // Varinha: recorte automático do fundo.
    "canvas:fundo" to listOf("M4 20L14 10", "M17 3v4", "M15 5h4", "M20 10v3", "M18.5 11.5h3", "M10 3v2", "M9 4h2"),
    "canvas:editar" to listOf("M4 20h4L19 9l-4-4L4 16z", "M14 6l4 4"),
    "canvas:legendas" to listOf(
        "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z",
        "M7 11h4", "M13 11h4", "M7 15h6",
    ),
    "canvas:excluir" to listOf("M4 7h16", "M9 7V4h6v3", "M6 7l1 13h10l1-13", "M10 11v6", "M14 11v6"),
    "canvas:fechar" to listOf("M6 6l12 12", "M18 6L6 18"),
    "canvas:mais" to listOf(
        "M3.5 12a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0",
        "M10.5 12a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0",
        "M17.5 12a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0",
    ),
    "canvas:autoedit" to listOf("M13 2L4 14h7l-1 8 9-12h-7z"),
    // Seta do botão ‹ que volta da fileira de um grupo (jeito do CapCut).
    "canvas:voltar" to listOf("M15 5l-7 7 7 7"),
    // Ícones das fileiras de cada grupo (menu de baixo e menu do clipe).
    "canvas:cortar" to listOf("M6 4v16", "M18 4v16", "M6 12h12", "M9 9l-3 3 3 3", "M15 9l3 3-3 3"),
    "canvas:midia" to listOf("M4 5h16v14H4z", "M4 15l5-5 4 4 2-2 5 5", "M15 9.5a1.5 1.5 0 1 0 0.01 0"),
    "canvas:video_camada" to listOf("M3 8h12v11H3z", "M15 12l5-3v9l-5-3", "M7 4h13v3"),
    "canvas:imagem_camada" to listOf("M7 3h14v14H7z", "M3 7v14h14", "M7 13l4-4 3 3 2-2 5 5"),
    "canvas:sticker_animado" to listOf("M12 3a9 9 0 1 0 9 9", "M21 12h-6a3 3 0 0 1-3-3V3", "M8.5 14.5a4 4 0 0 0 6 0"),
    "canvas:nulo" to listOf("M12 3l8 4.5v9L12 21l-8-4.5v-9z", "M4 7.5l8 4.5 8-4.5", "M12 12v9"),
    "canvas:musica" to listOf("M9 18V5l11-2v13", "M3 18a3 3 0 1 0 6 0a3 3 0 1 0-6 0", "M14 16a3 3 0 1 0 6 0a3 3 0 1 0-6 0"),
    "canvas:narracao" to listOf("M9 3h6v11H9z", "M5 11a7 7 0 0 0 14 0", "M12 18v3"),
    "canvas:lista" to listOf("M9 6h11", "M9 12h11", "M9 18h11", "M4 6h1", "M4 12h1", "M4 18h1"),
    "canvas:arquivo" to listOf("M14 3H6v18h12V7z", "M14 3v4h4", "M9 13h6", "M9 17h4"),
    "canvas:legenda_auto" to listOf(
        "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z",
        "M7 15h6", "M15 9l1 2 2 1-2 1-1 2-1-2-2-1 2-1z",
    ),
    "canvas:ia" to listOf("M12 3l1.8 4.2L18 9l-4.2 1.8L12 15l-1.8-4.2L6 9l4.2-1.8z", "M18 15l.9 2.1L21 18l-2.1.9L18 21l-.9-2.1L15 18l2.1-.9z"),
    "canvas:reframe" to listOf("M4 8V4h4", "M16 4h4v4", "M20 16v4h-4", "M8 20H4v-4", "M9 9h6v6H9z"),
    "canvas:auto_cor" to listOf("M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18z", "M12 3v18", "M12 8h5", "M12 12h7", "M12 16h5"),
    "canvas:color_match" to listOf("M3 9a5 5 0 1 0 10 0a5 5 0 1 0-10 0", "M11 15a5 5 0 1 0 10 0a5 5 0 1 0-10 0", "M15 5h4v4", "M19 5l-4 4"),
    "canvas:paleta" to listOf(
        "M12 3a9 9 0 1 0 0 18c1 0 2-1 1.5-2-.6-1.2.3-2 1.5-2H18a3 3 0 0 0 3-3c0-6-4-11-9-11z",
        "M7 11h.01", "M10 7h.01", "M15 7.5h.01",
    ),
    "canvas:presets" to listOf("M4 4h7v7H4z", "M13 4h7v7h-7z", "M4 13h7v7H4z", "M13 13h7v7h-7z"),
    "canvas:lut" to listOf("M3 5h18v14H3z", "M3 12h18", "M12 5v14"),
    "canvas:scopes" to listOf("M3 20h18", "M3 16l4-5 3 3 4-7 3 5 4-4"),
    "canvas:keyframe" to listOf("M12 4l8 8-8 8-8-8z"),
    "canvas:camera3d" to listOf("M12 3l8 4.5v9L12 21l-8-4.5v-9z", "M4 7.5l8 4.5 8-4.5", "M12 12v9"),
    "canvas:rastrear" to listOf("M5 12a7 7 0 1 0 14 0a7 7 0 1 0-14 0", "M10 12a2 2 0 1 0 4 0a2 2 0 1 0-4 0", "M12 2v3", "M12 19v3", "M2 12h3", "M19 12h3"),
    "canvas:estabilizar" to listOf("M4 8h16v8H4z", "M2 12h2", "M20 12h2", "M12 4V2", "M12 22v-2"),
    "canvas:motionblur" to listOf("M12 12a4 4 0 1 0 8 0a4 4 0 1 0-8 0", "M3 12h6", "M5 8h5", "M5 16h5"),
    "canvas:suave" to listOf("M3 16c3 0 3-8 6-8s3 8 6 8 3-8 6-8"),
    "canvas:extrair_audio" to listOf("M4 10v4", "M8 7v10", "M12 4v9", "M16 15l3 3 3-3", "M19 9v9"),
    "canvas:batidas" to listOf("M3 12h3l2-6 3 12 3-9 2 6 2-3h3"),
    "canvas:transformar" to listOf("M4 4h6v6H4z", "M14 14h6v6h-6z", "M10 7h4a3 3 0 0 1 3 3v4"),
    "canvas:girar" to listOf("M20 12a8 8 0 1 1-2.3-5.7", "M20 4v5h-5"),
    "canvas:espelhar" to listOf("M12 3v18", "M9 6L4 18h5z", "M15 6l5 12h-5z"),
    "canvas:duplicar" to listOf("M8 8h12v12H8z", "M4 16V4h12"),
    "canvas:congelar" to listOf("M12 2v20", "M3.5 7l17 10", "M20.5 7l-17 10", "M9 4l3 2 3-2", "M9 20l3-2 3 2"),
    "canvas:mover" to listOf("M12 3v18", "M3 12h18", "M9 6l3-3 3 3", "M9 18l3 3 3-3", "M6 9l-3 3 3 3", "M18 9l3 3-3 3"),
    "canvas:substituir" to listOf("M4 8h14l-4-4", "M20 16H6l4 4"),
    "canvas:grupo" to listOf("M3 7h8v10H3z", "M13 7h8v10h-8z", "M2 4h20", "M2 20h20"),
    "canvas:copiar_estilo" to listOf("M9 9h11v11H9z", "M5 15H4V4h11v1", "M12 14l2 2 4-4"),
    "canvas:colar_estilo" to listOf("M8 4h8v3H8z", "M6 5H4v16h16V5h-2", "M9 14l2 2 4-4"),
    "canvas:idioma" to listOf("M4 5h9", "M8 3v2", "M6 5c1 4 4 7 7 8", "M11 5c-1 4-4 7-7 8", "M13 21l4-9 4 9", "M14.5 18h5"),
)

/** Ícones desenhados cheios (os demais são só traço). */
private val CANVAS_FILLED = setOf("canvas:autoedit", "canvas:mais")

/** Ícone da barra de ferramentas para cada nome de ferramenta, no mesmo traço e na cor do grupo. */
fun canvasIconFor(name: String): String? = when (name) {
    "Adicionar" -> "canvas:adicionar"
    "Dividir" -> "canvas:dividir"
    "Aparar" -> "canvas:aparar"
    "Texto" -> "canvas:texto"
    "Áudio" -> "canvas:audio"
    "Volume" -> "canvas:volume"
    "Camadas" -> "canvas:camadas"
    "Stickers" -> "canvas:stickers"
    "Transição" -> "canvas:transicao"
    "Filtros" -> "canvas:filtros"
    "Efeitos" -> "canvas:efeitos"
    "Velocidade" -> "canvas:velocidade"
    "Recorte" -> "canvas:recorte"
    "Ajustar" -> "canvas:ajustar"
    "Animação" -> "canvas:animacao"
    "Máscara" -> "canvas:mascara"
    "Remover fundo" -> "canvas:fundo"
    "Editar" -> "canvas:editar"
    "Legendas" -> "canvas:legendas"
    "Excluir" -> "canvas:excluir"
    "Fechar" -> "canvas:fechar"
    "Mais" -> "canvas:mais"
    "Auto Edit", "Analisar" -> "canvas:autoedit"
    // Itens das fileiras dos grupos (abrem na própria barra, com ‹ para voltar).
    "Cortar" -> "canvas:cortar"
    "Vídeo ou foto" -> "canvas:midia"
    "Vídeo em camada" -> "canvas:video_camada"
    "Imagem em camada", "Imagem ou sticker" -> "canvas:imagem_camada"
    "Sticker animado", "Stickers Lottie" -> "canvas:sticker_animado"
    "Camadas de imagem" -> "canvas:camadas"
    "Objeto Nulo" -> "canvas:nulo"
    "Música ou áudio", "Importar música" -> "canvas:musica"
    "Gravar narração" -> "canvas:narracao"
    "Faixas de áudio", "Textos e legendas" -> "canvas:lista"
    "Adicionar texto" -> "canvas:texto"
    "Legendas autom." -> "canvas:legenda_auto"
    "Importar legendas" -> "canvas:arquivo"
    "IA" -> "canvas:ia"
    "Auto Reframe" -> "canvas:reframe"
    "Auto Cor" -> "canvas:auto_cor"
    "Color Match" -> "canvas:color_match"
    "Cor", "Cor e LUT" -> "canvas:paleta"
    "Presets" -> "canvas:presets"
    "Navegador LUTs" -> "canvas:lut"
    "Scopes" -> "canvas:scopes"
    "Movimento", "Animações" -> "canvas:animacao"
    "Keyframe" -> "canvas:keyframe"
    "Câmera 3D" -> "canvas:camera3d"
    "Rastreamento" -> "canvas:rastrear"
    "Estabilizar" -> "canvas:estabilizar"
    "Motion Blur" -> "canvas:motionblur"
    "Movimento suave" -> "canvas:suave"
    "Extrair áudio" -> "canvas:extrair_audio"
    "Batidas" -> "canvas:batidas"
    "Clipe" -> "canvas:presets"
    "Transformar" -> "canvas:transformar"
    "Girar" -> "canvas:girar"
    "Espelhar" -> "canvas:espelhar"
    "Duplicar" -> "canvas:duplicar"
    "Congelar" -> "canvas:congelar"
    "Mover" -> "canvas:mover"
    "Substituir" -> "canvas:substituir"
    "Máscaras" -> "canvas:mascara"
    "Criar compound", "Abrir grupo", "Desagrupar" -> "canvas:grupo"
    "Copiar estilo" -> "canvas:copiar_estilo"
    "Colar estilo" -> "canvas:colar_estilo"
    else -> null
}

/**
 * Botão ‹ no começo da barra de baixo (jeito do CapCut): volta da fileira de um grupo para a
 * barra anterior, ou fecha a seleção do clipe. Quadradinho cinza da altura dos botões.
 */
fun Context.toolBackAction(click: () -> Unit): FrameLayout = FrameLayout(this).apply {
    contentDescription = "Voltar"
    tooltipText = "Voltar"
    isClickable = true
    isFocusable = true
    background = RippleDrawable(ColorStateList.valueOf(EditorStyle.RIPPLE), GradientDrawable().apply {
        setColor(EditorStyle.CARD_ELEVATED)
        setStroke(dp(1), EditorStyle.BORDER_LIGHT)
        cornerRadius = dp(10).toFloat()
    }, null)
    addView(ImageView(context).apply {
        setImageDrawable(EditorGlyph("canvas:voltar", EditorStyle.TEXT, context))
        importantForAccessibility = 2
    }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
    setOnClickListener { click() }
}

fun Context.toolAction(name: String, click: () -> Unit, glyph: String = name): LinearLayout = column().apply {
    gravity = Gravity.CENTER
    minimumWidth = dp(66)
    minimumHeight = dp(74)
    contentDescription = name
    tooltipText = name
    isClickable = true
    isFocusable = true
    setPadding(dp(4), dp(6), dp(4), dp(4))

    // Cada grupo de ferramenta tem a sua cor: ícone colorido sobre um quadradinho com fundo e
    // contorno leves da mesma cor. Auto Edit continua cheio de vermelho (ação principal).
    // Com botões pretos o Auto Edit também fica neutro (só o Exportar segue vermelho).
    val hot = (name == "Auto Edit" || name == "Analisar") && EditorStyle.COLORED_BUTTONS
    val color = toolColor(name)
    val tint = if (hot) EditorStyle.ON_ACTION else color
    val box = FrameLayout(context).apply {
        importantForAccessibility = 2
        isDuplicateParentStateEnabled = true
        background = RippleDrawable(ColorStateList.valueOf(if (hot) 0x33000000 else if (color == EditorStyle.TEXT) EditorStyle.RIPPLE else EditorStyle.alpha(color, 0x40)),
            if (hot) EditorStyle.accentGradient(dp(12).toFloat())
            else GradientDrawable().apply {
                setColor(if (color == EditorStyle.TEXT) EditorStyle.CARD_ELEVATED else EditorStyle.alpha(color, 0x24))
                setStroke(dp(1), if (color == EditorStyle.TEXT) EditorStyle.BORDER_LIGHT else EditorStyle.alpha(color, 0x5C))
                cornerRadius = dp(12).toFloat()
            }, null)
        addView(ImageView(context).apply {
            setImageDrawable(EditorGlyph(canvasIconFor(name) ?: glyph, tint, context))
            importantForAccessibility = 2
        }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
    }
    addView(box, LinearLayout.LayoutParams(dp(44), dp(44)))

    val labelTint = when {
        hot -> EditorStyle.ACTION_TEXT
        name == "Excluir" -> EditorStyle.DANGER
        else -> EditorStyle.MUTED
    }
    val labelView = label(name, 11f, labelTint).apply {
        gravity = Gravity.CENTER
        importantForAccessibility = 2
        setPadding(0, dp(3), 0, 0)
        maxLines = 1
    }
    addView(labelView)

    setOnClickListener {
        // Small "pop" on the glyph so every tap is acknowledged before the panel opens.
        box.animate().cancel()
        box.scaleX = .8f; box.scaleY = .8f
        box.animate().scaleX(1f).scaleY(1f).setDuration(240)
            .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
        click()
    }
}

fun Context.iconAction(name: String, click: () -> Unit) = FrameLayout(this).apply {
    contentDescription = name
    tooltipText = name
    isClickable = true
    isFocusable = true
    val bg = GradientDrawable().apply {
        setColor(EditorStyle.CARD)
        setStroke(dp(1), EditorStyle.BORDER)
        cornerRadius = dp(14).toFloat()
    }
    background = RippleDrawable(ColorStateList.valueOf(EditorStyle.RIPPLE), bg, null)
    val icon = ImageView(context).apply {
        setImageDrawable(EditorGlyph(name, EditorStyle.TEXT, context))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }
    addView(icon, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
    setOnClickListener { click() }
    expandTouchTarget(48)
}


/**
 * Cor de cada grupo da barra de ferramentas (mesmas cores das faixas da timeline):
 * mídia e camadas azul, corte e tempo laranja, texto amarelo, áudio verde, efeitos e
 * filtros roxo, ajustes e movimento ciano. Fechar/Mais/Editar ficam neutros.
 */
private fun toolGroupColor(name: String): Int = when (name) {
    "Adicionar", "Camadas", "Stickers", "Vídeo ou foto", "Vídeo em camada", "Imagem em camada",
    "Imagem ou sticker", "Sticker animado", "Stickers Lottie", "Camadas de imagem", "Objeto Nulo",
    "Criar compound", "Abrir grupo", "Desagrupar", "Copiar estilo", "Colar estilo" -> EditorStyle.TRACK_STICKER
    "Dividir", "Aparar", "Recorte", "Velocidade", "Cortar", "Clipe", "Transformar", "Girar",
    "Espelhar", "Duplicar", "Congelar", "Mover", "Substituir" -> EditorStyle.TOOL_CUT
    "Texto", "Legendas", "Adicionar texto", "Legendas autom.", "Textos e legendas",
    "Importar legendas" -> EditorStyle.TRACK_TEXT
    "Áudio", "Volume", "Música ou áudio", "Importar música", "Gravar narração", "Faixas de áudio",
    "Extrair áudio", "Batidas" -> EditorStyle.TRACK_AUDIO
    "Filtros", "Efeitos", "Cor", "Cor e LUT", "Presets", "Navegador LUTs", "Scopes" -> EditorStyle.TRACK_EFFECT
    "Transição" -> EditorStyle.ACTION_TEXT
    "Ajustar", "Animação", "Máscara", "Máscaras", "Remover fundo", "Movimento", "Animações", "Keyframe",
    "Câmera 3D", "Rastreamento", "Estabilizar", "Motion Blur", "Movimento suave" -> EditorStyle.TOOL_ADJUST
    "Auto Edit", "Analisar", "IA", "Auto Reframe", "Auto Cor", "Color Match" -> EditorStyle.ACTION
    "Excluir" -> EditorStyle.DANGER
    else -> EditorStyle.TEXT
}

/**
 * Cor do botão da ferramenta respeitando a escolha em Config. > Aparência: coloridos (cor do
 * grupo; um pouco mais fechada no tema claro para não sumir no branco) ou pretos (todos
 * neutros, só Excluir continua vermelho como aviso).
 */
fun toolColor(name: String): Int {
    val group = toolGroupColor(name)
    if (!EditorStyle.COLORED_BUTTONS) return if (name == "Excluir") EditorStyle.DANGER else EditorStyle.TEXT
    if (group == EditorStyle.TEXT || !EditorStyle.IS_LIGHT) return group
    return EditorStyle.darken(group, .32f)
}
