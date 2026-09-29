package com.termex.replay15.editor.render

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import com.termex.replay15.editor.domain.MaskState
import com.termex.replay15.editor.domain.MaskType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Applies the same normalized mask contract to bitmap-backed text/sticker export overlays. */
internal object CanvasLayerMask {
    fun draw(canvas: Canvas, bounds: RectF, mask: MaskState?, content: () -> Unit) {
        if (mask == null || mask.opacity <= 0f || bounds.width() <= 0f || bounds.height() <= 0f) {
            content()
            return
        }
        val checkpoint = canvas.saveLayer(bounds, null)
        content()
        val shape = shape(bounds, mask)
        val removal = if (mask.inverted) shape else Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(bounds, Path.Direction.CW)
            addPath(shape)
        }
        val featherPx = mask.feather * min(bounds.width(), bounds.height())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt()
            alpha = (mask.opacity * 255f).toInt().coerceIn(0, 255)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            if (featherPx > .5f) maskFilter = BlurMaskFilter(featherPx, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawPath(removal, paint)
        canvas.restoreToCount(checkpoint)
    }

    private fun shape(bounds: RectF, mask: MaskState): Path {
        val minimum = min(bounds.width(), bounds.height())
        val centerX = bounds.left + mask.centerX * bounds.width()
        val centerY = bounds.top + mask.centerY * bounds.height()
        val halfWidth = (mask.width * bounds.width() * .5f + mask.expansion * minimum).coerceAtLeast(1f)
        val halfHeight = (mask.height * bounds.height() * .5f + mask.expansion * minimum).coerceAtLeast(1f)
        val rect = RectF(centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight)
        val path = when (mask.type) {
            MaskType.RECTANGLE -> Path().apply { addRect(rect, Path.Direction.CW) }
            MaskType.CIRCLE -> Path().apply { addCircle(centerX, centerY, min(halfWidth, halfHeight), Path.Direction.CW) }
            MaskType.ELLIPSE -> Path().apply { addOval(rect, Path.Direction.CW) }
            MaskType.LINEAR -> Path().apply {
                moveTo(rect.left, bounds.top)
                lineTo(rect.right, bounds.top)
                lineTo(rect.right, rect.bottom)
                lineTo(rect.left, rect.bottom)
                close()
            }
            MaskType.MIRROR -> Path().apply { addRect(rect.left, bounds.top, rect.right, bounds.bottom, Path.Direction.CW) }
            MaskType.HEART -> heart(rect)
            MaskType.STAR -> star(rect)
            MaskType.CUSTOM_PATH -> custom(rect, mask)
        }
        if (mask.rotation != 0f) {
            path.transform(Matrix().apply { setRotate(mask.rotation, centerX, centerY) })
        }
        return path
    }

    private fun heart(rect: RectF): Path {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val w = rect.width()
        val h = rect.height()
        return Path().apply {
            moveTo(cx, rect.bottom)
            cubicTo(rect.left - .08f * w, cy + .18f * h, rect.left, cy - .22f * h, cx, cy - .05f * h)
            cubicTo(rect.right, cy - .22f * h, rect.right + .08f * w, cy + .18f * h, cx, rect.bottom)
            close()
        }
    }

    private fun star(rect: RectF): Path {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val outerX = rect.width() * .5f
        val outerY = rect.height() * .5f
        return Path().apply {
            repeat(10) { index ->
                val radius = if (index % 2 == 0) 1f else .43f
                val angle = -PI / 2.0 + index * PI / 5.0
                val x = cx + cos(angle).toFloat() * outerX * radius
                val y = cy + sin(angle).toFloat() * outerY * radius
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
    }

    private fun custom(rect: RectF, mask: MaskState): Path {
        val points = mask.customPath.takeIf { it.size >= 3 } ?: MaskState.DEFAULT_CUSTOM_PATH
        return Path().apply {
            points.forEachIndexed { index, point ->
                val x = rect.left + point.x * rect.width()
                val y = rect.top + point.y * rect.height()
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
    }
}
