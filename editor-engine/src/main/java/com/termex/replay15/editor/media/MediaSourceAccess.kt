package com.termex.replay15.editor.media

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import java.io.File
import java.io.FileNotFoundException

sealed interface MediaSourceStatus {
    data object Available : MediaSourceStatus
    data object Missing : MediaSourceStatus
    data object PermissionLost : MediaSourceStatus
    data class ReadError(val cause: Throwable) : MediaSourceStatus
}

/** Performs source checks only at import/open/recovery boundaries, never in a frame loop. */
object MediaSourceAccess {
    fun verify(resolver: ContentResolver, uri: Uri): MediaSourceStatus {
        if (uri.scheme == "null") return MediaSourceStatus.Available
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            return if (uri.path?.let(::File)?.isFile == true) MediaSourceStatus.Available else MediaSourceStatus.Missing
        }
        return try {
            resolver.openFileDescriptor(uri, "r")?.use { MediaSourceStatus.Available }
                ?: MediaSourceStatus.Missing
        } catch (_: SecurityException) {
            MediaSourceStatus.PermissionLost
        } catch (_: FileNotFoundException) {
            MediaSourceStatus.Missing
        } catch (failure: Throwable) {
            MediaSourceStatus.ReadError(failure)
        }
    }

    /** Persist only grants explicitly offered by an ACTION_OPEN_DOCUMENT result. */
    fun persistReadPermission(resolver: ContentResolver, uri: Uri, resultFlags: Int): Boolean {
        val read = resultFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        val persistable = resultFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
        if (!read || !persistable || uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return true
    }
}
