package com.termex.replay15.editor.core

import android.content.Context
import android.content.pm.ApplicationInfo

fun Context.isEditorDebuggable(): Boolean =
    applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
