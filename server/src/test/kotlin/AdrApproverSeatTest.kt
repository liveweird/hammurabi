package ch.nokillswit

import ch.nokillswit.adrs.AdrParticipantRequest
import ch.nokillswit.adrs.AdrRole
import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.infra.ProblemTypes
import ch.nokillswit.plugins.ProblemDetail
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 0.7.0 "who holds the Approver seat" (`adr-model.md` "Participants", `authorization.md`): nobody but ADMIN makes themselves the
 * Approver, and from `IN_REVIEW` on only the current Approver or ADMIN changes who holds the seat. Without it the Driver could hand
 * themselves the seat in a participants PUT and then approve or withdraw their own ADR.
 */
class AdrApproverSeatTest {
    private fun roster(vararg seats: Pair<AdrActor, AdrRole>) = seats.map { (who, role) -> AdrParticipantRequest(who.id, role) }

    @Test
    fun `the two-step exploit - take the Approver seat of an ACTIVE ADR, then withdraw it - is refused at step one`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        cast.activate(adr)
        val heir = adrActor("heir")
        val active = cast.driver.client.getAdr(adr.id)
        // Step 1: the Driver names themselves Approver (handing the Driver seat to someone else).
        val takeSeat = roster(cast.driver to AdrRole.APPROVER, heir to AdrRole.DRIVER)
        val step1 = cast.driver.client.setParticipants(adr.id, takeSeat, active.revision)
        assertEquals(HttpStatusCode.Forbidden, step1.status)
        // Step 2 has nothing to stand on: the Approver is who it was, and the Driver cannot withdraw.
        assertEquals(HttpStatusCode.Forbidden, cast.driver.client.moveAdr(adr.id, active.revision, AdrStatus.WITHDRAWN).status)
        val after = cast.admin.client.getAdr(adr.id)
        assertEquals(AdrStatus.ACTIVE, after.status)
        assertEquals(active.revision, after.revision, "a refused write bumps nothing")
        assertEquals(cast.approver.id, after.participants.single { it.role == AdrRole.APPROVER }.userId)
    }

    @Test
    fun `a Driver cannot approve their own ADR in review through the participants either`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        cast.driver.client.moveAdrNow(adr.id, AdrStatus.IN_REVIEW)
        val heir = adrActor("heir")
        val review = cast.driver.client.getAdr(adr.id)
        val takeSeat = roster(cast.driver to AdrRole.APPROVER, heir to AdrRole.DRIVER)
        val taken = cast.driver.client.setParticipants(adr.id, takeSeat, review.revision)
        assertEquals(HttpStatusCode.Forbidden, taken.status)
        // Dropping or replacing the Approver is the Approver's, too.
        val dropped = cast.driver.client.setParticipants(adr.id, roster(cast.driver to AdrRole.DRIVER), review.revision)
        assertEquals(HttpStatusCode.Forbidden, dropped.status)
        val swapSeat = roster(cast.driver to AdrRole.DRIVER, heir to AdrRole.APPROVER)
        val replaced = cast.driver.client.setParticipants(adr.id, swapSeat, review.revision)
        assertEquals(HttpStatusCode.Forbidden, replaced.status)
        assertEquals(review.revision, cast.admin.client.getAdr(adr.id).revision)
    }

    @Test
    fun `self-approval is refused before review too, and the 403 wins over a malformed set and a stale revision`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val draft = cast.driver.client.createAdr()
        val heir = adrActor("heir")
        val self = roster(cast.driver to AdrRole.APPROVER, heir to AdrRole.DRIVER)
        assertEquals(HttpStatusCode.Forbidden, cast.driver.client.setParticipants(draft.id, self, draft.revision).status)
        // No Driver in the set (a 400 for anybody else) and a stale revision (a 409): still the 403.
        val headless = roster(cast.driver to AdrRole.APPROVER)
        assertEquals(HttpStatusCode.Forbidden, cast.driver.client.setParticipants(draft.id, headless, draft.revision).status)
        assertEquals(HttpStatusCode.Forbidden, cast.driver.client.setParticipants(draft.id, self, 99).status)
        // The Driver may name SOMEONE ELSE before review, replace them and drop them.
        val named = cast.driver.client.setParticipants(draft.id, roster(cast.driver to AdrRole.DRIVER, cast.approver to AdrRole.APPROVER))
        assertEquals(HttpStatusCode.NoContent, named.status)
        val replaced = cast.driver.client.setParticipants(draft.id, roster(cast.driver to AdrRole.DRIVER, heir to AdrRole.APPROVER))
        assertEquals(HttpStatusCode.NoContent, replaced.status)
        val dropped = cast.driver.client.setParticipants(draft.id, roster(cast.driver to AdrRole.DRIVER))
        assertEquals(HttpStatusCode.NoContent, dropped.status)
        // A Contributor never reaches the verdict (the coarse guard), an Approver already in the seat keeps it.
        val adr = cast.raise()
        assertEquals(
            HttpStatusCode.NoContent,
            cast.approver.client.setParticipants(
                adr.id,
                roster(cast.driver to AdrRole.DRIVER, cast.approver to AdrRole.APPROVER, heir to AdrRole.CONTRIBUTOR),
            ).status,
        )
    }

    @Test
    fun `the refusal names the rule and is audited as an authz denial`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        cast.activate(adr)
        val heir = adrActor("heir")
        withAuditCapture { capture ->
            val response = cast.driver.client.setParticipants(adr.id, roster(cast.driver to AdrRole.APPROVER, heir to AdrRole.DRIVER))
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertNotNull(response.body<ProblemDetail>().detail)
            val path = "/api/v1/adrs/${adr.id}/participants"
            val denied = capture.awaitEvent { it.message == "authz.denied" && it.hasKeyValue("path", path) }
            assertNotNull(denied, "the 403 leaves the denial trail")
            assertTrue(denied.hasKeyValue("byUserId", cast.driver.id.toLong()))
        }
    }

    @Test
    fun `from review on the Approver hands the seat to someone else, in review and when ACTIVE`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        cast.driver.client.moveAdrNow(adr.id, AdrStatus.IN_REVIEW)
        val second = adrActor("second")
        val third = adrActor("third")
        val team = roster(cast.driver to AdrRole.DRIVER, cast.contributor to AdrRole.CONTRIBUTOR, cast.informed to AdrRole.INFORMED)
        val handOn = team + roster(second to AdrRole.APPROVER)
        assertEquals(HttpStatusCode.NoContent, cast.approver.client.setParticipants(adr.id, handOn).status)
        // The old Approver is out; the new one decides.
        val revision = cast.admin.client.getAdr(adr.id).revision
        assertEquals(HttpStatusCode.Forbidden, cast.approver.client.moveAdr(adr.id, revision, AdrStatus.ACTIVE).status)
        second.client.moveAdrNow(adr.id, AdrStatus.ACTIVE)
        assertEquals(HttpStatusCode.NoContent, second.client.setParticipants(adr.id, team + roster(third to AdrRole.APPROVER)).status)
        val seat = cast.admin.client.getAdr(adr.id).participants.single { it.role == AdrRole.APPROVER }
        assertEquals(third.id, seat.userId)
        third.client.moveAdrNow(adr.id, AdrStatus.WITHDRAWN)
    }

    @Test
    fun `a Driver handover and the other seats still work from review on, the Approver seat unchanged`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        cast.activate(adr)
        val heir = adrActor("heir")
        val handover = roster(
            heir to AdrRole.DRIVER,
            cast.approver to AdrRole.APPROVER,
            cast.driver to AdrRole.CONTRIBUTOR,
            cast.informed to AdrRole.INFORMED,
        )
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setParticipants(adr.id, handover).status)
        val after = cast.admin.client.getAdr(adr.id)
        assertEquals(heir.id, after.participants.single { it.role == AdrRole.DRIVER }.userId)
        assertEquals(cast.approver.id, after.participants.single { it.role == AdrRole.APPROVER }.userId)
        // The new Driver, in turn, cannot take the seat either.
        val grab = heir.client.setParticipants(adr.id, roster(heir to AdrRole.APPROVER, cast.driver to AdrRole.DRIVER))
        assertEquals(HttpStatusCode.Forbidden, grab.status)
    }

    @Test
    fun `ADMIN may do anything with the seat, naming themselves included, at any status`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        cast.activate(adr)
        val heir = adrActor("heir")
        // Replace the Approver when ACTIVE, then take the seat, then clear it.
        assertEquals(
            HttpStatusCode.NoContent,
            cast.admin.client.setParticipants(adr.id, roster(cast.driver to AdrRole.DRIVER, heir to AdrRole.APPROVER)).status,
        )
        assertEquals(
            HttpStatusCode.NoContent,
            cast.admin.client.setParticipants(adr.id, roster(cast.driver to AdrRole.DRIVER, cast.admin to AdrRole.APPROVER)).status,
        )
        assertEquals(HttpStatusCode.NoContent, cast.admin.client.setParticipants(adr.id, roster(cast.driver to AdrRole.DRIVER)).status)
    }

    @Test
    fun `canChangeApprover is served per caller and status`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        fun seats() = listOf("driver", "approver", "contributor", "stranger", "admin")
        suspend fun flags() = seats().associateWith { cast.everyone.getValue(it).client.getAdr(adr.id).canChangeApprover }
        assertEquals(
            mapOf("driver" to true, "approver" to true, "contributor" to false, "stranger" to false, "admin" to true),
            flags(),
            "before review the Driver and the Approver",
        )
        cast.driver.client.moveAdrNow(adr.id, AdrStatus.IN_REVIEW)
        val inReview = flags()
        assertFalse(inReview.getValue("driver"))
        assertTrue(inReview.getValue("approver"))
        assertTrue(inReview.getValue("admin"))
        cast.approver.client.moveAdrNow(adr.id, AdrStatus.ACTIVE)
        assertEquals(inReview, flags(), "the same once ACTIVE")
        cast.approver.client.moveAdrNow(adr.id, AdrStatus.WITHDRAWN)
        assertTrue(flags().values.none { it }, "a terminal ADR changes nobody")
    }

    @Test
    fun `the send back is no way round the seat - once an ADR has been in review only its Approver or ADMIN changes the Approver`() =
        testApplication {
            usePostgresTestcontainer()
            val cast = adrCast()
            val adr = cast.raise(complete = true)
            cast.driver.client.moveAdrNow(adr.id, AdrStatus.IN_REVIEW)
            val accomplice = adrActor("accomplice")
            // Step 1: the Driver sends it back to draft (allowed, to fix the text).
            val draft = cast.driver.client.moveAdrNow(adr.id, AdrStatus.SOLUTION_DRAFT)
            assertEquals(AdrStatus.SOLUTION_DRAFT, draft.status)
            // Step 2: replacing the Approver in the draft is refused (or the accomplice would activate it after step 3).
            val swap = roster(cast.driver to AdrRole.DRIVER, cast.contributor to AdrRole.CONTRIBUTOR, accomplice to AdrRole.APPROVER)
            assertEquals(HttpStatusCode.Forbidden, cast.driver.client.setParticipants(adr.id, swap, draft.revision).status)
            val dropped = roster(cast.driver to AdrRole.DRIVER)
            assertEquals(HttpStatusCode.Forbidden, cast.driver.client.setParticipants(adr.id, dropped, draft.revision).status)
            // The verdict agrees: no seat for the Driver, one for the Approver and ADMIN.
            assertFalse(cast.driver.client.getAdr(adr.id).canChangeApprover)
            assertTrue(cast.approver.client.getAdr(adr.id).canChangeApprover)
            assertTrue(cast.admin.client.getAdr(adr.id).canChangeApprover)
            // The Driver keeps the rest: a Driver handover with the seat untouched.
            val keep = roster(cast.driver to AdrRole.DRIVER, cast.approver to AdrRole.APPROVER, accomplice to AdrRole.CONTRIBUTOR)
            assertEquals(HttpStatusCode.NoContent, cast.driver.client.setParticipants(adr.id, keep).status)
            // The Approver (and ADMIN) can still hand the seat on in the draft.
            val handOn = roster(cast.driver to AdrRole.DRIVER, accomplice to AdrRole.APPROVER)
            assertEquals(HttpStatusCode.NoContent, cast.approver.client.setParticipants(adr.id, handOn).status)
            val another = adrActor("another")
            val adminSwap = roster(cast.driver to AdrRole.DRIVER, another to AdrRole.APPROVER)
            assertEquals(HttpStatusCode.NoContent, cast.admin.client.setParticipants(adr.id, adminSwap).status)
            assertEquals(another.id, cast.admin.client.getAdr(adr.id).participants.single { it.role == AdrRole.APPROVER }.userId)
        }

    @Test
    fun `a stale editor that did not touch the seat gets the revision conflict, not a false 403`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        cast.driver.client.moveAdrNow(adr.id, AdrStatus.IN_REVIEW)
        val stale = cast.driver.client.getAdr(adr.id)
        val staleSet = stale.participants.map { AdrParticipantRequest(it.userId, it.role) }
        // Meanwhile the Approver hands the seat to someone else (the revision moves).
        val successor = adrActor("successor")
        val handOn = roster(cast.driver to AdrRole.DRIVER, successor to AdrRole.APPROVER)
        assertEquals(HttpStatusCode.NoContent, cast.approver.client.setParticipants(adr.id, handOn).status)
        // The Driver's editor still lists the old Approver, so against the locked set it looks like a seat change: it is stale first.
        val refused = cast.driver.client.setParticipants(adr.id, staleSet, stale.revision)
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals(ProblemTypes.REVISION_CONFLICT, refused.body<ProblemDetail>().type)
        // Reloaded, the same Driver is judged on the seat as usual.
        val fresh = cast.driver.client.getAdr(adr.id)
        val again = cast.driver.client.setParticipants(adr.id, staleSet, fresh.revision)
        assertEquals(HttpStatusCode.Forbidden, again.status)
    }

    @Test
    fun `an Approver whose account was deleted cannot be replaced by the Driver, ADMIN fixes it`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        cast.driver.client.moveAdrNow(adr.id, AdrStatus.IN_REVIEW)
        TestUsers.softDelete(cast.approver.id)
        val heir = adrActor("heir")
        val replace = roster(cast.driver to AdrRole.DRIVER, heir to AdrRole.APPROVER)
        assertEquals(HttpStatusCode.Forbidden, cast.driver.client.setParticipants(adr.id, replace).status)
        assertEquals(HttpStatusCode.Forbidden, cast.driver.client.setParticipants(adr.id, roster(cast.driver to AdrRole.DRIVER)).status)
        // The seat untouched (the deleted Approver stays listed) is not judged.
        val keep = roster(cast.driver to AdrRole.DRIVER, cast.approver to AdrRole.APPROVER, heir to AdrRole.CONTRIBUTOR)
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setParticipants(adr.id, keep).status)
        assertEquals(HttpStatusCode.NoContent, cast.admin.client.setParticipants(adr.id, replace).status)
        assertEquals(heir.id, cast.admin.client.getAdr(adr.id).participants.single { it.role == AdrRole.APPROVER }.userId)
    }
}
