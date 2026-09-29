package com.termex.replay15.editor.font

/**
 * Extended metadata for on-demand fonts loaded from the remote catalog.
 *
 * [FontMetadata] complements the existing [FontAsset] model, which covers built-in and Recly
 * Original fonts. Remote/downloadable fonts are described by [FontMetadata] entries parsed from
 * `font_catalog.json`. A bridge method ([toFontAsset]) enables seamless interoperability with
 * all existing code that consumes [FontAsset].
 */
data class FontMetadata(
    /** Stable unique identifier (e.g. `"inter"`, `"fira_code"`). */
    val id: String,
    /** Canonical family name (e.g. `"Inter"`, `"Fira Code"`). */
    val family: String,
    /** User-facing display name. */
    val displayName: String,
    /** Available font weights (e.g. `[100, 200, ..., 900]`). */
    val weights: List<Int> = listOf(400),
    /** Available styles (`"normal"`, `"italic"`). */
    val styles: List<String> = listOf("normal"),
    /** Whether this is a variable font with continuous weight/italic axes. */
    val variable: Boolean = false,
    /** Unicode subsets covered (`"latin"`, `"latin-ext"`, `"cyrillic"`, …). */
    val subsets: List<String> = listOf("latin"),
    /** Primary typographic category. */
    val category: FontCategory = FontCategory.SANS,
    /** Thematic tags independent of category (e.g. `"Gaming"`, `"Minimal"`, `"Elegante"`). */
    val tags: List<String> = emptyList(),
    /** Origin source for auditing. */
    val source: String = "google-fonts",
    /** SPDX license identifier. */
    val license: String = "OFL-1.1",
    /** Public URL of the license text. */
    val licenseUrl: String = "",
    /** Direct download URL for the primary `.ttf` or variable font file. */
    val remoteUrl: String = "",
    /** Catalog version string (e.g. `"v20"`, `"1.0"`). */
    val version: String = "",
    /** SHA-256 hex digest of the font file for integrity validation. */
    val sha256: String = "",
    /** Estimated file size in bytes. */
    val fileSize: Long = 0,
    /** Whether the font is currently downloaded and cached on disk. Mutable at runtime. */
    var downloaded: Boolean = false,
    /** Absolute local path when downloaded, empty otherwise. */
    var localPath: String = "",
) {
    init {
        require(id.matches(Regex("[a-z0-9_]{1,80}"))) { "Invalid font id: $id" }
        require(family.isNotBlank() && displayName.isNotBlank())
        require(weights.isNotEmpty() && weights.all { it in 1..1000 })
        require(styles.isNotEmpty() && styles.all { it in VALID_STYLES })
    }

    /** Convert to the legacy [FontAsset] model used throughout the codebase. */
    fun toFontAsset(weight: Int = weights.firstOrNull { it == 400 } ?: weights.first()): FontAsset =
        FontAsset(
            id = id,
            displayName = displayName,
            family = family,
            weight = weight,
            italic = false,
            file = if (downloaded) localPath else "",
            source = FontSource.DOWNLOADED,
            license = license,
            categories = setOf(category),
        )

    companion object {
        val VALID_STYLES = setOf("normal", "italic")
    }
}

/** Thematic tag constants used by the catalog and the UI filter tabs. */
object FontTag {
    const val TRENDING = "Em alta"
    const val BOLD = "Bold"
    const val GAMING = "Gaming"
    const val MINIMAL = "Minimal"
    const val ELEGANT = "Elegante"
    const val HANDWRITING = "Escrita"
    const val RETRO = "Retro"
    const val MODERN = "Moderno"
}
