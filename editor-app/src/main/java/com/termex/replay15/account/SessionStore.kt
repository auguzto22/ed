package com.termex.replay15.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Guarda a sessão criptografada com AES-GCM; a chave fica no Android Keystore e nunca sai do aparelho.
 * Se a chave sumir (backup restaurado em outro aparelho, limpeza de credenciais), a sessão antiga
 * simplesmente não abre e a pessoa entra de novo — nunca trava o app.
 */
class SessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun load(): AuthSession? {
        val stored = prefs.getString(KEY_SESSION, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
            val json = JSONObject(String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8))
            AuthSession(
                accessToken = json.getString("access"),
                refreshToken = json.getString("refresh"),
                expiresAtEpochSec = json.getLong("expires"),
                userId = json.optString("user"),
                email = json.optString("email"),
                name = json.optString("name"),
            )
        }.getOrElse {
            clear()
            null
        }
    }

    @Synchronized
    fun save(session: AuthSession) {
        val json = JSONObject()
            .put("access", session.accessToken).put("refresh", session.refreshToken)
            .put("expires", session.expiresAtEpochSec).put("user", session.userId)
            .put("email", session.email).put("name", session.name)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + encrypted
        check(cipher.iv.size == IV_SIZE)
        // commit (síncrono): a sessão precisa existir no disco antes de abrir a próxima tela.
        prefs.edit().putString(KEY_SESSION, Base64.encodeToString(payload, Base64.NO_WRAP)).commit()
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_SESSION).commit()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "recly-account"
        const val KEY_SESSION = "session"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "recly-account-session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
