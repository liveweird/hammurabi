package ch.nokillswit

import ch.nokillswit.authz.ConflictException
import ch.nokillswit.authz.ForbiddenException
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.plugins.configureErrorHandling
import ch.nokillswit.plugins.respondProblem
import com.auth0.jwt.JWT
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.r2dbc.spi.R2dbcNonTransientResourceException
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The `ErrorHandling.kt` mappings that no feature route reaches over HTTP: the database failures that classify (a unique violation by
 * constraint name, the NUL character, anything else a logged 500), a conflict that names the `instance` it collides with, the default
 * problem detail, and a 403 for a caller with and without a verified principal. A bare error-handling application with probe routes on
 * synthetic paths, called with the default `client` (no OpenAPI conformance for a path the spec does not declare), as
 * `ExpectedRevisionTest`.
 */
class ErrorMappingTest {
    private val problemJson = Json { ignoreUnknownKeys = true }

    private fun r2dbc(state: String, message: String = "failure") = R2dbcNonTransientResourceException(message, state)

    /** The Exposed wrapper of a driver failure; it only keeps the (finished) transaction it was raised in. */
    private suspend fun exposed(cause: Throwable): ExposedSQLException =
        ExposedSQLException(cause, emptyList(), suspendTransaction(sharedDatabaseForTests()) { this })

    private fun Application.probes() {
        configureErrorHandling()
        routing {
            get("/probe/instance") { throw ConflictException("collides", instance = "/api/v1/adrs/7") }
            get("/probe/default-detail") { call.respondProblem(HttpStatusCode.Gone) }
            get("/probe/nul") { throw r2dbc("22021") }
            get("/probe/unique-named") { throw r2dbc("23505", "duplicate key value violates unique constraint \"uq_adrs_name_active\"") }
            get("/probe/unique-unmapped") { throw r2dbc("23505", "duplicate key value violates unique constraint \"uq_future\"") }
            // The Exposed wrapper, then a link with no message at all, then the driver failure that names the constraint.
            get("/probe/unique-wrapped") {
                throw exposed(RuntimeException(null as String?, r2dbc("23505", "violates unique constraint \"uq_teams_name_active\"")))
            }
            get("/probe/other") { throw r2dbc("40001", "could not serialize SECRET-VALUE") }
            get("/probe/exposed") { throw exposed(r2dbc("22021")) }
            get("/probe/forbidden") { throw ForbiddenException("not yours") }
            get("/probe/forbidden-authenticated") {
                val token = JWT.create().withClaim("userId", 7L).sign(com.auth0.jwt.algorithms.Algorithm.HMAC256("secret"))
                call.authentication.principal(JWTPrincipal(JWT.decode(token)))
                throw ForbiddenException("not yours")
            }
        }
    }

    private fun probe(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { probes() }
        block()
    }

    private suspend fun HttpResponse.problem(): ProblemDetail = problemJson.decodeFromString(bodyAsText())

    @Test
    fun `a conflict names the instance it collides with, and a problem without a detail says the status description`() = probe {
        val conflict = client.get("/probe/instance")
        assertEquals(HttpStatusCode.Conflict, conflict.status)
        assertEquals("/api/v1/adrs/7", conflict.problem().instance)

        val gone = client.get("/probe/default-detail")
        assertEquals(HttpStatusCode.Gone, gone.status)
        assertEquals(HttpStatusCode.Gone.description, gone.problem().detail)
        assertEquals("/probe/default-detail", gone.problem().instance, "the request path when none is given")
    }

    @Test
    fun `a unique violation answers 409 with the wording of the constraint it names, or the generic one`() = probe {
        for ((path, detail) in listOf(
            "/probe/unique-named" to "An ADR with this name already exists",
            "/probe/unique-wrapped" to "A team with this name already exists",
            "/probe/unique-unmapped" to "Resource already exists",
        )) {
            val response = client.get(path)
            assertEquals(HttpStatusCode.Conflict, response.status, path)
            assertEquals(detail, response.problem().detail, path)
        }
    }

    @Test
    fun `the NUL character is a 400 and any other database failure a 500 that echoes nothing`() = probe {
        for (path in listOf("/probe/nul", "/probe/exposed")) {
            val nul = client.get(path)
            assertEquals(HttpStatusCode.BadRequest, nul.status, path)
            assertEquals("Text must not contain the NUL character", nul.problem().detail, path)
        }
        val other = client.get("/probe/other")
        assertEquals(HttpStatusCode.InternalServerError, other.status)
        assertEquals("An unexpected error occurred", other.problem().detail)
        assertTrue("SECRET-VALUE" !in other.bodyAsText(), "a driver message never reaches the client")
    }

    @Test
    fun `a denial is audited with the caller when there is a verified principal and without one when there is none`() = probe {
        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.Forbidden, client.get("/probe/forbidden").status)
            val anonymous = assertNotNull(capture.awaitEvent { it.message == "authz.denied" && it.hasKeyValue("path", "/probe/forbidden") })
            assertTrue(anonymous.keyValuePairs.orEmpty().none { it.key == "byUserId" && it.value != null })

            assertEquals(HttpStatusCode.Forbidden, client.get("/probe/forbidden-authenticated").status)
            val known = assertNotNull(
                capture.awaitEvent { it.message == "authz.denied" && it.hasKeyValue("path", "/probe/forbidden-authenticated") },
            )
            assertTrue(known.hasKeyValue("byUserId", 7L))
        }
    }
}
