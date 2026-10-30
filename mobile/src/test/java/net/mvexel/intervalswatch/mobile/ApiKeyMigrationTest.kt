package net.mvexel.intervalswatch.mobile

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiKeyMigrationTest {
    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = "enc:" + plain.reversed()
        override fun decrypt(encoded: String) = encoded.removePrefix("enc:").reversed()
    }
    private val plainKey = stringPreferencesKey("api_key")
    private val encKey = stringPreferencesKey("api_key_enc")

    @Test fun migratesPlainKeyAndRemovesIt() {
        val p = mutablePreferencesOf(plainKey to "secret")
        migratePlainApiKey(p, cipher)
        assertNull(p[plainKey])
        assertEquals("enc:terces", p[encKey])
        assertEquals("secret", readApiKey(p, cipher))
    }

    @Test fun migrationIsIdempotentAndKeepsExistingEncryptedKey() {
        val p = mutablePreferencesOf(encKey to "enc:wen", plainKey to "old")
        migratePlainApiKey(p, cipher)
        migratePlainApiKey(p, cipher)
        assertNull(p[plainKey])
        assertEquals("new", readApiKey(p, cipher))
    }

    @Test fun noKeyReadsEmpty() {
        assertEquals("", readApiKey(mutablePreferencesOf(), cipher))
    }

    private val tokenKey = stringPreferencesKey("access_token_enc")
    private val authType = stringPreferencesKey("auth_type")

    @Test fun existingPastedKeyKeepsWorkingAsApiKeyAuth() {
        val p = mutablePreferencesOf(encKey to "enc:terces", stringPreferencesKey("athlete_id") to "i42")
        val s = readSettings(p, cipher)
        assertEquals(AuthType.API_KEY, s.authType)
        assertEquals("secret", s.token)
        assertEquals("i42", s.athleteId)
        assertEquals(okhttp3.Credentials.basic("API_KEY", "secret"), s.authHeader)
    }

    @Test fun plainKeyMigratesThenReadsAsApiKey() {
        val p = mutablePreferencesOf(plainKey to "secret")
        migratePlainApiKey(p, cipher)
        assertEquals("secret", readSettings(p, cipher).token)
    }

    @Test fun savingOAuthLoginStoresTokenAndDeletesApiKey() {
        val p = mutablePreferencesOf(encKey to "enc:terces")
        writeSettings(p, cipher, Settings(athleteId = "i42", token = "tok", authType = AuthType.OAUTH, athleteName = "Ann"))
        assertNull(p[encKey])
        assertEquals("enc:kot", p[tokenKey])
        assertEquals("oauth", p[authType])
        val s = readSettings(p, cipher)
        assertEquals(AuthType.OAUTH, s.authType)
        assertEquals("tok", s.token)
        assertEquals("Ann", s.athleteName)
        assertEquals("Bearer tok", s.authHeader)
    }

    @Test fun clearCredentialsRemovesEverythingSecret() {
        val p = mutablePreferencesOf(tokenKey to "enc:kot", encKey to "enc:x", authType to "oauth")
        clearCredentials(p)
        assertEquals(Settings(), readSettings(p, cipher))
        assertNull(p[tokenKey])
        assertNull(p[encKey])
    }
}
