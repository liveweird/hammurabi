package ch.nokillswit

import ch.nokillswit.adrs.AdrParticipantRequest
import ch.nokillswit.adrs.AdrRole
import ch.nokillswit.adrs.DiscussionKind
import ch.nokillswit.plugins.ProblemDetail
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val ADR_WRITES_PER_MINUTE = 120
private const val ABSENT_ENTRY = 2_000_000_000
private const val ADRS = "/api/v1/adrs"

/**
 * The `adr-write` bucket (checkup C-SEC-4, `security.md`): the 13 writes under `/api/v1/adrs` share ONE per-user bucket of 120 a
 * minute, mounted before every guard, so a refused or invalid write spends a token too and 120 of them in any order change nothing the
 * next one needs. The reads on the SAME paths (the ADR, its list, its discussion, its history) stay outside it.
 */
class AdrWriteLimitsTest {
    /** A request that has not been sent yet (so the list below is data, not 13 immediate calls). */
    private fun HttpClient.raw(method: HttpMethod, path: String, body: String? = null): suspend () -> HttpResponse = {
        request(path) {
            this.method = method
            body?.let { contentType(ContentType.Application.Json); setBody(it) }
        }
    }

    /** The 13 writes, each a request that is refused or changes nothing: the Driver sends them, so a missing revision header is a 400. */
    private fun writes(client: HttpClient, adr: UInt): List<suspend () -> HttpResponse> = listOf(
        client.raw(HttpMethod.Post, ADRS, "{}"), // 400: no problem
        client.raw(HttpMethod.Put, "$ADRS/$adr", "{}"), // 400: no revision header
        client.raw(HttpMethod.Delete, "$ADRS/$adr"), // 403: the Driver is not an ADMIN
        client.raw(HttpMethod.Post, "$ADRS/$adr/transitions", "{}"), // 400: no revision header
        client.raw(HttpMethod.Put, "$ADRS/$adr/participants", "{}"), // 400: no revision header
        client.raw(HttpMethod.Put, "$ADRS/$adr/tags", "{}"), // 400: no revision header
        client.raw(HttpMethod.Put, "$ADRS/$adr/supersedes", "{}"), // 400: no revision header
        client.raw(HttpMethod.Delete, "$ADRS/$adr/successor"), // 404: nothing pending
        client.raw(HttpMethod.Post, "$ADRS/$adr/successor/accept", "{}"), // 403: the Driver is not the Approver
        client.raw(HttpMethod.Post, "$ADRS/$adr/discussion", "{}"), // 400: no kind and no body
        client.raw(HttpMethod.Put, "$ADRS/$adr/discussion/$ABSENT_ENTRY", "{}"), // 404: no such entry
        client.raw(HttpMethod.Delete, "$ADRS/$adr/discussion/$ABSENT_ENTRY"), // 404
        client.raw(HttpMethod.Put, "$ADRS/$adr/discussion/$ABSENT_ENTRY/state", "{}"), // 404
    )

    @Test
    fun `the 13 ADR writes share one bucket of 120 a minute per user, then 429 on each, and the reads and other users are untouched`() =
        testApplication {
            usePostgresTestcontainer()
            val driver = adrActor("aw-drv")
            val approver = adrActor("aw-apr")
            val adr = driver.client.createAdr(participants = listOf(AdrParticipantRequest(approver.id, AdrRole.APPROVER)))
            val requests = writes(driver.client, adr.id)
            assertEquals(13, requests.size)

            withAuditCapture { capture ->
                // The creation above spent one token.
                repeat(ADR_WRITES_PER_MINUTE - 1) { n ->
                    val response = requests[n % requests.size]()
                    assertTrue(response.status != HttpStatusCode.TooManyRequests, "request ${n + 2}: ${response.status}")
                }
                val rejected = requests.map { it() }
                assertTrue(
                    rejected.all { it.status == HttpStatusCode.TooManyRequests },
                    "every one of the 13 spends the same bucket: ${rejected.map { it.status }}",
                )
                assertEquals(429, rejected.first().body<ProblemDetail>().status)
                assertNotNull(rejected.first().headers["Retry-After"], "the bucket 429 says when to retry")
                val event = capture.awaitEvent { it.message == "rate_limit.exceeded" && it.hasKeyValue("bucket", "adr-write") }
                assertNotNull(event)
                assertTrue(event.hasKeyValue("byUserId", driver.id.toLong()))

                // The same user's reads on the very same paths, another user's write, and a write by the Approver are untouched.
                for (path in listOf("$ADRS/${adr.id}", ADRS, "$ADRS/${adr.id}/discussion", "$ADRS/${adr.id}/events", "$ADRS/overview")) {
                    assertEquals(HttpStatusCode.OK, driver.client.get(path).status, "GET $path is not bucketed")
                }
                assertEquals(HttpStatusCode.NotFound, driver.client.get("$ADRS/${adr.id}/discussion/$ABSENT_ENTRY").status)
                assertEquals(HttpStatusCode.Created, approver.client.postEntry(adr.id, DiscussionKind.COMMENT).status, "another user")
                val again = driver.client.postEntry(adr.id, DiscussionKind.COMMENT)
                assertEquals(HttpStatusCode.TooManyRequests, again.status, "still limited")
                assertEquals(HttpStatusCode.TooManyRequests, driver.client.delete("$ADRS/${adr.id}").status)
            }
        }
}
