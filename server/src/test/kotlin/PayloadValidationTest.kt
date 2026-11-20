package ch.nokillswit

import ch.nokillswit.plugins.MAX_REQUEST_BODY_BYTES
import ch.nokillswit.plugins.hasEncodedBody
import ch.nokillswit.plugins.routedPath
import ch.nokillswit.users.UserRole
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Malformed input handling (plugins/ErrorHandling.kt): every rejection is a clean RFC 7807
 * 400 with fixed, client-safe vocabulary — never a 500, never internal class names.
 */
class PayloadValidationTest {

    @Test
    fun `JSON sent as plain text is a 415 problem with an occurrence path`() = testApplication {
        usePostgresTestcontainer()
        val response = jsonClient().post("/api/v1/login") {
            header("Content-Type", "text/plain")
            setBody("""{"email":"nobody@example.test","password":"unused"}""")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        assertContains(response.bodyAsText(), "application/json")
        assertContains(response.bodyAsText(), "\"instance\":\"/api/v1/login\"")
    }

    @Test
    fun `malformed JSON is a 400 problem without internal class names`() = testApplication {
        usePostgresTestcontainer()
        val response = jsonClient().postJson("/api/v1/login", "{ not json")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertContains(body, "Request body is invalid")
        assertFalse("ch.nokillswit" in body, "internal class names must not leak into error bodies")
    }

    @Test
    fun `a request with a Content-Encoding is a 415 problem and nothing decodes it - a bomb that inflates to valid JSON included`() =
        testApplication {
            usePostgresTestcontainer()
            val client = jsonClient()
            // Decoded, this is a well-formed login for an unknown account: a 401. Refused unread, it is a 415 (C-SEC-1: the Compression
            // plugin used to inflate request bodies AFTER the wire-byte ceiling had counted them).
            for ((encoding, body) in compressedBodies("""{"email":"nobody@example.test","password":"unused"}""")) {
                assertTrue(body.size < 16 * 1024, "the $encoding wire body is under the small-body ceiling")
                val response = client.post("/api/v1/login") {
                    header(HttpHeaders.ContentEncoding, encoding)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                assertEquals(HttpStatusCode.UnsupportedMediaType, response.status, encoding)
                assertContains(response.bodyAsText(), "\"instance\":\"/api/v1/login\"")
                assertFalse("ch.nokillswit" in response.bodyAsText(), "no internal class names")
            }
            // A label alone is refused too (a WAF and the server could otherwise disagree about the body); `identity` is no encoding.
            val labelled = client.post("/api/v1/login") {
                header(HttpHeaders.ContentEncoding, "br")
                contentType(ContentType.Application.Json)
                setBody("""{"email":"nobody@example.test","password":"unused"}""")
            }
            assertEquals(HttpStatusCode.UnsupportedMediaType, labelled.status)
            val identity = client.post("/api/v1/login") {
                header(HttpHeaders.ContentEncoding, "identity")
                contentType(ContentType.Application.Json)
                setBody("""{"email":"nobody@example.test","password":"unused"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, identity.status, "identity is no encoding: the body reaches the route")
        }

    @Test
    fun `only a Content-Encoding other than identity counts as an encoded body`() {
        assertFalse(hasEncodedBody(null))
        assertFalse(hasEncodedBody(emptyList()))
        assertFalse(hasEncodedBody(listOf("identity")))
        assertFalse(hasEncodedBody(listOf(" Identity ", "")))
        assertTrue(hasEncodedBody(listOf("gzip")))
        assertTrue(hasEncodedBody(listOf("identity, gzip")))
        assertTrue(hasEncodedBody(listOf("identity", "BR")))
    }

    @Test
    fun `responses are still compressed when the client accepts gzip`() = testApplication {
        usePostgresTestcontainer()
        // The spec is a large public text resource in development mode; the plain client skips the /api conformance plugin, which
        // cannot parse gzip.
        val response = createClient { }.get("/openapi/documentation.yaml") { header(HttpHeaders.AcceptEncoding, "gzip") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("gzip", response.headers[HttpHeaders.ContentEncoding], "only REQUEST bodies stopped being decoded")
    }

    @Test
    fun `a body-less POST without a content type is a 400 problem`() = testApplication {
        usePostgresTestcontainer()
        val response = jsonClient().post("/api/v1/login")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "Request body is missing or not JSON")
    }

    @Test
    fun `a request body over the global ceiling is a 413 problem`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("bodycap")
        TestUsers.seed(email = email, password = "pw", role = UserRole.ADMIN)
        val client = authedClient(email, "pw")
        // A body over MAX_REQUEST_BODY_BYTES (512 KiB): the RequestBodyLimit backstop rejects
        // it before any receive/validation work, with the RFC 7807 body like every error.
        val oversized = """{"name":"""" + "x".repeat(MAX_REQUEST_BODY_BYTES.toInt()) + "\"}"
        val response = client.postJson("/api/v1/users", oversized)
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertContains(response.bodyAsText(), "size limit")
    }

    @Test
    fun `the anonymous auth routes refuse a body over 16 KiB with 413 while a normal body still reaches them`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        // 20 KiB: far under the global ceiling, over the small-body one. Refused before the body is decoded (a password this long would
        // otherwise be a 400/401 after the work).
        val padding = "x".repeat(20 * 1024)
        for (path in listOf("login", "login/mfa", "refresh", "password-reset", "password-reset/confirm")) {
            val response = client.postJson("/api/v1/$path", """{"email":"a@b.test","password":"$padding"}""")
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status, path)
            assertContains(response.bodyAsText(), "size limit")
        }
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.postJson("/api/v1/login", """{"email":"nobody@example.test","password":"unused"}""").status,
            "a normal body is not caught by the small limit",
        )
    }

    @Test
    fun `the small ceiling matches the path as routing sees it - an encoded letter, a trailing or a doubled slash does not slip past`() =
        testApplication {
            usePostgresTestcontainer()
            // No token and no conformance plugin: the ceiling answers before authentication, and an encoded spelling is not a spec path.
            val plain = createClient { }
            val junk = """{"email":"a@b.c","password":"${"p".repeat(20 * 1024)}"}"""
            for (path in listOf("/api/v1/%6Cogin", "/api/v1//login/", "/api/v1/login/", "/api/v1/%72efresh")) {
                val response = plain.post(path) {
                    contentType(ContentType.Application.Json)
                    setBody(junk)
                }
                assertEquals(HttpStatusCode.PayloadTooLarge, response.status, path)
            }
        }

    @Test
    fun `routedPath decodes segments, drops empty ones and keeps an undecodable path raw`() {
        assertEquals("/api/v1/login", routedPath("/api/v1/%6Cogin"))
        assertEquals("/api/v1/login", routedPath("/api//v1/login/"))
        assertEquals("/api/v1/%zz", routedPath("/api/v1/%zz"), "a segment that does not decode cannot route: raw path, global ceiling")
    }

    @Test
    fun `a negative path id is rejected with 400 before routing`() = testApplication {
        usePostgresTestcontainer()
        // kotlinx UInt decoding would silently wrap /users/-1 to 4294967295 — the pre-routing
        // interceptor must reject it instead.
        val response = jsonClient().putJson("/api/v1/users/-1/password", """{"password":"whatever-works"}""")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "non-negative")
        // The intercept runs pre-routing, so it covers every verb — DELETE included.
        assertEquals(HttpStatusCode.BadRequest, jsonClient().delete("/api/v1/users/-1").status)
    }

    @Test
    fun `a repeated scalar query parameter is a 400 problem`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("repeated")
        TestUsers.seed(email = email, password = "pw", role = UserRole.ADMIN)
        val client = authedClient(email, "pw")
        // Repetition is reserved for per-endpoint documented IN semantics (API-LIST-004) —
        // silently first-winning would hide the caller's conflicting input.
        val paging = client.get("/api/v1/users?page=1&page=2")
        assertEquals(HttpStatusCode.BadRequest, paging.status)
        assertContains(paging.bodyAsText(), "Parameter 'page' must not be repeated")
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/users?sort=id&sort=name").status)
        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/api/v1/users?email=a&email=b").status,
        )
    }

    @Test
    fun `a wrong method on an existing path is a 405 problem body`() = testApplication {
        usePostgresTestcontainer()
        // Deliberately the DEFAULT client: a wrong-method operation is outside the spec by
        // definition, and the conformance plugin would (correctly) flag it on jsonClient().
        val response = client.put("/api/v1/login")
        assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
        assertContains(response.bodyAsText(), "Method not allowed")
    }
}
