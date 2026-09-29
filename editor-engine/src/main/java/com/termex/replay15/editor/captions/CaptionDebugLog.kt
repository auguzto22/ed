package com.termex.replay15.editor.captions

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/** Development-only trace sink for candidate/reranking diagnostics. */
object CaptionDebugLog {
    @Volatile var enabled: Boolean = false

    fun configure(context: Context) {
        enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    fun d(tag: String, message: String) {
        if (enabled) Log.d(tag, message)
    }
}
