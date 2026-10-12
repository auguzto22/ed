package com.termex.replay15.account

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

/**
 * "Continuar com Google" pelo Credential Manager. Devolve o ID token do Google e o nonce cru; o
 * Google recebe só o hash SHA-256 do nonce e o Supabase confere os dois.
 */
object GoogleSignIn {
    data class Token(val idToken: String, val rawNonce: String)

    suspend fun requestToken(activity: Activity, webClientId: String): Token {
        val rawNonce = Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) },
            Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
        val hashedNonce = MessageDigest.getInstance("SHA-256").digest(rawNonce.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClientId)
            .setNonce(hashedNonce)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = try {
            CredentialManager.create(activity).getCredential(activity, request).credential
        } catch (cancelled: GetCredentialCancellationException) {
            throw AuthFailure(AuthFailureKind.CANCELLED, "Login com Google cancelado.", cancelled)
        } catch (none: NoCredentialException) {
            throw AuthFailure(AuthFailureKind.INVALID_INPUT, "Nenhuma conta Google neste aparelho. Adicione uma nas configurações ou use e-mail.", none)
        } catch (error: GetCredentialException) {
            throw AuthFailure(AuthFailureKind.SERVER, "O Google não respondeu. Tente de novo ou use e-mail.", error)
        }
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val idToken = runCatching { GoogleIdTokenCredential.createFrom(credential.data).idToken }
                .getOrElse { throw AuthFailure(AuthFailureKind.SERVER, "Não foi possível ler a conta Google.", it) }
            return Token(idToken, rawNonce)
        }
        throw AuthFailure(AuthFailureKind.SERVER, "Resposta inesperada do Google.")
    }
}
