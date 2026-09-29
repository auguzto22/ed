package com.recly.core.media

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

object ReclyMediaContract {
    const val ACTION_EDIT_VIDEO = "com.recly.action.EDIT_VIDEO"
    const val EXTRA_VIDEO_URI = "uri"
    const val EDITOR_PACKAGE_NAME = "com.recly.editor"
    const val EDITOR_ACTIVITY_NAME = "com.termex.replay15.editor.ui.EditorActivity"

    /**
     * Creates an Intent to open the specified video content:// URI in the Recly Editor.
     * Ensures read permissions are granted and explicit component or action is configured.
     */
    fun createEditVideoIntent(context: Context, videoUri: Uri): Intent {
        return Intent(ACTION_EDIT_VIDEO).apply {
            data = videoUri
            putExtra(EXTRA_VIDEO_URI, videoUri.toString())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Try explicit package first if available
            setPackage(EDITOR_PACKAGE_NAME)
        }
    }

    /**
     * Checks if Recly Editor (or any handler for ACTION_EDIT_VIDEO) is installed.
     */
    fun isEditorInstalled(context: Context): Boolean {
        val pm = context.packageManager
        // 1. Check explicit package
        val packageInstalled = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(EDITOR_PACKAGE_NAME, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(EDITOR_PACKAGE_NAME, 0)
            }
        }.isSuccess

        if (packageInstalled) return true

        // 2. Check intent resolution
        val probeIntent = Intent(ACTION_EDIT_VIDEO).apply {
            type = "video/*"
        }
        val resolveInfo = pm.queryIntentActivities(probeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        if (resolveInfo.isNotEmpty()) return true
        return false
    }

    const val RECORDER_PACKAGE_NAME = "com.recly.recorder"

    /**
     * Checks if Recly Recorder is installed.
     */
    fun isRecorderInstalled(context: Context): Boolean {
        val pm = context.packageManager
        return runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(RECORDER_PACKAGE_NAME, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(RECORDER_PACKAGE_NAME, 0)
            }
        }.isSuccess
    }
}
