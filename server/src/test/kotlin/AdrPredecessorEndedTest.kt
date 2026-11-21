package ch.nokillswit

import ch.nokillswit.adrs.AdrEventService
import ch.nokillswit.adrs.AdrEventType
import ch.nokillswit.adrs.AdrExpirySweep
import ch.nokillswit.adrs.AdrPageResponse
import ch.nokillswit.adrs.AdrParticipantRequest
import ch.nokillswit.adrs.AdrResponse
import ch.nokillswit.adrs.AdrRole
import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.infra.ProblemTypes
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.plugins.ProblemDetail
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A successor still pre-active when the ADR it was set to replace is cancelled, withdrawn or expires (1.2.0, `adr-model.md`
 * "Successors"): the predecessor stops listing it, the successor gets a `PREDECESSOR_ENDED` event and its Driver and Approver a notice,
 * and the link stays on the successor (its Driver removes it; the activation keeps its `400`). The predecessor and the successor each
 * come from their own [AdrCast], so the leads who are told are not the people who ended the predecessor.
 */
class AdrPredecessorEndedTest {

    private data class Linked(val old: AdrResponse, val successor: AdrResponse)

    /** The predecessor of [cast] (ACTIVE, or in review when [active] is false) and a pending successor of [next], linked by its Driver. */
    private suspend fun link(cast: AdrCast, next: AdrCast, active: Boolean = true): Linked {
        val old = cast.raise(complete = true)
        if (active) cast.activate(old) else cast.driver.client.moveAdrNow(old.id, AdrStatus.IN_REVIEW)
        val successor = next.raise(complete = true)
        assertEquals(HttpStatusCode.NoContent, next.driver.client.setSupersedes(successor.id, old.id).status)
        return Linked(old, successor)
    }

    private suspend fun HttpClient.ended(adrId: UInt) = adrEventsOldestFirst(adrId).filter { it.type == AdrEventType.PREDECESSOR_ENDED }

    private suspend fun HttpClient.told(successor: AdrResponse) = noticesOf(NotificationType.PREDECESSOR_ENDED, successor.id)

    /** Everything an ending must leave true on the successor, the predecessor, the list, the export and the bell. */
    private suspend fun assertTold(
        cast: AdrCast,
        next: AdrCast,
        pair: Linked,
        status: AdrStatus,
        actorId: UInt?,
        revisionBefore: Long,
    ) {
        val (old, successor) = pair
        val reader = cast.stranger.client
        val events = next.stranger.client.ended(successor.id)
        assertEquals(1, events.size)
        assertEquals(
            mapOf("predecessorId" to old.id.toString(), "key" to old.key, "name" to old.name, "status" to status.name),
            events.single().params,
        )
        assertEquals(actorId, events.single().actorId, "the actor is who ended it, none for the sweep")
        val after = next.stranger.client.getAdr(successor.id)
        assertEquals(revisionBefore, after.revision, "the successor's revision does not move")
        assertEquals(old.id, after.supersedes?.id, "the link stays")
        assertEquals(status, after.supersedes?.status)
        assertEquals(old.id, rawSupersedesId(successor.id))
        // The leads of the SUCCESSOR are told; a Contributor, an Informed and the stranger are not.
        for (lead in listOf(next.driver, next.approver)) {
            val notices = lead.client.told(successor)
            assertEquals(1, notices.size, "a lead of the successor is told once")
            assertEquals(old.id, notices.single().otherAdrId)
            assertEquals(old.key, notices.single().otherAdrKey)
            assertEquals(status.name, notices.single().detail)
            assertEquals(actorId, notices.single().actorId)
        }
        for (other in listOf(next.contributor, next.informed, next.stranger)) assertEquals(emptyList(), other.client.told(successor))
        for (anyone in listOf(cast.driver, cast.approver, cast.contributor, cast.informed)) {
            assertEquals(emptyList(), anyone.client.told(successor), "the predecessor's people are not the successor's audience")
        }
        // The predecessor and every read of it stop listing the successor.
        assertNull(reader.getAdr(old.id).supersededBy)
        assertFalse(reader.getAdr(old.id).supersededByAccepted)
        val found = reader.get("/api/v1/adrs?pageSize=20&q=${old.key}&hasSuccessor=true").body<AdrPageResponse>().items.map { it.id }
        assertFalse(old.id in found, "hasSuccessor=true does not match it")
        val export = reader.get("/api/v1/adrs/${old.id}/export").bodyAsText()
        assertFalse("Successor proposed" in export || "superseded by" in export.lowercase(), export)
        // Activation keeps its 400 until the link goes.
        next.driver.client.moveAdrNow(successor.id, AdrStatus.IN_REVIEW)
        val refused = next.approver.client.moveAdr(successor.id, next.approver.client.getAdr(successor.id).revision, AdrStatus.ACTIVE)
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertTrue("unlink it first" in refused.bodyAsText())
    }

    @Test
    fun `withdrawing an ACTIVE predecessor tells the pending successor's leads and the predecessor stops listing it`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val next = adrCast()
        val pair = link(cast, next)
        val revision = next.stranger.client.getAdr(pair.successor.id).revision
        assertEquals(pair.successor.id, cast.stranger.client.getAdr(pair.old.id).supersededBy?.id, "listed while it is pending")
        cast.approver.client.moveAdrNow(pair.old.id, AdrStatus.WITHDRAWN, reason = "no longer valid")
        assertTold(cast, next, pair, AdrStatus.WITHDRAWN, cast.approver.id, revision)
        // The reason of the move is free text and reaches no notice or event of the successor.
        assertFalse("reason" in next.stranger.client.ended(pair.successor.id).single().params)
    }

    @Test
    fun `withdrawing from review and cancelling a draft predecessor do the same`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val next = adrCast()
        val review = link(cast, next, active = false)
        val reviewRevision = next.stranger.client.getAdr(review.successor.id).revision
        cast.approver.client.moveAdrNow(review.old.id, AdrStatus.WITHDRAWN)
        assertTold(cast, next, review, AdrStatus.WITHDRAWN, cast.approver.id, reviewRevision)

        val draft = cast.raise()
        val successor = next.raise(complete = true)
        assertEquals(HttpStatusCode.NoContent, next.driver.client.setSupersedes(successor.id, draft.id).status)
        val revision = next.stranger.client.getAdr(successor.id).revision
        cast.driver.client.moveAdrNow(draft.id, AdrStatus.CANCELLED)
        assertTold(cast, next, Linked(draft, successor), AdrStatus.CANCELLED, cast.driver.id, revision)
    }

    @Test
    fun `the expiry sweep tells it too, with no actor`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val next = adrCast()
        val pair = link(cast, next)
        val revision = next.stranger.client.getAdr(pair.successor.id).revision
        forceAdrExpiry(pair.old.id, "2001-01-01")
        val clock = Clock.fixed(LocalDate.now(ZoneOffset.UTC).atTime(13, 30).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
        val sweep = AdrExpirySweep(sharedDatabaseForTests(), AdrEventService(sharedDatabaseForTests(), newNotificationService()), clock)
        assertTrue(sweep.sweep() >= 1)
        assertEquals(AdrStatus.EXPIRED, cast.stranger.client.getAdr(pair.old.id).status)
        assertTold(cast, next, pair, AdrStatus.EXPIRED, null, revision)
        val swept = sweep.sweep()
        assertEquals(1, next.stranger.client.ended(pair.successor.id).size, "a second pass writes nothing more ($swept moved)")
    }

    @Test
    fun `the person who ended the predecessor is not told even when they lead the successor`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val old = cast.raise(complete = true)
        cast.activate(old)
        // The Approver of the predecessor DRIVES the successor and the predecessor's Driver approves it.
        val successor = cast.approver.client.createAdr(
            complete = true,
            participants = listOf(
                AdrParticipantRequest(cast.driver.id, AdrRole.APPROVER),
                AdrParticipantRequest(cast.informed.id, AdrRole.INFORMED),
            ),
        )
        assertEquals(HttpStatusCode.NoContent, cast.approver.client.setSupersedes(successor.id, old.id).status)
        cast.approver.client.moveAdrNow(old.id, AdrStatus.WITHDRAWN)
        assertEquals(emptyList(), cast.approver.client.told(successor), "nobody is told of their own write")
        assertEquals(1, cast.driver.client.told(successor).size)
        assertEquals(emptyList(), cast.informed.client.told(successor))
        assertEquals(1, cast.stranger.client.ended(successor.id).size, "the event is the history's, whoever is told")
    }

    @Test
    fun `no pending successor means no event - none, a cancelled or withdrawn one, a refused ending`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val next = adrCast()
        val alone = cast.raise(complete = true)
        cast.activate(alone)
        cast.approver.client.moveAdrNow(alone.id, AdrStatus.WITHDRAWN)
        assertEquals(emptyList(), cast.stranger.client.adrEventsOldestFirst(alone.id).filter { it.type == AdrEventType.PREDECESSOR_ENDED })

        for (leaves in listOf(AdrStatus.CANCELLED, AdrStatus.WITHDRAWN)) {
            val old = cast.raise(complete = true)
            cast.activate(old)
            val successor = next.raise(complete = true)
            next.driver.client.setSupersedes(successor.id, old.id)
            if (leaves == AdrStatus.WITHDRAWN) {
                next.driver.client.moveAdrNow(successor.id, AdrStatus.IN_REVIEW)
                next.approver.client.moveAdrNow(successor.id, AdrStatus.WITHDRAWN)
            } else {
                next.driver.client.moveAdrNow(successor.id, AdrStatus.CANCELLED)
            }
            val before = next.stranger.client.adrEventsOldestFirst(successor.id).size
            cast.approver.client.moveAdrNow(old.id, AdrStatus.WITHDRAWN)
            assertEquals(before, next.stranger.client.adrEventsOldestFirst(successor.id).size, "a $leaves successor is told nothing")
            assertEquals(emptyList(), next.driver.client.told(successor))
        }

        // A refused ending (a stale revision, a caller without the role) rolls back whole: the successor hears nothing.
        val old = cast.raise(complete = true)
        cast.activate(old)
        val pending = next.raise(complete = true)
        next.driver.client.setSupersedes(pending.id, old.id)
        val live = cast.stranger.client.getAdr(old.id)
        assertEquals(HttpStatusCode.Conflict, cast.approver.client.moveAdr(old.id, live.revision - 1, AdrStatus.WITHDRAWN).status)
        assertEquals(HttpStatusCode.Forbidden, cast.contributor.client.moveAdr(old.id, live.revision, AdrStatus.WITHDRAWN).status)
        assertEquals(HttpStatusCode.Forbidden, cast.driver.client.moveAdr(old.id, live.revision, AdrStatus.WITHDRAWN).status)
        assertEquals(emptyList(), next.stranger.client.ended(pending.id))
        assertEquals(pending.id, cast.stranger.client.getAdr(old.id).supersededBy?.id, "still pending, still listed")
    }

    @Test
    fun `the supersede path is unchanged - no ending event, the predecessor keeps pointing at its successor`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val next = adrCast()
        val pair = link(cast, next)
        cast.approver.client.acceptSuccessorOk(pair.old.id, pair.successor.id)
        next.driver.client.moveAdrNow(pair.successor.id, AdrStatus.IN_REVIEW)
        next.approver.client.moveAdrNow(pair.successor.id, AdrStatus.ACTIVE)
        assertEquals(AdrStatus.SUPERSEDED, cast.stranger.client.getAdr(pair.old.id).status)
        assertEquals(pair.successor.id, cast.stranger.client.getAdr(pair.old.id).supersededBy?.id)
        assertEquals(emptyList(), next.stranger.client.ended(pair.successor.id))
        assertEquals(emptyList(), next.driver.client.told(pair.successor))
        val found = cast.stranger.client.get("/api/v1/adrs?pageSize=20&q=${pair.old.key}&hasSuccessor=true").body<AdrPageResponse>()
        assertTrue(pair.old.id in found.items.map { it.id })
    }

    @Test
    fun `a legacy dead link reads as none and writes no event`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val next = adrCast()
        val pair = link(cast, next)
        // The predecessor ended before 1.2.0 (or by a path that never told anyone): the status moved, nothing else did.
        forceAdrStatus(pair.old.id, AdrStatus.WITHDRAWN)
        assertNull(cast.stranger.client.getAdr(pair.old.id).supersededBy)
        assertEquals(emptyList(), next.stranger.client.ended(pair.successor.id))
        assertEquals(pair.old.id, next.stranger.client.getAdr(pair.successor.id).supersedes?.id, "the dead link stays on the successor")
        // Its Driver removes it and the successor is free to link and activate again.
        assertEquals(HttpStatusCode.NoContent, next.driver.client.setSupersedes(pair.successor.id, null).status)
        assertNull(next.stranger.client.getAdr(pair.successor.id).supersedes)
        assertNull(rawSupersedesId(pair.successor.id))
    }

    @Test
    fun `a link racing the withdrawal is either told or refused, never a link nobody hears about`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val next = adrCast()
        repeat(4) {
            val old = cast.raise(complete = true)
            cast.activate(old)
            val successor = next.raise(complete = true)
            val revision = next.stranger.client.getAdr(successor.id).revision
            val (link, withdraw) = coroutineScope {
                val linking = async(Dispatchers.Default) { next.driver.client.setSupersedes(successor.id, old.id, revision) }
                val withdrawing = async(Dispatchers.Default) {
                    cast.approver.client.moveAdr(old.id, cast.stranger.client.getAdr(old.id).revision, AdrStatus.WITHDRAWN)
                }
                listOf(linking, withdrawing).awaitAll()
            }
            val told = next.stranger.client.ended(successor.id).size
            if (link.status == HttpStatusCode.NoContent) {
                // The link was there when the predecessor ended: whatever the withdrawal answered, it saw the link under its locks.
                assertEquals(HttpStatusCode.OK, withdraw.status)
                assertEquals(1, told, "a link that won the race is told")
            } else {
                assertEquals(HttpStatusCode.BadRequest, link.status, "the link met a terminal predecessor")
                assertEquals(HttpStatusCode.OK, withdraw.status)
                assertEquals(0, told)
                assertNull(rawSupersedesId(successor.id))
            }
        }
    }

    @Test
    fun `a withdrawal racing the successor's activation - exactly one wins, the loser leaves nothing behind`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val next = adrCast()
        repeat(4) {
            val pair = link(cast, next)
            cast.approver.client.acceptSuccessorOk(pair.old.id, pair.successor.id)
            next.driver.client.moveAdrNow(pair.successor.id, AdrStatus.IN_REVIEW)
            val successorRevision = next.stranger.client.getAdr(pair.successor.id).revision
            val oldRevision = cast.stranger.client.getAdr(pair.old.id).revision
            val (activate, withdraw) = coroutineScope {
                val activating = async(Dispatchers.Default) {
                    next.approver.client.moveAdr(pair.successor.id, successorRevision, AdrStatus.ACTIVE)
                }
                val withdrawing = async(Dispatchers.Default) { cast.admin.client.moveAdr(pair.old.id, oldRevision, AdrStatus.WITHDRAWN) }
                listOf(activating, withdrawing).awaitAll()
            }
            val told = next.stranger.client.ended(pair.successor.id).size
            if (activate.status == HttpStatusCode.OK) {
                assertEquals(AdrStatus.SUPERSEDED.name, rawAdrStatus(pair.old.id), "the activation superseded it first")
                assertEquals(HttpStatusCode.Conflict, withdraw.status)
                assertEquals(ProblemTypes.ILLEGAL_TRANSITION, withdraw.body<ProblemDetail>().type, "SUPERSEDED cannot be withdrawn")
                assertEquals(0, told, "a superseded predecessor ended nothing")
            } else {
                assertEquals(HttpStatusCode.BadRequest, activate.status, "the activation met an ended predecessor")
                assertEquals(HttpStatusCode.OK, withdraw.status)
                assertEquals(1, told)
                assertEquals(AdrStatus.WITHDRAWN.name, rawAdrStatus(pair.old.id))
                assertEquals(AdrStatus.IN_REVIEW.name, rawAdrStatus(pair.successor.id), "the successor was not activated")
            }
        }
    }
}
