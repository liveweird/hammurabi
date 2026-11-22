package ch.nokillswit

import ch.nokillswit.adrs.AdrEventType
import ch.nokillswit.adrs.AdrResponse
import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.infra.ProblemTypes
import ch.nokillswit.plugins.ProblemDetail
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Detaching a pending successor (`DELETE /adrs/{id}/successor`, 0.4.0): the predecessor's owner frees it from a link set by somebody
 * who holds no role on it, and the activation chain's locking. Linking stays the successor's write (`AdrSuccessorTest`).
 */
class AdrSuccessorDetachTest {

    private suspend fun AdrCast.activeAdr(): AdrResponse {
        val adr = raise(complete = true)
        activate(adr)
        return driver.client.getAdr(adr.id)
    }

    @Test
    fun `the predecessor's owner detaches a successor a stranger parked on it - both sides get the event, the revision bumps`() =
        testApplication {
            usePostgresTestcontainer()
            val cast = adrCast()
            val squatter = adrActor("squat")
            val old = cast.activeAdr()
            val parked = squatter.client.createAdr(complete = true)
            val link = squatter.client.setSupersedes(parked.id, old.id)
            assertEquals(HttpStatusCode.NoContent, link.status, "linking needs no role on the predecessor")
            val linked = squatter.client.getAdr(parked.id)
            withAuditCapture { capture ->
                assertEquals(HttpStatusCode.NoContent, cast.driver.client.detachSuccessor(old.id).status)
                val audit = capture.awaitEvent { it.message == "adr.successor_detached" && it.hasKeyValue("adrId", old.id.toLong()) }
                assertNotNull(audit)
                assertTrue(audit.hasKeyValue("successorId", parked.id.toLong()))
                assertTrue(audit.hasKeyValue("successorRevision", linked.revision + 1))
                assertTrue(audit.hasKeyValue("byUserId", cast.driver.id.toLong()))
            }
            val freed = squatter.client.getAdr(parked.id)
            assertNull(freed.supersedes)
            assertEquals(linked.revision + 1, freed.revision, "the successor's open editors now conflict")
            val predecessor = cast.stranger.client.getAdr(old.id)
            assertNull(predecessor.supersededBy)
            assertEquals(old.revision, predecessor.revision, "the predecessor's document is untouched")
            assertEquals(AdrStatus.ACTIVE, predecessor.status)
            assertNull(rawSupersedesId(parked.id))
            val onSuccessor = squatter.client.adrEventsOldestFirst(parked.id).last()
            assertEquals(AdrEventType.SUCCESSOR_UNLINKED, onSuccessor.type)
            assertEquals(mapOf("predecessorId" to old.id.toString(), "key" to old.key, "name" to old.name), onSuccessor.params)
            assertEquals(cast.driver.id, onSuccessor.actorId, "the detaching owner is the actor")
            val onPredecessor = cast.driver.client.adrEventsOldestFirst(old.id).last()
            assertEquals(AdrEventType.SUCCESSOR_UNLINKED, onPredecessor.type)
            assertEquals(mapOf("successorId" to parked.id.toString(), "key" to parked.key, "name" to parked.name), onPredecessor.params)
            assertEquals(cast.driver.id, onPredecessor.actorId)
            // The squatter's editor, opened before the detach, is stale; the predecessor is free for an honest successor.
            val stale = squatter.client.setSupersedes(parked.id, old.id, revision = linked.revision)
            assertEquals(ProblemTypes.REVISION_CONFLICT, stale.body<ProblemDetail>().type)
            val honest = cast.raise()
            assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(honest.id, old.id).status)
            assertEquals(honest.id, cast.stranger.client.getAdr(old.id).supersededBy?.id)
        }

    @Test
    fun `a detach naming the current pending successor is accepted and frees the link`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val old = cast.activeAdr()
        val pending = cast.raise(complete = true)
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(pending.id, old.id).status)
        assertEquals(pending.id, cast.driver.client.getAdr(old.id).supersededBy?.id)

        val detached = cast.driver.client.delete("/api/v1/adrs/${old.id}/successor?successorId=${pending.id}")
        assertEquals(HttpStatusCode.NoContent, detached.status)
        assertNull(cast.driver.client.getAdr(old.id).supersededBy)
        assertNull(rawSupersedesId(pending.id))
    }

    @Test
    fun `a detach naming a stale successor cannot detach its replacement`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val old = cast.activeAdr()
        val first = cast.raise(complete = true)
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(first.id, old.id).status)
        val capturedSuccessorId = cast.driver.client.getAdr(old.id).supersededBy?.id
        assertEquals(first.id, capturedSuccessorId)

        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(first.id, null).status)
        val replacement = cast.raise(complete = true)
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(replacement.id, old.id).status)

        val staleDetach = cast.driver.client.delete("/api/v1/adrs/${old.id}/successor?successorId=$capturedSuccessorId")
        assertEquals(HttpStatusCode.NotFound, staleDetach.status)
        assertEquals(replacement.id, cast.stranger.client.getAdr(old.id).supersededBy?.id)
        assertEquals(old.id, rawSupersedesId(replacement.id), "the replacement link is left alone")
    }

    @Test
    fun `who may detach - the predecessor's Driver, Approver and ADMIN, never the successor's owner or anyone else`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val squatter = adrActor("squat")
        val old = cast.activeAdr()
        val parked = squatter.client.createAdr(complete = true)
        squatter.client.setSupersedes(parked.id, old.id)
        val allowed = setOf("admin", "driver", "approver")
        for ((who, actor) in cast.everyone) {
            assertEquals(who in allowed, actor.client.getAdr(old.id).canDetachSuccessor, "$who canDetachSuccessor")
        }
        assertFalse(squatter.client.getAdr(old.id).canDetachSuccessor, "the squatter holds no role on the predecessor")
        assertFalse(squatter.client.getAdr(parked.id).canDetachSuccessor, "the verdict is the predecessor's, not the successor's")
        for ((who, actor) in cast.everyone.filterKeys { it !in allowed }) {
            assertEquals(HttpStatusCode.Forbidden, actor.client.detachSuccessor(old.id).status, who)
        }
        assertEquals(HttpStatusCode.Forbidden, squatter.client.detachSuccessor(old.id).status, "linking is theirs, detaching is not")
        assertEquals(parked.id, cast.stranger.client.getAdr(old.id).supersededBy?.id, "refused detaches change nothing")
        assertEquals(HttpStatusCode.NotFound, cast.admin.client.detachSuccessor(4_000_000u).status)
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().delete("/api/v1/adrs/${old.id}/successor").status)
        assertEquals(HttpStatusCode.NoContent, cast.approver.client.detachSuccessor(old.id).status)
        assertEquals(HttpStatusCode.NotFound, cast.admin.client.detachSuccessor(old.id).status, "nothing pending any more")
    }

    @Test
    fun `a pre-active predecessor is detachable too, a terminal one and a successor that ended are not`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val old = cast.raise(complete = true)
        val next = cast.raise(complete = true)
        cast.driver.client.setSupersedes(next.id, old.id)
        assertTrue(cast.driver.client.getAdr(old.id).canDetachSuccessor, "a draft predecessor with a pending successor")
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.detachSuccessor(old.id).status)
        // A cancelled successor never was pending: nothing to detach, and the predecessor says so.
        cast.driver.client.setSupersedes(next.id, old.id)
        cast.driver.client.moveAdrNow(next.id, AdrStatus.CANCELLED)
        assertFalse(cast.driver.client.getAdr(old.id).canDetachSuccessor)
        assertEquals(HttpStatusCode.NotFound, cast.driver.client.detachSuccessor(old.id).status)
        // A terminal predecessor is read-only, like every other write.
        val live = cast.activeAdr()
        val pending = cast.raise()
        cast.driver.client.setSupersedes(pending.id, live.id)
        forceAdrStatus(live.id, AdrStatus.EXPIRED)
        assertFalse(cast.admin.client.getAdr(live.id).canDetachSuccessor)
        val frozen = cast.admin.client.detachSuccessor(live.id)
        assertEquals(HttpStatusCode.Conflict, frozen.status)
        assertEquals(ProblemTypes.ADR_FROZEN, frozen.body<ProblemDetail>().type)
        assertEquals(live.id, rawSupersedesId(pending.id), "the link is left alone")
    }

    @Test
    fun `a successor that already activated cannot be detached - the predecessor is superseded and frozen`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val old = cast.activeAdr()
        val next = cast.raise(complete = true)
        cast.driver.client.setSupersedes(next.id, old.id)
        cast.approver.client.acceptSuccessorOk(old.id, next.id)
        cast.driver.client.moveAdrNow(next.id, AdrStatus.IN_REVIEW)
        cast.approver.client.moveAdrNow(next.id, AdrStatus.ACTIVE)
        assertFalse(cast.admin.client.getAdr(old.id).canDetachSuccessor)
        assertFalse(cast.admin.client.getAdr(old.id).canAcceptSuccessor, "nothing pending any more")
        assertEquals(ProblemTypes.ADR_FROZEN, cast.admin.client.detachSuccessor(old.id).body<ProblemDetail>().type)
        assertEquals(old.id, rawSupersedesId(next.id))
    }

    @Test
    fun `a detach racing the activation of the successor - exactly one wins, never a half state`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        repeat(3) {
            val old = cast.activeAdr()
            val next = cast.raise(complete = true)
            cast.driver.client.setSupersedes(next.id, old.id)
            // Accepted, so the activation can win: without it the race would be a refusal against a detach, not two writes.
            cast.approver.client.acceptSuccessorOk(old.id, next.id)
            cast.driver.client.moveAdrNow(next.id, AdrStatus.IN_REVIEW)
            val revision = cast.approver.client.getAdr(next.id).revision
            val (activation, detach) = coroutineScope {
                listOf(
                    async(Dispatchers.IO) { cast.approver.client.moveAdr(next.id, revision, AdrStatus.ACTIVE).status },
                    async(Dispatchers.IO) { cast.driver.client.detachSuccessor(old.id).status },
                ).awaitAll()
            }
            if (detach == HttpStatusCode.NoContent) {
                assertEquals(HttpStatusCode.Conflict, activation, "the detach bumped the revision under the activation")
                assertEquals("ACTIVE", rawAdrStatus(old.id))
                assertNull(rawSupersedesId(next.id))
            } else {
                assertEquals(HttpStatusCode.OK, activation)
                assertEquals(HttpStatusCode.Conflict, detach, "the predecessor was superseded first")
                assertEquals("SUPERSEDED", rawAdrStatus(old.id))
                assertEquals(old.id, rawSupersedesId(next.id))
            }
        }
    }

    @Test
    fun `two activations along a chain at once - B replaces A, C replaces B - no deadlock, C waits for B's Approver`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        repeat(4) { round ->
            // Alternate which ADR has the lowest id: the row locks are taken in id order, whichever way the chain runs.
            val (a, b, c) = if (round % 2 == 0) {
                val first = cast.activeAdr()
                Triple(first, cast.raise(complete = true), cast.raise(complete = true))
            } else {
                val last = cast.raise(complete = true)
                val middle = cast.raise(complete = true)
                Triple(cast.activeAdr(), middle, last)
            }
            assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(b.id, a.id).status)
            assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(c.id, b.id).status)
            // A's Approver accepted B. C replaces B, which cannot be accepted before B is in force: C waits for it, whatever the order.
            cast.approver.client.acceptSuccessorOk(a.id, b.id)
            cast.driver.client.moveAdrNow(b.id, AdrStatus.IN_REVIEW)
            cast.driver.client.moveAdrNow(c.id, AdrStatus.IN_REVIEW)
            val revisionB = cast.approver.client.getAdr(b.id).revision
            val revisionC = cast.approver.client.getAdr(c.id).revision
            val (activationB, activationC) = coroutineScope {
                listOf(
                    async(Dispatchers.IO) { cast.approver.client.moveAdr(b.id, revisionB, AdrStatus.ACTIVE).status },
                    async(Dispatchers.IO) { cast.approver.client.moveAdr(c.id, revisionC, AdrStatus.ACTIVE).status },
                ).awaitAll()
            }
            assertEquals(HttpStatusCode.OK, activationB, "B's predecessor is ACTIVE and accepted throughout")
            assertEquals("SUPERSEDED", rawAdrStatus(a.id))
            // C never moves in the race: B was still in review (400, naming the predecessor) or already ACTIVE but not yet accepted by
            // its Approver (409 successor-not-accepted). Either way nothing of C moved.
            assertTrue(activationC == HttpStatusCode.BadRequest || activationC == HttpStatusCode.Conflict, "C's activation: $activationC")
            assertEquals("ACTIVE", rawAdrStatus(b.id))
            assertEquals("IN_REVIEW", rawAdrStatus(c.id))
            // With B in force its Approver accepts C and the chain moves on.
            cast.approver.client.acceptSuccessorOk(b.id, c.id)
            val finalMove = cast.approver.client.moveAdr(c.id, cast.approver.client.getAdr(c.id).revision, AdrStatus.ACTIVE)
            assertEquals(HttpStatusCode.OK, finalMove.status)
            assertEquals("SUPERSEDED", rawAdrStatus(b.id))
            assertEquals("ACTIVE", rawAdrStatus(c.id))
        }
    }
}
