package com.termex.replay15.account

import android.app.Activity
import android.content.Intent

/** Portão das telas do app: sem conta, manda para o cadastro e volta para onde a pessoa ia. */
object AccountGate {
    /** Retorna true se pode seguir. Se não, já abriu a tela de conta e fechou a atual. */
    fun ensure(activity: Activity): Boolean {
        if (AccountManager.isSignedIn(activity)) {
            AccountManager.refreshIfNeeded(activity) { openAuth(activity, null, AuthActivity.MODE_SIGN_IN, expired = true) }
            return true
        }
        openAuth(activity, Intent(activity.intent).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null)
        return false
    }

    fun openAuth(activity: Activity, next: Intent?, mode: String?, expired: Boolean = false) {
        val intent = Intent(activity, AuthActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (next != null) intent.putExtra(AuthActivity.EXTRA_NEXT, next)
        if (mode != null) intent.putExtra(AuthActivity.EXTRA_MODE, mode)
        if (expired) intent.putExtra(AuthActivity.EXTRA_EXPIRED, true)
        activity.startActivity(intent)
        activity.finish()
    }
}
