package ch.nokillswit

import ch.nokillswit.authz.caller
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.plugins.configureErrorHandling
import com.auth0.jwt.JWT
import com.auth0.jwt.JWTCreator
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `authz/Principal.kt` `caller()`: the claims it reads from the verified principal. The JWT `validate` block already refuses a token
 * without them, so over HTTP these branches cannot be reached; this class mounts a probe route that installs a principal built from an
 * unverified token (the probe header) and answers what `caller()` made of it, on a bare error-handling application.
 */
class CallerPrincipalTest {
    private val probeHeader = "X-Probe-Token"

    private fun Application.probes() {
        configureErrorHandling()
        routing {
            get("/probe/caller") {
                call.request.headers[probeHeader]?.let { call.authentication.principal(JWTPrincipal(JWT.decode(it))) }
                val caller = call.caller()
                val roles = caller.roles.sorted().joinToString(",")
                call.respondText("${caller.userId}|${caller.email}|$roles|${caller.disabledFeatures.joinToString(",")}")
            }
        }
    }

    private fun probe(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { probes() }
        block()
    }

    private fun token(configure: JWTCreator.Builder.() -> Unit = {}): String = JWT.create()
        .withClaim("email", "a@test")
        .withClaim("userId", 5L)
        .withArrayClaim("roles", arrayOf("ADMIN"))
        .apply(configure)
        .sign(Algorithm.HMAC256("secret"))

    private suspend fun ApplicationTestBuilder.callerOf(token: String?): HttpResponse = client.get("/probe/caller") {
        token?.let { header(probeHeader, it) }
    }

    private suspend fun HttpResponse.detail(): String? =
        Json { ignoreUnknownKeys = true }.decodeFromString<ProblemDetail>(bodyAsText()).detail

    @Test
    fun `a complete token is the caller, with no disabled features when the claim is absent`() = probe {
        val response = callerOf(token())
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("5|a@test|ADMIN|", response.bodyAsText())
    }

    @Test
    fun `the disabled-features claim is read`() = probe {
        val response = callerOf(token { withArrayClaim("disabledFeatures", arrayOf("MFA")) })
        assertEquals("5|a@test|ADMIN|MFA", response.bodyAsText())
    }

    @Test
    fun `a missing principal or a missing required claim is a 401 naming it`() = probe {
        assertEquals(HttpStatusCode.Unauthorized, callerOf(null).status)
        val unsigned = { claim: String ->
            JWT.create()
                .apply {
                    if (claim != "email") withClaim("email", "a@test")
                    if (claim != "userId") withClaim("userId", 5L)
                    if (claim != "roles") withArrayClaim("roles", arrayOf("USER"))
                }
                .sign(Algorithm.HMAC256("secret"))
        }
        val expected = listOf("email" to "Missing email claim", "userId" to "Missing userId claim", "roles" to "Missing roles claim")
        for ((claim, detail) in expected) {
            val response = callerOf(unsigned(claim))
            assertEquals(HttpStatusCode.Unauthorized, response.status, claim)
            assertEquals(detail, response.detail(), claim)
        }
    }

    @Test
    fun `an unknown role or an unknown feature is a forged token and a 401`() = probe {
        val role = callerOf(token { withArrayClaim("roles", arrayOf("SUPERUSER")) })
        assertEquals(HttpStatusCode.Unauthorized, role.status)
        assertEquals("Unknown role SUPERUSER", role.detail())

        val feature = callerOf(token { withArrayClaim("disabledFeatures", arrayOf("TELEPORT")) })
        assertEquals(HttpStatusCode.Unauthorized, feature.status)
        assertEquals("Unknown feature TELEPORT", feature.detail())
    }
}
