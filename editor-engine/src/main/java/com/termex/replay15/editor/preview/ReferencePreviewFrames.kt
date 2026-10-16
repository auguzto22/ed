package com.termex.replay15.editor.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

/**
 * Mídia de referência das prévias das galerias (filtros, presets e transições).
 *
 * Em vez de aplicar cada look num quadro do vídeo da pessoa, as miniaturas mostram sempre o mesmo
 * vídeo de referência (rua com casarões) em loop, como um GIF. Assim a pessoa compara os efeitos
 * entre si, num vídeo que tem movimento, céu, pele e cor, e a galeria não depende de decodificar
 * o clipe dela.
 *
 * Os arquivos saem de tools/assets/reference_previews.py; as constantes abaixo precisam bater
 * com as de lá (LOOP_FRAMES, LOOP_FPS).
 */
object ReferencePreviewFrames {
    private const val TAG = "ReclyReferencePreview"
    private const val DIR = "editor/previews/reference"
    const val LOOP_ASSET = "$DIR/reference_loop.webp"
    const val FRAME_COUNT = 12
    const val FRAME_MS = 125L

    private val worker by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "ReclyReferenceFrames").apply { isDaemon = true } } }
    private val main by lazy { Handler(Looper.getMainLooper()) }

    @Volatile private var loop: List<Bitmap>? = null
    @Volatile private var loopFailed = false
    @Volatile private var transitionIds: Set<String>? = null

    /** Tempo do efeito (uTime, em segundos) no quadro [index] do loop. */
    fun timeSecAt(index: Int): Float = 0.5f + index * FRAME_MS / 1000f

    /** Quadros do loop (128 px), decodificados uma vez e compartilhados. Bloqueia: chamar fora da main. */
    fun loopBlocking(context: Context): List<Bitmap>? {
        loop?.let { return it }
        if (loopFailed) return null
        return synchronized(this) {
            loop ?: runCatching {
                val strip = context.assets.open(LOOP_ASSET).use { BitmapFactory.decodeStream(it) }
                    ?: error("faixa de referência ilegível")
                val side = strip.width
                val count = (strip.height / side).coerceAtMost(FRAME_COUNT)
                require(count > 0)
                List(count) { i -> Bitmap.createBitmap(strip, 0, i * side, side, side) }
                    .also { if (it.none { frame -> frame === strip }) strip.recycle() }
            }.onFailure {
                loopFailed = true
                Log.w(TAG, "Loop de referência indisponível; as galerias voltam às miniaturas antigas", it)
            }.getOrNull().also { loop = it }
        }
    }

    /** Entrega os quadros do loop na main thread (null se o asset faltar). */
    fun withLoop(context: Context, callback: (List<Bitmap>?) -> Unit) {
        loop?.let { callback(it); return }
        val app = context.applicationContext
        worker.execute {
            val frames = loopBlocking(app)
            main.post { callback(frames) }
        }
    }

    fun transitionAsset(id: String) = "$DIR/transitions/$id.webp"

    private fun transitionIds(context: Context): Set<String> = transitionIds ?: runCatching {
        context.assets.list("$DIR/transitions").orEmpty().filter { it.endsWith(".webp") }.map { it.removeSuffix(".webp") }.toSet()
    }.getOrDefault(emptySet()).also { transitionIds = it }

    /**
     * Prévia animada (WebP animado, da foto de referência para o vídeo) de uma transição.
     * Entrega null quando a transição não tem arquivo (ex.: instalada depois por pacote).
     */
    fun loadTransition(context: Context, id: String, callback: (Drawable?) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val drawable = if (id !in transitionIds(app)) null else runCatching {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(app.assets, transitionAsset(id)))
            }.onFailure { Log.w(TAG, "Prévia da transição $id ilegível", it) }.getOrNull()
            main.post {
                (drawable as? Animatable)?.start()
                callback(drawable)
            }
        }
    }
}
