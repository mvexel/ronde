package net.mvexel.intervalswatch.mobile

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import net.mvexel.intervalswatch.mobile.data.session.SessionState
import net.mvexel.intervalswatch.mobile.feature.login.LoginUiState
import net.mvexel.intervalswatch.mobile.feature.settings.SettingsUiState

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

    @Test fun wrongStateRedirectKeepsPendingLogin() {
        val prefs = mutablePreferencesOf()
        writePending(prefs, p)
        assertNull(takeMatching(prefs, "net.mvexel.intervalswatch:/oauth?code=c&state=forged", 2_000))
        assertEquals(p, readPending(prefs, 2_000))
        assertEquals(p, takeMatching(prefs, "net.mvexel.intervalswatch:/oauth?code=c&state=s", 2_000))
        assertNull(readPending(prefs, 2_000))
    }

    @Test fun settingsAndUiStateToStringHideTheToken() {
        val s = Settings(token = "sekrit-token", authType = AuthType.OAUTH)
        assertFalse(s.toString().contains("sekrit-token"))
        assertFalse(SessionState(settings = s).toString().contains("sekrit-token"))
        assertFalse(SettingsUiState(settings = s).toString().contains("sekrit-token"))
        assertFalse(LoginUiState(settings = s).toString().contains("sekrit-token"))
    }

    @Test fun oauth403CountsAsAuthRejectionButApiKey403DoesNot() {
        assertTrue(isAuthRejection(401, AuthType.API_KEY))
        assertTrue(isAuthRejection(403, AuthType.OAUTH))
        assertFalse(isAuthRejection(403, AuthType.API_KEY))
        assertFalse(isAuthRejection(500, AuthType.OAUTH))
    }
}
