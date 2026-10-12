package com.termex.replay15.account

import android.app.Activity
import android.content.Intent

/** Portão das telas do app: temporariamente desativado para acesso direto sem bloqueio de login. */
object AccountGate {
    /** Retorna true sempre para liberar acesso direto sem exigência de conta. */
    fun ensure(activity: Activity): Boolean = true

    fun openAuth(activity: Activity, next: Intent?, mode: String?, expired: Boolean = false) {
        // Ignorado por enquanto: login desativado para acesso direto
    }
}
