package ch.nokillswit

import ch.nokillswit.adrs.DiscussionKind
import ch.nokillswit.adrs.DiscussionState
import ch.nokillswit.infra.revision.EXPECTED_REVISION_HEADER
import io.ktor.client.HttpClient
import io.ktor.client.request.header
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
import kotlin.test.assertFalse

/**
 * The OPTIONAL `X-Expected-Revision` of the discussion writes (edit, delete, state): absent is accepted, but a malformed or repeated
 * header is a `400`, and the guard still comes first (`authorization.md`: 403 before 400), so a non-author learns nothing from the header.
 */
class AdrDiscussionRevisionHeaderTest {
    /**
     * One value each: not a positive decimal int64. Then the repeated forms: two header lines whose values are EACH valid alone, so only
     * the repetition can make them a 400. Known limitation: the server answers a malformed and a repeated header with the same message
     * (`must be one positive integer`), and an HTTP stack may fold two lines into one `1, 2` value, so the test pins the status, not
     * which of the two paths produced it; the folded value is itself malformed, which is why both paths end in the same 400.
     */
    private val badRevisions: List<List<String>> = listOf(
        listOf("abc"), listOf("0"), listOf("-1"), listOf("01"), listOf("1.5"), listOf("2 3"), listOf("9223372036854775808"),
        listOf("1", "1"), listOf("1", "2"),
    )

    private suspend fun HttpClient.send(method: HttpMethod, path: String, revisions: List<String>, json: String?): HttpResponse =
        request(path) {
            this.method = method
            revisions.forEach { header(EXPECTED_REVISION_HEADER, it) }
            json?.let {
                contentType(ContentType.Application.Json)
                setBody(it)
            }
        }

    /** The three revision-aware writes on one entry, as data: (method, path suffix, JSON body). */
    private val writes = listOf(
        Triple(HttpMethod.Put, "", """{"body":"Edited"}"""),
        Triple(HttpMethod.Delete, "", null),
        Triple(HttpMethod.Put, "/state", """{"state":"ANSWERED"}"""),
    )

    @Test
    fun `a malformed or repeated revision header on the edit, delete and state routes is a 400 and changes nothing`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        val question = cast.contributor.client.postEntryOk(adr.id, DiscussionKind.QUESTION, "Original")
        val path = "/api/v1/adrs/${adr.id}/discussion/${question.id}"

        for ((method, suffix, json) in writes) {
            for (revisions in badRevisions) {
                val response = cast.contributor.client.send(method, path + suffix, revisions, json)
                assertEquals(HttpStatusCode.BadRequest, response.status, "$method$suffix with $revisions")
            }
        }
        val unchanged = cast.contributor.client.entry(adr.id, question.id)
        assertEquals("Original", unchanged.body)
        assertEquals(DiscussionState.OPEN, unchanged.state)
        assertFalse(unchanged.deleted)
        assertEquals(question.revision, unchanged.revision)
    }

    @Test
    fun `a non-author with a malformed revision header gets 403, never 400`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        val question = cast.contributor.client.postEntryOk(adr.id, DiscussionKind.QUESTION, "Original")
        val path = "/api/v1/adrs/${adr.id}/discussion/${question.id}"

        for ((method, suffix, json) in writes) {
            for (revisions in listOf(listOf("abc"), listOf("1", "2"))) {
                val response = cast.informed.client.send(method, path + suffix, revisions, json)
                assertEquals(HttpStatusCode.Forbidden, response.status, "$method$suffix with $revisions")
            }
        }
        assertFalse(cast.contributor.client.entry(adr.id, question.id).deleted)
    }
}
