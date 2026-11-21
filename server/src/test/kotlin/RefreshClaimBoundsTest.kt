package ch.nokillswit

import ch.nokillswit.auth.LogoutRequest
import ch.nokillswit.auth.RefreshRequest
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.JWTCreator
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The numeric bounds and the optional claims of a refresh token that `RefreshTest` leaves alone: a signed, typed, otherwise valid token
 * whose user id is outside the 1..UInt range or whose credential revision is negative is `malformed` (never a lookup, never a 500), and
 * a logout that presents a refresh token without a `jti` or an `exp` still answers 204 (best-effort revocation of what can be revoked).
 * The tokens are minted with the dev secret in the shape of `auth/Tokens.kt`, minus or plus the one claim under test.
 */
class RefreshClaimBoundsTest {
    private fun refreshBuilder(email: String): JWTCreator.Builder = JWT.create()
        .withAudience("hammurabi-api")
        .withIssuer("http://0.0.0.0:8090/")
        .withIssuedAt(Date())
        .withClaim("email", email)
        .withClaim("typ", "refresh")

    private fun JWTCreator.Builder.signed(): String = sign(Algorithm.HMAC256("secret"))

    private suspend fun HttpClient.refreshStatus(token: String) = postJson("/api/v1/refresh", RefreshRequest(token)).status

    @Test
    fun `a user id outside 1 to the unsigned maximum, or a negative credential revision, is malformed`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("refresh-bounds")
        val userId = TestUsers.seed(email = email, password = "pw")
        val client = jsonClient()

        fun token(id: Long, revision: Long) = refreshBuilder(email)
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .withJWTId(UUID.randomUUID().toString())
            .withClaim("userId", id)
            .withClaim("credentialRevision", revision)
            .signed()

        // The control: the same builder with in-range values is accepted, so only the bound can explain a 401 below.
        assertEquals(HttpStatusCode.OK, client.refreshStatus(token(userId.toLong(), 0)))
        withAuditCapture { audit ->
            val bad = mapOf(
                "user id 0" to token(0, 0),
                "user id past the unsigned maximum" to token(UInt.MAX_VALUE.toLong() + 1, 0),
                "negative credential revision" to token(userId.toLong(), -1),
            )
            for ((what, token) in bad) {
                val seen = audit.events.toSet()
                assertEquals(HttpStatusCode.Unauthorized, client.refreshStatus(token), what)
                // Each token is judged on its own line: an event from an earlier one cannot stand in for it.
                assertNotNull(
                    audit.awaitEvent {
                        it !in seen && it.formattedMessage.contains("refresh.rejected") && it.hasKeyValue("reason", "malformed")
                    },
                    "$what: audited refresh.rejected reason=malformed",
                )
            }
        }
    }

    @Test
    fun `a logout presenting a refresh token without a jti or without an exp still answers 204`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("logout-partial")
        val userId = TestUsers.seed(email = email, password = "pw")

        fun token(jti: Boolean, exp: Boolean) = refreshBuilder(email)
            .withClaim("userId", userId.toLong())
            .withClaim("credentialRevision", 0L)
            .apply {
                if (jti) withJWTId(UUID.randomUUID().toString())
                if (exp) withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            }
            .signed()

        for ((jti, exp) in listOf(false to true, true to false)) {
            // A logout ends its access token, so each case signs in afresh.
            val response = authedClient(email, "pw").post("/api/v1/logout") {
                contentType(ContentType.Application.Json)
                setBody(LogoutRequest(refreshToken = token(jti, exp)))
            }
            assertEquals(HttpStatusCode.NoContent, response.status, "jti=$jti exp=$exp")
        }
    }
}
