package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.termex.replay15.editor.domain.MAX_STICKER_ANIMATION_US
import com.termex.replay15.editor.domain.MAX_STICKER_LOOP_US
import com.termex.replay15.editor.domain.MIN_STICKER_ANIMATION_US
import com.termex.replay15.editor.domain.MIN_STICKER_LOOP_US
import com.termex.replay15.editor.domain.StickerAnimationKind
import com.termex.replay15.editor.domain.StickerAnimationType
import com.termex.replay15.editor.domain.StickerAnimations
import com.termex.replay15.editor.domain.StickerClip
import com.termex.replay15.editor.domain.StickerMotion
import com.termex.replay15.editor.domain.TextAnimation
import kotlin.math.cos
import kotlin.math.sin

/**
 * Painel Animações do sticker, como no CapCut: abas Entrada · Saída · Loop, uma fileira de
 * cartões com a animação tocando em miniatura, a duração da animação escolhida e ✓.
 * Entrada e saída podem ser usadas juntas; o loop substitui as duas.
 */
object StickerAnimationTools {

    fun show(
        activity: Activity,
        sticker: StickerClip,
        /** Mostra o sticker com a animação na prévia e toca o trecho dela. */
        preview: (StickerClip, StickerAnimationKind) -> Unit,
        restore: () -> Unit,
        apply: (StickerClip) -> Unit,
    ) = with(activity) {
        val body = column()
        lateinit var dialog: Dialog
        var committed = false
        // Stickers de projetos antigos tinham uma animação só: abre já convertida para as abas.
        var animations = StickerAnimations.fromLegacy(sticker.animations, sticker.animation)
        var kind = StickerAnimationKind.IN

        fun draft() = sticker.copy(animations = animations, animation = TextAnimation.NONE)

        val tabs = row().apply { setPadding(0, 0, 0, dp(8)) }
        body.addView(tabs)
        val cardsRow = row().apply { setPadding(0, dp(2), 0, dp(2)) }
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            addView(cardsRow)
        }
        body.addView(scroller, LinearLayout.LayoutParams(-1, -2))

        val durationBox = column().apply { setPadding(0, dp(8), 0, 0) }
        body.addView(durationBox)
        body.addView(label("As animações de entrada e saída podem ser aplicadas juntas.", 12f, EditorStyle.MUTED).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(4))
        })

        val tabViews = LinkedHashMap<StickerAnimationKind, TextView>()
        lateinit var showKind: (StickerAnimationKind) -> Unit

        fun rebuildDuration() {
            durationBox.removeAllViews()
            val selected = animations.selected(kind)
            if (selected == StickerAnimationType.NONE) return
            val loop = kind == StickerAnimationKind.LOOP
            val min = if (loop) MIN_STICKER_LOOP_US else MIN_STICKER_ANIMATION_US
            val max = if (loop) MAX_STICKER_LOOP_US else MAX_STICKER_ANIMATION_US
            // Entrada/saída não passam da duração do sticker.
            val cap = if (loop) max else minOf(max, sticker.durationUs.coerceAtLeast(min))
            val tenths = { us: Long -> (us / 100_000L).toInt() }
            slider(durationBox, if (loop) "Velocidade (duração do ciclo)" else "Duração",
                tenths(animations.durationUs(kind).coerceIn(min, cap)), tenths(cap), tenths(min),
                format = { String.format(java.util.Locale.ROOT, "%.1fs", it / 10f) },
                onStop = { preview(draft(), kind) },
            ) { value -> animations = animations.withDuration(kind, value * 100_000L) }
        }

        fun rebuildCards() {
            cardsRow.removeAllViews()
            val options = listOf(StickerAnimationType.NONE) + StickerAnimationType.of(kind)
            options.forEach { type ->
                val selected = animations.selected(kind) == type
                val cell = column().apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(4), 0, dp(4), 0)
                    isClickable = true; isFocusable = true
                    contentDescription = "${kind.label}: ${type.label}"
                }
                val tile = AnimationTileView(this, type, kind).apply {
                    background = GradientDrawable().apply {
                        setColor(EditorStyle.CARD_ELEVATED); cornerRadius = dp(12).toFloat()
                        setStroke(dp(if (selected) 2f else 1f), if (selected) EditorStyle.ACCENT else EditorStyle.HAIRLINE)
                    }
                }
                cell.addView(tile, LinearLayout.LayoutParams(dp(62), dp(62)))
                cell.addView(label(type.label, 11f, if (selected) EditorStyle.TEXT else EditorStyle.MUTED).apply {
                    gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(5), 0, 0)
                }, LinearLayout.LayoutParams(dp(66), -2))
                cell.setOnClickListener {
                    animations = if (type == StickerAnimationType.NONE) animations.cleared(kind) else animations.with(type)
                    rebuildCards(); rebuildDuration()
                    preview(draft(), kind)
                }
                cardsRow.addView(cell)
            }
        }

        showKind = { next ->
            kind = next
            tabViews.forEach { (key, view) ->
                val on = key == next
                view.setTextColor(if (on) EditorStyle.TEXT else EditorStyle.MUTED)
                view.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            }
            rebuildCards(); rebuildDuration()
            scroller.post { scroller.scrollTo(0, 0) }
        }
        StickerAnimationKind.entries.forEach { k ->
            val view = label(k.label, 15f).apply {
                setPadding(dp(4), dp(6), dp(18), dp(6)); isClickable = true
                setOnClickListener { showKind(k) }
            }
            tabViews[k] = view
            tabs.addView(view)
        }

        // Rodapé com ✓, como o "Animações ✓" do CapCut.
        val footer = row().apply { setPadding(0, dp(10), 0, 0) }
        footer.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        footer.addView(editorIconButton("Aplicar animações", com.recly.editor.engine.R.drawable.ic_recly_check, accent = true) {
            committed = true
            apply(draft())
            dialog.dismiss()
        }, LinearLayout.LayoutParams(dp(52), dp(48)))
        body.addView(footer)

        // Abre na aba que já tem animação (loop, senão saída se só ela existir).
        showKind(when {
            animations.loop != StickerAnimationType.NONE -> StickerAnimationKind.LOOP
            animations.enter == StickerAnimationType.NONE && animations.exit != StickerAnimationType.NONE -> StickerAnimationKind.OUT
            else -> StickerAnimationKind.IN
        })
        dialog = sheet("Animações", body, dim = 0f)
        dialog.setOnDismissListener { if (!committed) restore() }
    }

    /** Cartão com a animação tocando em miniatura numa estrela, para ver antes de escolher. */
    private class AnimationTileView(
        context: Context,
        private val type: StickerAnimationType,
        private val kind: StickerAnimationKind,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val started = SystemClock.uptimeMillis()
        private val cycleUs = 1_600_000L
        private val animations = when (kind) {
            StickerAnimationKind.IN -> StickerAnimations(enter = type, enterUs = 700_000L)
            StickerAnimationKind.OUT -> StickerAnimations(exit = type, exitUs = 700_000L)
            StickerAnimationKind.LOOP -> StickerAnimations(loop = type, loopUs = 800_000L)
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val radius = minOf(width, height) * .26f
            if (type == StickerAnimationType.NONE) {
                // ⊘
                paint.style = Paint.Style.STROKE; paint.strokeWidth = context.dp(1.8f).toFloat()
                paint.color = EditorStyle.MUTED
                canvas.drawCircle(cx, cy, radius * .8f, paint)
                canvas.drawLine(cx - radius * .56f, cy + radius * .56f, cx + radius * .56f, cy - radius * .56f, paint)
                return
            }
            // Entrada: toca e segura; saída: segura e sai; loop: sempre.
            val elapsedUs = ((SystemClock.uptimeMillis() - started) * 1000L) % (cycleUs + 400_000L)
            val t = elapsedUs.coerceAtMost(cycleUs)
            val motion = StickerMotion.evaluate(animations, TextAnimation.NONE, 0L, cycleUs, t)
            canvas.save()
            canvas.translate(cx + motion.offsetX * width * 1.6f, cy + motion.offsetY * height * 1.6f)
            canvas.rotate(motion.rotation)
            canvas.scale(motion.scaleX, motion.scaleY)
            star(radius)
            paint.style = Paint.Style.FILL
            paint.color = EditorStyle.alpha(0xFFFFC21A.toInt(), (255 * motion.alpha).toInt().coerceIn(0, 255))
            canvas.drawPath(path, paint)
            canvas.restore()
            if (isAttachedToWindow) postInvalidateOnAnimation()
        }

        private fun star(radius: Float) {
            path.reset()
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) radius else radius * .45f
                val angle = Math.toRadians(-90.0 + i * 36.0)
                val x = (r * cos(angle)).toFloat(); val y = (r * sin(angle)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
        }
    }
}
