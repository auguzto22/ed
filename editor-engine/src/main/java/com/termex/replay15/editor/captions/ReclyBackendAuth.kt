package com.termex.replay15.editor.captions

import java.net.HttpURLConnection

/**
 * Identifica a conta em cada chamada ao backend de legendas. Sem isso, qualquer pessoa que
 * achasse a URL no APK poderia transcrever à vontade e gastar os créditos da Gemini.
 *
 * O módulo do editor não conhece a conta; o app instala o [accessTokenProvider] ao abrir
 * (ReclyApplication). O backend precisa validar o JWT do Supabase e recusar sem ele (401).
 */
object ReclyBackendAuth {
    /** Devolve um access token válido do Supabase, ou null sem sessão. Chamado fora da thread principal. */
    @Volatile var accessTokenProvider: (() -> String?)? = null

    fun authorize(connection: HttpURLConnection) {
        val provider = accessTokenProvider ?: return
        val token = runCatching(provider).getOrNull()?.takeIf { it.isNotBlank() }
            ?: throw CaptionBackendUnauthorized()
        connection.setRequestProperty("Authorization", "Bearer $token")
    }
}

class CaptionBackendUnauthorized : IllegalStateException("Entre na sua conta para usar as legendas automáticas.")
