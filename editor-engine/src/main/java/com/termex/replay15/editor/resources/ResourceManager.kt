package com.termex.replay15.editor.resources

import android.content.Context
import com.termex.replay15.editor.animation.AnimationCatalog
import com.termex.replay15.editor.assets.EffectCatalog
import com.termex.replay15.editor.assets.TransitionCatalog
import com.termex.replay15.editor.font.FontCatalog
import com.termex.replay15.editor.licenses.ResourceCreditsRegistry
import com.termex.replay15.editor.packs.ResourcePackManager
import com.termex.replay15.editor.stickers.StickerCatalog

/**
 * Tipos de recursos suportados pelo motor de edição do Recly.
 */
enum class ResourceType(val label: String) {
    FONT("Fonte"),
    TRANSITION("Transição"),
    EFFECT("Efeito"),
    ANIMATION("Animação"),
    STICKER("Sticker / Elemento"),
    PRESET("Preset"),
}

/**
 * Representação unificada de qualquer recurso do catálogo.
 */
data class UnifiedResourceItem(
    val id: String,
    val name: String,
    val type: ResourceType,
    val category: String,
    val author: String,
    val license: String,
    val previewText: String = "",
    val previewUri: String = "",
    val isFavorite: Boolean = false,
    val isRecent: Boolean = false,
    val isAvailable: Boolean = true,
    val tags: List<String> = emptyList(),
)

/**
 * Fachada Central de Recursos do Recly.
 *
 * Desacopla o editor da origem física e formato dos assets, coordenando catálogos,
 * pacotes modulares, buscas globais, favoritos/recentes unificados e relatórios de licença.
 */
object ResourceManager {

    private var initialized = false
    private var appContext: Context? = null

    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        FontCatalog.loadCatalog(context)
        ResourcePackManager.init(context)
        initialized = true
    }

    // Acesso direto aos catálogos especializados
    val fonts get() = FontCatalog
    val transitions get() = TransitionCatalog
    val effects get() = EffectCatalog
    val animations get() = AnimationCatalog
    val stickers get() = StickerCatalog
    val packs get() = ResourcePackManager
    val credits get() = ResourceCreditsRegistry

    /**
     * Busca global unificada em todas as categorias de recursos.
     */
    fun searchAll(query: String): List<UnifiedResourceItem> {
        val q = query.trim()
        val results = ArrayList<UnifiedResourceItem>()

        // 1. Fontes
        FontCatalog.search(q).forEach { font ->
            results.add(
                UnifiedResourceItem(
                    id = font.id,
                    name = font.displayName,
                    type = ResourceType.FONT,
                    category = font.categories.firstOrNull()?.label ?: "Sans",
                    author = FontCatalog.findRemote(font.id)?.author ?: "Google Fonts Contributors",
                    license = font.license,
                    previewText = FontCatalog.previewTextFor(font.id),
                    isFavorite = false,
                    isAvailable = ResourcePackManager.isResourceAvailable(font.id),
                    tags = FontCatalog.tagsFor(font.id),
                )
            )
        }

        // 2. Transições
        val ctx = appContext
        if (ctx != null) {
            TransitionCatalog.search(ctx, q).forEach { trans ->
                results.add(
                    UnifiedResourceItem(
                        id = trans.id,
                        name = trans.name,
                        type = ResourceType.TRANSITION,
                        category = trans.category,
                        author = "GL Transitions / Recly Community",
                        license = "MIT",
                        previewUri = trans.shaderFile,
                        isAvailable = ResourcePackManager.isResourceAvailable(trans.id),
                        tags = listOf(trans.category, trans.engine.name),
                    )
                )
            }
        }

        // 3. Animações
        AnimationCatalog.search(q).forEach { anim ->
            results.add(
                UnifiedResourceItem(
                    id = anim.id,
                    name = anim.name,
                    type = ResourceType.ANIMATION,
                    category = anim.category.label,
                    author = "Recly Motion Design",
                    license = "MIT",
                    isFavorite = ctx?.let { AnimationCatalog.favorites(it).contains(anim.id) } ?: false,
                    isAvailable = ResourcePackManager.isResourceAvailable(anim.id),
                    tags = listOf(anim.category.name.lowercase(), "animation", "motion"),
                )
            )
        }

        // 4. Stickers / Lottie
        StickerCatalog.search(q).forEach { sticker ->
            results.add(
                UnifiedResourceItem(
                    id = sticker.id,
                    name = sticker.name,
                    type = ResourceType.STICKER,
                    category = sticker.category.label,
                    author = sticker.author,
                    license = sticker.license,
                    previewUri = sticker.uri,
                    isFavorite = StickerCatalog.isFavorite(sticker.id),
                    isAvailable = ResourcePackManager.isResourceAvailable(sticker.id),
                    tags = sticker.tags,
                )
            )
        }

        return results
    }

    /**
     * Verifica disponibilidade de um recurso com base nos pacotes instalados.
     */
    fun isAvailable(type: ResourceType, id: String): Boolean {
        return ResourcePackManager.isResourceAvailable(id)
    }

    /**
     * Resolve um recurso aplicando fallback gracioso em caso de desinstalação.
     */
    fun resolveWithFallback(type: ResourceType, id: String): String? {
        return when (type) {
            ResourceType.FONT -> ResourcePackManager.resolveFontWithFallback(id)
            ResourceType.TRANSITION -> ResourcePackManager.resolveTransitionWithFallback(id)
            ResourceType.EFFECT -> ResourcePackManager.resolveEffectWithFallback(id)
            ResourceType.ANIMATION -> ResourcePackManager.resolveAnimationWithFallback(id)
            ResourceType.STICKER, ResourceType.PRESET -> id
        }
    }

    /**
     * Exporta o relatório completo e auditável de licenças e atribuições em Markdown.
     */
    fun exportCreditsMarkdown(): String =
        ResourceCreditsRegistry.formatMarkdownCredits()
}

