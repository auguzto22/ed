package com.termex.replay15.editor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.termex.replay15.editor.captions.AnimatedCaptionStyle
import com.termex.replay15.editor.captions.AnimatedCaptionStyles
import com.termex.replay15.editor.domain.SubtitleWordCue
import com.termex.replay15.editor.domain.TextClip
import com.termex.replay15.editor.render.TextLayout

/**
 * Amostra viva de um estilo de legenda animada: a frase "ISSO MUDOU TUDO" falada em loop,
 * desenhada pelo mesmo [TextLayout] usado no preview e na exportação — o que aparece aqui
 * é exatamente o que vai para o vídeo.
 */
class AnimatedCaptionPreview(context: Context, private val style: AnimatedCaptionStyle) : View(context) {
    private val words = listOf("Isso", "mudou", "tudo")
    private val wordUs = 520_000L
    private val loopUs = wordUs * words.size + 700_000L
    private val startedAt = System.nanoTime()
    private var lastFrame = 0L

    private val clip: TextClip by lazy {
        val base = TextClip(
            text = words.joinToString(" "), startUs = 0L, endUs = wordUs * words.size,
            size = .2f, isCaption = true,
            wordCues = words.mapIndexed { i, w -> SubtitleWordCue(w, i * wordUs, (i + 1) * wordUs) },
        )
        style.patch().applyTo(base)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val elapsed = ((System.nanoTime() - startedAt) / 1_000L) % loopUs
        // Depois da última palavra, a frase fica inteira um instante antes de recomeçar.
        val timeUs = elapsed.coerceAtMost(clip.endUs - 1)
        val block = runCatching { TextLayout.create(clip, width, height, context, timeUs) }.getOrNull() ?: return
        val scale = minOf(1f, width * .94f / block.width.coerceAtLeast(1), height * .9f / block.height.coerceAtLeast(1))
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(scale, scale)
        canvas.translate(-block.width / 2f, -block.height / 2f)
        block.draw(canvas)
        canvas.restore()
        // ~30 quadros por segundo bastam para a amostra e poupam bateria.
        val now = System.nanoTime()
        val delay = (33L - (now - lastFrame) / 1_000_000L).coerceIn(0L, 33L)
        lastFrame = now
        if (isAttachedToWindow) postInvalidateDelayed(delay)
    }
}

object AnimatedCaptionPicker {
    /**
     * Grade de estilos (3 por linha). [onPick] recebe o estilo tocado; a moldura vermelha marca
     * o escolhido, como os chips selecionados do canvas.
     */
    fun build(context: Context, selectedId: String?, onPick: (AnimatedCaptionStyle) -> Unit): LinearLayout = with(context) {
        val grid = column()
        val tiles = mutableListOf<Pair<String, FrameLayout>>()
        fun paint(selected: String?) = tiles.forEach { (id, tile) ->
            val on = id == selected
            tile.background = RippleDrawable(android.content.res.ColorStateList.valueOf(0x26FFFFFF), GradientDrawable().apply {
                // Fundo escuro nos dois temas: é a prévia da legenda como ela fica sobre o vídeo.
                setColor(0xFF0A0A0B.toInt())
                setStroke(dp(if (on) 2 else 1), if (on) EditorStyle.ACTION else EditorStyle.BORDER)
                cornerRadius = dp(12).toFloat()
            }, null)
        }
        AnimatedCaptionStyles.all.chunked(3).forEach { rowStyles ->
            val line = row()
            rowStyles.forEach { style ->
                val cell = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
                val tile = FrameLayout(this).apply {
                    contentDescription = "Estilo ${style.name}"
                    isClickable = true
                    isFocusable = true
                    clipToOutline = true
                    addView(AnimatedCaptionPreview(context, style), FrameLayout.LayoutParams(-1, -1))
                    setOnClickListener {
                        paint(style.id)
                        animate().cancel(); scaleX = .94f; scaleY = .94f
                        animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                        onPick(style)
                    }
                }
                tiles += style.id to tile
                cell.addView(tile, LinearLayout.LayoutParams(-1, dp(68)))
                cell.addView(label(style.name, 11f, EditorStyle.TEXT).apply {
                    gravity = Gravity.CENTER; maxLines = 1; setPadding(0, dp(4), 0, 0)
                }, LinearLayout.LayoutParams(-1, -2))
                line.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(6)) })
            }
            repeat(3 - rowStyles.size) { line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            grid.addView(line, LinearLayout.LayoutParams(-1, -2))
        }
        paint(selectedId)
        grid
    }
}
