package ch.nokillswit

import ch.nokillswit.adrs.AdrParticipantRequest
import ch.nokillswit.adrs.AdrRole
import ch.nokillswit.adrs.AdrStanding
import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.adrs.AdrUpdateRequest
import ch.nokillswit.adrs.allowedTransitions
import ch.nokillswit.adrs.canChangeApprover
import ch.nokillswit.adrs.canEditContent
import ch.nokillswit.adrs.canEditExpiry
import ch.nokillswit.adrs.canManageParticipants
import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.infra.ProblemTypes
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The DACI verdict rows (`adrs/AdrAccess.kt`, `adr-model.md` "DACI roles and verdicts"), pure and over HTTP: for every caller (ADMIN,
 * Driver, Approver, Contributor, Informed, a stranger) and every move, edit and participants write, who may and who gets the 403 - and
 * that the 403 wins over a bad body, a bad header and a malformed JSON.
 */
class AdrAccessTest {
    private fun standing(role: AdrRole?, admin: Boolean = false) = AdrStanding(
        CallerPrincipal(1u, "x@test", if (admin) setOf(UserRole.ADMIN) else emptySet()),
        role,
    )

    private val pre = listOf(AdrStatus.PROBLEM_RAISED, AdrStatus.SOLUTION_DRAFT, AdrStatus.IN_REVIEW)

    @Test
    fun `pure verdicts - who may edit, change the expiry and manage participants in which status`() {
        for (status in AdrStatus.entries) {
            for (admin in listOf(true, false)) {
                for (role in listOf(null) + AdrRole.entries) {
                    val s = standing(role, admin)
                    val content = status in pre && (admin || role == AdrRole.DRIVER || role == AdrRole.CONTRIBUTOR)
                    val lead = admin || role == AdrRole.DRIVER || role == AdrRole.APPROVER
                    val terminal = status !in pre && status != AdrStatus.ACTIVE
                    // The expiry date of a decision in force is the Approver's (ADMIN's): the Driver may date a draft, not a live decision.
                    val expiry = when {
                        terminal -> false
                        status == AdrStatus.ACTIVE -> admin || role == AdrRole.APPROVER
                        else -> lead
                    }
                    assertEquals(content, canEditContent(s, status), "content $status admin=$admin role=$role")
                    assertEquals(expiry, canEditExpiry(s, status), "expiry $status admin=$admin role=$role")
                    assertEquals(lead && !terminal, canManageParticipants(s, status), "manage $status admin=$admin role=$role")
                    // The Approver seat: the leads until the ADR has been in review, only the Approver (and ADMIN) from then on
                    // (in review, in force, or a draft again after a send back), nobody once terminal.
                    for (reviewed in listOf(true, false)) {
                        val guarded = reviewed || status == AdrStatus.IN_REVIEW || status == AdrStatus.ACTIVE
                        val seat = lead && !terminal && (admin || !guarded || role == AdrRole.APPROVER)
                        assertEquals(seat, canChangeApprover(s, status, reviewed), "seat $status reviewed=$reviewed admin=$admin $role")
                    }
                }
            }
        }
    }

    @Test
    fun `pure verdicts - the moves each role is offered`() {
        fun offered(role: AdrRole?, status: AdrStatus, admin: Boolean = false) = allowedTransitions(standing(role, admin), status)
        assertEquals(
            listOf(AdrStatus.SOLUTION_DRAFT, AdrStatus.IN_REVIEW, AdrStatus.CANCELLED),
            offered(AdrRole.DRIVER, AdrStatus.PROBLEM_RAISED),
        )
        assertEquals(emptyList(), offered(AdrRole.APPROVER, AdrStatus.PROBLEM_RAISED))
        // The send back (IN_REVIEW to SOLUTION_DRAFT) is the Driver's AND the Approver's; from PROBLEM_RAISED it is the Driver's alone.
        assertEquals(listOf(AdrStatus.SOLUTION_DRAFT, AdrStatus.CANCELLED), offered(AdrRole.DRIVER, AdrStatus.IN_REVIEW))
        assertEquals(
            listOf(AdrStatus.SOLUTION_DRAFT, AdrStatus.ACTIVE, AdrStatus.WITHDRAWN),
            offered(AdrRole.APPROVER, AdrStatus.IN_REVIEW),
        )
        assertEquals(
            listOf(AdrStatus.SOLUTION_DRAFT, AdrStatus.ACTIVE, AdrStatus.CANCELLED, AdrStatus.WITHDRAWN),
            offered(null, AdrStatus.IN_REVIEW, admin = true),
        )
        assertEquals(emptyList(), offered(AdrRole.CONTRIBUTOR, AdrStatus.SOLUTION_DRAFT))
        // Withdrawing an ACTIVE decision is the Approver's (or ADMIN's) alone; the Driver is offered nothing once it is in force.
        assertEquals(listOf(AdrStatus.WITHDRAWN), offered(AdrRole.APPROVER, AdrStatus.ACTIVE))
        assertEquals(listOf(AdrStatus.WITHDRAWN), offered(null, AdrStatus.ACTIVE, admin = true))
        assertEquals(emptyList(), offered(AdrRole.INFORMED, AdrStatus.ACTIVE))
        assertEquals(emptyList(), offered(AdrRole.CONTRIBUTOR, AdrStatus.IN_REVIEW))
        assertEquals(emptyList(), offered(AdrRole.INFORMED, AdrStatus.SOLUTION_DRAFT))
        assertEquals(emptyList(), offered(null, AdrStatus.PROBLEM_RAISED))
        // Nobody is offered the system's moves, ADMIN included.
        assertEquals(emptyList(), offered(AdrRole.DRIVER, AdrStatus.ACTIVE))
    }

    private val moves: List<Triple<AdrStatus, AdrStatus, Set<String>>> = listOf(
        Triple(AdrStatus.PROBLEM_RAISED, AdrStatus.SOLUTION_DRAFT, setOf("admin", "driver")),
        Triple(AdrStatus.SOLUTION_DRAFT, AdrStatus.IN_REVIEW, setOf("admin", "driver")),
        Triple(AdrStatus.PROBLEM_RAISED, AdrStatus.CANCELLED, setOf("admin", "driver")),
        Triple(AdrStatus.IN_REVIEW, AdrStatus.ACTIVE, setOf("admin", "approver")),
        Triple(AdrStatus.IN_REVIEW, AdrStatus.WITHDRAWN, setOf("admin", "approver")),
        Triple(AdrStatus.IN_REVIEW, AdrStatus.SOLUTION_DRAFT, setOf("admin", "driver", "approver")),
        Triple(AdrStatus.ACTIVE, AdrStatus.WITHDRAWN, setOf("admin", "approver")),
    )

    @Test
    fun `every move row - exactly the documented roles may request it, everyone else is 403`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        for ((from, to, allowed) in moves) {
            for ((who, actor) in cast.everyone) {
                forceAdrStatus(adr.id, from)
                val response = actor.client.moveAdr(adr.id, actor.client.getAdr(adr.id).revision, to)
                val expected = if (who in allowed) HttpStatusCode.OK else HttpStatusCode.Forbidden
                assertEquals(expected, response.status, "$who moving $from -> $to")
            }
        }
    }

    @Test
    fun `the Driver cannot activate and the Approver cannot send a draft forward - the per-target verdict`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        // Both pass the coarse guard (Driver or Approver) and fail on the target, under the lock.
        assertEquals(HttpStatusCode.Forbidden, cast.approver.client.moveAdr(adr.id, adr.revision, AdrStatus.IN_REVIEW).status)
        forceAdrStatus(adr.id, AdrStatus.IN_REVIEW)
        assertEquals(HttpStatusCode.Forbidden, cast.driver.client.moveAdr(adr.id, adr.revision, AdrStatus.ACTIVE).status)
        // The system's own targets belong to nobody who asks.
        assertEquals(HttpStatusCode.Conflict, cast.admin.client.moveAdr(adr.id, adr.revision, AdrStatus.EXPIRED).status)
    }

    @Test
    fun `the response shows each caller the buttons the verdicts allow`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        fun seen(who: String) = cast.everyone.getValue(who).client
        val forward = listOf(AdrStatus.SOLUTION_DRAFT, AdrStatus.IN_REVIEW, AdrStatus.CANCELLED)
        assertEquals(forward, seen("driver").getAdr(adr.id).allowedTransitions)
        assertEquals(emptyList(), seen("approver").getAdr(adr.id).allowedTransitions)
        assertEquals(emptyList(), seen("informed").getAdr(adr.id).allowedTransitions)
        // The content, the expiry date and the participants, per caller, on a draft.
        val expectations = mapOf(
            "admin" to Triple(true, true, true), "driver" to Triple(true, true, true), "approver" to Triple(false, true, true),
            "contributor" to Triple(true, false, false), "informed" to Triple(false, false, false),
            "stranger" to Triple(false, false, false),
        )
        for ((who, flags) in expectations) {
            val view = seen(who).getAdr(adr.id)
            assertEquals(flags.first, view.canEditContent, "$who canEditContent")
            assertEquals(flags.second, view.canEditExpiry, "$who canEditExpiry")
            assertEquals(flags.third, view.canManageParticipants, "$who canManageParticipants")
        }
        // Once in force the date is the Approver's (and ADMIN's): the Driver's flag turns off, the content's is off for everyone.
        cast.activate(adr)
        for ((who, expiry) in mapOf("admin" to true, "driver" to false, "approver" to true, "contributor" to false)) {
            val view = seen(who).getAdr(adr.id)
            assertEquals(expiry, view.canEditExpiry, "$who canEditExpiry once ACTIVE")
            assertEquals(false, view.canEditContent, "$who canEditContent once ACTIVE")
        }
    }

    @Test
    fun `an edit - the content by the Driver, a Contributor or ADMIN, the expiry by the Driver, the Approver or ADMIN`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val contentEditors = setOf("admin", "driver", "contributor")
        val expiryEditors = setOf("admin", "driver", "approver")
        for ((who, actor) in cast.everyone) {
            val adr = cast.raise(complete = true)
            val content = actor.client.saveAdr(adr.id, adr.revision, adr.asUpdate().copy(context = "changed by $who"))
            val allowedContent = who in contentEditors
            assertEquals(if (allowedContent) HttpStatusCode.NoContent else HttpStatusCode.Forbidden, content.status, "$who edits content")
            val now = cast.admin.client.getAdr(adr.id)
            val expiry = actor.client.saveAdr(adr.id, now.revision, now.asUpdate().copy(expiresOn = "2099-01-01"))
            val allowedExpiry = who in expiryEditors
            assertEquals(if (allowedExpiry) HttpStatusCode.NoContent else HttpStatusCode.Forbidden, expiry.status, "$who edits expiry")
        }
    }

    @Test
    fun `the Driver cannot change the expiry of an ACTIVE ADR - the Approver and ADMIN can, a stale revision is still 409 first`() =
        testApplication {
            usePostgresTestcontainer()
            val cast = adrCast()
            val adr = cast.raise(complete = true)
            cast.activate(adr)
            val live = cast.admin.client.getAdr(adr.id)
            withAuditCapture { capture ->
                val refused = cast.driver.client.saveAdr(adr.id, live.revision, live.asUpdate().copy(expiresOn = "2099-01-01"))
                assertEquals(HttpStatusCode.Forbidden, refused.status)
                assertNotNull(refused.body<ProblemDetail>().detail)
                val denied = capture.awaitEvent { it.message == "authz.denied" && it.hasKeyValue("path", "/api/v1/adrs/${adr.id}") }
                assertNotNull(denied, "the 403 leaves the denial trail")
                assertTrue(denied.hasKeyValue("byUserId", cast.driver.id.toLong()))
            }
            val untouched = cast.admin.client.getAdr(adr.id)
            assertEquals(live.expiresOn, untouched.expiresOn, "the date is unchanged")
            assertEquals(live.revision, untouched.revision, "a refused write bumps nothing")

            // A stale revision is judged before the per-field verdict: the Driver's stale save is a 409, never a false 403.
            val stale = cast.driver.client.saveAdr(adr.id, live.revision - 1, live.asUpdate().copy(expiresOn = "2099-01-01"))
            assertEquals(HttpStatusCode.Conflict, stale.status)
            assertEquals(ProblemTypes.REVISION_CONFLICT, stale.body<ProblemDetail>().type)

            // A save that changes nothing is a no-op for the Driver: 204, nothing written.
            assertEquals(HttpStatusCode.NoContent, cast.driver.client.saveAdr(adr.id, live.revision, live.asUpdate()).status)
            assertEquals(live.revision, cast.admin.client.getAdr(adr.id).revision)

            val set = cast.approver.client.saveAdr(adr.id, live.revision, live.asUpdate().copy(expiresOn = "2099-01-01"))
            assertEquals(HttpStatusCode.NoContent, set.status)
            val dated = cast.admin.client.getAdr(adr.id)
            assertEquals("2099-01-01", dated.expiresOn)
            val moved = cast.admin.client.saveAdr(adr.id, dated.revision, dated.asUpdate().copy(expiresOn = "2099-02-02"))
            assertEquals(HttpStatusCode.NoContent, moved.status, "ADMIN changes it too")
            assertEquals("2099-02-02", cast.admin.client.getAdr(adr.id).expiresOn)
        }

    @Test
    fun `the Driver still changes the expiry date while the ADR is not in force`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        val dated = cast.driver.client.saveAdr(adr.id, adr.revision, adr.asUpdate().copy(expiresOn = "2099-01-01"))
        assertEquals(HttpStatusCode.NoContent, dated.status)
        cast.driver.client.moveAdrNow(adr.id, AdrStatus.IN_REVIEW)
        val inReview = cast.driver.client.getAdr(adr.id)
        val again = cast.driver.client.saveAdr(adr.id, inReview.revision, inReview.asUpdate().copy(expiresOn = "2099-03-03"))
        assertEquals(HttpStatusCode.NoContent, again.status, "in review the Driver still dates it")
        assertEquals("2099-03-03", cast.admin.client.getAdr(adr.id).expiresOn)
    }

    @Test
    fun `the participants - the Driver, the Approver or ADMIN replace them, everyone else is 403`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        val same = listOf(
            AdrParticipantRequest(cast.driver.id, AdrRole.DRIVER),
            AdrParticipantRequest(cast.approver.id, AdrRole.APPROVER),
            AdrParticipantRequest(cast.contributor.id, AdrRole.CONTRIBUTOR),
            AdrParticipantRequest(cast.informed.id, AdrRole.INFORMED),
        )
        val allowed = setOf("admin", "driver", "approver")
        for ((who, actor) in cast.everyone) {
            val expected = if (who in allowed) HttpStatusCode.NoContent else HttpStatusCode.Forbidden
            assertEquals(expected, actor.client.setParticipants(adr.id, same).status, "$who replaces participants")
        }
    }

    @Test
    fun `delete is ADMIN only and guarded before the id lookup`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        for ((who, actor) in cast.everyone - "admin") {
            assertEquals(HttpStatusCode.Forbidden, actor.client.delete("/api/v1/adrs/${adr.id}").status, "$who deletes")
            // Uniform 403 whether or not the id exists: the guard runs before the read.
            assertEquals(HttpStatusCode.Forbidden, actor.client.delete("/api/v1/adrs/999999").status, "$who deletes a missing ADR")
        }
        assertEquals(HttpStatusCode.NoContent, cast.admin.client.delete("/api/v1/adrs/${adr.id}").status)
    }

    @Test
    fun `403 wins over 400 - an invalid body, a missing header and a malformed JSON stay 403 for the wrong role`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        val invalidDocument = adr.asUpdate().copy(name = "", problem = "")
        for (actor in listOf(cast.informed, cast.stranger)) {
            assertEquals(HttpStatusCode.Forbidden, actor.client.saveAdr(adr.id, adr.revision, invalidDocument).status)
            val noHeader = actor.client.put("/api/v1/adrs/${adr.id}") {
                contentType(ContentType.Application.Json)
                setBody("{ not json")
            }
            assertEquals(HttpStatusCode.Forbidden, noHeader.status)
        }
        for (actor in listOf(cast.contributor, cast.informed, cast.stranger)) {
            val badMove = actor.client.post("/api/v1/adrs/${adr.id}/transitions") {
                header("X-Expected-Revision", "not-a-number")
                contentType(ContentType.Application.Json)
                setBody("""{"to":"NOT_A_STATUS"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, badMove.status, "a bad transition request from a caller without the role")
            val badSet = actor.client.setParticipants(adr.id, emptyList())
            assertEquals(HttpStatusCode.Forbidden, badSet.status, "an empty participant set from a caller without the role")
        }
        // The same requests from a caller who may ask ARE judged: 400.
        assertEquals(HttpStatusCode.BadRequest, cast.driver.client.saveAdr(adr.id, adr.revision, invalidDocument).status)
        assertEquals(HttpStatusCode.BadRequest, cast.driver.client.setParticipants(adr.id, emptyList()).status)
        val unknown = cast.driver.client.post("/api/v1/adrs/${adr.id}/transitions") {
            header("X-Expected-Revision", adr.revision.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"to":"NOT_A_STATUS"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
    }

    @Test
    fun `a missing ADR is 404 for every read and write, before any guard`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val client = cast.stranger.client
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/adrs/999999").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/adrs/999999/events").status)
        assertEquals(HttpStatusCode.NotFound, client.saveAdr(999999u, 1, AdrUpdateRequest("n", "p")).status)
        assertEquals(HttpStatusCode.NotFound, client.moveAdr(999999u, 1, AdrStatus.CANCELLED).status)
        assertEquals(HttpStatusCode.NotFound, client.setParticipants(999999u, emptyList()).status)
    }
}
