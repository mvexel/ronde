package net.mvexel.intervalswatch.mobile

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginFlowTest {
    private val p = PendingLogin("v", "s", 1_000)

    @Test fun pendingRoundTripsThroughPrefs() {
        val prefs = mutablePreferencesOf()
        writePending(prefs, p)
        assertEquals(p, readPending(prefs, 2_000))
    }

    @Test fun pendingExpiresAndClears() {
        val prefs = mutablePreferencesOf()
        writePending(prefs, p)
        assertNull(readPending(prefs, 1_000 + PENDING_LOGIN_TTL_MS + 1))
        clearPending(prefs)
        assertNull(readPending(prefs, 2_000))
    }

    @Test fun noPendingReadsNull() = assertNull(readPending(mutablePreferencesOf(), 0))

    @Test fun recognisesOnlyTheRedirectUri() {
        assertTrue(isLoginRedirect("net.mvexel.intervalswatch:/oauth?code=c&state=s"))
        assertFalse(isLoginRedirect("net.mvexel.intervalswatch:/other?code=c"))
        assertFalse(isLoginRedirect(null))
    }
}
