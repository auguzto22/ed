package com.termex.replay15.account

/**
 * Sessão do Supabase Auth. [expiresAtEpochSec] vem de `expires_at` (ou agora + `expires_in`).
 * O refresh token é o que mantém a pessoa logada; o access token dura cerca de uma hora.
 */
data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSec: Long,
    val userId: String,
    val email: String,
    val name: String,
) {
    fun expiresWithin(seconds: Long, nowEpochSec: Long = System.currentTimeMillis() / 1000): Boolean =
        expiresAtEpochSec - nowEpochSec <= seconds

    /** Primeiro nome para saudação; cai no começo do e-mail quando não há nome. */
    val displayName: String
        get() = name.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotBlank() }
            ?: email.substringBefore('@')
}

/** Resultado do cadastro: sessão pronta, ou conta criada aguardando confirmação do e-mail. */
sealed class SignUpResult {
    data class SignedIn(val session: AuthSession) : SignUpResult()
    data class ConfirmEmail(val email: String) : SignUpResult()
}

enum class AuthFailureKind { INVALID_INPUT, INVALID_CREDENTIALS, EMAIL_NOT_CONFIRMED, ALREADY_REGISTERED,
    WEAK_PASSWORD, RATE_LIMITED, NETWORK, SESSION_EXPIRED, CANCELLED, NOT_CONFIGURED, SERVER }

/** Erro já traduzido para mostrar na tela. */
class AuthFailure(val kind: AuthFailureKind, override val message: String, cause: Throwable? = null) : Exception(message, cause)

object AuthValidation {
    const val MIN_PASSWORD = 8
    private val EMAIL = Regex("^[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    fun normalizeEmail(raw: String): String = raw.trim().lowercase()

    /** Mensagem do primeiro problema encontrado, ou null quando está tudo certo. */
    fun email(raw: String): String? {
        val email = normalizeEmail(raw)
        return when {
            email.isEmpty() -> "Digite seu e-mail."
            email.length > 254 || !EMAIL.matches(email) -> "Esse e-mail não parece válido."
            else -> null
        }
    }

    fun password(raw: String, forSignUp: Boolean): String? = when {
        raw.isEmpty() -> "Digite sua senha."
        !forSignUp -> null
        raw.length < MIN_PASSWORD -> "Use pelo menos $MIN_PASSWORD caracteres."
        raw.length > 72 -> "Use no máximo 72 caracteres."
        raw.none { it.isLetter() } || raw.none { it.isDigit() } -> "Misture letras e números."
        else -> null
    }

    fun name(raw: String): String? {
        val name = raw.trim()
        return when {
            name.isEmpty() -> "Como podemos te chamar?"
            name.length < 2 -> "Nome muito curto."
            name.length > 60 -> "Use no máximo 60 caracteres."
            else -> null
        }
    }
}

/**
 * Traduz as respostas de erro do Supabase Auth (GoTrue). Versões novas mandam `error_code` + `msg`;
 * as antigas mandam `error` + `error_description`. As duas são tratadas.
 */
object AuthErrors {
    fun fromResponse(status: Int, errorCode: String?, message: String?): AuthFailure {
        val code = errorCode.orEmpty().lowercase()
        val text = message.orEmpty().lowercase()
        return when {
            code == "invalid_credentials" || text.contains("invalid login credentials") ||
                (code == "invalid_grant" && text.contains("credentials")) ->
                AuthFailure(AuthFailureKind.INVALID_CREDENTIALS, "E-mail ou senha incorretos.")
            code == "email_not_confirmed" || text.contains("email not confirmed") ->
                AuthFailure(AuthFailureKind.EMAIL_NOT_CONFIRMED, "Confirme seu e-mail pelo link que enviamos antes de entrar.")
            code == "user_already_exists" || code == "email_exists" || text.contains("already registered") ->
                AuthFailure(AuthFailureKind.ALREADY_REGISTERED, "Já existe uma conta com esse e-mail. Entre com ela.")
            code == "weak_password" || text.contains("password should") ->
                AuthFailure(AuthFailureKind.WEAK_PASSWORD, "Senha fraca. Use pelo menos 8 caracteres com letras e números.")
            status == 429 || code.startsWith("over_") || text.contains("rate limit") ->
                AuthFailure(AuthFailureKind.RATE_LIMITED, "Muitas tentativas. Espere um minuto e tente de novo.")
            code == "refresh_token_not_found" || code == "session_not_found" || code == "session_expired" ||
                (code == "invalid_grant" && text.contains("refresh")) || text.contains("refresh token") ->
                AuthFailure(AuthFailureKind.SESSION_EXPIRED, "Sua sessão expirou. Entre de novo.")
            code == "email_address_invalid" || code == "validation_failed" ->
                AuthFailure(AuthFailureKind.INVALID_INPUT, "Esse e-mail não pode ser usado.")
            code == "signup_disabled" ->
                AuthFailure(AuthFailureKind.SERVER, "Novos cadastros estão desativados no momento.")
            status in 500..599 ->
                AuthFailure(AuthFailureKind.SERVER, "O servidor de contas está instável. Tente de novo em instantes.")
            else -> AuthFailure(AuthFailureKind.SERVER, "Não foi possível concluir (${status}). Tente de novo.")
        }
    }

    fun network(cause: Throwable) =
        AuthFailure(AuthFailureKind.NETWORK, "Sem conexão com a internet. Verifique e tente de novo.", cause)
}
