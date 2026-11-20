package ch.nokillswit

import ch.nokillswit.adrs.DiscussionKind
import ch.nokillswit.adrs.DiscussionStatus
import io.ktor.client.request.delete
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The discussion audit events (`observability.md`): every mutation after its commit and only on a change, never the text; a denial is
 * `authz.denied`.
 */
class AdrDiscussionAuditTest {

    @Test
    fun `every mutation audits with ids and kind, never the text`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        withAuditCapture { capture ->
            val adr = cast.raise(complete = true)
            val adrId = adr.id.toLong()
            val entry = cast.contributor.client.postEntryOk(adr.id, DiscussionKind.QUESTION, "A confidential question")
            val entryId = entry.id.toLong()
            val posted = capture.awaitEvent { it.message == "adr.discussion_posted" && it.hasKeyValue("entryId", entryId) }
            assertNotNull(posted, "a post audits")
            assertTrue(posted.hasKeyValue("byUserId", cast.contributor.id.toLong()) && posted.hasKeyValue("adrId", adrId))
            assertTrue(posted.hasKeyValue("kind", "QUESTION") && posted.hasKeyValue("number", adr.number))

            cast.contributor.client.editEntry(adr.id, entry.id, "A confidential edit")
            val edited = capture.awaitEvent { it.message == "adr.discussion_edited" && it.hasKeyValue("entryId", entryId) }
            assertNotNull(edited, "an edit audits")

            cast.driver.client.setEntryState(adr.id, entry.id, DiscussionStatus.ANSWERED)
            val state = capture.awaitEvent { it.message == "adr.discussion_state_changed" && it.hasKeyValue("entryId", entryId) }
            assertNotNull(state, "a state change audits")
            assertTrue(state.hasKeyValue("from", "OPEN") && state.hasKeyValue("to", "ANSWERED"))
            assertTrue(state.hasKeyValue("byUserId", cast.driver.id.toLong()))

            assertEquals(HttpStatusCode.NoContent, cast.admin.client.delete("/api/v1/adrs/${adr.id}/discussion/${entry.id}").status)
            val deleted = capture.awaitEvent { it.message == "adr.discussion_deleted" && it.hasKeyValue("entryId", entryId) }
            assertNotNull(deleted, "a delete audits")
            assertTrue(deleted.hasKeyValue("byAdmin", true) && deleted.hasKeyValue("byUserId", cast.admin.id.toLong()))

            val leaked = capture.events.any { e -> e.keyValuePairs.orEmpty().any { "confidential" in it.value.toString() } }
            assertTrue(!leaked, "the text never reaches the trail")
        }
    }

    @Test
    fun `a denial is an authz denied event, and a refused, closed or unchanged write audits nothing`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        val entry = cast.contributor.client.postEntryOk(adr.id, DiscussionKind.QUESTION)
        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.Forbidden, cast.stranger.client.editEntry(adr.id, entry.id, "mine now").status)
            val path = "/api/v1/adrs/${adr.id}/discussion/${entry.id}"
            val denied = capture.awaitEvent { it.message == "authz.denied" && it.hasKeyValue("path", path) }
            assertNotNull(denied, "the 403 leaves the denial trail")
            assertTrue(denied.hasKeyValue("byUserId", cast.stranger.id.toLong()))

            // An unchanged edit, an already-answered question, a refused kind and a blank body are no mutations.
            cast.contributor.client.editEntry(adr.id, entry.id, entry.body.orEmpty())
            cast.driver.client.setEntryState(adr.id, entry.id, DiscussionStatus.ANSWERED)
            capture.awaitEvent { it.message == "adr.discussion_state_changed" && it.hasKeyValue("entryId", entry.id.toLong()) }
            cast.driver.client.setEntryState(adr.id, entry.id, DiscussionStatus.ANSWERED)
            cast.stranger.client.postEntry(adr.id, DiscussionKind.ISSUE)
            cast.stranger.client.postEntry(adr.id, DiscussionKind.COMMENT, "")
            delay(300)
            val audits = capture.events.filter {
                it.message?.startsWith("adr.discussion_") == true && it.hasKeyValue("adrId", adr.id.toLong())
            }
            assertEquals(listOf("adr.discussion_state_changed"), audits.map { it.message })
        }
    }
}
