package com.termex.replay15.editor.licenses

import org.json.JSONArray
import org.json.JSONObject

/**
 * Standardized licensing and attribution metadata for all external assets in Recly.
 * Covers: fonts, transitions, effects, animations, stickers, and Lottie elements.
 */
data class ResourceLicenseMetadata(
    val id: String,
    val name: String,
    val author: String,
    val source: String,
    val license: String,
    val licenseUrl: String = "",
    val sourceUrl: String = "",
    val version: String = "1.0",
    val category: String = "General",
    val tags: List<String> = emptyList(),
    val notes: String = "",
) {
    init {
        require(id.matches(Regex("[a-z0-9_]{1,80}"))) { "Invalid resource id: $id" }
        require(name.isNotBlank()) { "Resource name cannot be blank" }
        require(license.isNotBlank()) { "License cannot be blank" }
    }

    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("author", author)
        put("source", source)
        put("license", license)
        put("licenseUrl", licenseUrl)
        put("sourceUrl", sourceUrl)
        put("version", version)
        put("category", category)
        put("tags", JSONArray(tags))
        if (notes.isNotBlank()) put("notes", notes)
    }

    companion object {
        fun fromJsonObject(json: JSONObject): ResourceLicenseMetadata = ResourceLicenseMetadata(
            id = json.getString("id"),
            name = json.getString("name"),
            author = json.optString("author", "Open Source Contributor"),
            source = json.optString("source", "Open Source"),
            license = json.getString("license"),
            licenseUrl = json.optString("licenseUrl", ""),
            sourceUrl = json.optString("sourceUrl", ""),
            version = json.optString("version", "1.0"),
            category = json.optString("category", "General"),
            tags = json.optJSONArray("tags")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList(),
            notes = json.optString("notes", ""),
        )
    }
}

