package ch.nokillswit

import ch.nokillswit.adrs.AdrEventService
import ch.nokillswit.adrs.AdrEventType
import ch.nokillswit.adrs.AdrExpiryConfig
import ch.nokillswit.adrs.AdrExpirySweep
import ch.nokillswit.adrs.AdrExpirySweepKey
import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.adrs.readAdrExpiryConfig
import io.ktor.client.request.delete
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The expiry sweep (`adrs/AdrExpirySweep.kt`): an ACTIVE ADR whose expiry date is BEFORE today (UTC) becomes EXPIRED with a system
 * event, never earlier, once. The sweep is built directly with a pinned [Clock] ("today" is injectable); one test boots the real module
 * and its loop, and the config is boot-validated.
 */
class AdrExpirySweepTest {
    private fun clockAt(day: LocalDate): Clock = Clock.fixed(day.atTime(13, 30).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    private fun sweepAt(day: LocalDate) = AdrExpirySweep(sharedDatabaseForTests(), AdrEventService(sharedDatabaseForTests()), clockAt(day))

    @Test
    fun `an ACTIVE ADR expires the day after its date - not before, once, with a system event`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        cast.driver.client.moveAdrNow(adr.id, AdrStatus.IN_REVIEW)
        cast.approver.client.moveAdrNow(adr.id, AdrStatus.ACTIVE)
        val last = LocalDate.now(ZoneOffset.UTC).plusDays(30)
        val active = cast.driver.client.getAdr(adr.id)
        val dated = cast.driver.client.saveAdr(adr.id, active.revision, active.asUpdate().copy(expiresOn = last.toString()))
        assertEquals(HttpStatusCode.NoContent, dated.status)

        sweepAt(last.minusDays(1)).sweep()
        assertEquals(AdrStatus.ACTIVE, cast.stranger.client.getAdr(adr.id).status, "before the date")
        sweepAt(last).sweep()
        assertEquals(AdrStatus.ACTIVE, cast.stranger.client.getAdr(adr.id).status, "the date itself is the last day in force")

        val before = cast.stranger.client.getAdr(adr.id)
        withAuditCapture { capture ->
            assertTrue(sweepAt(last.plusDays(1)).sweep() >= 1)
            val expired = cast.stranger.client.getAdr(adr.id)
            assertEquals(AdrStatus.EXPIRED, expired.status)
            assertEquals(before.revision + 1, expired.revision)
            assertTrue(expired.statusChangedAt >= before.statusChangedAt)
            assertEquals(emptyList(), expired.allowedTransitions)
            val audited = capture.awaitEvent { it.message == "adr.expired" && it.hasKeyValue("adrId", adr.id.toLong()) }
            assertNotNull(audited)
            assertTrue(audited.hasKeyValue("expiresOn", last.toString()))

            // Idempotent: another sweep (or another replica's) finds nothing to do and records nothing more.
            val events = cast.stranger.client.adrEvents(adr.id).total
            sweepAt(last.plusDays(5)).sweep()
            assertEquals(events, cast.stranger.client.adrEvents(adr.id).total)
            assertEquals(expired.revision, cast.stranger.client.getAdr(adr.id).revision)
        }
        val system = cast.stranger.client.adrEvents(adr.id).items.single { it.type == AdrEventType.EXPIRED }
        assertNull(system.userId, "the system is the actor")
        assertNull(system.userName)
        assertEquals(mapOf("expiresOn" to last.toString()), system.params)
        assertEquals(1, cast.stranger.client.adrEvents(adr.id).items.count { it.type == AdrEventType.EXPIRED })
    }

    @Test
    fun `only ACTIVE ADRs with a date before today expire - other statuses, no date and a deleted ADR stay`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val past = "2001-01-01"
        val review = cast.raise(complete = true)
        cast.driver.client.moveAdrNow(review.id, AdrStatus.IN_REVIEW)
        forceAdrExpiry(review.id, past)
        val noDate = cast.raise(complete = true)
        forceAdrStatus(noDate.id, AdrStatus.ACTIVE)
        val draft = cast.raise()
        forceAdrExpiry(draft.id, past)
        val cancelled = cast.raise()
        cast.driver.client.moveAdrNow(cancelled.id, AdrStatus.CANCELLED)
        forceAdrExpiry(cancelled.id, past)
        val deleted = cast.raise(complete = true)
        forceAdrStatus(deleted.id, AdrStatus.ACTIVE)
        forceAdrExpiry(deleted.id, past)
        assertEquals(HttpStatusCode.NoContent, cast.admin.client.delete("/api/v1/adrs/${deleted.id}").status)
        val due = cast.raise(complete = true)
        forceAdrStatus(due.id, AdrStatus.ACTIVE)
        forceAdrExpiry(due.id, past)

        sweepAt(LocalDate.now(ZoneOffset.UTC)).sweep()
        suspend fun status(id: UInt) = cast.admin.client.getAdr(id).status
        assertEquals(AdrStatus.IN_REVIEW, status(review.id))
        assertEquals(AdrStatus.ACTIVE, status(noDate.id))
        assertEquals(AdrStatus.PROBLEM_RAISED, status(draft.id))
        assertEquals(AdrStatus.CANCELLED, status(cancelled.id))
        assertEquals(AdrStatus.EXPIRED, status(due.id))
        assertEquals(AdrStatus.ACTIVE.name, rawAdrStatus(deleted.id), "a soft-deleted ADR is not swept")
    }

    @Test
    fun `the real module runs the loop - a due ADR expires without a request`() = testApplication {
        lateinit var app: Application
        configureApp("adrs.expiry.enabled" to "true", "adrs.expiry.intervalSeconds" to "1")
        application { app = this }
        startApplication()
        assertNotNull(app.attributes.getOrNull(AdrExpirySweepKey), "the loop's sweep is published once the application started")
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        forceAdrStatus(adr.id, AdrStatus.ACTIVE)
        forceAdrExpiry(adr.id, "2001-01-01")
        withTimeout(30_000) {
            while (cast.admin.client.getAdr(adr.id).status != AdrStatus.EXPIRED) delay(200)
        }
        assertEquals(1, cast.admin.client.adrEvents(adr.id).items.count { it.type == AdrEventType.EXPIRED })
    }

    @Test
    fun `the sweep config is boot-validated, enabled or not`() {
        fun read(vararg pairs: Pair<String, String>) = readAdrExpiryConfig(MapApplicationConfig(*pairs))
        assertEquals(AdrExpiryConfig(true, 3600L), read("adrs.expiry.enabled" to "true", "adrs.expiry.intervalSeconds" to "3600"))
        assertEquals(AdrExpiryConfig(false, 1L), read("adrs.expiry.enabled" to "false", "adrs.expiry.intervalSeconds" to "1"))
        for (interval in listOf("0", "86401", "-5", "soon", "")) {
            val failure = assertFailsWith<IllegalStateException>(interval) {
                read("adrs.expiry.enabled" to "false", "adrs.expiry.intervalSeconds" to interval)
            }
            assertTrue("adrs.expiry.intervalSeconds" in failure.message.orEmpty(), failure.message)
        }
        val maybe = assertFailsWith<IllegalStateException> { read("adrs.expiry.enabled" to "maybe", "adrs.expiry.intervalSeconds" to "60") }
        assertTrue("adrs.expiry.enabled" in maybe.message.orEmpty())
    }

    @Test
    fun `an invalid sweep setting refuses to boot the application`() = testApplication {
        configureApp("adrs.expiry.intervalSeconds" to "0")
        assertStartupFails("adrs.expiry.intervalSeconds") { startApplication() }
    }
}
