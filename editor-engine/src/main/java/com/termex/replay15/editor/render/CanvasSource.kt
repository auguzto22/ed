package com.termex.replay15.editor.render

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.File

/** A tiny immutable image gives Media3 an opaque canvas without another video decoder. */
object CanvasSource {
    @Synchronized fun uri(context: Context, color: Int): Uri {
        val directory = File(context.cacheDir, "editor-canvas").apply { check(isDirectory || mkdirs()) }
        val target = File(directory, "${Integer.toHexString(color)}.png")
        if (!target.isFile || target.length() == 0L) {
            val temporary = File.createTempFile("canvas-", ".tmp", directory)
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(color)
                temporary.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                check(temporary.renameTo(target))
            } finally { bitmap.recycle(); temporary.delete() }
        }
        return Uri.fromFile(target)
    }
}
