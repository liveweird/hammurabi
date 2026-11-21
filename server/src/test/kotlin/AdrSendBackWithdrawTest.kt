package ch.nokillswit

import ch.nokillswit.adrs.AdrEventResponse
import ch.nokillswit.adrs.AdrEventType
import ch.nokillswit.adrs.AdrPageResponse
import ch.nokillswit.adrs.AdrRef
import ch.nokillswit.adrs.AdrResponse
import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.adrs.DiscussionKind
import ch.nokillswit.adrs.DiscussionStatus
import ch.nokillswit.infra.ProblemTypes
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.plugins.ProblemDetail
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.7.0 "Send back and withdraw": `IN_REVIEW -> SOLUTION_DRAFT` (the Approver or the Driver returns the ADR for rework) and
 * `ACTIVE -> WITHDRAWN` (the Approver or ADMIN revokes the decision), each with an optional reason kept in the `TRANSITIONED` event,
 * and what those moves leave behind (content, link, issues, notices).
 */
class AdrSendBackWithdrawTest {
    private fun transitions(events: List<AdrEventResponse>) = events.filter { it.type == AdrEventType.TRANSITIONED }.map { it.params }

    /** Whether the list, narrowed to [adr] by its key and to [filter], contains it. */
    private suspend fun HttpClient.listed(adr: AdrResponse, filter: String): Boolean {
        val page = get("/api/v1/adrs?pageSize=20&q=${adr.key}&$filter").body<AdrPageResponse>()
        return page.items.any { it.id == adr.id }
    }

    @Test
    fun `the Approver sends an ADR in review back with a reason - it is a draft again and nothing is cleared`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setTags(adr.id, listOf("security")).status)
        cast.driver.client.moveAdrNow(adr.id, AdrStatus.IN_REVIEW)
        val before = cast.driver.client.getAdr(adr.id)
        val back = cast.approver.client.moveAdrNow(adr.id, AdrStatus.SOLUTION_DRAFT, "  Needs the cost\r\nof the second option  ")
        assertEquals(AdrStatus.SOLUTION_DRAFT, back.status)
        assertEquals(before.revision + 1, back.revision)
        assertEquals(before.context, back.context)
        assertEquals(before.decision, back.decision)
        assertEquals(before.consequences, back.consequences)
        assertEquals(before.participants, back.participants, "the people stay")
        assertEquals(listOf("security"), back.tags.map { it.name }, "the tags stay")
        // Pre-active again (content was editable in review already): a Contributor edits the content.
        assertTrue(cast.driver.client.getAdr(adr.id).canEdit)
        val edit = cast.contributor.client.saveAdr(adr.id, back.revision, back.asUpdate().copy(context = "A better context"))
        assertEquals(HttpStatusCode.NoContent, edit.status)
        val last = transitions(cast.admin.client.adrEventsOldestFirst(adr.id)).last()
        assertEquals(mapOf("from" to "IN_REVIEW", "to" to "SOLUTION_DRAFT", "reason" to "Needs the cost\nof the second option"), last)
    }

    @Test
    fun `the Driver may pull it back too, and the next send to review re-validates the required fields`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        cast.driver.client.moveAdrNow(adr.id, AdrStatus.IN_REVIEW)
        val back = cast.driver.client.moveAdrNow(adr.id, AdrStatus.SOLUTION_DRAFT)
        assertEquals(AdrStatus.SOLUTION_DRAFT, back.status)
        assertNull(transitions(cast.admin.client.adrEventsOldestFirst(adr.id)).last()["reason"], "no reason, no key")
        // Emptying a required field while it is a draft again makes the resubmission a 400 naming it.
        val emptied = cast.driver.client.saveAdr(adr.id, back.revision, back.asUpdate().copy(consequences = ""))
        assertEquals(HttpStatusCode.NoContent, emptied.status)
        val refused = cast.driver.client.moveAdr(adr.id, cast.driver.client.getAdr(adr.id).revision, AdrStatus.IN_REVIEW)
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertTrue("consequences" in refused.body<ProblemDetail>().detail.orEmpty())
        assertTrue(AdrStatus.IN_REVIEW in cast.driver.client.getAdr(adr.id).allowedTransitions, "forward again")
    }

    @Test
    fun `who may send back - the Approver, the Driver and ADMIN, nobody else, and a draft cannot be sent back`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        forceAdrStatus(adr.id, AdrStatus.IN_REVIEW)
        for (who in listOf("contributor", "informed", "stranger")) {
            val response = cast.everyone.getValue(who).client.moveAdr(adr.id, adr.revision, AdrStatus.SOLUTION_DRAFT)
            assertEquals(HttpStatusCode.Forbidden, response.status, who)
        }
        for (who in listOf("approver", "driver", "admin")) {
            forceAdrStatus(adr.id, AdrStatus.IN_REVIEW)
            val moved = cast.everyone.getValue(who).client.moveAdrNow(adr.id, AdrStatus.SOLUTION_DRAFT)
            assertEquals(AdrStatus.SOLUTION_DRAFT, moved.status, who)
        }
        // The same target from the first status stays the Driver's alone, and a draft cannot go back to a draft.
        forceAdrStatus(adr.id, AdrStatus.PROBLEM_RAISED)
        assertEquals(HttpStatusCode.Forbidden, cast.approver.client.moveAdr(adr.id, adr.revision, AdrStatus.SOLUTION_DRAFT).status)
        forceAdrStatus(adr.id, AdrStatus.SOLUTION_DRAFT)
        val draft = cast.driver.client.moveAdr(adr.id, cast.driver.client.getAdr(adr.id).revision, AdrStatus.SOLUTION_DRAFT)
        assertEquals(HttpStatusCode.Conflict, draft.status)
        assertEquals(ProblemTypes.ILLEGAL_TRANSITION, draft.body<ProblemDetail>().type)
    }

    @Test
    fun `the Approver withdraws an ACTIVE decision with a reason, it is terminal, the Driver and a Contributor cannot`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        cast.activate(adr)
        val active = cast.approver.client.getAdr(adr.id)
        assertEquals(listOf(AdrStatus.WITHDRAWN), active.allowedTransitions)
        assertEquals(emptyList(), cast.driver.client.getAdr(adr.id).allowedTransitions, "the Driver has no say once it is in force")
        assertEquals(HttpStatusCode.Forbidden, cast.driver.client.moveAdr(adr.id, active.revision, AdrStatus.WITHDRAWN, "no").status)
        assertEquals(HttpStatusCode.Forbidden, cast.contributor.client.moveAdr(adr.id, active.revision, AdrStatus.WITHDRAWN).status)
        assertEquals(AdrStatus.ACTIVE, cast.admin.client.getAdr(adr.id).status, "refused moves write nothing")

        val withdrawn = cast.approver.client.moveAdrNow(adr.id, AdrStatus.WITHDRAWN, "Superseded by the platform standard")
        assertEquals(AdrStatus.WITHDRAWN, withdrawn.status)
        assertEquals(emptyList(), withdrawn.allowedTransitions)
        assertFalse(withdrawn.canEdit)
        assertFalse(withdrawn.canManageParticipants)
        val last = transitions(cast.admin.client.adrEventsOldestFirst(adr.id)).last()
        assertEquals(mapOf("from" to "ACTIVE", "to" to "WITHDRAWN", "reason" to "Superseded by the platform standard"), last)
        // Terminal: nothing moves it and nothing in it is writable.
        assertEquals(HttpStatusCode.Conflict, cast.admin.client.moveAdr(adr.id, withdrawn.revision, AdrStatus.ACTIVE).status)
        val edit = cast.driver.client.saveAdr(adr.id, withdrawn.revision, withdrawn.asUpdate().copy(expiresOn = "2099-01-01"))
        assertEquals(HttpStatusCode.Conflict, edit.status)
    }

    @Test
    fun `ADMIN withdraws an ACTIVE decision`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        cast.activate(adr)
        assertEquals(AdrStatus.WITHDRAWN, cast.admin.client.moveAdrNow(adr.id, AdrStatus.WITHDRAWN).status)
    }

    @Test
    fun `the reason is optional free text of at most 1000 code points, judged after the role and the revision`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        cast.driver.client.moveAdrNow(adr.id, AdrStatus.IN_REVIEW)
        suspend fun revision() = cast.driver.client.getAdr(adr.id).revision
        suspend fun sendBack(reason: String, at: Long? = null) =
            cast.approver.client.moveAdr(adr.id, at ?: revision(), AdrStatus.SOLUTION_DRAFT, reason)
        // 1001 code points, and a control or hidden direction character, are 400s that write nothing.
        assertEquals(HttpStatusCode.BadRequest, sendBack("x".repeat(1001)).status)
        assertEquals(HttpStatusCode.BadRequest, sendBack("a\u0000b").status)
        assertEquals(HttpStatusCode.BadRequest, sendBack("a‮b").status)
        assertEquals(AdrStatus.IN_REVIEW, cast.driver.client.getAdr(adr.id).status)
        // A caller without the role is 403 even with an invalid reason; a stale revision is 409, not 400.
        assertEquals(HttpStatusCode.Forbidden, cast.driver.client.moveAdr(adr.id, revision(), AdrStatus.ACTIVE, "x".repeat(5000)).status)
        val stale = sendBack("x".repeat(5000), at = revision() - 1)
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertEquals(ProblemTypes.REVISION_CONFLICT, stale.body<ProblemDetail>().type)
        // Exactly 1000 code points, supplementary ones included (2000 UTF-16 units), is fine; blank is no reason; a reason rides ANY move.
        val emoji = "😀".repeat(1000)
        assertEquals(AdrStatus.SOLUTION_DRAFT, cast.approver.client.moveAdrNow(adr.id, AdrStatus.SOLUTION_DRAFT, emoji).status)
        cast.driver.client.moveAdrNow(adr.id, AdrStatus.IN_REVIEW, " \n\t ")
        cast.approver.client.moveAdrNow(adr.id, AdrStatus.ACTIVE, "Looks good")
        val moves = transitions(cast.admin.client.adrEventsOldestFirst(adr.id))
        assertEquals(emoji, moves[moves.size - 3]["reason"])
        assertNull(moves[moves.size - 2]["reason"], "a blank reason is none")
        assertEquals("Looks good", moves.last()["reason"])
    }

    @Test
    fun `the audit line says a reason was given, never what it was`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        cast.driver.client.moveAdrNow(adr.id, AdrStatus.IN_REVIEW)
        withAuditCapture { capture ->
            cast.approver.client.moveAdrNow(adr.id, AdrStatus.SOLUTION_DRAFT, "top-secret-reason")
            val line = capture.awaitEvent { it.message == "adr.transitioned" && it.hasKeyValue("adrId", adr.id.toLong()) }
            assertNotNull(line)
            assertTrue(line.hasKeyValue("from", "IN_REVIEW") && line.hasKeyValue("to", "SOLUTION_DRAFT"))
            assertTrue(line.hasKeyValue("reasoned", true))
            assertTrue(capture.events.none { e -> e.keyValuePairs.orEmpty().any { "top-secret" in it.value.toString() } })
        }
    }

    @Test
    fun `issues stay resolvable on a withdrawn decision, as on an expired one`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        cast.activate(adr)
        val issue = cast.stranger.client.postEntryOk(adr.id, DiscussionKind.ISSUE)
        cast.approver.client.moveAdrNow(adr.id, AdrStatus.WITHDRAWN, "Revoked")
        assertEquals(1, cast.stranger.client.getAdr(adr.id).openIssueCount)
        assertEquals(HttpStatusCode.OK, cast.driver.client.setEntryState(adr.id, issue.id, DiscussionStatus.RESOLVED, "Moot now").status)
        assertEquals(0, cast.stranger.client.getAdr(adr.id).openIssueCount)
        // New issues are not accepted: the window is the ACTIVE status.
        assertEquals(HttpStatusCode.Conflict, cast.stranger.client.postEntry(adr.id, DiscussionKind.ISSUE).status)
    }

    @Test
    fun `a pending successor of a withdrawn decision cannot be activated until the link is removed`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val old = cast.raise(complete = true)
        cast.activate(old)
        val next = cast.raise(complete = true)
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(next.id, old.id).status)
        cast.driver.client.moveAdrNow(next.id, AdrStatus.IN_REVIEW)
        cast.approver.client.moveAdrNow(old.id, AdrStatus.WITHDRAWN, "Revoked")
        val refused = cast.approver.client.moveAdr(next.id, cast.approver.client.getAdr(next.id).revision, AdrStatus.ACTIVE)
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        val detail = refused.body<ProblemDetail>().detail.orEmpty()
        assertTrue(old.key in detail && "WITHDRAWN" in detail && "unlink" in detail, detail)
        assertEquals(AdrStatus.WITHDRAWN, cast.admin.client.getAdr(old.id).status, "nothing was superseded")
        // The predecessor is terminal, so its owners cannot detach; the successor's Driver removes the link and activation goes through.
        assertFalse(cast.approver.client.getAdr(old.id).canDetachSuccessor)
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(next.id, null).status)
        assertEquals(AdrStatus.ACTIVE, cast.approver.client.moveAdrNow(next.id, AdrStatus.ACTIVE).status)
        assertEquals(AdrStatus.WITHDRAWN, cast.admin.client.getAdr(old.id).status)
    }

    @Test
    fun `withdrawing a successor that took effect leaves its predecessor superseded and still pointing at it`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val old = cast.raise(complete = true)
        cast.activate(old)
        val next = cast.raise(complete = true)
        cast.driver.client.setSupersedes(next.id, old.id)
        cast.activate(next)
        assertEquals(AdrStatus.SUPERSEDED, cast.admin.client.getAdr(old.id).status)
        val withdrawn = cast.approver.client.moveAdrNow(next.id, AdrStatus.WITHDRAWN, "Revoked")
        val predecessor = cast.stranger.client.getAdr(old.id)
        assertEquals(AdrStatus.SUPERSEDED, predecessor.status, "withdrawing does not resurrect what it replaced")
        assertEquals(AdrRef(next.id, next.key, next.name, AdrStatus.WITHDRAWN), predecessor.supersededBy)
        assertEquals(old.key, withdrawn.supersedes?.key)
        assertTrue(cast.stranger.client.listed(old, "supersededBy=true"), "the list filter still finds the superseded predecessor")
    }

    @Test
    fun `a successor withdrawn before it activated does not count as the replacement`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val old = cast.raise(complete = true)
        cast.activate(old)
        val next = cast.raise(complete = true)
        cast.driver.client.setSupersedes(next.id, old.id)
        cast.driver.client.moveAdrNow(next.id, AdrStatus.IN_REVIEW)
        cast.approver.client.moveAdrNow(next.id, AdrStatus.WITHDRAWN)
        assertNull(cast.stranger.client.getAdr(old.id).supersededBy)
        assertFalse(cast.stranger.client.listed(old, "supersededBy=true"))
        assertTrue(cast.stranger.client.listed(old, "supersededBy=false"))
    }

    @Test
    fun `a send back and a withdrawal notify every participant but the actor, with a word and never the reason`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        cast.driver.client.moveAdrNow(adr.id, AdrStatus.IN_REVIEW)
        cast.approver.client.moveAdrNow(adr.id, AdrStatus.SOLUTION_DRAFT, "private reasoning")
        for (who in listOf(cast.driver, cast.contributor, cast.informed)) {
            assertEquals("SENT_BACK", who.client.noticesOf(NotificationType.STATUS_CHANGED, adr.id).first().detail, "newest first")
        }
        assertTrue(
            cast.approver.client.noticesOf(NotificationType.STATUS_CHANGED, adr.id).none { it.detail == "SENT_BACK" },
            "the actor is not told",
        )
        // The forward move into review and the withdrawal are plain status words.
        cast.driver.client.moveAdrNow(adr.id, AdrStatus.IN_REVIEW)
        cast.approver.client.moveAdrNow(adr.id, AdrStatus.ACTIVE)
        cast.approver.client.moveAdrNow(adr.id, AdrStatus.WITHDRAWN, "private reasoning")
        for (who in listOf(cast.driver, cast.contributor, cast.informed)) {
            assertEquals("WITHDRAWN", who.client.noticesOf(NotificationType.STATUS_CHANGED, adr.id).first().detail)
        }
        assertTrue(rawNotices(cast.informed.id).none { "private" in it.detail.orEmpty() })
    }
}
