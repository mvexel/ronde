package net.mvexel.intervalswatch.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FriendlyHttpErrorTest {

    @Test
    fun unauthorizedAndForbiddenSayKeyRejected() {
        val expected = "API key rejected, check it in intervals.icu settings"
        assertEquals(expected, friendlyHttpError(401, """{"status":401,"error":"Unauthorized"}"""))
        assertEquals(expected, friendlyHttpError(403, ""))
    }

    @Test
    fun otherErrorsShowCodeAndShortText() {
        assertEquals(
            "intervals.icu HTTP 500: Internal error",
            friendlyHttpError(500, """{"status":500,"error":"Internal error"}"""),
        )
        assertEquals(
            "intervals.icu HTTP 422: bad athlete id",
            friendlyHttpError(422, """{"message":"bad athlete id"}"""),
        )
    }

    @Test
    fun errorsWithoutJsonShowCodeOnly() {
        assertEquals("intervals.icu HTTP 503", friendlyHttpError(503, "<html>Service Unavailable</html>"))
        assertEquals("intervals.icu HTTP 404", friendlyHttpError(404, ""))
    }

    @Test
    fun rawJsonIsNeverShown() {
        val messages = listOf(
            friendlyHttpError(400, """{"status":400,"error":"Bad","extra":{"a":1}}"""),
            friendlyHttpError(502, """{"error":"${"x".repeat(500)}"}"""),
        )
        messages.forEach { assertFalse(it, it.contains("{")) }
        assertFalse(messages[1].length > 200)
    }

    @Test
    fun oauthAuthErrorIsNotAboutApiKeys() {
        val m = friendlyHttpError(401, "", AuthType.OAUTH)
        assertFalse(m, m.contains("API key"))
        assertEquals(m, friendlyHttpError(403, "", AuthType.OAUTH))
    }
}
