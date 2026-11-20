package ch.nokillswit

import ch.nokillswit.adrs.AdrEventService
import ch.nokillswit.adrs.AdrEventType
import ch.nokillswit.adrs.AdrDiscussionService
import ch.nokillswit.adrs.AdrExpirySweep
import ch.nokillswit.adrs.AdrPageResponse
import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.adrs.DiscussionEntryResponse
import ch.nokillswit.adrs.DiscussionKind
import ch.nokillswit.adrs.DiscussionStatus
import ch.nokillswit.adrs.MAX_DISCUSSION_BODY_LENGTH
import ch.nokillswit.adrs.MAX_DISCUSSION_RESOLUTION_LENGTH
import ch.nokillswit.infra.ProblemTypes
import ch.nokillswit.plugins.MAX_DISCUSSION_REQUEST_BODY_BYTES
import ch.nokillswit.plugins.MAX_SMALL_REQUEST_BODY_BYTES
import ch.nokillswit.plugins.ProblemDetail
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ADR discussion (V12, `adr-model.md` "Discussion"): the window per kind and the typed `discussion-closed` 409, the entry life (post,
 * edit, tombstone), the question and issue states, the list filters, the counts the ADR rows carry, the history events and the sizes.
 * The role matrix is `AdrDiscussionAccessTest`, the audit lines `AdrDiscussionAuditTest`.
 */
class AdrDiscussionTest {

    private val preActive = listOf(AdrStatus.PROBLEM_RAISED, AdrStatus.SOLUTION_DRAFT, AdrStatus.IN_REVIEW)
    private val closed = listOf(AdrStatus.CANCELLED, AdrStatus.WITHDRAWN, AdrStatus.EXPIRED, AdrStatus.SUPERSEDED)

    @Test
    fun `a comment and a question are accepted while the ADR is pre-active, an issue and an escalation are not`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        for (status in preActive) {
            val adr = cast.raise(complete = true)
            forceAdrStatus(adr.id, status)
            val comment = cast.stranger.client.postEntryOk(adr.id, DiscussionKind.COMMENT, "Looks fine in $status")
            assertEquals(null, comment.status, "a comment has no state")
            assertEquals(DiscussionStatus.OPEN, cast.stranger.client.postEntryOk(adr.id, DiscussionKind.QUESTION).status)
            for (kind in listOf(DiscussionKind.ISSUE, DiscussionKind.ESCALATION)) {
                val refused = cast.stranger.client.postEntry(adr.id, kind)
                assertEquals(HttpStatusCode.Conflict, refused.status, "$kind in $status")
                assertEquals(ProblemTypes.DISCUSSION_CLOSED, refused.body<ProblemDetail>().type)
            }
            val view = cast.stranger.client.getAdr(adr.id)
            assertTrue(view.canComment, "the composer is open in $status")
            assertFalse(view.canRaiseIssue)
        }
    }

    @Test
    fun `an issue and an escalation are accepted only while ACTIVE, a comment and a question are not`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        cast.activate(adr)
        val issue = cast.stranger.client.postEntryOk(adr.id, DiscussionKind.ISSUE)
        assertEquals(DiscussionStatus.OPEN, issue.status)
        assertEquals(DiscussionStatus.OPEN, cast.stranger.client.postEntryOk(adr.id, DiscussionKind.ESCALATION).status)
        for (kind in listOf(DiscussionKind.COMMENT, DiscussionKind.QUESTION)) {
            val refused = cast.stranger.client.postEntry(adr.id, kind)
            assertEquals(HttpStatusCode.Conflict, refused.status, "$kind while ACTIVE")
            assertEquals(ProblemTypes.DISCUSSION_CLOSED, refused.body<ProblemDetail>().type)
        }
        val view = cast.stranger.client.getAdr(adr.id)
        assertTrue(view.canRaiseIssue)
        assertFalse(view.canComment)
    }

    @Test
    fun `a closed ADR takes nothing new but stays readable, and its entries can still be deleted`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        for (status in closed) {
            val adr = cast.raise(complete = true)
            val comment = cast.contributor.client.postEntryOk(adr.id, DiscussionKind.COMMENT, "Said before $status")
            forceAdrStatus(adr.id, status)
            for (kind in DiscussionKind.entries) {
                assertEquals(HttpStatusCode.Conflict, cast.stranger.client.postEntry(adr.id, kind).status, "$kind in $status")
            }
            // Edit and state are closed with the window; the entry reads on, forever.
            assertEquals(HttpStatusCode.Conflict, cast.contributor.client.editEntry(adr.id, comment.id, "Late edit").status)
            assertEquals("Said before $status", cast.informed.client.discussion(adr.id).items.single().body)
            val view = cast.stranger.client.getAdr(adr.id)
            assertFalse(view.canComment || view.canRaiseIssue)
            assertFalse(cast.contributor.client.entry(adr.id, comment.id).canEdit)
            // The moderation path is never closed: the author deletes, leaving the tombstone.
            assertEquals(HttpStatusCode.NoContent, cast.contributor.client.delete("/api/v1/adrs/${adr.id}/discussion/${comment.id}").status)
            assertTrue(cast.informed.client.discussion(adr.id).items.single().deleted)
        }
    }

    @Test
    fun `an entry posted in the pre-active window stays readable after the ADR is active, in order`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        val first = cast.stranger.client.postEntryOk(adr.id, DiscussionKind.COMMENT, "First")
        val second = cast.informed.client.postEntryOk(adr.id, DiscussionKind.QUESTION, "Second")
        cast.activate(adr)
        val issue = cast.stranger.client.postEntryOk(adr.id, DiscussionKind.ISSUE, "Third")
        val all = cast.informed.client.discussion(adr.id)
        assertEquals(3, all.total)
        assertEquals(listOf(first.id, second.id, issue.id), all.items.map { it.id }, "oldest first by default")
        assertEquals(listOf(issue.id, second.id, first.id), cast.informed.client.discussion(adr.id, "?sort=-id").items.map { it.id })
        // Neither the comment nor the question can be edited now: the window moved on.
        assertFalse(cast.stranger.client.discussion(adr.id).items[0].canEdit)
        val own = cast.informed.client.discussion(adr.id).items[1]
        assertTrue(own.canChangeState && !own.canEdit, "the author still settles their question, but cannot edit it")
    }

    @Test
    fun `the state has no window - answered after ACTIVE, resolved after the ADR expired or was superseded`() =
        testApplication {
            usePostgresTestcontainer()
            val cast = adrCast()
            val adr = cast.raise(complete = true)
            val question = cast.stranger.client.postEntryOk(adr.id, DiscussionKind.QUESTION, "Asked while in review")
            cast.activate(adr)
            // Answered right after activation: the question's own window is closed (no edit), its state still moves.
            assertEquals(HttpStatusCode.Conflict, cast.stranger.client.editEntry(adr.id, question.id, "Edited late").status)
            val seen = cast.driver.client.entry(adr.id, question.id)
            assertTrue(seen.canChangeState && !seen.canEdit)
            assertEquals(HttpStatusCode.OK, cast.driver.client.setEntryState(adr.id, question.id, DiscussionStatus.ANSWERED).status)
            assertEquals(0, cast.informed.client.getAdr(adr.id).unansweredQuestionCount)

            val expired = cast.stranger.client.postEntryOk(adr.id, DiscussionKind.ISSUE, "Raised while active")
            val superseded = cast.stranger.client.postEntryOk(adr.id, DiscussionKind.ESCALATION, "Escalated while active")
            assertEquals(2, cast.informed.client.getAdr(adr.id).openIssueCount)
            // The sweep expires the ADR (an injected clock a day past its last day in force); the open issues are still counted.
            val active = cast.driver.client.getAdr(adr.id)
            val last = LocalDate.now(ZoneOffset.UTC).plusDays(30)
            val dated = cast.driver.client.saveAdr(adr.id, active.revision, active.asUpdate().copy(expiresOn = last.toString()))
            assertEquals(HttpStatusCode.NoContent, dated.status)
            val day = last.plusDays(1).atTime(13, 30).toInstant(ZoneOffset.UTC)
            AdrExpirySweep(sharedDatabaseForTests(), AdrEventService(sharedDatabaseForTests()), Clock.fixed(day, ZoneOffset.UTC)).sweep()
            assertEquals(AdrStatus.EXPIRED, cast.informed.client.getAdr(adr.id).status)
            assertEquals(2, cast.informed.client.getAdr(adr.id).openIssueCount, "the open issues keep counting once expired")
            assertTrue(cast.informed.client.entry(adr.id, expired.id).canChangeState.not(), "the informed still cannot")
            val resolved = cast.approver.client.setEntryState(adr.id, expired.id, DiscussionStatus.RESOLVED, "Moot: the decision expired")
            assertEquals(HttpStatusCode.OK, resolved.status)
            assertEquals(1, cast.informed.client.getAdr(adr.id).openIssueCount)
            // The same after a supersede (forced: the successor link arrives with 0.4.0), reopen included.
            forceAdrStatus(adr.id, AdrStatus.SUPERSEDED)
            assertEquals(HttpStatusCode.OK, cast.driver.client.setEntryState(adr.id, superseded.id, DiscussionStatus.RESOLVED).status)
            assertEquals(HttpStatusCode.OK, cast.driver.client.setEntryState(adr.id, expired.id, DiscussionStatus.OPEN).status)
            assertEquals(1, cast.informed.client.getAdr(adr.id).openIssueCount)
            // Posting and editing stay closed in a terminal status.
            assertEquals(HttpStatusCode.Conflict, cast.stranger.client.postEntry(adr.id, DiscussionKind.ISSUE).status)
            assertEquals(HttpStatusCode.Conflict, cast.stranger.client.editEntry(adr.id, expired.id, "Late").status)
        }

    @Test
    fun `the list filters by kind, any-of, and by status, and answers 400 for a bad value`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        val comment = cast.stranger.client.postEntryOk(adr.id, DiscussionKind.COMMENT)
        val open = cast.stranger.client.postEntryOk(adr.id, DiscussionKind.QUESTION)
        val answered = cast.stranger.client.postEntryOk(adr.id, DiscussionKind.QUESTION)
        assertEquals(HttpStatusCode.OK, cast.driver.client.setEntryState(adr.id, answered.id, DiscussionStatus.ANSWERED).status)
        val client = cast.informed.client
        val both = client.discussion(adr.id, "?kind=COMMENT&kind=QUESTION")
        assertEquals(setOf(comment.id, open.id, answered.id), both.items.map { it.id }.toSet())
        assertEquals(listOf(comment.id), client.discussion(adr.id, "?kind=COMMENT").items.map { it.id })
        assertEquals(listOf(open.id), client.discussion(adr.id, "?status=OPEN").items.map { it.id })
        assertEquals(listOf(answered.id), client.discussion(adr.id, "?status=ANSWERED&kind=QUESTION").items.map { it.id })
        assertEquals(0, client.discussion(adr.id, "?status=RESOLVED").total)
        assertEquals(2, client.discussion(adr.id, "?pageSize=2").items.size)
        assertEquals(3, client.discussion(adr.id, "?pageSize=2").total)
        assertEquals(listOf(answered.id), client.discussion(adr.id, "?pageSize=2&page=2").items.map { it.id })
        val tooManyKinds = "?kind=COMMENT&kind=QUESTION&kind=ISSUE&kind=ESCALATION&kind=X"
        for (bad in listOf("?kind=NOPE", "?status=NOPE", tooManyKinds, "?status=OPEN&status=ANSWERED", "?sort=body")) {
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/adrs/${adr.id}/discussion$bad").status, bad)
        }
    }

    @Test
    fun `the author edits the text, the edit is stamped and an unchanged text writes nothing`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        val entry = cast.contributor.client.postEntryOk(adr.id, DiscussionKind.COMMENT, "Original")
        assertNull(entry.editedAt)
        val same = cast.contributor.client.editEntry(adr.id, entry.id, "Original")
        assertEquals(HttpStatusCode.OK, same.status)
        assertNull(same.body<DiscussionEntryResponse>().editedAt, "the same text is not an edit")
        val edited = cast.contributor.client.editEntry(adr.id, entry.id, "Changed *text*\r\nsecond line  \n")
        assertEquals(HttpStatusCode.OK, edited.status)
        val after = edited.body<DiscussionEntryResponse>()
        assertEquals("Changed *text*\nsecond line", after.body)
        assertNotNull(after.editedAt)
        assertEquals(entry.createdAt, after.createdAt)
        // An edit is audit-only: the history holds the post and nothing else.
        val types = cast.driver.client.adrEvents(adr.id).items.map { it.type }
        assertEquals(1, types.count { it == AdrEventType.COMMENTED })
        val aboutEntry = cast.driver.client.adrEvents(adr.id).items.filter { it.params["entryId"] == entry.id.toString() }
        assertEquals(listOf(AdrEventType.COMMENTED), aboutEntry.map { it.type })
    }

    @Test
    fun `a delete leaves a tombstone - the body is gone from the wire and the table - and a second delete is 404`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        val secret = cast.contributor.client.postEntryOk(adr.id, DiscussionKind.QUESTION, "A secret question")
        val before = cast.driver.client.adrEvents(adr.id).total
        assertEquals(HttpStatusCode.NoContent, cast.contributor.client.delete("/api/v1/adrs/${adr.id}/discussion/${secret.id}").status)
        val tombstone = cast.informed.client.discussion(adr.id).items.single()
        assertTrue(tombstone.deleted)
        assertNull(tombstone.body)
        assertNull(tombstone.status, "a tombstone is in no state")
        assertEquals(DiscussionKind.QUESTION, tombstone.kind)
        assertEquals(secret.author, tombstone.author)
        assertEquals(secret.createdAt, tombstone.createdAt)
        assertFalse(tombstone.canEdit || tombstone.canDelete || tombstone.canChangeState)
        assertEquals("", rawEntry(secret.id).body, "the text is wiped from the store too")
        assertEquals(before, cast.driver.client.adrEvents(adr.id).total, "a delete is audit-only, not a history event")
        assertEquals(0, cast.informed.client.discussion(adr.id, "?status=OPEN").total, "no status filter matches a tombstone")
        assertEquals(HttpStatusCode.NotFound, cast.contributor.client.delete("/api/v1/adrs/${adr.id}/discussion/${secret.id}").status)
        assertEquals(HttpStatusCode.NotFound, cast.contributor.client.editEntry(adr.id, secret.id, "Back").status)
        assertEquals(HttpStatusCode.NotFound, cast.driver.client.setEntryState(adr.id, secret.id, DiscussionStatus.ANSWERED).status)
        assertTrue(cast.informed.client.entry(adr.id, secret.id).deleted, "the tombstone still reads")
    }

    @Test
    fun `a question is answered and reopened with its history, a no-op records nothing`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        val question = cast.stranger.client.postEntryOk(adr.id, DiscussionKind.QUESTION, "Why Postgres?")
        val answered = cast.driver.client.setEntryState(adr.id, question.id, DiscussionStatus.ANSWERED)
        assertEquals(HttpStatusCode.OK, answered.status)
        val entry = answered.body<DiscussionEntryResponse>()
        assertEquals(DiscussionStatus.ANSWERED, entry.status)
        assertEquals(cast.driver.id, entry.stateChangedBy?.id)
        assertNotNull(entry.stateChangedAt)
        val total = cast.driver.client.adrEvents(adr.id).total
        assertEquals(HttpStatusCode.OK, cast.driver.client.setEntryState(adr.id, question.id, DiscussionStatus.ANSWERED).status)
        assertEquals(total, cast.driver.client.adrEvents(adr.id).total, "answering an answered question records nothing")
        val reopened = cast.stranger.client.setEntryState(adr.id, question.id, DiscussionStatus.OPEN)
        assertEquals(DiscussionStatus.OPEN, reopened.body<DiscussionEntryResponse>().status, "its author may reopen it")
        val events = cast.driver.client.adrEventsOldestFirst(adr.id).filter { it.params["entryId"] == question.id.toString() }
        val expected = listOf(AdrEventType.QUESTION_ASKED, AdrEventType.QUESTION_ANSWERED, AdrEventType.QUESTION_REOPENED)
        assertEquals(expected, events.map { it.type })
        assertEquals(mapOf("entryId" to question.id.toString(), "kind" to "QUESTION"), events[1].params)
        assertEquals(listOf(cast.stranger.id, cast.driver.id, cast.stranger.id), events.map { it.userId })
    }

    @Test
    fun `an issue is resolved with a note and reopened, and the counts follow`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        cast.activate(adr)
        val issue = cast.stranger.client.postEntryOk(adr.id, DiscussionKind.ISSUE, "It does not scale")
        val escalation = cast.stranger.client.postEntryOk(adr.id, DiscussionKind.ESCALATION, "Production is down")
        assertEquals(2, cast.informed.client.getAdr(adr.id).openIssueCount)
        val resolved = cast.approver.client.setEntryState(adr.id, issue.id, DiscussionStatus.RESOLVED, "Sharded in 2.1 \r\n")
        assertEquals(HttpStatusCode.OK, resolved.status)
        val entry = resolved.body<DiscussionEntryResponse>()
        assertEquals(DiscussionStatus.RESOLVED, entry.status)
        assertEquals("Sharded in 2.1", entry.resolution)
        assertEquals(cast.approver.id, entry.stateChangedBy?.id)
        assertEquals(1, cast.informed.client.getAdr(adr.id).openIssueCount)
        val resolvedList = cast.informed.client.discussion(adr.id, "?status=RESOLVED").items
        assertEquals(listOf(issue.id), resolvedList.map { it.id })
        val reopened = cast.driver.client.setEntryState(adr.id, issue.id, DiscussionStatus.OPEN).body<DiscussionEntryResponse>()
        assertEquals(DiscussionStatus.OPEN, reopened.status)
        assertNull(reopened.resolution, "a reopen clears the note")
        assertEquals(2, cast.informed.client.getAdr(adr.id).openIssueCount)
        assertEquals(HttpStatusCode.OK, cast.admin.client.setEntryState(adr.id, escalation.id, DiscussionStatus.RESOLVED).status)
        val events = cast.driver.client.adrEventsOldestFirst(adr.id).filter { "entryId" in it.params }
        assertEquals(
            listOf(
                AdrEventType.ISSUE_RAISED, AdrEventType.ESCALATED, AdrEventType.ISSUE_RESOLVED,
                AdrEventType.ISSUE_REOPENED, AdrEventType.ISSUE_RESOLVED,
            ),
            events.map { it.type },
        )
        assertEquals("ESCALATION", events.last().params["kind"])
    }

    @Test
    fun `the state must fit the kind - a comment has none, a question is answered, an issue resolved`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        val comment = cast.stranger.client.postEntryOk(adr.id, DiscussionKind.COMMENT)
        val question = cast.stranger.client.postEntryOk(adr.id, DiscussionKind.QUESTION)
        assertEquals(HttpStatusCode.BadRequest, cast.driver.client.setEntryState(adr.id, comment.id, DiscussionStatus.OPEN).status)
        assertEquals(HttpStatusCode.BadRequest, cast.driver.client.setEntryState(adr.id, question.id, DiscussionStatus.RESOLVED).status)
        val longNote = "x".repeat(MAX_DISCUSSION_RESOLUTION_LENGTH + 1)
        val driver = cast.driver.client
        assertEquals(HttpStatusCode.BadRequest, driver.setEntryState(adr.id, question.id, DiscussionStatus.ANSWERED, longNote).status)
        assertEquals(HttpStatusCode.BadRequest, driver.setEntryState(adr.id, question.id, DiscussionStatus.OPEN, "a note").status)
        cast.activate(adr)
        val issue = cast.stranger.client.postEntryOk(adr.id, DiscussionKind.ISSUE)
        assertEquals(HttpStatusCode.BadRequest, cast.driver.client.setEntryState(adr.id, issue.id, DiscussionStatus.ANSWERED).status)
        // The state is not windowed: the question is still answered by the Driver now that the ADR is ACTIVE.
        assertEquals(HttpStatusCode.OK, cast.driver.client.setEntryState(adr.id, question.id, DiscussionStatus.ANSWERED).status)
        val unknown = cast.driver.client.put("/api/v1/adrs/${adr.id}/discussion/${issue.id}/state") {
            contentType(ContentType.Application.Json)
            setBody("""{"status":"WHATEVER"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
    }

    @Test
    fun `the counts ride the ADR - on the detail and the list rows, a deleted or settled entry drops out`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val quiet = cast.raise(complete = true)
        val busy = cast.raise(complete = true)
        cast.stranger.client.postEntryOk(busy.id, DiscussionKind.QUESTION)
        val settled = cast.stranger.client.postEntryOk(busy.id, DiscussionKind.QUESTION)
        val gone = cast.stranger.client.postEntryOk(busy.id, DiscussionKind.QUESTION)
        cast.stranger.client.postEntryOk(busy.id, DiscussionKind.COMMENT)
        cast.driver.client.setEntryState(busy.id, settled.id, DiscussionStatus.ANSWERED)
        cast.stranger.client.delete("/api/v1/adrs/${busy.id}/discussion/${gone.id}")
        val detail = cast.informed.client.getAdr(busy.id)
        assertEquals(1, detail.unansweredQuestionCount)
        assertEquals(0, detail.openIssueCount)
        cast.activate(busy)
        val issue = cast.stranger.client.postEntryOk(busy.id, DiscussionKind.ISSUE)
        cast.stranger.client.postEntryOk(busy.id, DiscussionKind.ESCALATION)
        val dropped = cast.stranger.client.postEntryOk(busy.id, DiscussionKind.ISSUE)
        cast.stranger.client.delete("/api/v1/adrs/${busy.id}/discussion/${dropped.id}")
        val rows = cast.informed.client.get("/api/v1/adrs?pageSize=100").body<AdrPageResponse>().items.associateBy { it.id }
        assertEquals(2, rows.getValue(busy.id).openIssueCount)
        assertEquals(0, rows.getValue(quiet.id).openIssueCount)
        cast.driver.client.setEntryState(busy.id, issue.id, DiscussionStatus.RESOLVED)
        assertEquals(1, cast.informed.client.getAdr(busy.id).openIssueCount)
        assertEquals(1, cast.informed.client.getAdr(busy.id).unansweredQuestionCount, "the question stays counted once active")
    }

    @Test
    fun `the body is trimmed, bounded and clean - blank, too long and control characters are 400`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        val client = cast.stranger.client
        for (bad in listOf("", "   \n\t ", "x".repeat(MAX_DISCUSSION_BODY_LENGTH + 1), "bell\u0007", "bidi‮text")) {
            assertEquals(HttpStatusCode.BadRequest, client.postEntry(adr.id, DiscussionKind.COMMENT, bad).status, "post ${bad.take(12)}")
        }
        val full = client.postEntryOk(adr.id, DiscussionKind.COMMENT, "y".repeat(MAX_DISCUSSION_BODY_LENGTH))
        assertEquals(MAX_DISCUSSION_BODY_LENGTH, full.body?.length)
        assertEquals(HttpStatusCode.BadRequest, client.editEntry(adr.id, full.id, "").status)
        assertEquals(HttpStatusCode.BadRequest, client.editEntry(adr.id, full.id, "z".repeat(MAX_DISCUSSION_BODY_LENGTH + 1)).status)
        val malformed = client.post("/api/v1/adrs/${adr.id}/discussion") {
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"SHOUT","body":"x"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        assertEquals(1, client.discussion(adr.id).total, "a refused post wrote nothing")
    }

    @Test
    fun `the entry writes ride their own body ceilings - the entry text a wide one, the state a small one`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        val client = cast.stranger.client
        // The widest legitimate entries: 10,000 three-byte characters (30 KB), or every unit \u-escaped (60 KB), fit the 64 KiB ceiling.
        val wide = client.postEntryOk(adr.id, DiscussionKind.QUESTION, "…".repeat(MAX_DISCUSSION_BODY_LENGTH))
        assertEquals("…".repeat(MAX_DISCUSSION_BODY_LENGTH), wide.body)
        val escaped = """{"kind":"COMMENT","body":"""" + "\\u2026".repeat(MAX_DISCUSSION_BODY_LENGTH) + "\"}"
        val escapedPost = client.post("/api/v1/adrs/${adr.id}/discussion") { contentType(ContentType.Application.Json); setBody(escaped) }
        assertEquals(HttpStatusCode.Created, escapedPost.status)
        assertEquals("…".repeat(MAX_DISCUSSION_BODY_LENGTH), escapedPost.body<DiscussionEntryResponse>().body)
        val oversize = """{"kind":"COMMENT","body":"""" + "x".repeat(MAX_DISCUSSION_REQUEST_BODY_BYTES.toInt()) + "\"}"
        assertEquals(
            HttpStatusCode.PayloadTooLarge,
            client.post("/api/v1/adrs/${adr.id}/discussion") { contentType(ContentType.Application.Json); setBody(oversize) }.status,
        )
        assertEquals(
            HttpStatusCode.PayloadTooLarge,
            client.put("/api/v1/adrs/${adr.id}/discussion/${wide.id}") {
                contentType(ContentType.Application.Json)
                setBody(oversize)
            }.status,
        )
        val smallOversize = """{"status":"ANSWERED","resolution":"""" + "x".repeat(MAX_SMALL_REQUEST_BODY_BYTES.toInt()) + "\"}"
        assertEquals(
            HttpStatusCode.PayloadTooLarge,
            cast.driver.client.put("/api/v1/adrs/${adr.id}/discussion/${wide.id}/state") {
                contentType(ContentType.Application.Json)
                setBody(smallOversize)
            }.status,
        )
        // 10,000 characters would be refused by the small ceiling: the entry route is not on it.
        assertTrue(MAX_DISCUSSION_BODY_LENGTH * 3 > MAX_SMALL_REQUEST_BODY_BYTES)
    }

    @Test
    fun `deleting a resolved, edited issue wipes the text, the note and the stamps - on the wire and in the table`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        cast.activate(adr)
        val issue = cast.stranger.client.postEntryOk(adr.id, DiscussionKind.ISSUE, "It leaks")
        assertEquals(HttpStatusCode.OK, cast.stranger.client.editEntry(adr.id, issue.id, "It leaks badly").status)
        assertEquals(HttpStatusCode.OK, cast.approver.client.setEntryState(adr.id, issue.id, DiscussionStatus.RESOLVED, "Patched").status)
        val live = rawEntry(issue.id)
        assertEquals("Patched", live.resolution)
        assertNotNull(live.editedAt)
        assertNotNull(live.stateChangedBy)
        assertEquals(HttpStatusCode.NoContent, cast.stranger.client.delete("/api/v1/adrs/${adr.id}/discussion/${issue.id}").status)
        val wire = cast.informed.client.entry(adr.id, issue.id)
        assertTrue(wire.deleted)
        assertNull(wire.body)
        assertNull(wire.status)
        assertNull(wire.resolution)
        assertNull(wire.editedAt)
        assertNull(wire.stateChangedBy)
        assertNull(wire.stateChangedAt)
        val stored = rawEntry(issue.id)
        assertEquals(RawEntryWiped, stored.copy(deletedAt = null))
        assertNotNull(stored.deletedAt)
        // The table itself refuses a half-wiped tombstone: marking a row deleted without wiping it violates the constraint.
        val live2 = cast.stranger.client.postEntryOk(adr.id, DiscussionKind.ISSUE, "Another")
        assertFailsWith<Exception> {
            suspendTransaction(sharedDatabaseForTests()) {
                val t = AdrDiscussionService.AdrDiscussion
                t.update({ t.id eq live2.id }) { it[markedAsDeleted] = true }
            }
        }
        assertEquals("Another", rawEntry(live2.id).body)
    }

    @Test
    fun `a changed note on a resolved entry replaces the note with no event, the same note changes nothing`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        cast.activate(adr)
        val issue = cast.stranger.client.postEntryOk(adr.id, DiscussionKind.ISSUE)
        cast.driver.client.setEntryState(adr.id, issue.id, DiscussionStatus.RESOLVED, "First note")
        val events = cast.driver.client.adrEvents(adr.id).total
        val same = cast.driver.client.setEntryState(adr.id, issue.id, DiscussionStatus.RESOLVED, "First note")
        assertEquals(cast.driver.id, same.body<DiscussionEntryResponse>().stateChangedBy?.id)
        val better = cast.approver.client.setEntryState(adr.id, issue.id, DiscussionStatus.RESOLVED, "Better note")
        val replaced = better.body<DiscussionEntryResponse>()
        assertEquals("Better note", replaced.resolution)
        assertEquals(DiscussionStatus.RESOLVED, replaced.status)
        assertEquals(cast.approver.id, replaced.stateChangedBy?.id)
        val cleared = cast.driver.client.setEntryState(adr.id, issue.id, DiscussionStatus.RESOLVED).body<DiscussionEntryResponse>()
        assertNull(cleared.resolution)
        assertEquals(events, cast.driver.client.adrEvents(adr.id).total, "a note change is not a history event")
    }

    @Test
    fun `discussion writes never bump the ADR revision or its updatedAt`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        val before = cast.driver.client.getAdr(adr.id)
        val question = cast.stranger.client.postEntryOk(adr.id, DiscussionKind.QUESTION)
        assertEquals(HttpStatusCode.OK, cast.stranger.client.editEntry(adr.id, question.id, "Edited").status)
        assertEquals(HttpStatusCode.OK, cast.driver.client.setEntryState(adr.id, question.id, DiscussionStatus.ANSWERED).status)
        assertEquals(HttpStatusCode.NoContent, cast.stranger.client.delete("/api/v1/adrs/${adr.id}/discussion/${question.id}").status)
        val after = cast.driver.client.getAdr(adr.id)
        assertEquals(before.revision, after.revision)
        assertEquals(before.updatedAt, after.updatedAt)
        // So a document edit under the revision read before the discussion still succeeds.
        val save = cast.driver.client.saveAdr(adr.id, before.revision, before.asUpdate().copy(context = "Still fresh"))
        assertEquals(HttpStatusCode.NoContent, save.status)
    }

    @Test
    fun `a missing ADR, an entry of another ADR and a deleted ADR are 404 everywhere`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        val other = cast.raise(complete = true)
        val entry = cast.stranger.client.postEntryOk(adr.id, DiscussionKind.COMMENT)
        val client = cast.driver.client
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/adrs/999999/discussion").status)
        assertEquals(HttpStatusCode.NotFound, client.postEntry(999999u, DiscussionKind.COMMENT).status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/adrs/${adr.id}/discussion/999999").status)
        // The entry exists, but under another ADR's path.
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/adrs/${other.id}/discussion/${entry.id}").status)
        assertEquals(HttpStatusCode.NotFound, cast.stranger.client.editEntry(other.id, entry.id, "x").status)
        assertEquals(HttpStatusCode.NotFound, client.setEntryState(other.id, entry.id, DiscussionStatus.OPEN).status)
        assertEquals(HttpStatusCode.NotFound, cast.stranger.client.delete("/api/v1/adrs/${other.id}/discussion/${entry.id}").status)
        assertEquals(HttpStatusCode.NoContent, cast.admin.client.delete("/api/v1/adrs/${adr.id}").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/adrs/${adr.id}/discussion").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/adrs/${adr.id}/discussion/${entry.id}").status)
        assertEquals(HttpStatusCode.NotFound, client.postEntry(adr.id, DiscussionKind.COMMENT).status)
    }

    @Test
    fun `posting sets Location and answers the entry with the author and the caller's verdicts`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        val response = cast.contributor.client.postEntry(adr.id, DiscussionKind.QUESTION, "Does it work?")
        assertEquals(HttpStatusCode.Created, response.status)
        val entry = response.body<DiscussionEntryResponse>()
        assertEquals("/api/v1/adrs/${adr.id}/discussion/${entry.id}", response.headers[HttpHeaders.Location])
        assertEquals(adr.id, entry.adrId)
        assertEquals(cast.contributor.id, entry.author.id)
        assertEquals("Con", entry.author.name)
        assertFalse(entry.author.deleted)
        assertEquals("Does it work?", entry.body)
        assertTrue(entry.canEdit && entry.canDelete && entry.canChangeState, "the author of a question may do all three")
        assertEquals(entry, cast.contributor.client.entry(adr.id, entry.id))
    }
}

/** What a tombstone holds in the table, but for `deletedAt` (stamped): every wiped column. */
private val RawEntryWiped = RawEntry(null, "", "", null, null, null, deleted = true, deletedAt = null)
