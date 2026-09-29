package com.recly.core.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

object MediaUriHelper {

    /**
     * Extracts a display name for the given content:// or file:// URI.
     */
    fun getDisplayName(context: Context, uri: Uri, fallback: String = "Video"): String {
        if (uri.scheme == "file") {
            return uri.lastPathSegment ?: fallback
        }
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback
    }

    /**
     * Checks if a given URI is accessible for reading.
     */
    fun isUriReadable(context: Context, uri: Uri): Boolean {
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
    }
}
