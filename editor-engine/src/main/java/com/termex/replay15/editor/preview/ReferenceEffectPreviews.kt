package com.termex.replay15.editor.preview

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.termex.replay15.editor.assets.EffectDefinition
import com.termex.replay15.editor.assets.EffectPreset
import com.termex.replay15.editor.assets.EffectPresetNode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Prévias animadas dos presets de efeito (e dos efeitos avulsos) sobre o vídeo de referência.
 *
 * Cada preset é renderizado na GPU do aparelho sobre os 12 quadros do loop de referência (com o
 * tempo do efeito andando junto), então glitch, partículas e luz aparecem se mexendo — como um GIF —
 * sem guardar 1000 arquivos no APK. Os quadros prontos ficam num cache compartilhado entre as
 * aberturas da galeria.
 *
 * Use [createOrNull]: sem o asset de referência ela devolve null e a galeria volta ao modo antigo
 * (miniatura no quadro do clipe).
 */
class ReferenceEffectPreviews private constructor(
    context: Context,
    private val onReady: (presetId: String, frames: List<Bitmap>) -> Unit,
) : AutoCloseable {

    companion object {
        /** 12 quadros de 128 px ≈ 770 KB por preset; o teto acompanha a memória do app. */
        private val cache = object : LruCache<String, List<Bitmap>>(
            (Runtime.getRuntime().maxMemory() / 10).coerceIn(8L shl 20, 32L shl 20).toInt(),
        ) {
            override fun sizeOf(key: String, value: List<Bitmap>) = value.sumOf { it.allocationByteCount }
        }

        fun createOrNull(context: Context, onReady: (presetId: String, frames: List<Bitmap>) -> Unit): ReferenceEffectPreviews? {
            val hasAsset = runCatching { context.assets.open(ReferencePreviewFrames.LOOP_ASSET).close() }.isSuccess
            return if (hasAsset) ReferenceEffectPreviews(context.applicationContext, onReady) else null
        }

        private fun key(preset: EffectPreset, intensity: Float) = "${preset.id}@${(intensity * 10f).toInt()}"
    }

    private val appContext = context
    private val renderer = EffectThumbnailRenderer(appContext)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ReclyReferenceFx").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val pending = ConcurrentHashMap<String, Future<*>>()
    @Volatile private var closed = false

    /** Pede a prévia animada; [onReady] chega na main thread (na hora, se já estiver no cache). */
    fun request(preset: EffectPreset, intensity: Float = 1f, keyPrefix: String = "") {
        if (closed) return
        val key = keyPrefix + key(preset, intensity)
        cache.get(key)?.let { frames -> onReady(preset.id, frames); return }
        if (pending[preset.id]?.isDone == false) return
        pending[preset.id] = worker.submit(Runnable {
            try {
                val loop = ReferencePreviewFrames.loopBlocking(appContext) ?: return@Runnable
                if (closed) return@Runnable
                val frames = renderer.renderPresetFrames(loop, preset, intensity, ReferencePreviewFrames::timeSecAt).get()
                    ?: return@Runnable
                cache.put(key, frames)
                main.post { if (!closed) onReady(preset.id, frames) }
            } catch (_: InterruptedException) {
            } catch (_: java.util.concurrent.ExecutionException) {
            } finally {
                pending.remove(preset.id)
            }
        })
    }

    /**
     * Prévia de um efeito avulso da biblioteca (inclusive os instalados por pacote), com os valores
     * padrão dele — mesmo jeito que o EffectThumbnailRenderer.renderEffect monta. [onReady] recebe o id do efeito.
     */
    fun requestEffect(definition: EffectDefinition, intensity: Float = .7f) {
        val single = runCatching {
            EffectPreset(
                id = definition.id, name = definition.name, category = definition.category.ifBlank { "Efeito" },
                nodes = listOf(EffectPresetNode(
                    assetId = definition.id, version = definition.version, intensity = intensity.coerceIn(0f, 1f),
                    values = definition.parameters.associate { it.id to it.default },
                )),
            )
        }.getOrNull() ?: return
        request(single, 1f, keyPrefix = "fx:")
    }

    /** O cartão saiu da tela: tira da fila (rolar 1000 presets rápido não pode enfileirar todos). */
    fun cancel(presetId: String) {
        pending.remove(presetId)?.cancel(false)
    }

    override fun close() {
        closed = true
        pending.values.forEach { it.cancel(false) }
        pending.clear()
        worker.shutdown()
        renderer.close()
    }
}
