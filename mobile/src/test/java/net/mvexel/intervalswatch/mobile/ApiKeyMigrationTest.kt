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
}
