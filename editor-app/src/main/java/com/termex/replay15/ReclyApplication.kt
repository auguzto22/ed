package com.termex.replay15

import android.app.Application
import com.termex.replay15.account.AccountManager
import com.termex.replay15.editor.captions.ReclyBackendAuth

class ReclyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // O backend de legendas só aceita quem tem conta: cada chamada leva o token do Supabase.
        ReclyBackendAuth.accessTokenProvider = { AccountManager.freshAccessToken(this) }
    }
}
