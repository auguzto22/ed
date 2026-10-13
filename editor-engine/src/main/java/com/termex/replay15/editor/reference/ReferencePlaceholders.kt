package com.termex.replay15.editor.reference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import java.io.File
import kotlin.math.roundToInt

/**
 * Neutral "put your image here" pictures used for image/B-roll slots copied from a reference.
 * Stored in filesDir (not cache) because projects reference them until the user swaps them.
 */
object ReferencePlaceholders {
    @Synchronized fun uri(context: Context, aspect: Float): String? = runCatching {
        val ratio = (aspect.coerceIn(.1f, 10f) * 20).roundToInt() / 20f
        val directory = File(context.filesDir, "reference-placeholders").apply { check(isDirectory || mkdirs()) }
        val target = File(directory, "slot_${(ratio * 100).roundToInt()}.png")
        if (!target.isFile || target.length() == 0L) {
            val longSide = 640
            val width = if (ratio >= 1f) longSide else (longSide * ratio).roundToInt().coerceAtLeast(32)
            val height = if (ratio >= 1f) (longSide / ratio).roundToInt().coerceAtLeast(32) else longSide
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                val radius = minOf(width, height) * .06f
                canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB31E1E24.toInt() })
                val stroke = minOf(width, height) * .025f
                canvas.drawRoundRect(RectF(stroke, stroke, width - stroke, height - stroke), radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = stroke; color = 0xE6FFFFFF.toInt()
                    pathEffect = DashPathEffect(floatArrayOf(stroke * 3, stroke * 2), 0f)
                })
                val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
                    textSize = (minOf(width, height) * .14f).coerceIn(14f, 72f)
                }
                val text = if (width < label.measureText("+ Sua imagem") * 1.1f) "+" else "+ Sua imagem"
                canvas.drawText(text, width / 2f, height / 2f - (label.descent() + label.ascent()) / 2f, label)
                val temporary = File.createTempFile("slot-", ".tmp", directory)
                try {
                    temporary.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    check(temporary.renameTo(target))
                } finally { temporary.delete() }
            } finally { bitmap.recycle() }
        }
        Uri.fromFile(target).toString()
    }.getOrNull()
}
