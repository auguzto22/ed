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
    private val kind: String,
    private val tint: Int = Color.WHITE,
    private val context: Context? = null,
) : Drawable() {

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
            "Econômico" -> R.drawable.ic_recly_economy
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

    override fun draw(canvas: Canvas) {
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

fun Context.toolAction(name: String, click: () -> Unit): LinearLayout = column().apply {
    gravity = Gravity.CENTER
    minimumWidth = dp(76)
    minimumHeight = dp(74)
    contentDescription = name
    isClickable = true
    isFocusable = true
    setPadding(dp(8), dp(8), dp(8), dp(6))
    val value = android.util.TypedValue()
    theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)
    setBackgroundResource(value.resourceId)

    val iconView = ImageView(context).apply {
        setImageDrawable(EditorGlyph(name, if (name == "+ Midia") EditorStyle.WHITE else 0xFFE4E4E7.toInt(), context))
        importantForAccessibility = 2
    }
    addView(iconView, LinearLayout.LayoutParams(dp(26), dp(26)))

    val labelView = label(name, 11.5f, 0xFFD4D4D8.toInt()).apply {
        gravity = Gravity.CENTER
        importantForAccessibility = 2
        setPadding(0, dp(4), 0, 0)
    }
    addView(labelView)

    setOnClickListener { click() }
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
    background = RippleDrawable(ColorStateList.valueOf(0x26FFFFFF), bg, null)
    val icon = ImageView(context).apply {
        setImageDrawable(EditorGlyph(name, Color.WHITE, context))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }
    addView(icon, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
    setOnClickListener { click() }
    expandTouchTarget(48)
}
