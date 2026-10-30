package net.mvexel.intervalswatch.mobile

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OAuthTest {
    @Test fun challengeMatchesRfc7636Vector() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            OAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test fun verifierLengthAndAlphabet() {
        repeat(20) {
            val v = OAuth.newVerifier()
            assertTrue(v.length in 43..128)
            assertTrue(v.all { it.isLetterOrDigit() && it.code < 128 || it in "-._~" })
        }
        assertTrue(OAuth.newVerifier() != OAuth.newVerifier())
    }

    @Test fun authorizeUrlHasAllParameters() {
        val u = OAuth.authorizeUrl("cid", "st", "chal")
        assertEquals("/oauth/authorize", u.encodedPath)
        assertEquals("cid", u.queryParameter("client_id"))
        assertEquals("net.mvexel.intervalswatch:/oauth", u.queryParameter("redirect_uri"))
        assertEquals("CALENDAR:READ,ACTIVITY:WRITE", u.queryParameter("scope"))
        assertEquals("st", u.queryParameter("state"))
        assertEquals("chal", u.queryParameter("code_challenge"))
        assertEquals("S256", u.queryParameter("code_challenge_method"))
    }

    private val base = "net.mvexel.intervalswatch:/oauth"

    @Test fun redirectWithCode() {
        assertEquals(RedirectResult.Code("abc"), OAuth.parseRedirect("$base?code=abc&state=s1", "s1"))
    }

    @Test fun redirectAccessDenied() {
        assertEquals(RedirectResult.Failure(OAuthError.AccessDenied), OAuth.parseRedirect("$base?error=access_denied&state=s1", "s1"))
    }

    @Test fun redirectStateMismatchWinsOverCode() {
        assertEquals(RedirectResult.Failure(OAuthError.StateMismatch), OAuth.parseRedirect("$base?code=abc&state=evil", "s1"))
        assertEquals(RedirectResult.Failure(OAuthError.StateMismatch), OAuth.parseRedirect("$base?code=abc", "s1"))
    }

    @Test fun redirectMissingCode() {
        assertEquals(RedirectResult.Failure(OAuthError.MissingCode), OAuth.parseRedirect("$base?state=s1", "s1"))
        assertEquals(RedirectResult.Failure(OAuthError.MissingCode), OAuth.parseRedirect("$base?code=&state=s1", "s1"))
        assertEquals(RedirectResult.Failure(OAuthError.StateMismatch), OAuth.parseRedirect(base, "s1")) // no query, no state
    }

    private val tokenJson = """{"token_type":"Bearer","access_token":"tok-secret","scope":"CALENDAR:READ,ACTIVITY:WRITE","athlete":{"id":"i12345","name":"Test Runner"}}"""

    @Test fun exchangePostsPublicClientFormAndParsesToken() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(tokenJson))
            val t = runBlocking { OAuth.exchange("the-code", "the-verifier", "cid", server.url("/").toString().trimEnd('/')) }
            val req = server.takeRequest()
            assertEquals("POST", req.method)
            assertEquals("/api/oauth/token", req.path)
            val body = req.body.readUtf8()
            assertTrue(body, "client_id=cid" in body && "code=the-code" in body && "code_verifier=the-verifier" in body)
            assertFalse(body.contains("secret"))
            assertNull(req.getHeader("Authorization"))
            assertEquals("tok-secret", t.accessToken)
            assertEquals("CALENDAR:READ,ACTIVITY:WRITE", t.scope)
            assertEquals("i12345", t.athleteId)
            assertEquals("Test Runner", t.athleteName)
            assertFalse(t.toString().contains("tok-secret"))
        }
    }

    @Test fun exchangeFailureDoesNotLeakSecrets() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
            val e = runCatching { runBlocking { OAuth.exchange("the-code", "the-verifier", "cid", server.url("/").toString().trimEnd('/')) } }.exceptionOrNull()
            assertTrue(e is IOException)
            assertTrue(e!!.message!!.contains("400"))
            assertFalse(e.message!!.contains("the-code") || e.message!!.contains("the-verifier"))
        }
    }

    @Test fun tokenWithoutAthleteIsRejected() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"access_token":"x"}"""))
            val e = runCatching { runBlocking { OAuth.exchange("c", "v", "cid", server.url("/").toString().trimEnd('/')) } }.exceptionOrNull()
            assertTrue(e is IOException)
        }
    }
}
