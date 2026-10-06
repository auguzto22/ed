package com.termex.replay15.editor.licenses

import android.content.Context
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/**
 * Central registry for resource licenses, copyright notices, and author attributions.
 * Ensures that every open source font, transition shader, animation preset, effect,
 * sticker, and Lottie asset used in Recly has proper attribution available in the UI.
 */
object ResourceCreditsRegistry {
    private val credits = ConcurrentHashMap<String, ResourceLicenseMetadata>()

    /**
     * Register an asset's license and attribution metadata.
     */
    fun register(metadata: ResourceLicenseMetadata) {
        credits[metadata.id] = metadata
    }

    /**
     * Register a batch of metadata entries.
     */
    fun registerAll(items: Collection<ResourceLicenseMetadata>) {
        items.forEach { register(it) }
    }

    /**
     * Get metadata for a specific resource ID.
     */
    fun get(id: String): ResourceLicenseMetadata? = credits[id]

    /**
     * Returns all registered credits, sorted by category then name.
     */
    fun all(): List<ResourceLicenseMetadata> =
        credits.values.sortedWith(compareBy({ it.category }, { it.name }))

    /**
     * Filter credits by category (e.g. "Font", "Transition", "Effect", "Animation", "Sticker", "Lottie").
     */
    fun byCategory(category: String): List<ResourceLicenseMetadata> =
        credits.values.filter { it.category.equals(category, ignoreCase = true) }
            .sortedBy { it.name }

    /**
     * Search credits by name, author, tag, or license.
     */
    fun search(query: String): List<ResourceLicenseMetadata> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all()
        return credits.values.filter {
            it.name.lowercase().contains(q) ||
                it.author.lowercase().contains(q) ||
                it.license.lowercase().contains(q) ||
                it.category.lowercase().contains(q) ||
                it.tags.any { tag -> tag.lowercase().contains(q) }
        }.sortedWith(compareBy({ it.category }, { it.name }))
    }

    fun exportAttributionMarkdown(): String = formatMarkdownCredits()

    /**
     * Formats all credits into Markdown for display in the Credits / About screen or legal export.
     */
    fun formatMarkdownCredits(): String = buildString {
        appendLine("# Créditos e Licenças de Recursos do Recly")
        appendLine()
        appendLine("O Recly utiliza recursos abertos de alta qualidade licenciados sob licenças permissivas (SIL Open Font License 1.1, MIT, Apache 2.0).")
        appendLine("Preservamos integralmente todos os direitos autorais e créditos dos autores originais.")
        appendLine()

        val grouped = credits.values.groupBy { it.category }
        grouped.toSortedMap().forEach { (cat, list) ->
            appendLine("## $cat")
            appendLine()
            list.sortedBy { it.name }.forEach { item ->
                appendLine("### ${item.name} (${item.license})")
                appendLine("- **Autor:** ${item.author}")
                appendLine("- **Origem:** ${item.source}")
                if (item.licenseUrl.isNotBlank()) {
                    appendLine("- **Licença:** [${item.license}](${item.licenseUrl})")
                } else {
                    appendLine("- **Licença:** ${item.license}")
                }
                if (item.sourceUrl.isNotBlank()) {
                    appendLine("- **Repositório:** [${item.sourceUrl}](${item.sourceUrl})")
                }
                if (item.notes.isNotBlank()) {
                    appendLine("- **Notas:** ${item.notes}")
                }
                appendLine()
            }
        }
    }

    /**
     * Export all registered credits as a JSON string.
     */
    fun toJson(): String {
        val array = JSONArray()
        all().forEach { array.put(it.toJsonObject()) }
        return array.toString(2)
    }

    /**
     * Clear all registered credits (useful for testing).
     */
    fun clear() {
        credits.clear()
    }
}

