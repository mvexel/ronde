package net.mvexel.intervalswatch.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/** OAuth login with intervals.icu: public client + PKCE (S256), no client secret. */
object OAuth {
    const val REDIRECT_URI = "net.mvexel.intervalswatch:/oauth"
    const val SCOPES = "CALENDAR:READ,ACTIVITY:WRITE"
    private const val VERIFIER_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private val random = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    /** 64 random characters from the RFC 7636 unreserved set. */
    fun newVerifier(): String = randomString(64)

    fun newState(): String = randomString(32)

    private fun randomString(n: Int) = buildString { repeat(n) { append(VERIFIER_CHARS[random.nextInt(VERIFIER_CHARS.length)]) } }

    /** BASE64URL(SHA256(verifier)) without padding. */
    fun challenge(verifier: String): String =
        b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun authorizeUrl(
        clientId: String,
        state: String,
        challenge: String,
        redirectUri: String = REDIRECT_URI,
        baseUrl: String = "https://intervals.icu",
    ): HttpUrl = "$baseUrl/oauth/authorize".toHttpUrl().newBuilder()
        .addQueryParameter("client_id", clientId)
        .addQueryParameter("redirect_uri", redirectUri)
        .addQueryParameter("scope", SCOPES)
        .addQueryParameter("state", state)
        .addQueryParameter("code_challenge", challenge)
        .addQueryParameter("code_challenge_method", "S256")
        .build()

    /**
     * Reads the redirect the browser handed back. [redirect] is the full URI string; [expectedState] the
     * value sent in the authorize request. The state is checked before anything else is trusted.
     */
    fun parseRedirect(redirect: String, expectedState: String): RedirectResult {
        // HttpUrl needs http(s); swap the scheme just for query parsing.
        val query = runCatching { "http://redirect/?" + redirect.substringAfter('?', "").substringBefore('#') }
            .getOrNull()?.toHttpUrlOrNull() ?: return RedirectResult.Failure(OAuthError.MissingCode)
        if (query.queryParameter("state") != expectedState) return RedirectResult.Failure(OAuthError.StateMismatch)
        if (query.queryParameter("error") == "access_denied") return RedirectResult.Failure(OAuthError.AccessDenied)
        val code = query.queryParameter("code")
        return if (code.isNullOrEmpty()) RedirectResult.Failure(OAuthError.MissingCode) else RedirectResult.Code(code)
    }

    private fun String.toHttpUrlOrNull(): HttpUrl? = try { toHttpUrl() } catch (_: IllegalArgumentException) { null }

    internal fun parseToken(body: String): OAuthToken {
        val o = try { Json.parseToJsonElement(body).jsonObject } catch (e: Exception) { throw IOException("Unexpected login response") }
        val token = o.str("access_token") ?: throw IOException("Login response had no access token")
        val athlete = o["athlete"] as? JsonObject
        val id = athlete?.str("id") ?: o.str("athlete_id") ?: throw IOException("Login response had no athlete id")
        return OAuthToken(
            accessToken = token,
            scope = o.str("scope").orEmpty(),
            athleteId = id,
            athleteName = athlete?.str("name") ?: o.str("athlete_name").orEmpty(),
        )
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull

    /** POSTs the authorization code; the message of a thrown exception never contains the code, verifier or token. */
    suspend fun exchange(
        code: String,
        verifier: String,
        clientId: String,
        baseUrl: String = "https://intervals.icu",
        client: OkHttpClient = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build(),
    ): OAuthToken = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("code", code)
            .add("code_verifier", verifier)
            .build()
        val req = Request.Builder().url("$baseUrl/api/oauth/token").post(form).header("Accept", "application/json").build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("Login failed: intervals.icu HTTP ${resp.code}")
            parseToken(body)
        }
    }
}

data class OAuthToken(val accessToken: String, val scope: String, val athleteId: String, val athleteName: String) {
    override fun toString() = "OAuthToken(athleteId=$athleteId, scope=$scope)" // never print the token
}

enum class OAuthError { AccessDenied, StateMismatch, MissingCode }

sealed interface RedirectResult {
    data class Code(val code: String) : RedirectResult { override fun toString() = "Code(***)" }
    data class Failure(val error: OAuthError) : RedirectResult
}
