package ch.nokillswit

import ch.nokillswit.adrs.AdrEventType
import ch.nokillswit.adrs.AdrParticipantRequest
import ch.nokillswit.adrs.AdrRef
import ch.nokillswit.adrs.AdrResponse
import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.infra.ProblemTypes
import ch.nokillswit.plugins.ProblemDetail
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Successor links (V14): `PUT /adrs/{id}/supersedes` on the SUCCESSOR while it is pre-active, the checks on the predecessor, and the
 * activation that supersedes it in the same transaction.
 */
class AdrSuccessorTest {

    private fun AdrResponse.ref() = AdrRef(id, key, name, status)

    /** An ACTIVE ADR (the predecessor) of the cast. */
    private suspend fun AdrCast.activeAdr(): AdrResponse {
        val adr = raise(complete = true)
        activate(adr)
        return driver.client.getAdr(adr.id)
    }

    @Test
    fun `a draft is linked to an ACTIVE ADR - both ends show it, the predecessor stays ACTIVE until the successor is`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val old = cast.activeAdr()
        val draft = cast.raise(complete = true)
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(draft.id, old.id).status)
        val successor = cast.stranger.client.getAdr(draft.id)
        assertEquals(old.ref(), successor.supersedes)
        assertNull(successor.supersededBy)
        assertEquals(draft.revision + 1, successor.revision, "a link bumps the successor's revision")
        val predecessor = cast.stranger.client.getAdr(old.id)
        assertEquals(AdrRef(draft.id, draft.key, draft.name, AdrStatus.PROBLEM_RAISED), predecessor.supersededBy, "the pending successor")
        assertEquals(AdrStatus.ACTIVE, predecessor.status)
        assertEquals(old.revision, predecessor.revision, "the predecessor's document is untouched by a link")
        val event = cast.driver.client.adrEventsOldestFirst(draft.id).last { it.type == AdrEventType.SUCCESSOR_LINKED }
        assertEquals(mapOf("predecessorId" to old.id.toString(), "key" to old.key, "name" to old.name), event.params)
        assertEquals(cast.driver.id, event.userId)
    }

    @Test
    fun `activating the successor supersedes the predecessor in the same transaction with both events`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val old = cast.activeAdr()
        val next = cast.raise(complete = true)
        cast.driver.client.setSupersedes(next.id, old.id)
        cast.approver.client.acceptSuccessorOk(old.id, next.id)
        cast.driver.client.moveAdrNow(next.id, AdrStatus.IN_REVIEW)
        withAuditCapture { capture ->
            val activated = cast.approver.client.moveAdrNow(next.id, AdrStatus.ACTIVE)
            assertEquals(AdrStatus.ACTIVE, activated.status)
            val replaced = cast.stranger.client.getAdr(old.id)
            assertEquals(AdrStatus.SUPERSEDED, replaced.status)
            assertEquals(old.revision + 1, replaced.revision)
            assertTrue(replaced.statusChangedAt >= old.statusChangedAt)
            assertEquals(AdrRef(next.id, next.key, next.name, AdrStatus.ACTIVE), replaced.supersededBy)
            assertEquals(old.ref().copy(status = AdrStatus.SUPERSEDED), cast.stranger.client.getAdr(next.id).supersedes)
            assertEquals(emptyList(), replaced.allowedTransitions)
            val superseded = cast.driver.client.adrEventsOldestFirst(old.id).last()
            assertEquals(AdrEventType.SUPERSEDED, superseded.type)
            assertEquals(mapOf("successorId" to next.id.toString(), "key" to next.key, "name" to next.name), superseded.params)
            assertEquals(cast.approver.id, superseded.userId, "the activator is the actor")
            val successorEvents = cast.driver.client.adrEventsOldestFirst(next.id).takeLast(2)
            assertEquals(listOf(AdrEventType.TRANSITIONED, AdrEventType.SUCCESSOR_ACTIVATED), successorEvents.map { it.type })
            assertEquals(mapOf("predecessorId" to old.id.toString(), "key" to old.key, "name" to old.name), successorEvents.last().params)
            val audit = capture.awaitEvent { it.message == "adr.superseded" && it.hasKeyValue("adrId", old.id.toLong()) }
            assertNotNull(audit, "the superseded predecessor audits")
            assertTrue(audit.hasKeyValue("successorId", next.id.toLong()) && audit.hasKeyValue("revision", old.revision + 1))
        }
        // The superseded ADR is terminal: read-only for everyone.
        val frozen = cast.admin.client.setTags(old.id, emptyList())
        assertEquals(ProblemTypes.ADR_FROZEN, frozen.body<ProblemDetail>().type)
        val again = cast.admin.client.moveAdr(old.id, cast.admin.client.getAdr(old.id).revision, AdrStatus.ACTIVE)
        assertEquals(HttpStatusCode.Conflict, again.status, "nothing leaves SUPERSEDED")
    }

    @Test
    fun `an ADR without a link activates as before and moves nothing else`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val bystander = cast.activeAdr()
        val adr = cast.raise(complete = true)
        cast.activate(adr)
        val after = cast.driver.client.getAdr(bystander.id)
        assertEquals(AdrStatus.ACTIVE, after.status)
        assertEquals(bystander.revision, after.revision)
        assertNull(after.supersedes)
        assertNull(after.supersededBy)
    }

    @Test
    fun `an unlink and a re-link leave their events and the same link again is a no-op`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val first = cast.activeAdr()
        val second = cast.activeAdr()
        val draft = cast.raise()
        cast.driver.client.setSupersedes(draft.id, first.id)
        val linked = cast.driver.client.getAdr(draft.id)
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(draft.id, first.id).status, "the same link again")
        assertEquals(linked.revision, cast.driver.client.getAdr(draft.id).revision)
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(draft.id, second.id).status, "a re-link")
        assertEquals(second.ref(), cast.driver.client.getAdr(draft.id).supersedes)
        assertNull(cast.driver.client.getAdr(first.id).supersededBy, "the old end is free again")
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(draft.id, null).status, "an unlink")
        assertNull(cast.driver.client.getAdr(draft.id).supersedes)
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(draft.id, null).status, "nothing to unlink")
        val types = cast.driver.client.adrEventsOldestFirst(draft.id).map { it.type }.filter { it.name.startsWith("SUCCESSOR_") }
        assertEquals(
            listOf(AdrEventType.SUCCESSOR_LINKED, AdrEventType.SUCCESSOR_UNLINKED).let { it + it },
            types,
        )
        assertNull(rawSupersedesId(draft.id))
    }

    @Test
    fun `the predecessor must be another live ADR that is not terminal`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val draft = cast.raise()
        suspend fun link(target: UInt) = cast.driver.client.setSupersedes(draft.id, target).status
        assertEquals(HttpStatusCode.BadRequest, link(draft.id), "not itself")
        assertEquals(HttpStatusCode.BadRequest, link(4_000_000u), "an unknown ADR")
        val deleted = cast.raise()
        cast.admin.client.delete("/api/v1/adrs/${deleted.id}")
        assertEquals(HttpStatusCode.BadRequest, link(deleted.id), "a deleted ADR")
        for (terminal in listOf(AdrStatus.CANCELLED, AdrStatus.WITHDRAWN, AdrStatus.EXPIRED, AdrStatus.SUPERSEDED)) {
            val gone = cast.raise()
            forceAdrStatus(gone.id, terminal)
            assertEquals(HttpStatusCode.BadRequest, link(gone.id), "a $terminal predecessor has nothing left to replace")
        }
        val inReview = cast.raise(complete = true)
        cast.driver.client.moveAdrNow(inReview.id, AdrStatus.IN_REVIEW)
        assertEquals(HttpStatusCode.NoContent, link(inReview.id), "a pre-active predecessor may be linked")
        assertNull(rawSupersedesId(deleted.id))
    }

    @Test
    fun `a link that would close a cycle is a 409 naming it`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val a = cast.raise()
        val b = cast.raise()
        val c = cast.raise()
        // b replaces a, c replaces b: a <- b <- c. Making a replace c would close the loop.
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(b.id, a.id).status)
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(c.id, b.id).status)
        val direct = cast.driver.client.setSupersedes(a.id, b.id)
        assertEquals(HttpStatusCode.Conflict, direct.status, "a two-ADR loop")
        val long = cast.driver.client.setSupersedes(a.id, c.id)
        assertEquals(HttpStatusCode.Conflict, long.status, "a three-ADR loop")
        val detail = long.body<ProblemDetail>().detail!!
        assertTrue(detail.contains("cycle") && listOf(a, b, c).all { it.key in detail }, detail)
        assertNull(rawSupersedesId(a.id))
    }

    @Test
    fun `a cancelled link in the middle of a chain no longer counts toward a cycle`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val a = cast.raise()
        val b = cast.raise()
        val c = cast.raise()
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(b.id, a.id).status)
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(c.id, b.id).status)
        assertEquals(HttpStatusCode.Conflict, cast.driver.client.setSupersedes(a.id, c.id).status, "while b is alive the loop is real")
        cast.driver.client.moveAdrNow(b.id, AdrStatus.CANCELLED)
        // b never replaced a, so c -> b -> (nothing): a may now replace c.
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(a.id, c.id).status)
        assertEquals(c.id, rawSupersedesId(a.id))
    }

    @Test
    fun `unlinking a predecessor that was soft-deleted meanwhile still records the unlink`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val old = cast.activeAdr()
        val draft = cast.raise()
        cast.driver.client.setSupersedes(draft.id, old.id)
        assertEquals(HttpStatusCode.NoContent, cast.admin.client.delete("/api/v1/adrs/${old.id}").status)
        assertNull(cast.driver.client.getAdr(draft.id).supersedes, "a deleted predecessor reads as no link")
        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(draft.id, null).status)
            val unlinked = cast.driver.client.adrEventsOldestFirst(draft.id).last()
            assertEquals(AdrEventType.SUCCESSOR_UNLINKED, unlinked.type, "the old end is resolved without the live filter")
            assertEquals(mapOf("predecessorId" to old.id.toString(), "key" to old.key, "name" to old.name), unlinked.params)
            val audit = capture.awaitEvent { it.message == "adr.successor_changed" && it.hasKeyValue("adrId", draft.id.toLong()) }
            assertNotNull(audit)
            assertTrue(audit.hasKeyValue("supersedesFrom", old.id.toLong()) && audit.hasKeyValue("supersedesTo", null))
        }
        assertNull(rawSupersedesId(draft.id))
    }

    @Test
    fun `a predecessor has one successor in play - a second is 409 until the first ends`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val old = cast.activeAdr()
        val first = cast.raise()
        val second = cast.raise()
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(first.id, old.id).status)
        val rival = cast.driver.client.setSupersedes(second.id, old.id)
        assertEquals(HttpStatusCode.Conflict, rival.status)
        assertTrue(rival.body<ProblemDetail>().detail!!.contains(first.key))
        cast.driver.client.moveAdrNow(first.id, AdrStatus.CANCELLED)
        assertNull(cast.driver.client.getAdr(old.id).supersededBy, "a cancelled successor never replaced anything")
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(second.id, old.id).status, "free again")
        assertEquals(second.ref().copy(status = AdrStatus.PROBLEM_RAISED), cast.driver.client.getAdr(old.id).supersededBy)
    }

    @Test
    fun `parallel links to one predecessor - exactly one wins and nobody sees a 500`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val old = cast.activeAdr()
        val drafts = List(5) { cast.raise() }
        val results = coroutineScope {
            drafts.map { draft -> async(Dispatchers.IO) { cast.driver.client.setSupersedes(draft.id, old.id).status } }.awaitAll()
        }
        assertEquals(1, results.count { it == HttpStatusCode.NoContent }, "$results")
        assertEquals(4, results.count { it == HttpStatusCode.Conflict }, "$results")
    }

    @Test
    fun `parallel opposite links never close a cycle`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val a = cast.raise()
        val b = cast.raise()
        val results = coroutineScope {
            listOf(
                async(Dispatchers.IO) { cast.driver.client.setSupersedes(a.id, b.id).status },
                async(Dispatchers.IO) { cast.driver.client.setSupersedes(b.id, a.id).status },
            ).awaitAll()
        }
        assertEquals(1, results.count { it == HttpStatusCode.NoContent }, "$results")
        assertEquals(1, results.count { it == HttpStatusCode.Conflict }, "$results")
        assertTrue(rawSupersedesId(a.id) == null || rawSupersedesId(b.id) == null)
    }

    @Test
    fun `who may link - the Driver, the Approver and ADMIN of the successor, and a 403 beats a bad body`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val old = cast.activeAdr()
        val draft = cast.raise()
        val allowed = setOf("admin", "driver", "approver")
        for ((who, actor) in cast.everyone) {
            val expected = if (who in allowed) HttpStatusCode.NoContent else HttpStatusCode.Forbidden
            assertEquals(expected, actor.client.setSupersedes(draft.id, old.id).status, who)
            cast.admin.client.setSupersedes(draft.id, null)
            assertEquals(who in allowed, actor.client.getAdr(draft.id).canEditSuccessor, "$who canEditSuccessor")
        }
        val malformed = cast.contributor.client.put("/api/v1/adrs/${draft.id}/supersedes") {
            contentType(ContentType.Application.Json)
            setBody("{")
        }
        assertEquals(HttpStatusCode.Forbidden, malformed.status)
        assertEquals(HttpStatusCode.NotFound, cast.admin.client.setSupersedes(4_000_000u, old.id, revision = 1).status)
        // The predecessor's roles do not matter: the link is the successor's.
        val strangerOfOld = adrActor("alien")
        val theirs = strangerOfOld.client.createAdr()
        assertEquals(HttpStatusCode.NoContent, strangerOfOld.client.setSupersedes(theirs.id, old.id).status)
    }

    @Test
    fun `the link is set before the decision is in force - ACTIVE and terminal ADRs are frozen, a stale save is a 409`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val old = cast.activeAdr()
        val draft = cast.raise(complete = true)
        val stale = cast.driver.client.setSupersedes(draft.id, old.id, revision = draft.revision + 5)
        assertEquals(ProblemTypes.REVISION_CONFLICT, stale.body<ProblemDetail>().type)
        cast.activate(draft)
        val active = cast.driver.client.setSupersedes(draft.id, old.id)
        assertEquals(HttpStatusCode.Conflict, active.status)
        assertEquals(ProblemTypes.ADR_FROZEN, active.body<ProblemDetail>().type)
        forceAdrStatus(draft.id, AdrStatus.EXPIRED)
        assertEquals(ProblemTypes.ADR_FROZEN, cast.driver.client.setSupersedes(draft.id, null).body<ProblemDetail>().type)
        assertEquals(false, cast.admin.client.getAdr(draft.id).canEditSuccessor)
    }

    @Test
    fun `a successor cannot be activated while the ADR it replaces is not ACTIVE - unlink it and it goes through`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val old = cast.raise(complete = true)
        val next = cast.raise(complete = true)
        cast.driver.client.setSupersedes(next.id, old.id)
        cast.driver.client.moveAdrNow(next.id, AdrStatus.IN_REVIEW)
        // The predecessor is still a draft: there is nothing in force to replace.
        val blocked = cast.approver.client.moveAdr(next.id, cast.approver.client.getAdr(next.id).revision, AdrStatus.ACTIVE)
        assertEquals(HttpStatusCode.BadRequest, blocked.status)
        assertTrue(blocked.body<ProblemDetail>().detail!!.contains("supersedes ${old.key}"))
        assertEquals(AdrStatus.IN_REVIEW, cast.driver.client.getAdr(next.id).status)
        // Once the predecessor is in force the link still needs its Approver's acceptance; then the same move supersedes it.
        cast.activate(old)
        val unaccepted = cast.approver.client.moveAdr(next.id, cast.approver.client.getAdr(next.id).revision, AdrStatus.ACTIVE)
        assertEquals(ProblemTypes.SUCCESSOR_NOT_ACCEPTED, unaccepted.body<ProblemDetail>().type)
        cast.approver.client.acceptSuccessorOk(old.id, next.id)
        cast.approver.client.moveAdrNow(next.id, AdrStatus.ACTIVE)
        assertEquals(AdrStatus.SUPERSEDED, cast.driver.client.getAdr(old.id).status)
    }

    @Test
    fun `a predecessor that expired meanwhile blocks the activation until the link is removed - then it stays EXPIRED`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val old = cast.activeAdr()
        val next = cast.raise(complete = true)
        cast.driver.client.setSupersedes(next.id, old.id)
        cast.driver.client.moveAdrNow(next.id, AdrStatus.IN_REVIEW)
        forceAdrStatus(old.id, AdrStatus.EXPIRED)
        val blocked = cast.approver.client.moveAdr(next.id, cast.approver.client.getAdr(next.id).revision, AdrStatus.ACTIVE)
        assertEquals(HttpStatusCode.BadRequest, blocked.status)
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(next.id, null).status)
        cast.approver.client.moveAdrNow(next.id, AdrStatus.ACTIVE)
        assertEquals("EXPIRED", rawAdrStatus(old.id))
    }

    @Test
    fun `a predecessor deleted meanwhile reads as no link and the activation goes through`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val old = cast.activeAdr()
        val next = cast.raise(complete = true)
        cast.driver.client.setSupersedes(next.id, old.id)
        cast.driver.client.moveAdrNow(next.id, AdrStatus.IN_REVIEW)
        assertEquals(HttpStatusCode.NoContent, cast.admin.client.delete("/api/v1/adrs/${old.id}").status)
        assertNull(cast.driver.client.getAdr(next.id).supersedes)
        assertEquals(AdrStatus.ACTIVE, cast.approver.client.moveAdrNow(next.id, AdrStatus.ACTIVE).status)
        assertEquals("ACTIVE", rawAdrStatus(old.id), "a deleted ADR is left alone")
    }

    @Test
    fun `an activation racing writes on the predecessor never deadlocks`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        repeat(3) {
            val old = cast.activeAdr()
            val next = cast.raise(complete = true)
            cast.driver.client.setSupersedes(next.id, old.id)
            cast.approver.client.acceptSuccessorOk(old.id, next.id)
            cast.driver.client.moveAdrNow(next.id, AdrStatus.IN_REVIEW)
            val revision = cast.approver.client.getAdr(next.id).revision
            val same = old.participants.map { AdrParticipantRequest(it.userId, it.role) }
            val results = coroutineScope {
                listOf(
                    async(Dispatchers.IO) { cast.approver.client.moveAdr(next.id, revision, AdrStatus.ACTIVE).status },
                    async(Dispatchers.IO) { cast.driver.client.setTags(old.id, emptyList()).status },
                    async(Dispatchers.IO) { cast.driver.client.setParticipants(old.id, same).status },
                ).awaitAll()
            }
            assertEquals(HttpStatusCode.OK, results[0], "the activation wins: $results")
            assertTrue(results.drop(1).all { it == HttpStatusCode.NoContent || it == HttpStatusCode.Conflict }, "$results")
            assertEquals("SUPERSEDED", rawAdrStatus(old.id))
        }
    }

    @Test
    fun `a link change audits and a refused one does not`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val old = cast.activeAdr()
        val draft = cast.raise()
        withAuditCapture { capture ->
            cast.driver.client.setSupersedes(draft.id, old.id)
            val event = capture.awaitEvent { it.message == "adr.successor_changed" && it.hasKeyValue("adrId", draft.id.toLong()) }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("supersedesTo", old.id.toLong()) && event.hasKeyValue("supersedesFrom", null))
            cast.driver.client.setSupersedes(draft.id, draft.id)
            kotlinx.coroutines.delay(200)
            assertEquals(1, capture.events.count { it.message == "adr.successor_changed" && it.hasKeyValue("adrId", draft.id.toLong()) })
        }
    }
}
