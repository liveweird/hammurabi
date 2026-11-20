package ch.nokillswit

import ch.nokillswit.adrs.ALLOWED_TRANSITIONS
import ch.nokillswit.adrs.AdrParticipantRequest
import ch.nokillswit.adrs.AdrRecord
import ch.nokillswit.adrs.AdrResponse
import ch.nokillswit.adrs.AdrRole
import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.adrs.SYSTEM_STATUSES
import ch.nokillswit.adrs.canRequest
import ch.nokillswit.adrs.canTransitionTo
import ch.nokillswit.adrs.contentEditable
import ch.nokillswit.adrs.defaultAdrName
import ch.nokillswit.adrs.adrKey
import ch.nokillswit.adrs.missingForTransition
import ch.nokillswit.adrs.requireTransitionTo
import ch.nokillswit.adrs.terminal
import ch.nokillswit.authz.ConflictException
import ch.nokillswit.infra.ProblemTypes
import ch.nokillswit.plugins.ProblemDetail
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ADR lifecycle map (`adrs/AdrLifecycle.kt`): the pure map, and EVERY (from, to) pair of it over HTTP - the legal ones move, every
 * other one (including the moves only the system makes) is the typed `illegal-transition` 409 - plus the requirements of the review.
 */
class AdrLifecycleTest {
    private val expected: Map<AdrStatus, Set<AdrStatus>> = mapOf(
        AdrStatus.PROBLEM_RAISED to setOf(AdrStatus.SOLUTION_DRAFT, AdrStatus.IN_REVIEW, AdrStatus.CANCELLED),
        AdrStatus.SOLUTION_DRAFT to setOf(AdrStatus.IN_REVIEW, AdrStatus.CANCELLED),
        AdrStatus.IN_REVIEW to setOf(AdrStatus.ACTIVE, AdrStatus.CANCELLED, AdrStatus.WITHDRAWN),
        AdrStatus.ACTIVE to setOf(AdrStatus.EXPIRED, AdrStatus.SUPERSEDED),
    )

    @Test
    fun `the map is exactly the documented lifecycle and every other status is terminal`() {
        for (from in AdrStatus.entries) {
            assertEquals(expected[from].orEmpty(), ALLOWED_TRANSITIONS.getValue(from), "edges out of $from")
            assertEquals(expected[from] == null, from.terminal, "$from terminal")
            for (to in AdrStatus.entries) {
                assertEquals(to in expected[from].orEmpty(), from.canTransitionTo(to), "$from -> $to")
            }
        }
    }

    @Test
    fun `a user can request every edge except the ones into the system statuses`() {
        assertEquals(setOf(AdrStatus.EXPIRED, AdrStatus.SUPERSEDED), SYSTEM_STATUSES)
        for (from in AdrStatus.entries) {
            for (to in AdrStatus.entries) {
                assertEquals(from.canTransitionTo(to) && to !in SYSTEM_STATUSES, from.canRequest(to), "$from -> $to")
            }
        }
    }

    @Test
    fun `only the three pre-decision statuses have editable content`() {
        val editable = AdrStatus.entries.filter { it.contentEditable }
        assertEquals(listOf(AdrStatus.PROBLEM_RAISED, AdrStatus.SOLUTION_DRAFT, AdrStatus.IN_REVIEW), editable)
    }

    @Test
    fun `requireTransitionTo throws the typed illegal-transition conflict`() {
        val illegal = assertFailsWith<ConflictException> { AdrStatus.CANCELLED.requireTransitionTo(AdrStatus.ACTIVE) }
        assertEquals(ProblemTypes.ILLEGAL_TRANSITION, illegal.problemType)
        val system = assertFailsWith<ConflictException> { AdrStatus.ACTIVE.requireTransitionTo(AdrStatus.EXPIRED) }
        assertEquals(ProblemTypes.ILLEGAL_TRANSITION, system.problemType)
        AdrStatus.IN_REVIEW.requireTransitionTo(AdrStatus.ACTIVE)
    }

    private val today = LocalDate.parse("2026-11-20")
    private fun record(
        context: String = "c",
        decision: String = "d",
        consequences: String = "q",
        expiresOn: String? = null,
    ) = AdrRecord(
        id = 1u, number = 1, name = "n", problem = "p", context = context, decision = decision, alternatives = "",
        consequences = consequences, status = AdrStatus.SOLUTION_DRAFT, expiresOn = expiresOn, revision = 1,
        createdBy = 1u, createdAt = 0, updatedAt = 0, statusChangedAt = 0,
    )

    @Test
    fun `entering review or active names every missing field, other targets need nothing`() {
        assertEquals(
            listOf("context", "decision", "consequences", "approver"),
            missingForTransition(AdrStatus.IN_REVIEW, record("", " ", ""), hasApprover = false, today = today),
        )
        assertEquals(emptyList(), missingForTransition(AdrStatus.IN_REVIEW, record(), hasApprover = true, today = today))
        assertEquals(listOf("decision"), missingForTransition(AdrStatus.ACTIVE, record(decision = ""), hasApprover = true, today = today))
        assertEquals(emptyList(), missingForTransition(AdrStatus.CANCELLED, record("", "", ""), hasApprover = false, today = today))
        assertEquals(emptyList(), missingForTransition(AdrStatus.SOLUTION_DRAFT, record("", "", ""), hasApprover = false, today = today))
    }

    @Test
    fun `entering active refuses a date already in the past but review does not care`() {
        val past = record(expiresOn = "2026-11-19")
        assertEquals(listOf("expiresOn (must not be in the past)"), missingForTransition(AdrStatus.ACTIVE, past, true, today))
        assertEquals(emptyList(), missingForTransition(AdrStatus.ACTIVE, record(expiresOn = "2026-11-20"), true, today))
        assertEquals(emptyList(), missingForTransition(AdrStatus.IN_REVIEW, past, true, today))
    }

    @Test
    fun `the key pads the number to four digits and the default name is the first line cut to 120`() {
        assertEquals("ADR-0007", adrKey(7))
        assertEquals("ADR-0042", adrKey(42))
        assertEquals("ADR-12345", adrKey(12345))
        assertEquals("First", defaultAdrName("\n  First  \nSecond"))
        assertEquals("a".repeat(120), defaultAdrName("a".repeat(500)))
        // A surrogate pair straddling the cut is dropped whole, never split.
        assertEquals("a".repeat(119), defaultAdrName("a".repeat(119) + "😀" + "b"))
        assertEquals("", defaultAdrName("   \n  "))
        // Tabs and whitespace runs collapse to single spaces, so the defaulted name is a valid single-line name.
        assertEquals("a b c", defaultAdrName("a\tb  \t c\nnext"))
    }

    @Test
    fun `every pair of the map over HTTP - legal moves move, everything else is a typed 409`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        // ADMIN holds every role verdict, so only the lifecycle map decides here; the ADR has the content and the Approver a review needs.
        val adr = cast.raise(complete = true)
        for (from in AdrStatus.entries) {
            for (to in AdrStatus.entries) {
                forceAdrStatus(adr.id, from)
                val revision = cast.admin.client.getAdr(adr.id).revision
                val response = cast.admin.client.moveAdr(adr.id, revision, to)
                if (from.canRequest(to)) {
                    assertEquals(HttpStatusCode.OK, response.status, "$from -> $to should move")
                    assertEquals(to, response.body<AdrResponse>().status)
                    assertEquals(revision + 1, cast.admin.client.getAdr(adr.id).revision, "$from -> $to bumps the revision")
                } else {
                    assertEquals(HttpStatusCode.Conflict, response.status, "$from -> $to is illegal")
                    assertEquals(ProblemTypes.ILLEGAL_TRANSITION, response.body<ProblemDetail>().type, "$from -> $to")
                    assertEquals(revision, cast.admin.client.getAdr(adr.id).revision, "an illegal move writes nothing")
                }
            }
        }
    }

    @Test
    fun `the happy path walks from raised to active and every step is in the history`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        assertEquals(AdrStatus.PROBLEM_RAISED, adr.status)
        assertEquals(AdrStatus.SOLUTION_DRAFT, cast.driver.client.moveAdrNow(adr.id, AdrStatus.SOLUTION_DRAFT).status)
        assertEquals(AdrStatus.IN_REVIEW, cast.driver.client.moveAdrNow(adr.id, AdrStatus.IN_REVIEW).status)
        val active = cast.approver.client.moveAdrNow(adr.id, AdrStatus.ACTIVE)
        assertEquals(AdrStatus.ACTIVE, active.status)
        assertTrue(active.statusChangedAt >= adr.statusChangedAt)
        val moves = cast.admin.client.adrEventsOldestFirst(adr.id).filter { it.type.name == "TRANSITIONED" }.map { it.params }
        assertEquals(
            listOf(
                mapOf("from" to "PROBLEM_RAISED", "to" to "SOLUTION_DRAFT"),
                mapOf("from" to "SOLUTION_DRAFT", "to" to "IN_REVIEW"),
                mapOf("from" to "IN_REVIEW", "to" to "ACTIVE"),
            ),
            moves,
        )
    }

    @Test
    fun `a problem that arrives with its solution skips the draft and withdrawal ends a review`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        assertEquals(AdrStatus.IN_REVIEW, cast.driver.client.moveAdrNow(adr.id, AdrStatus.IN_REVIEW).status)
        assertEquals(AdrStatus.WITHDRAWN, cast.approver.client.moveAdrNow(adr.id, AdrStatus.WITHDRAWN).status)
        val none = cast.admin.client.getAdr(adr.id)
        assertEquals(emptyList(), none.allowedTransitions, "a terminal ADR offers no move")
        assertFalse(none.canEdit)
        assertFalse(none.canManageParticipants)
    }

    @Test
    fun `entering review without its fields is a 400 naming them, and entering active re-checks the approver`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val bare = cast.raise(complete = false)
        val missing = cast.driver.client.moveAdr(bare.id, bare.revision, AdrStatus.IN_REVIEW)
        assertEquals(HttpStatusCode.BadRequest, missing.status)
        val detail = missing.body<ProblemDetail>().detail.orEmpty()
        listOf("context", "decision", "consequences").forEach { assertTrue(it in detail, "names $it: $detail") }
        assertFalse("approver" in detail, "the Approver is assigned: $detail")

        val noApprover = cast.driver.client.createAdr(complete = true)
        val review = cast.driver.client.moveAdr(noApprover.id, noApprover.revision, AdrStatus.IN_REVIEW)
        assertEquals(HttpStatusCode.BadRequest, review.status)
        assertTrue("approver" in review.body<ProblemDetail>().detail.orEmpty())

        // A review that lost its Approver after the move cannot be activated, not even by an ADMIN.
        val adr = cast.raise(complete = true)
        cast.driver.client.moveAdrNow(adr.id, AdrStatus.IN_REVIEW)
        val driverOnly = listOf(AdrParticipantRequest(cast.driver.id, AdrRole.DRIVER))
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setParticipants(adr.id, driverOnly).status)
        val activate = cast.admin.client.moveAdr(adr.id, cast.admin.client.getAdr(adr.id).revision, AdrStatus.ACTIVE)
        assertEquals(HttpStatusCode.BadRequest, activate.status)
        assertTrue("approver" in activate.body<ProblemDetail>().detail.orEmpty())
    }

    @Test
    fun `a transition to a past expiry date is refused when it would activate`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        cast.driver.client.moveAdrNow(adr.id, AdrStatus.IN_REVIEW)
        forceAdrExpiry(adr.id, "2000-01-01")
        val refused = cast.approver.client.moveAdr(adr.id, cast.approver.client.getAdr(adr.id).revision, AdrStatus.ACTIVE)
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertTrue("expiresOn" in refused.body<ProblemDetail>().detail.orEmpty())
    }

    @Test
    fun `a deleted Approver account does not count as an Approver`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        cast.driver.client.moveAdrNow(adr.id, AdrStatus.IN_REVIEW)
        TestUsers.softDelete(cast.approver.id)
        val activate = cast.admin.client.moveAdr(adr.id, cast.admin.client.getAdr(adr.id).revision, AdrStatus.ACTIVE)
        assertEquals(HttpStatusCode.BadRequest, activate.status)
        assertTrue("approver" in activate.body<ProblemDetail>().detail.orEmpty())
    }
}
