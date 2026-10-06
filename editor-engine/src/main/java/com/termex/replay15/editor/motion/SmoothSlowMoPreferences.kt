package com.termex.replay15.editor.motion

import android.content.Context

/**
 * Remembers the user's interpolation choice.
 *
 * It lives outside the project on purpose: a device that cannot afford the render should not
 * be asked again for every project it opens, and turning it off has to mean "stop doing this
 * work" rather than "edit every project".
 */
object SmoothSlowMoPreferences {
    private const val FILE = "editor-smooth-slowmo"
    private const val KEY_PROFILE = "profile"

    fun profile(context: Context): SmoothSlowMoProfile {
        val stored = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_PROFILE, null)
        return SmoothSlowMoProfile.entries.firstOrNull { it.name == stored } ?: SmoothSlowMoProfile.OFF
    }

    fun setProfile(context: Context, profile: SmoothSlowMoProfile) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILE, profile.name)
            .apply()
    }
}
