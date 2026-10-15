package com.termex.replay15.editor.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * The animated scene the effects library plays every effect on: an original sunset with a bright
 * sun, dark edges, twinkling stars and two moving balls, so light, vignette, particle and motion
 * effects all have something to show. Stored as one strip of [FRAMES] frames
 * (editor/previews/effect_loop_base.webp, made by tools/make_effect_loop_base.py) and decoded once
 * per process.
 */
object EffectLoopBase {
    const val ASSET = "editor/previews/effect_loop_base.webp"
    const val FRAMES = 12

    @Volatile private var frames: List<Bitmap>? = null

    /** The scene frames, or null if the asset cannot be decoded. Safe from any thread. */
    fun frames(context: Context): List<Bitmap>? {
        frames?.let { return it }
        return synchronized(this) {
            frames ?: runCatching { decode(context) }.getOrNull()?.also { frames = it }
        }
    }

    private fun decode(context: Context): List<Bitmap> {
        val sheet = context.applicationContext.assets.open(ASSET).use { input ->
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } ?: error("Cena de prévia de efeitos inválida")
        try {
            val width = sheet.width / FRAMES
            require(width > 0 && sheet.height > 0)
            return List(FRAMES) { index -> Bitmap.createBitmap(sheet, index * width, 0, width, sheet.height) }
        } finally {
            sheet.recycle()
        }
    }
}
