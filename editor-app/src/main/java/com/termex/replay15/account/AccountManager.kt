package com.termex.replay15.account

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.recly.editor.BuildConfig
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Ponto único de acesso à conta. Rede e Keystore rodam numa thread própria; os callbacks voltam
 * sempre na thread principal. A sessão salva vale mesmo offline: quem já entrou nunca fica preso
 * fora do editor por falta de internet; só um refresh token recusado pelo servidor desloga.
 */
object AccountManager {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val client = SupabaseAuthClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
    @Volatile private var cached: AuthSession? = null
    @Volatile private var loaded = false
    @Volatile private var refreshing = false
    private val refreshLock = Any()

    val isConfigured: Boolean get() = client.isConfigured
    val googleClientId: String get() = BuildConfig.GOOGLE_WEB_CLIENT_ID
    val isGoogleAvailable: Boolean get() = isConfigured && googleClientId.isNotBlank()

    /** Sessão atual (lê o disco uma vez). Seguro na thread principal: é um SharedPreferences pequeno. */
    fun session(context: Context): AuthSession? {
        if (!loaded) synchronized(this) {
            if (!loaded) { cached = SessionStore(context).load(); loaded = true }
        }
        return cached
    }

    fun isSignedIn(context: Context): Boolean = session(context) != null || isGuest(context) || devBypass(context) || BuildConfig.SKIP_LOGIN

    /** Tem conta de verdade (sessão do Supabase), e não só modo visitante. */
    fun hasAccount(context: Context): Boolean = session(context) != null

    /**
     * Modo visitante ("Entrar sem conta"): usa o editor sem cadastro. Recursos que gastam créditos
     * no servidor (legendas automáticas pelo backend) continuam pedindo conta, porque não mandam
     * token sem sessão. Entrar ou criar conta depois desliga o modo visitante.
     */
    fun isGuest(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(GUEST_PREFS, Context.MODE_PRIVATE).getBoolean(GUEST, false)

    fun continueAsGuest(context: Context) {
        // commit (síncrono): a próxima tela já checa isSignedIn.
        context.applicationContext.getSharedPreferences(GUEST_PREFS, Context.MODE_PRIVATE).edit().putBoolean(GUEST, true).commit()
    }

    private fun leaveGuest(context: Context) {
        context.applicationContext.getSharedPreferences(GUEST_PREFS, Context.MODE_PRIVATE).edit().remove(GUEST).commit()
    }

    /**
     * Só em builds de desenvolvimento sem Supabase configurado: permite usar o editor sem conta,
     * para o projeto continuar rodando antes de o backend existir. Release nunca usa isso.
     */
    fun devBypass(context: Context): Boolean = BuildConfig.DEBUG && !isConfigured &&
        context.applicationContext.getSharedPreferences(DEV_PREFS, Context.MODE_PRIVATE).getBoolean(DEV_SKIP, false)

    fun enableDevBypass(context: Context) {
        check(BuildConfig.DEBUG && !isConfigured)
        context.applicationContext.getSharedPreferences(DEV_PREFS, Context.MODE_PRIVATE).edit().putBoolean(DEV_SKIP, true).apply()
    }

    fun signUp(context: Context, name: String, email: String, password: String, done: (Result<SignUpResult>) -> Unit) =
        background(done) {
            val result = client.signUp(AuthValidation.normalizeEmail(email), password, name.trim())
            if (result is SignUpResult.SignedIn) store(context, result.session)
            result
        }

    fun signIn(context: Context, email: String, password: String, done: (Result<AuthSession>) -> Unit) =
        background(done) { client.signInWithPassword(AuthValidation.normalizeEmail(email), password).also { store(context, it) } }

    fun signInWithGoogle(context: Context, idToken: String, rawNonce: String, done: (Result<AuthSession>) -> Unit) =
        background(done) { client.signInWithGoogleIdToken(idToken, rawNonce).also { store(context, it) } }

    fun sendPasswordReset(email: String, done: (Result<Unit>) -> Unit) =
        background(done) { client.sendPasswordReset(AuthValidation.normalizeEmail(email)) }

    fun resendConfirmation(email: String, done: (Result<Unit>) -> Unit) =
        background(done) { client.resendConfirmation(AuthValidation.normalizeEmail(email)) }

    /**
     * Renova o access token quando faltam menos de 5 minutos. [onSignedOut] só é chamado quando o
     * servidor recusa o refresh token (conta excluída, senha trocada, sessão revogada); sem internet
     * a sessão é mantida.
     */
    fun refreshIfNeeded(context: Context, onSignedOut: () -> Unit = {}) {
        val current = session(context) ?: return
        if (!current.expiresWithin(300) || refreshing) return
        refreshing = true
        worker.execute {
            val result = synchronized(refreshLock) {
                // Outra thread pode ter renovado enquanto esta esperava: usa a sessão mais nova.
                val latest = session(context) ?: current
                if (!latest.expiresWithin(300)) Result.success(latest)
                else runCatching { client.refresh(latest.refreshToken) }.onSuccess { store(context, it) }
            }
            val expired = (result.exceptionOrNull() as? AuthFailure)?.kind == AuthFailureKind.SESSION_EXPIRED
            if (expired) clearLocal(context)
            main.post { refreshing = false; if (expired) onSignedOut() }
        }
    }

    /** Access token válido para chamar outros serviços (renova antes, se preciso). Chamar fora da thread principal. */
    fun freshAccessToken(context: Context): String? = synchronized(refreshLock) {
        // Uma renovação por vez: o Supabase gira o refresh token, e duas renovações paralelas
        // com o mesmo token (legendas em paralelo + refreshIfNeeded) podem derrubar a sessão.
        val current = session(context) ?: return null
        if (!current.expiresWithin(60)) return current.accessToken
        runCatching { client.refresh(current.refreshToken).also { store(context, it) }.accessToken }.getOrNull()
    }

    fun signOut(context: Context, done: () -> Unit) {
        val current = session(context)
        clearLocal(context)
        leaveGuest(context)
        worker.execute {
            current?.let { client.signOut(it.accessToken) }
            main.post(done)
        }
    }

    /**
     * Exclui a conta chamando a função `delete_user` do banco (ver docs/accounts.md). Exigido pela
     * política do Google Play para apps que permitem criar conta.
     */
    fun deleteAccount(context: Context, done: (Result<Unit>) -> Unit) = background(done) {
        val token = freshAccessToken(context) ?: throw AuthFailure(AuthFailureKind.SESSION_EXPIRED, "Entre de novo para excluir a conta.")
        val connection = try {
            (URL("${BuildConfig.SUPABASE_URL}/rest/v1/rpc/delete_user").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 20_000; doOutput = true
                setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
            }
        } catch (error: IOException) { throw AuthErrors.network(error) }
        try {
            connection.outputStream.use { it.write("{}".toByteArray()) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val text = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val message = runCatching { JSONObject(text).optString("message") }.getOrDefault("")
                throw AuthFailure(AuthFailureKind.SERVER,
                    if (status == 404) "A exclusão de conta ainda não foi ativada no servidor." else "Não foi possível excluir a conta. $message".trim())
            }
        } catch (error: IOException) {
            throw AuthErrors.network(error)
        } finally { connection.disconnect() }
        clearLocal(context)
    }

    private fun store(context: Context, session: AuthSession) {
        SessionStore(context).save(session)
        leaveGuest(context)
        cached = session; loaded = true
    }

    private fun clearLocal(context: Context) {
        SessionStore(context).clear()
        cached = null; loaded = true
    }

    private fun <T> background(done: (Result<T>) -> Unit, block: () -> T) {
        worker.execute {
            val result = runCatching(block).recoverCatching { error ->
                throw error as? AuthFailure ?: if (error is IOException) AuthErrors.network(error)
                    else AuthFailure(AuthFailureKind.SERVER, "Algo deu errado. Tente de novo.", error)
            }
            main.post { done(result) }
        }
    }

    private const val GUEST_PREFS = "recly-account-guest"
    private const val GUEST = "guest"
    private const val DEV_PREFS = "recly-account-dev"
    private const val DEV_SKIP = "skip"
}
