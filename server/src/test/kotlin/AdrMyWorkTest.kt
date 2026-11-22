package ch.nokillswit

import ch.nokillswit.adrs.AdrEventService
import ch.nokillswit.adrs.AdrEventType
import ch.nokillswit.adrs.AdrMyWorkReason
import ch.nokillswit.adrs.AdrMyWorkResponse
import ch.nokillswit.adrs.AdrMyWorkService
import ch.nokillswit.adrs.AdrRole
import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.adrs.DiscussionKind
import ch.nokillswit.adrs.adrDiscussionPostedEvent
import ch.nokillswit.adrs.adrTagsChangedEvent
import ch.nokillswit.adrs.adrUpdatedEvent
import ch.nokillswit.infra.db.FieldChange
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import java.time.LocalDate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdrMyWorkTest {
    private suspend fun HttpClient.myWork(query: String = ""): AdrMyWorkResponse {
        val response = get("/api/v1/adrs/my-work$query")
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body()
    }

    /** Moves every existing event of one ADR to [at], so the test controls its activity and stall boundary without minting fake events. */
    private suspend fun forceEventTimes(id: UInt, at: Long) {
        suspendTransaction(sharedDatabaseForTests()) {
            AdrEventService.AdrEvents.update({ AdrEventService.AdrEvents.ownerId eq id }) {
                it[createdAt] = at
            }
        }
    }

    private suspend fun recordEvents(id: UInt, actorId: UInt, at: Long, vararg events: ch.nokillswit.adrs.AdrEventDescriptor) {
        suspendTransaction(sharedDatabaseForTests()) {
            AdrEventService(sharedDatabaseForTests()).recordAll(id, actorId, events.toList(), at)
        }
    }

    @Test
    fun `the personal view separates actions from watching and filters by the caller's actual DACI role`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val now = System.currentTimeMillis()
        val stale = now - 15L * 24 * 60 * 60 * 1000

        val draft = cast.raise()
        forceAdrTimes(draft.id, stale, stale)
        forceAdrStatusChangedAt(draft.id, stale)
        forceEventTimes(draft.id, stale)

        val review = cast.raise()
        cast.driver.client.moveAdrNow(review.id, AdrStatus.IN_REVIEW)
        val reviewAt = now - 8L * 24 * 60 * 60 * 1000
        forceAdrStatusChangedAt(review.id, reviewAt)
        forceEventTimes(review.id, reviewAt)

        val returned = cast.raise()
        cast.driver.client.moveAdrNow(returned.id, AdrStatus.IN_REVIEW)
        cast.approver.client.moveAdrNow(returned.id, AdrStatus.SOLUTION_DRAFT, "Needs another option")

        val expiring = cast.raise()
        cast.activate(expiring)
        forceAdrExpiry(expiring.id, LocalDate.now(ZoneOffset.UTC).plusDays(3).toString())

        val issue = cast.raise()
        cast.activate(issue)
        cast.stranger.client.postEntryOk(issue.id, DiscussionKind.ESCALATION)

        val driver = cast.driver.client.myWork()
        assertEquals(AdrRole.DRIVER, driver.actions.items.first { it.adr.id == draft.id }.adr.role)
        assertEquals(AdrMyWorkReason.STALLED_DRAFT, driver.actions.items.first { it.adr.id == draft.id }.reason)
        assertEquals(AdrMyWorkReason.RETURNED, driver.actions.items.first { it.adr.id == returned.id }.reason)
        assertEquals(AdrMyWorkReason.ESCALATION, driver.actions.items.first { it.adr.id == issue.id }.reason)
        assertEquals(AdrMyWorkReason.STALLED_REVIEW, driver.watching.items.first { it.adr.id == review.id }.reason)
        assertEquals(AdrMyWorkReason.EXPIRING, driver.watching.items.first { it.adr.id == expiring.id }.reason)

        val approver = cast.approver.client.myWork("?participantRole=APPROVER")
        assertTrue(approver.adrs.items.all { it.role == AdrRole.APPROVER })
        assertEquals(AdrMyWorkReason.STALLED_REVIEW, approver.actions.items.first { it.adr.id == review.id }.reason)
        assertEquals(AdrMyWorkReason.EXPIRING, approver.actions.items.first { it.adr.id == expiring.id }.reason)
        assertEquals(AdrMyWorkReason.ESCALATION, approver.actions.items.first { it.adr.id == issue.id }.reason)
        assertFalse(approver.actions.items.any { it.adr.id == draft.id })

        val informed = cast.informed.client.myWork("?participantRole=INFORMED")
        assertTrue(informed.actions.items.isEmpty())
        assertTrue(informed.watching.items.map { it.adr.id }.containsAll(listOf(review.id, expiring.id, issue.id)))
        assertTrue(informed.adrs.items.all { it.role == AdrRole.INFORMED })

        assertTrue(driver.activity.items.any { it.adr.id == issue.id && it.event.type.name == "ESCALATED" })
        assertTrue(
            driver.activity.items.none {
                it.event.type in setOf(
                    AdrEventType.PARTICIPANT_ADDED,
                    AdrEventType.PARTICIPANT_REMOVED,
                    AdrEventType.PARTICIPANT_CHANGED,
                    AdrEventType.TAGS_CHANGED,
                )
            },
        )
        assertTrue(driver.generatedAt > 0)
    }

    @Test
    fun `the role filter is strict and each section reports when its twelve-row cap omitted rows`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val owned = (1..13).map { cast.raise() }

        val recent = cast.driver.client.myWork("?participantRole=DRIVER")
        assertEquals(12, recent.adrs.items.size)
        assertTrue(recent.adrs.hasMore)
        assertTrue(recent.activity.hasMore)
        assertTrue(recent.adrs.items.all { it.role == AdrRole.DRIVER })
        assertTrue(recent.adrs.items.all { it.adrIsOneOf(owned) })

        val stale = System.currentTimeMillis() - 15L * 24 * 60 * 60 * 1000
        owned.forEach {
            forceAdrTimes(it.id, stale, stale)
            forceAdrStatusChangedAt(it.id, stale)
            forceEventTimes(it.id, stale)
        }
        val stalled = cast.driver.client.myWork("?participantRole=DRIVER")
        assertEquals(12, stalled.actions.items.size)
        assertTrue(stalled.actions.hasMore)

        assertEquals(HttpStatusCode.BadRequest, cast.driver.client.get("/api/v1/adrs/my-work?participantRole=OWNER").status)
        assertEquals(
            HttpStatusCode.BadRequest,
            cast.driver.client.get("/api/v1/adrs/my-work?participantRole=DRIVER&participantRole=APPROVER").status,
        )
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get("/api/v1/adrs/my-work").status)
    }

    @Test
    fun `stall boundaries and progress rules are exact while a zero reminder horizon disables expiry attention`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val instant = Instant.parse("2030-06-20T12:00:00Z")
        val clock = Clock.fixed(instant, ZoneOffset.UTC)
        val threshold = instant.toEpochMilli() - 14L * 24 * 60 * 60 * 1000
        val atBoundary = cast.raise()
        val stillMoving = cast.raise()
        listOf(atBoundary to threshold, stillMoving to threshold + 1).forEach { (adr, at) ->
            forceAdrTimes(adr.id, at, at)
            forceAdrStatusChangedAt(adr.id, at)
            forceEventTimes(adr.id, at)
        }

        val resetByDocument = cast.raise()
        val noisyDraft = cast.raise()
        listOf(resetByDocument, noisyDraft).forEach { adr ->
            forceAdrTimes(adr.id, threshold, threshold)
            forceAdrStatusChangedAt(adr.id, threshold)
            forceEventTimes(adr.id, threshold)
        }
        recordEvents(
            resetByDocument.id,
            cast.driver.id,
            threshold + 1,
            adrUpdatedEvent(listOf(FieldChange("decision", "Old", "New"))),
        )
        recordEvents(
            noisyDraft.id,
            cast.driver.id,
            threshold + 1,
            adrUpdatedEvent(listOf(FieldChange("expiresOn", null, "2030-12-01"))),
            adrTagsChangedEvent(listOf("security"), emptyList()),
            adrDiscussionPostedEvent(1u, DiscussionKind.COMMENT),
        )

        val reviewThreshold = instant.toEpochMilli() - 7L * 24 * 60 * 60 * 1000
        val reviewBoundary = cast.raise()
        val reviewRecent = cast.raise()
        listOf(reviewBoundary to reviewThreshold, reviewRecent to reviewThreshold + 1).forEach { (adr, at) ->
            cast.driver.client.moveAdrNow(adr.id, AdrStatus.IN_REVIEW)
            forceAdrStatusChangedAt(adr.id, at)
            forceEventTimes(adr.id, at)
        }
        val expiring = cast.raise()
        cast.activate(expiring)
        forceAdrExpiry(expiring.id, "2030-06-19") // overdue but still ACTIVE until the expiry sweep moves it

        val enabled = AdrMyWorkService(sharedDatabaseForTests(), expiryReminderDays = 14, clock = clock)
            .myWork(cast.driver.id, AdrRole.DRIVER)
        assertEquals(AdrMyWorkReason.STALLED_DRAFT, enabled.actions.items.single { it.adr.id == atBoundary.id }.reason)
        assertFalse(enabled.actions.items.any { it.adr.id == stillMoving.id })
        assertFalse(enabled.actions.items.any { it.adr.id == resetByDocument.id })
        assertEquals(AdrMyWorkReason.STALLED_DRAFT, enabled.actions.items.single { it.adr.id == noisyDraft.id }.reason)
        assertEquals(AdrMyWorkReason.EXPIRING, enabled.watching.items.single { it.adr.id == expiring.id }.reason)

        val approverWork = AdrMyWorkService(sharedDatabaseForTests(), expiryReminderDays = 14, clock = clock)
            .myWork(cast.approver.id, AdrRole.APPROVER)
        assertEquals(AdrMyWorkReason.STALLED_REVIEW, approverWork.actions.singleFor(reviewBoundary.id).reason)
        assertEquals(AdrMyWorkReason.REVIEW, approverWork.actions.singleFor(reviewRecent.id).reason)

        val disabled = AdrMyWorkService(sharedDatabaseForTests(), expiryReminderDays = 0, clock = clock)
            .myWork(cast.driver.id, AdrRole.DRIVER)
        assertFalse(disabled.watching.items.any { it.adr.id == expiring.id })
    }

    private fun ch.nokillswit.adrs.AdrMyWorkAdr.adrIsOneOf(adrs: List<ch.nokillswit.adrs.AdrResponse>) = adrs.any { it.id == id }

    private fun ch.nokillswit.adrs.AdrMyWorkAttentionSection.singleFor(id: UInt) = items.single { it.adr.id == id }
}
