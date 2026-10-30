package net.mvexel.intervalswatch.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsSyncTest {
    private val old = ScreenSettings(updatedAt = 100)
    private val newer = ScreenSettings(buzzStrength = BuzzStrength.STRONG, updatedAt = 200)

    @Test fun newerRemoteWins() = assertEquals(newer, SettingsSync.merge(old, newer))
    @Test fun olderRemoteLoses() = assertEquals(newer, SettingsSync.merge(newer, old))
    @Test fun tieKeepsLocal() {
        val other = old.copy(buzzStrength = BuzzStrength.STRONG)
        assertEquals(old, SettingsSync.merge(old, other))
    }
    @Test fun jsonRoundTrips() = assertEquals(newer, SettingsSync.decodeOrNull(newer.toJson()))
    @Test fun garbageIsIgnored() {
        assertNull(SettingsSync.decodeOrNull("{nope"))
        assertNull(SettingsSync.decodeOrNull(null))
    }
    @Test fun noCredentialFieldsInJson() {
        val json = newer.toJson().lowercase()
        assert("key" !in json.replace("keepscreenon", "") && "token" !in json)
    }
}
