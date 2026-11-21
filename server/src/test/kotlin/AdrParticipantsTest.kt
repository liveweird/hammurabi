package ch.nokillswit

import ch.nokillswit.adrs.AdrEventType
import ch.nokillswit.adrs.AdrParticipant
import ch.nokillswit.adrs.AdrParticipantRequest
import ch.nokillswit.adrs.AdrRole
import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.adrs.MAX_ADR_PARTICIPANTS
import ch.nokillswit.adrs.AdrParticipantsRequest
import ch.nokillswit.infra.ProblemTypes
import ch.nokillswit.plugins.ProblemDetail
import io.ktor.client.call.body
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The DACI participants (V10): the wholesale-replace PUT as a diff - exactly one Driver, at most one Approver, one role per user - with
 * its events, the Driver handover, the unchanged-set no-op and the rules for users deleted since.
 */
class AdrParticipantsTest {
    private fun req(actor: AdrActor, role: AdrRole) = AdrParticipantRequest(actor.id, role)

    /** [MAX_ADR_PARTICIPANTS] unknown users after [base]: one more than the Driver leaves room for. */
    private fun crowd(base: AdrActor) = (1..MAX_ADR_PARTICIPANTS).map { AdrParticipantRequest(base.id + it.toUInt(), AdrRole.INFORMED) }

    @Test
    fun `the set must hold exactly one Driver, at most one Approver, each user once, only active users`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        val outsider = adrActor("outsider")
        val driver = req(cast.driver, AdrRole.DRIVER)
        val gone = adrActor("deleted")
        TestUsers.softDelete(gone.id)
        val bad = mapOf(
            "empty" to emptyList(),
            "no Driver" to listOf(req(cast.approver, AdrRole.APPROVER)),
            "two Drivers" to listOf(driver, req(cast.approver, AdrRole.DRIVER)),
            "two Approvers" to listOf(driver, req(cast.approver, AdrRole.APPROVER), req(cast.contributor, AdrRole.APPROVER)),
            "a user twice" to listOf(driver, req(cast.approver, AdrRole.APPROVER), req(cast.approver, AdrRole.INFORMED)),
            "an unknown user" to listOf(driver, AdrParticipantRequest(4_000_000_000u, AdrRole.INFORMED)),
            "a deleted user" to listOf(driver, req(gone, AdrRole.INFORMED)),
            "too many" to listOf(driver) + crowd(outsider),
        )
        for ((label, set) in bad) {
            assertEquals(HttpStatusCode.BadRequest, cast.driver.client.setParticipants(adr.id, set).status, label)
        }
        // None of it changed anything.
        assertEquals(4, cast.driver.client.getAdr(adr.id).participants.size)
    }

    @Test
    fun `a replace is a diff - added, removed and changed participants each leave one event`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        val newcomer = adrActor("newcomer")
        val set = listOf(
            req(cast.driver, AdrRole.DRIVER),
            req(cast.approver, AdrRole.APPROVER),
            req(cast.contributor, AdrRole.INFORMED), // changed
            req(newcomer, AdrRole.CONTRIBUTOR), // added; the Informed one is removed
        )
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setParticipants(adr.id, set).status)
        val view = cast.stranger.client.getAdr(adr.id)
        assertEquals(
            listOf(
                AdrParticipant(cast.driver.id, "Drv", AdrRole.DRIVER),
                AdrParticipant(cast.approver.id, "Apr", AdrRole.APPROVER),
                AdrParticipant(newcomer.id, "Newcomer", AdrRole.CONTRIBUTOR),
                AdrParticipant(cast.contributor.id, "Con", AdrRole.INFORMED),
            ),
            view.participants,
            "driver first, then the approver, the contributors and the informed",
        )
        assertEquals(adr.revision + 1, view.revision, "a real participant change bumps the revision")
        assertTrue(view.updatedAt >= adr.updatedAt)
        val events = cast.driver.client.adrEventsOldestFirst(adr.id).filter { it.type.name.startsWith("PARTICIPANT_") }
        // The creation's three ADDED events come first; this write's three follow: removed, changed, added.
        assertEquals(
            listOf(
                AdrEventType.PARTICIPANT_ADDED, AdrEventType.PARTICIPANT_ADDED, AdrEventType.PARTICIPANT_ADDED,
                AdrEventType.PARTICIPANT_REMOVED, AdrEventType.PARTICIPANT_CHANGED, AdrEventType.PARTICIPANT_ADDED,
            ),
            events.map { it.type },
        )
        val (removed, changed, added) = events.takeLast(3)
        assertEquals(mapOf("userId" to cast.informed.id.toString(), "name" to "Inf", "role" to "INFORMED"), removed.params)
        val change = mapOf("userId" to cast.contributor.id.toString(), "name" to "Con", "from" to "CONTRIBUTOR", "to" to "INFORMED")
        assertEquals(change, changed.params)
        assertEquals(mapOf("userId" to newcomer.id.toString(), "name" to "Newcomer", "role" to "CONTRIBUTOR"), added.params)
        assertEquals(cast.driver.id, added.actorId)
    }

    @Test
    fun `the same set again changes nothing and records nothing`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        val same = cast.driver.client.getAdr(adr.id).participants.map { AdrParticipantRequest(it.userId, it.role) }
        val before = cast.driver.client.adrEvents(adr.id).total
        val updatedAt = cast.driver.client.getAdr(adr.id).updatedAt
        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.NoContent, cast.driver.client.setParticipants(adr.id, same.reversed()).status)
            kotlinx.coroutines.delay(300)
            assertTrue(capture.events.none { it.message == "adr.participants_changed" && it.hasKeyValue("adrId", adr.id.toLong()) })
        }
        assertEquals(before, cast.driver.client.adrEvents(adr.id).total)
        assertEquals(updatedAt, cast.driver.client.getAdr(adr.id).updatedAt)
        assertEquals(adr.revision, cast.driver.client.getAdr(adr.id).revision, "a no-op bumps nothing")
    }

    @Test
    fun `the Driver role is handed over, never left empty - and a Driver and Approver can swap in one write`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        // The Driver hands over to the Contributor and stays as an Informed.
        val handover = listOf(
            req(cast.contributor, AdrRole.DRIVER),
            req(cast.driver, AdrRole.INFORMED),
            req(cast.approver, AdrRole.APPROVER),
            req(cast.informed, AdrRole.INFORMED),
        )
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setParticipants(adr.id, handover).status)
        val view = cast.admin.client.getAdr(adr.id)
        assertEquals(cast.contributor.id, view.participants.first().userId)
        assertEquals(AdrRole.DRIVER, view.participants.first().role)
        // The old Driver lost the Driver's rights at once; the new one holds them.
        assertEquals(HttpStatusCode.Forbidden, cast.driver.client.setParticipants(adr.id, handover).status)
        val forward = listOf(AdrStatus.SOLUTION_DRAFT, AdrStatus.IN_REVIEW, AdrStatus.CANCELLED)
        assertEquals(forward, cast.contributor.client.getAdr(adr.id).allowedTransitions)
        assertEquals(emptyList(), cast.driver.client.getAdr(adr.id).allowedTransitions)
        // The new Driver and the Approver swap places in one write (the unique indexes never see it half-done).
        val swap = listOf(
            req(cast.contributor, AdrRole.APPROVER),
            req(cast.approver, AdrRole.DRIVER),
            req(cast.driver, AdrRole.INFORMED),
            req(cast.informed, AdrRole.INFORMED),
        )
        // The new Driver may not make themselves the Approver (that is the swap's other half), so the Approver does the swap.
        assertEquals(HttpStatusCode.Forbidden, cast.contributor.client.setParticipants(adr.id, swap).status)
        assertEquals(HttpStatusCode.NoContent, cast.approver.client.setParticipants(adr.id, swap).status)
        val swapped = cast.admin.client.getAdr(adr.id).participants
        assertEquals(AdrParticipant(cast.approver.id, "Apr", AdrRole.DRIVER), swapped[0])
        assertEquals(AdrParticipant(cast.contributor.id, "Con", AdrRole.APPROVER), swapped[1])
    }

    @Test
    fun `a participant whose account was deleted since may stay, but nobody deleted can be added`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        TestUsers.softDelete(cast.informed.id)
        val view = cast.driver.client.getAdr(adr.id)
        assertEquals("Inf", view.participants.single { it.role == AdrRole.INFORMED }.name, "a deleted user keeps the recorded name")
        // Re-sending the unchanged set (the deleted Informed included) is fine ...
        val unchanged = view.participants.map { AdrParticipantRequest(it.userId, it.role) }
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setParticipants(adr.id, unchanged).status)
        // ... but giving that user a new role is adding a deleted user.
        val promote = view.participants.map {
            AdrParticipantRequest(it.userId, if (it.userId == cast.informed.id) AdrRole.CONTRIBUTOR else it.role)
        }
        assertEquals(HttpStatusCode.BadRequest, cast.driver.client.setParticipants(adr.id, promote).status)
    }

    @Test
    fun `initial participants join at creation with one event each`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        assertEquals(
            listOf(
                AdrParticipant(cast.driver.id, "Drv", AdrRole.DRIVER),
                AdrParticipant(cast.approver.id, "Apr", AdrRole.APPROVER),
                AdrParticipant(cast.contributor.id, "Con", AdrRole.CONTRIBUTOR),
                AdrParticipant(cast.informed.id, "Inf", AdrRole.INFORMED),
            ),
            adr.participants,
        )
        val types = cast.driver.client.adrEventsOldestFirst(adr.id).map { it.type }
        assertEquals(listOf(AdrEventType.CREATED) + List(3) { AdrEventType.PARTICIPANT_ADDED }, types)
    }

    @Test
    fun `the participants PUT is guarded by X-Expected-Revision - required, and a stale editor cannot drop people`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        val newcomer = adrActor("latecomer")
        val everyone = cast.driver.client.getAdr(adr.id).participants.map { AdrParticipantRequest(it.userId, it.role) }
        // Missing and malformed headers are 400 for a caller who may ask; the wrong role stays 403 first.
        val noHeader = cast.driver.client.put("/api/v1/adrs/${adr.id}/participants") {
            contentType(ContentType.Application.Json)
            setBody(AdrParticipantsRequest(everyone))
        }
        assertEquals(HttpStatusCode.BadRequest, noHeader.status)
        val strangerNoHeader = cast.stranger.client.put("/api/v1/adrs/${adr.id}/participants") {
            contentType(ContentType.Application.Json)
            setBody(AdrParticipantsRequest(everyone))
        }
        assertEquals(HttpStatusCode.Forbidden, strangerNoHeader.status)
        // Two editors read revision 1; the first adds somebody, the second (stale, without him) would drop him.
        val added = cast.driver.client.setParticipants(adr.id, everyone + req(newcomer, AdrRole.INFORMED), adr.revision)
        assertEquals(HttpStatusCode.NoContent, added.status)
        val stale = cast.approver.client.setParticipants(adr.id, everyone, adr.revision)
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertEquals(ProblemTypes.REVISION_CONFLICT, stale.body<ProblemDetail>().type)
        assertEquals(5, cast.driver.client.getAdr(adr.id).participants.size, "the stale write dropped nobody")
        // A terminal ADR is frozen before the revision is looked at.
        forceAdrStatus(adr.id, AdrStatus.CANCELLED)
        val frozen = cast.driver.client.setParticipants(adr.id, everyone, 99)
        assertEquals(ProblemTypes.ADR_FROZEN, frozen.body<ProblemDetail>().type)
    }
}
