package com.termex.replay15.account

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cliente mínimo da API do Supabase Auth (GoTrue), sem SDK: só HttpURLConnection e org.json.
 * Todas as chamadas são bloqueantes; [AccountManager] as executa fora da thread principal.
 */
class SupabaseAuthClient(private val baseUrl: String, private val anonKey: String) {

    /** HTTPS em produção; HTTP só para o Supabase local (`supabase start`) no PC ou no emulador. */
    val isConfigured: Boolean get() = anonKey.isNotBlank() && (baseUrl.startsWith("https://") ||
        LOCAL_DEV_HOSTS.any { baseUrl.startsWith(it) })

    fun signUp(email: String, password: String, name: String): SignUpResult {
        val body = JSONObject().put("email", email).put("password", password)
            .put("data", JSONObject().put("full_name", name).put("name", name))
        val json = request("POST", "/auth/v1/signup", body)
        // Com "Confirm email" ligado o Supabase devolve só o usuário, sem tokens.
        return if (json.has("access_token")) SignUpResult.SignedIn(session(json))
        else SignUpResult.ConfirmEmail(email)
    }

    fun signInWithPassword(email: String, password: String): AuthSession =
        session(request("POST", "/auth/v1/token?grant_type=password",
            JSONObject().put("email", email).put("password", password)))

    /** [rawNonce] é o nonce original; o Google recebeu o hash SHA-256 dele. */
    fun signInWithGoogleIdToken(idToken: String, rawNonce: String): AuthSession =
        session(request("POST", "/auth/v1/token?grant_type=id_token",
            JSONObject().put("provider", "google").put("id_token", idToken).put("nonce", rawNonce)))

    fun refresh(refreshToken: String): AuthSession =
        session(request("POST", "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", refreshToken)))

    fun sendPasswordReset(email: String) {
        request("POST", "/auth/v1/recover", JSONObject().put("email", email))
    }

    fun resendConfirmation(email: String) {
        request("POST", "/auth/v1/resend", JSONObject().put("type", "signup").put("email", email))
    }

    /** Revoga o refresh token no servidor. Falhas são ignoradas: a sessão local é apagada de qualquer jeito. */
    fun signOut(accessToken: String) {
        runCatching { request("POST", "/auth/v1/logout", JSONObject(), bearer = accessToken) }
    }

    private fun session(json: JSONObject): AuthSession {
        val user = json.optJSONObject("user") ?: JSONObject()
        val meta = user.optJSONObject("user_metadata") ?: JSONObject()
        val now = System.currentTimeMillis() / 1000
        val expiresAt = json.optLong("expires_at", 0L).takeIf { it > 0 } ?: (now + json.optLong("expires_in", 3600L))
        val access = json.text("access_token")
        val refresh = json.text("refresh_token")
        if (access.isBlank() || refresh.isBlank()) throw AuthFailure(AuthFailureKind.SERVER, "Resposta inválida do servidor de contas.")
        return AuthSession(
            accessToken = access,
            refreshToken = refresh,
            expiresAtEpochSec = expiresAt,
            userId = user.text("id"),
            email = user.text("email"),
            name = meta.text("full_name").ifBlank { meta.text("name") },
        )
    }

    private fun request(method: String, path: String, body: JSONObject, bearer: String? = null): JSONObject {
        if (!isConfigured) throw AuthFailure(AuthFailureKind.NOT_CONFIGURED,
            "As contas ainda não foram configuradas neste aplicativo (SUPABASE_URL / SUPABASE_ANON_KEY).")
        val connection = try {
            (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("apikey", anonKey)
                setRequestProperty("Authorization", "Bearer ${bearer ?: anonKey}")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
        } catch (error: IOException) { throw AuthErrors.network(error) }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(64_000) }.orEmpty()
            val json = runCatching { if (text.isBlank()) JSONObject() else JSONObject(text) }.getOrDefault(JSONObject())
            if (status in 200..299) return json
            val code = json.text("error_code").ifBlank { json.text("error") }
            val message = json.text("msg").ifBlank { json.text("error_description") }.ifBlank { json.text("message") }
            throw AuthErrors.fromResponse(status, code, message)
        } catch (failure: AuthFailure) {
            throw failure
        } catch (error: IOException) {
            throw AuthErrors.network(error)
        } finally {
            connection.disconnect()
        }
    }

    /** optString devolve "null" para valores JSON nulos; aqui nulo e ausente viram "". */
    private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)

    private companion object {
        val LOCAL_DEV_HOSTS = listOf("http://127.0.0.1", "http://localhost", "http://10.0.2.2")
    }
}
