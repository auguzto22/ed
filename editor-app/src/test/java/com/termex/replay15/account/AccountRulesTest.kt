package com.termex.replay15.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountRulesTest {
    @Test fun acceptsCommonEmailsAndNormalizesThem() {
        assertNull(AuthValidation.email("  Mauro.Silva+recly@Gmail.com "))
        assertEquals("mauro.silva+recly@gmail.com", AuthValidation.normalizeEmail("  Mauro.Silva+recly@Gmail.com "))
        assertNull(AuthValidation.email("a@b.co"))
    }

    @Test fun rejectsBrokenEmails() {
        listOf("", "mauro", "mauro@", "@gmail.com", "mauro@gmail", "mau ro@gmail.com").forEach {
            assertTrue("deveria recusar '$it'", AuthValidation.email(it) != null)
        }
    }

    @Test fun signUpPasswordNeedsLengthLettersAndDigits() {
        assertTrue(AuthValidation.password("abc12", forSignUp = true) != null)
        assertTrue(AuthValidation.password("abcdefgh", forSignUp = true) != null)
        assertTrue(AuthValidation.password("12345678", forSignUp = true) != null)
        assertNull(AuthValidation.password("recly2026", forSignUp = true))
        // No login não se aplica regra de força: senhas antigas continuam entrando.
        assertNull(AuthValidation.password("123", forSignUp = false))
        assertTrue(AuthValidation.password("", forSignUp = false) != null)
    }

    @Test fun nameRules() {
        assertTrue(AuthValidation.name(" ") != null)
        assertTrue(AuthValidation.name("M") != null)
        assertNull(AuthValidation.name("Mauro"))
    }

    @Test fun mapsNewAndLegacySupabaseErrors() {
        assertEquals(AuthFailureKind.INVALID_CREDENTIALS, AuthErrors.fromResponse(400, "invalid_credentials", "Invalid login credentials").kind)
        assertEquals(AuthFailureKind.INVALID_CREDENTIALS, AuthErrors.fromResponse(400, "invalid_grant", "Invalid login credentials").kind)
        assertEquals(AuthFailureKind.EMAIL_NOT_CONFIRMED, AuthErrors.fromResponse(400, "email_not_confirmed", "Email not confirmed").kind)
        assertEquals(AuthFailureKind.ALREADY_REGISTERED, AuthErrors.fromResponse(422, "user_already_exists", "User already registered").kind)
        assertEquals(AuthFailureKind.WEAK_PASSWORD, AuthErrors.fromResponse(422, "weak_password", "Password should be at least 8 characters").kind)
        assertEquals(AuthFailureKind.RATE_LIMITED, AuthErrors.fromResponse(429, "over_email_send_rate_limit", "").kind)
        assertEquals(AuthFailureKind.SESSION_EXPIRED, AuthErrors.fromResponse(400, "refresh_token_not_found", "Invalid Refresh Token").kind)
        assertEquals(AuthFailureKind.SESSION_EXPIRED, AuthErrors.fromResponse(400, "invalid_grant", "Invalid Refresh Token: Refresh Token Not Found").kind)
        assertEquals(AuthFailureKind.SERVER, AuthErrors.fromResponse(503, "", "").kind)
    }

    @Test fun credentialErrorsNeverLookLikeExpiredSessions() {
        // Senha errada não pode deslogar ninguém nem ser confundida com sessão expirada.
        assertFalse(AuthErrors.fromResponse(400, "invalid_credentials", "").kind == AuthFailureKind.SESSION_EXPIRED)
    }

    @Test fun sessionExpiryAndDisplayName() {
        val session = AuthSession("a", "r", expiresAtEpochSec = 1_000, userId = "u", email = "mauro@gmail.com", name = "")
        assertTrue(session.expiresWithin(300, nowEpochSec = 800))
        assertFalse(session.expiresWithin(60, nowEpochSec = 800))
        assertEquals("mauro", session.displayName)
        assertEquals("Mauro", session.copy(name = "  Mauro Augusto ").displayName)
    }
}
