package ch.nokillswit

import ch.nokillswit.adrs.AdrEventType
import ch.nokillswit.adrs.AdrParticipantRequest
import ch.nokillswit.adrs.AdrResponse
import ch.nokillswit.adrs.AdrRole
import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.infra.ProblemTypes
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.plugins.MAX_SMALL_REQUEST_BODY_BYTES
import ch.nokillswit.plugins.ProblemDetail
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.io.File
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Successor acceptance (V19, 1.0.0, checkup C-SEC-1; `adr-model.md` "Successors"): a successor replaces a decision in force only after
 * that decision's Approver (or ADMIN) accepted the link. `POST /adrs/{id}/successor/accept` on the PREDECESSOR, who may call it, what
 * each refusal is, that an acceptance belongs to ONE link, the order of the activation's refusals, and the races.
 */
class AdrSuccessorAcceptTest {

    /** An ACTIVE ADR (the predecessor) of the cast. */
    private suspend fun AdrCast.activeAdr(): AdrResponse {
        val adr = raise(complete = true)
        activate(adr)
        return driver.client.getAdr(adr.id)
    }

    /** A predecessor in force and a complete successor linked to it by the Driver, in the same cast, still in `PROBLEM_RAISED`. */
    private suspend fun AdrCast.linkedPair(): Pair<AdrResponse, AdrResponse> {
        val old = activeAdr()
        val next = raise(complete = true)
        assertEquals(HttpStatusCode.NoContent, driver.client.setSupersedes(next.id, old.id).status)
        return old to next
    }

    private fun path(id: UInt) = "/api/v1/adrs/$id/successor/accept"

    @Test
    fun `an Approver with no role on the predecessor cannot supersede it - the link needs the predecessor's Approver first`() =
        testApplication {
            usePostgresTestcontainer()
            val theirs = adrCast()
            val mine = adrCast()
            val old = theirs.activeAdr()
            val next = mine.raise(complete = true)
            assertEquals(HttpStatusCode.NoContent, mine.driver.client.setSupersedes(next.id, old.id).status)
            mine.driver.client.moveAdrNow(next.id, AdrStatus.IN_REVIEW)
            val revision = mine.approver.client.getAdr(next.id).revision
            val eventsBefore = mine.admin.client.adrEventsOldestFirst(next.id).size
            val oldEventsBefore = theirs.admin.client.adrEventsOldestFirst(old.id).size

            val refused = mine.approver.client.moveAdr(next.id, revision, AdrStatus.ACTIVE)
            assertEquals(HttpStatusCode.Conflict, refused.status)
            assertEquals(ProblemTypes.SUCCESSOR_NOT_ACCEPTED, refused.body<ProblemDetail>().type)
            // Nothing moved: no status, no revision, no event on either ADR.
            val stillNext = mine.admin.client.getAdr(next.id)
            assertEquals(AdrStatus.IN_REVIEW, stillNext.status)
            assertEquals(revision, stillNext.revision)
            assertEquals(eventsBefore, mine.admin.client.adrEventsOldestFirst(next.id).size)
            val stillOld = theirs.admin.client.getAdr(old.id)
            assertEquals(AdrStatus.ACTIVE, stillOld.status)
            assertEquals(old.revision, stillOld.revision)
            assertEquals(oldEventsBefore, theirs.admin.client.adrEventsOldestFirst(old.id).size)
            // The Approver of the successor cannot accept their own successor either: the consent is the predecessor's.
            assertEquals(HttpStatusCode.Forbidden, mine.approver.client.acceptSuccessor(old.id, next.id).status)

            theirs.approver.client.acceptSuccessorOk(old.id, next.id)
            assertEquals(AdrStatus.ACTIVE, mine.approver.client.moveAdrNow(next.id, AdrStatus.ACTIVE).status)
            assertEquals(AdrStatus.SUPERSEDED, theirs.admin.client.getAdr(old.id).status)
        }

    @Test
    fun `only the predecessor's Approver and ADMIN accept - the Driver, a Contributor, the Informed and a stranger are 403`() =
        testApplication {
            usePostgresTestcontainer()
            val cast = adrCast()
            val (old, next) = cast.linkedPair()
            for ((who, actor) in cast.everyone - "approver" - "admin") {
                assertEquals(HttpStatusCode.Forbidden, actor.client.acceptSuccessor(old.id, next.id).status, "$who accepts")
            }
            assertNull(rawSupersedesAcceptedId(next.id), "refused callers write nothing")
            cast.approver.client.acceptSuccessorOk(old.id, next.id)
            assertEquals(old.id, rawSupersedesAcceptedId(next.id))
            // ADMIN accepts too (on another link).
            val (other, theirNext) = cast.linkedPair()
            cast.admin.client.acceptSuccessorOk(other.id, theirNext.id)
            assertEquals(other.id, rawSupersedesAcceptedId(theirNext.id))
        }

    @Test
    fun `the verdict and the acceptance are served on both ends, to the callers who may act`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val (old, next) = cast.linkedPair()
        for ((who, may) in mapOf("admin" to true, "approver" to true, "driver" to false, "contributor" to false, "stranger" to false)) {
            assertEquals(may, cast.everyone.getValue(who).client.getAdr(old.id).canAcceptSuccessor, "$who before")
        }
        assertFalse(cast.approver.client.getAdr(old.id).supersededByAccepted)
        assertFalse(cast.approver.client.getAdr(next.id).supersedesAccepted)
        assertFalse(cast.approver.client.getAdr(next.id).canAcceptSuccessor, "the verdict is on the predecessor")

        cast.approver.client.acceptSuccessorOk(old.id, next.id)
        val accepted = cast.approver.client.getAdr(old.id)
        assertTrue(accepted.supersededByAccepted)
        assertFalse(accepted.canAcceptSuccessor, "nothing left to accept")
        assertTrue(cast.stranger.client.getAdr(next.id).supersedesAccepted)
        assertEquals(old.revision, accepted.revision, "no revision moves on the predecessor")
        // A predecessor that is not in force yet offers nothing to accept.
        val draft = cast.raise(complete = true)
        val child = cast.raise(complete = true)
        cast.driver.client.setSupersedes(child.id, draft.id)
        assertFalse(cast.approver.client.getAdr(draft.id).canAcceptSuccessor)
    }

    @Test
    fun `the guard beats a malformed body - 403 for the wrong role, 400 for the Approver`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val (old, next) = cast.linkedPair()
        suspend fun send(actor: AdrActor, body: String) = actor.client.post(path(old.id)) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.status
        for (broken in listOf("{ not json", """{"successorId":"x"}""", "{}", """{"successorId":-1}""")) {
            assertEquals(HttpStatusCode.Forbidden, send(cast.driver, broken), "the Driver with $broken")
            assertEquals(HttpStatusCode.Forbidden, send(cast.stranger, broken), "a stranger with $broken")
            assertEquals(HttpStatusCode.BadRequest, send(cast.approver, broken), "the Approver with $broken")
        }
        assertNull(rawSupersedesAcceptedId(next.id))
        // The route rides the small body ceiling, like the transition.
        val oversize = """{"successorId":""" + "1".repeat(MAX_SMALL_REQUEST_BODY_BYTES.toInt()) + "}"
        assertEquals(HttpStatusCode.PayloadTooLarge, send(cast.approver, oversize))
    }

    @Test
    fun `refusals - an unknown ADR 404, a draft 409, a terminal ADR the typed frozen 409, no pending successor or another one 404`() =
        testApplication {
            usePostgresTestcontainer()
            val cast = adrCast()
            assertEquals(HttpStatusCode.NotFound, cast.stranger.client.acceptSuccessor(4_000_000u, 1u).status, "404 before the guard")

            // Not in force yet: a plain conflict (nothing to accept before the decision is in force).
            val draft = cast.raise(complete = true)
            val child = cast.raise(complete = true)
            cast.driver.client.setSupersedes(child.id, draft.id)
            val early = cast.approver.client.acceptSuccessor(draft.id, child.id)
            assertEquals(HttpStatusCode.Conflict, early.status)
            assertEquals("about:blank", early.body<ProblemDetail>().type)

            val (old, next) = cast.linkedPair()
            // No pending successor with that id: an unknown id, an unrelated ADR, the predecessor itself.
            assertEquals(HttpStatusCode.NotFound, cast.approver.client.acceptSuccessor(old.id, 4_000_000u).status)
            assertEquals(HttpStatusCode.NotFound, cast.approver.client.acceptSuccessor(old.id, draft.id).status)
            assertEquals(HttpStatusCode.NotFound, cast.approver.client.acceptSuccessor(old.id, old.id).status)
            // A successor that ended before it activated is no longer pending.
            cast.driver.client.moveAdrNow(next.id, AdrStatus.CANCELLED)
            assertEquals(HttpStatusCode.NotFound, cast.approver.client.acceptSuccessor(old.id, next.id).status)
            // None at all.
            val alone = cast.activeAdr()
            assertEquals(HttpStatusCode.NotFound, cast.approver.client.acceptSuccessor(alone.id, next.id).status)

            // A terminal predecessor is read-only like every other write.
            val (dying, pending) = cast.linkedPair()
            forceAdrStatus(dying.id, AdrStatus.EXPIRED)
            val frozen = cast.admin.client.acceptSuccessor(dying.id, pending.id)
            assertEquals(HttpStatusCode.Conflict, frozen.status)
            assertEquals(ProblemTypes.ADR_FROZEN, frozen.body<ProblemDetail>().type)
            assertNull(rawSupersedesAcceptedId(pending.id))
        }

    @Test
    fun `accepting twice is a no-op - one event pair, no revision bump, no second audit line, no second notice`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val (old, next) = cast.linkedPair()
        val nextRevision = cast.driver.client.getAdr(next.id).revision
        withAuditCapture { capture ->
            cast.approver.client.acceptSuccessorOk(old.id, next.id)
            val line = capture.awaitEvent { it.message == "adr.successor_accepted" && it.hasKeyValue("adrId", old.id.toLong()) }
            assertNotNull(line, "an acceptance audits")
            assertTrue(line.hasKeyValue("byUserId", cast.approver.id.toLong()))
            assertTrue(line.hasKeyValue("number", old.number))
            assertTrue(line.hasKeyValue("successorId", next.id.toLong()) && line.hasKeyValue("successorNumber", next.number))
            cast.approver.client.acceptSuccessorOk(old.id, next.id)
            cast.admin.client.acceptSuccessorOk(old.id, next.id)
            delay(300)
            assertEquals(1, capture.events.count { it.message == "adr.successor_accepted" && it.hasKeyValue("adrId", old.id.toLong()) })
        }
        assertEquals(nextRevision, cast.driver.client.getAdr(next.id).revision, "no revision bump on the successor")
        assertEquals(old.revision, cast.driver.client.getAdr(old.id).revision, "none on the predecessor")
        assertEquals(1, cast.driver.client.adrEventsOldestFirst(next.id).count { it.type == AdrEventType.SUCCESSOR_ACCEPTED })
        assertEquals(1, cast.driver.client.adrEventsOldestFirst(old.id).count { it.type == AdrEventType.SUCCESSOR_ACCEPTED })
        assertEquals(1, cast.driver.client.noticesOf(NotificationType.SUCCESSOR_ACCEPTED, next.id).size)
    }

    @Test
    fun `an acceptance is history on both ADRs and a notice to the successor's Driver and Approver, not the actor`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val (old, next) = cast.linkedPair()
        cast.admin.client.acceptSuccessorOk(old.id, next.id)
        val onSuccessor = cast.driver.client.adrEventsOldestFirst(next.id).last()
        assertEquals(AdrEventType.SUCCESSOR_ACCEPTED, onSuccessor.type)
        assertEquals(mapOf("predecessorId" to old.id.toString(), "key" to old.key, "name" to old.name), onSuccessor.params)
        assertEquals(cast.admin.id, onSuccessor.userId)
        val onPredecessor = cast.driver.client.adrEventsOldestFirst(old.id).last()
        assertEquals(AdrEventType.SUCCESSOR_ACCEPTED, onPredecessor.type)
        assertEquals(mapOf("successorId" to next.id.toString(), "key" to next.key, "name" to next.name), onPredecessor.params)

        // The successor's Driver and Approver are told, about the SUCCESSOR, with the predecessor as the second ADR.
        for (who in listOf(cast.driver, cast.approver)) {
            val notice = who.client.noticesOf(NotificationType.SUCCESSOR_ACCEPTED, next.id).single()
            assertEquals(old.id, notice.otherAdrId)
            assertEquals(old.key, notice.otherAdrKey)
            assertEquals(cast.admin.id, notice.actorId)
        }
        assertEquals(emptyList(), cast.contributor.client.noticesOf(NotificationType.SUCCESSOR_ACCEPTED, next.id), "leads only")
        assertEquals(emptyList(), cast.driver.client.noticesOf(NotificationType.SUCCESSOR_ACCEPTED, old.id), "the mirror event makes none")
        // The actor is never told of their own write.
        val (second, theirs) = cast.linkedPair()
        cast.approver.client.acceptSuccessorOk(second.id, theirs.id)
        assertEquals(emptyList(), cast.approver.client.noticesOf(NotificationType.SUCCESSOR_ACCEPTED, theirs.id))
        assertEquals(1, cast.driver.client.noticesOf(NotificationType.SUCCESSOR_ACCEPTED, theirs.id).size)
    }

    @Test
    fun `an acceptance belongs to one link - a re-point, a removal and a detach void it, the same link again and a new Approver keep it`() =
        testApplication {
            usePostgresTestcontainer()
            val cast = adrCast()
            val (first, next) = cast.linkedPair()
            val second = cast.activeAdr()
            cast.approver.client.acceptSuccessorOk(first.id, next.id)
            assertTrue(cast.driver.client.getAdr(next.id).supersedesAccepted)

            // The same link again changes nothing, the acceptance stays.
            assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(next.id, first.id).status)
            assertEquals(first.id, rawSupersedesAcceptedId(next.id))
            // A new Approver on the predecessor inherits the acceptance: it belongs to the link, not to the person.
            val heir = adrActor("heir")
            val seats = listOf(
                AdrParticipantRequest(cast.driver.id, AdrRole.DRIVER),
                AdrParticipantRequest(heir.id, AdrRole.APPROVER),
            )
            assertEquals(HttpStatusCode.NoContent, cast.approver.client.setParticipants(first.id, seats).status)
            assertTrue(cast.driver.client.getAdr(next.id).supersedesAccepted)

            // Re-pointed to another predecessor, and back: each link needs its own acceptance.
            assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(next.id, second.id).status)
            assertNull(rawSupersedesAcceptedId(next.id), "re-pointing voided it")
            assertFalse(cast.driver.client.getAdr(next.id).supersedesAccepted)
            assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(next.id, first.id).status)
            assertFalse(cast.driver.client.getAdr(next.id).supersedesAccepted, "back to the first one: not accepted again")
            assertTrue(heir.client.getAdr(first.id).canAcceptSuccessor)

            // Removed, then linked again.
            heir.client.acceptSuccessorOk(first.id, next.id)
            assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(next.id, null).status)
            assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(next.id, first.id).status)
            assertNull(rawSupersedesAcceptedId(next.id), "unlinking voided it")

            // Detached by the predecessor's owner, then linked again.
            heir.client.acceptSuccessorOk(first.id, next.id)
            assertEquals(HttpStatusCode.NoContent, cast.driver.client.detachSuccessor(first.id).status)
            assertNull(rawSupersedesAcceptedId(next.id), "a detach voided it")
            assertNull(rawSupersedesId(next.id))
            assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(next.id, first.id).status)
            assertFalse(cast.driver.client.getAdr(next.id).supersedesAccepted)
        }

    @Test
    fun `an unaccepted link is refused at activation for ADMIN too, and each refusal keeps its place in the order`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val (old, next) = cast.linkedPair()
        cast.driver.client.moveAdrNow(next.id, AdrStatus.IN_REVIEW)
        val current = cast.approver.client.getAdr(next.id).revision
        for ((who, actor) in mapOf("admin" to cast.admin, "approver" to cast.approver)) {
            val refused = actor.client.moveAdr(next.id, current, AdrStatus.ACTIVE)
            assertEquals(HttpStatusCode.Conflict, refused.status, who)
            assertEquals(ProblemTypes.SUCCESSOR_NOT_ACCEPTED, refused.body<ProblemDetail>().type, who)
        }
        // The Driver is refused by the role (403) before the acceptance is looked at.
        assertEquals(HttpStatusCode.Forbidden, cast.driver.client.moveAdr(next.id, current, AdrStatus.ACTIVE).status)
        // A stale revision beats the new 409 ...
        val stale = cast.approver.client.moveAdr(next.id, current - 1, AdrStatus.ACTIVE)
        assertEquals(ProblemTypes.REVISION_CONFLICT, stale.body<ProblemDetail>().type)
        // ... which beats the 400s: a reason that is too long and a past date would be refused if the acceptance were not asked first.
        forceAdrExpiry(next.id, "2000-01-01")
        val tooLong = cast.approver.client.moveAdr(next.id, current, AdrStatus.ACTIVE, "x".repeat(1001))
        assertEquals(HttpStatusCode.Conflict, tooLong.status)
        assertEquals(ProblemTypes.SUCCESSOR_NOT_ACCEPTED, tooLong.body<ProblemDetail>().type)
        // Accepted, the same request meets the 400s it was ahead of.
        cast.approver.client.acceptSuccessorOk(old.id, next.id)
        val nowTooLong = cast.approver.client.moveAdr(next.id, current, AdrStatus.ACTIVE, "x".repeat(1001))
        assertEquals(HttpStatusCode.BadRequest, nowTooLong.status)
        forceAdrExpiry(next.id, null)
        assertEquals(AdrStatus.ACTIVE, cast.admin.client.moveAdrNow(next.id, AdrStatus.ACTIVE).status)
        assertEquals(AdrStatus.SUPERSEDED, cast.admin.client.getAdr(old.id).status)
    }

    @Test
    fun `the rule only concerns a predecessor in force - a draft one keeps its 400, and an ADR with no link activates freely`() =
        testApplication {
            usePostgresTestcontainer()
            val cast = adrCast()
            // A predecessor that is still a draft: the existing 400 ("unlink it first"), not the acceptance.
            val draft = cast.raise(complete = true)
            val next = cast.raise(complete = true)
            cast.driver.client.setSupersedes(next.id, draft.id)
            cast.driver.client.moveAdrNow(next.id, AdrStatus.IN_REVIEW)
            val early = cast.approver.client.moveAdr(next.id, cast.approver.client.getAdr(next.id).revision, AdrStatus.ACTIVE)
            assertEquals(HttpStatusCode.BadRequest, early.status)
            // No link at all: nothing to accept.
            val plain = cast.raise(complete = true)
            cast.activate(plain)
            assertEquals(AdrStatus.ACTIVE, cast.admin.client.getAdr(plain.id).status)
        }

    @Test
    fun `an accept racing a detach ends detached and unaccepted, an accept racing the activation never loses the acceptance`() =
        testApplication {
            usePostgresTestcontainer()
            val cast = adrCast()
            repeat(3) {
                val (old, next) = cast.linkedPair()
                val (accept, detach) = coroutineScope {
                    listOf(
                        async(Dispatchers.IO) { cast.approver.client.acceptSuccessor(old.id, next.id).status },
                        async(Dispatchers.IO) { cast.driver.client.detachSuccessor(old.id).status },
                    ).awaitAll()
                }
                assertEquals(HttpStatusCode.NoContent, detach, "the detach always completes")
                assertTrue(accept == HttpStatusCode.NoContent || accept == HttpStatusCode.NotFound, "accept: $accept")
                assertNull(rawSupersedesId(next.id))
                assertNull(rawSupersedesAcceptedId(next.id), "no acceptance survives its link")
            }
            repeat(3) {
                val (old, next) = cast.linkedPair()
                cast.driver.client.moveAdrNow(next.id, AdrStatus.IN_REVIEW)
                val revision = cast.approver.client.getAdr(next.id).revision
                val (accept, activation) = coroutineScope {
                    listOf(
                        async(Dispatchers.IO) { cast.approver.client.acceptSuccessor(old.id, next.id).status },
                        async(Dispatchers.IO) { cast.approver.client.moveAdr(next.id, revision, AdrStatus.ACTIVE).status },
                    ).awaitAll()
                }
                assertEquals(HttpStatusCode.NoContent, accept, "the acceptance is never lost")
                if (activation == HttpStatusCode.OK) {
                    assertEquals("SUPERSEDED", rawAdrStatus(old.id))
                } else {
                    // The activation ran first and was refused; nothing of it moved, and the accepted link now goes through.
                    assertEquals(HttpStatusCode.Conflict, activation)
                    assertEquals("ACTIVE", rawAdrStatus(old.id))
                    assertEquals("IN_REVIEW", rawAdrStatus(next.id))
                    assertEquals(HttpStatusCode.OK, cast.approver.client.moveAdr(next.id, revision, AdrStatus.ACTIVE).status)
                    assertEquals("SUPERSEDED", rawAdrStatus(old.id))
                }
            }
        }

    @Test
    fun `the table refuses an acceptance that names another predecessor than the link`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val (old, next) = cast.linkedPair()
        cast.approver.client.acceptSuccessorOk(old.id, next.id)
        PostgresTestSupport.connect().use { connection ->
            connection.createStatement().use { statement ->
                assertFailsWith<SQLException>("clearing the link and keeping the marker") {
                    statement.executeUpdate("UPDATE adrs SET supersedes_id = NULL WHERE id = ${next.id}")
                }
            }
        }
        assertEquals(old.id, rawSupersedesId(next.id))
    }

    @Test
    fun `only AdrLinkWrites assigns the two link columns`() {
        val root = File(System.getProperty("repo.root") ?: "..", "server/src")
        val writes = Regex("""(\bit\[\s*(\w+\.)*supersedes(Accepted)?Id\s*]\s*=[^=])|(\bsupersedes(_accepted)?_id\s*=[^=])""")
        val strays = listOf("main/kotlin", "test/kotlin").flatMap { dir ->
            File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }
                // The two tests below spell the columns in raw SQL on purpose (a scratch schema; a deliberately bad UPDATE).
                .filter { it.name !in setOf("AdrLinkWrites.kt", "AdrSuccessorAcceptTest.kt", "SuccessorAcceptanceMigrationTest.kt") }
                .filter { writes.containsMatchIn(it.readText()) }
                .map { it.name }.toList()
        }
        assertEquals(emptyList(), strays, "supersedes_id and supersedes_accepted_id are written by AdrLinkWrites.kt alone (writeLink)")
    }
}
