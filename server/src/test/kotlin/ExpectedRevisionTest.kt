package ch.nokillswit

import ch.nokillswit.infra.revision.EXPECTED_REVISION_HEADER
import ch.nokillswit.infra.revision.REVISION_CONFLICT_PROBLEM_TYPE
import ch.nokillswit.infra.revision.RevisionConflictException
import ch.nokillswit.infra.revision.expectedRevision
import ch.nokillswit.infra.revision.requireRevision
import ch.nokillswit.plugins.ProblemJson
import ch.nokillswit.plugins.configureErrorHandling
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `infra/revision/ExpectedRevision.kt` and its ErrorHandling mappings. No route consumes the guard yet (the ADR whole-document PUT arrives
 * with the ADR core), so a test-only route installed on a bare error-handling application stands in:
 * synthetic non-`/api/` paths, called with the default `client` (which bypasses the OpenAPI conformance plugin, as it must for a
 * path the spec does not declare).
 */
class ExpectedRevisionTest {
    private fun Application.probeRoutes() {
        configureErrorHandling()
        routing {
            get(ECHO_ROUTE) { call.respondText(call.expectedRevision().toString()) }
            get(GUARD_ROUTE) {
                requireRevision(call.expectedRevision(), CURRENT_REVISION)
                call.respondText("fresh")
            }
        }
    }

    private fun probe(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { probeRoutes() }
        block()
    }

    private suspend fun ApplicationTestBuilder.echo(vararg headerValues: String): HttpResponse = client.get(ECHO_ROUTE) {
        headerValues.forEach { header(EXPECTED_REVISION_HEADER, it) }
    }

    private suspend fun HttpResponse.problem() = Json.parseToJsonElement(bodyAsText()).jsonObject

    @Test
    fun `one positive integer is the expected revision, up to the largest int64`() = probe {
        for (value in listOf("1", "42", Long.MAX_VALUE.toString())) {
            val response = echo(value)
            assertEquals(HttpStatusCode.OK, response.status, value)
            assertEquals(value, response.bodyAsText())
        }
    }

    @Test
    fun `a missing header is a 400 with the problem body`() = probe {
        val response = client.get(ECHO_ROUTE)
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(ProblemJson, response.contentType()?.withoutParameters())
        val body = response.problem()
        assertEquals("$EXPECTED_REVISION_HEADER header is required", body["detail"]?.jsonPrimitive?.content)
    }

    @Test
    fun `zero, negative, signed, zero-padded, fractional, non-numeric, padded, listed, non-ASCII-digit and empty values are 400`() = probe {
        for (value in listOf("0", "-1", "+1", "01", "1.5", "abc", "1e3", "0x10", "", "1,2", " 1", "1 ", "1, 1", "\u0661\u0662\u0663")) {
            assertEquals(HttpStatusCode.BadRequest, echo(value).status, "'$value'")
        }
    }

    @Test
    fun `a value beyond int64 is a 400, the boundary itself is accepted`() = probe {
        assertEquals(HttpStatusCode.OK, echo("9223372036854775807").status)
        assertEquals(HttpStatusCode.BadRequest, echo("9223372036854775808").status)
        assertEquals(HttpStatusCode.BadRequest, echo("99999999999999999999").status)
    }

    @Test
    fun `a repeated header is a 400 even when both lines are valid`() = probe {
        assertEquals(HttpStatusCode.BadRequest, echo("1", "1").status)
        assertEquals(HttpStatusCode.BadRequest, echo("1", "2").status)
    }

    @Test
    fun `a stale revision is a typed 409 naming both revisions, a fresh one passes`() = probe {
        val fresh = client.get(GUARD_ROUTE) { header(EXPECTED_REVISION_HEADER, CURRENT_REVISION.toString()) }
        assertEquals(HttpStatusCode.OK, fresh.status)

        for (stale in listOf(CURRENT_REVISION - 1, CURRENT_REVISION + 1)) {
            val response = client.get(GUARD_ROUTE) { header(EXPECTED_REVISION_HEADER, stale.toString()) }
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals(ProblemJson, response.contentType()?.withoutParameters())
            val body = response.problem()
            assertEquals(REVISION_CONFLICT_PROBLEM_TYPE, body["type"]?.jsonPrimitive?.content)
            assertEquals(409, body["status"]?.jsonPrimitive?.content?.toInt())
            val detail = body["detail"]?.jsonPrimitive?.content.orEmpty()
            assertTrue("expected $stale" in detail && "current revision is $CURRENT_REVISION" in detail, detail)
        }
    }

    @Test
    fun `the guard reads the header before it compares, so a missing header stays a 400 not a 409`() = probe {
        assertEquals(HttpStatusCode.BadRequest, client.get(GUARD_ROUTE).status)
    }

    @Test
    fun `requireRevision is silent on equal revisions and carries both on a mismatch`() {
        requireRevision(3, 3)
        val stale = assertFailsWith<RevisionConflictException> { requireRevision(3, 4) }
        assertEquals(3, stale.expected)
        assertEquals(4, stale.current)
    }

    @Test
    fun `CORS preflight allows the expected-revision header`() = testApplication {
        configureApp("http.corsHosts" to "app.example.com")
        startApplication()
        val response = client.options("/api/v1/login") {
            header(HttpHeaders.Origin, "https://app.example.com")
            header(HttpHeaders.AccessControlRequestMethod, "PUT")
            header(HttpHeaders.AccessControlRequestHeaders, EXPECTED_REVISION_HEADER)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val allowed = response.headers[HttpHeaders.AccessControlAllowHeaders].orEmpty().split(',').map { it.trim().lowercase() }
        assertTrue(EXPECTED_REVISION_HEADER.lowercase() in allowed, "allowed headers: $allowed")
    }

    private fun HttpResponse.contentType(): ContentType? = headers[HttpHeaders.ContentType]?.let(ContentType::parse)

    private fun ContentType.withoutParameters(): ContentType = ContentType(contentType, contentSubtype)

    private companion object {
        const val ECHO_ROUTE = "/revision-probe/echo"
        const val GUARD_ROUTE = "/revision-probe/guard"
        const val CURRENT_REVISION = 7L
    }
}
