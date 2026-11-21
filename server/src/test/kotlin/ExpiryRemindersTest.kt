package ch.nokillswit

import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.notifications.ExpiryReminders
import ch.nokillswit.notifications.NotificationType
import io.ktor.client.request.delete
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The expiry reminder (`notifications/ExpiryReminders.kt`): an ACTIVE ADR whose date falls within `reminderDays` notifies its Driver and
 * Approver once per expiry date; a changed date re-arms it. Built with a pinned clock far from the other tests' dates.
 */
class ExpiryRemindersTest {
    private val today = LocalDate.of(2100, 3, 1)

    private fun remindersAt(day: LocalDate, days: Int = 14) = ExpiryReminders(
        sharedDatabaseForTests(), newNotificationService(), days, Clock.fixed(day.atTime(9, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
    )

    private suspend fun AdrCast.activeAdr(expiresOn: String?): ch.nokillswit.adrs.AdrResponse {
        val adr = raise(complete = true)
        activate(adr)
        forceAdrExpiry(adr.id, expiresOn)
        return adr
    }

    @Test
    fun `the Driver and the Approver are reminded once, by the system, and nobody else`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.activeAdr(today.plusDays(10).toString())
        withAuditCapture { capture ->
            assertTrue(remindersAt(today).run() >= 1)
            val audit = capture.awaitEvent { it.message == "adr.expiry_reminded" && it.hasKeyValue("adrId", adr.id.toLong()) }
            assertNotNull(audit)
            assertTrue(audit.hasKeyValue("expiresOn", today.plusDays(10).toString()) && audit.hasKeyValue("recipients", 2))
            assertTrue(audit.keyValuePairs.orEmpty().none { it.key == "byUserId" }, "the system acts")
        }
        for (who in listOf(cast.driver, cast.approver)) {
            val notice = who.client.noticesOf(NotificationType.EXPIRY_REMINDER, adr.id).single()
            assertEquals(today.plusDays(10).toString(), notice.detail)
            assertNull(notice.actorId)
        }
        for (who in listOf(cast.contributor, cast.informed, cast.stranger)) {
            assertEquals(emptyList(), who.client.noticesOf(NotificationType.EXPIRY_REMINDER, adr.id))
        }
        remindersAt(today).run()
        remindersAt(today.plusDays(3)).run()
        assertEquals(1, cast.driver.client.noticesOf(NotificationType.EXPIRY_REMINDER, adr.id).size, "once per expiry date")
    }

    @Test
    fun `a changed date re-arms the reminder, the same date again does not`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.activeAdr(today.plusDays(5).toString())
        remindersAt(today).run()
        forceAdrExpiry(adr.id, today.plusDays(9).toString())
        remindersAt(today).run()
        assertEquals(
            listOf(today.plusDays(9).toString(), today.plusDays(5).toString()),
            cast.approver.client.noticesOf(NotificationType.EXPIRY_REMINDER, adr.id).map { it.detail },
        )
        forceAdrExpiry(adr.id, today.plusDays(9).toString())
        remindersAt(today).run()
        assertEquals(2, cast.approver.client.noticesOf(NotificationType.EXPIRY_REMINDER, adr.id).size)
    }

    @Test
    fun `only the window counts - today to reminderDays ahead inclusive, ACTIVE ADRs only`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val edge = cast.activeAdr(today.plusDays(14).toString())
        val first = cast.activeAdr(today.toString())
        val far = cast.activeAdr(today.plusDays(15).toString())
        val past = cast.activeAdr(today.minusDays(1).toString())
        val undated = cast.activeAdr(null)
        val draft = cast.raise()
        forceAdrExpiry(draft.id, today.plusDays(2).toString())
        val cancelled = cast.activeAdr(today.plusDays(2).toString())
        forceAdrStatus(cancelled.id, AdrStatus.CANCELLED)
        // A decision revoked (0.7.0) leaves its reminder marker behind harmlessly: it is not ACTIVE any more, so it is never reminded.
        val withdrawn = cast.activeAdr(today.plusDays(2).toString())
        cast.approver.client.moveAdrNow(withdrawn.id, AdrStatus.WITHDRAWN)
        remindersAt(today).run()
        val driverNotices = rawNotices(cast.driver.id).filter { it.type == "EXPIRY_REMINDER" }.map { it.adrId }
        assertTrue(edge.id in driverNotices && first.id in driverNotices, "both ends are in")
        assertTrue(listOf(far, past, undated, draft, cancelled, withdrawn).none { it.id in driverNotices })
        remindersAt(today.plusDays(1)).run()
        assertTrue(far.id in rawNotices(cast.driver.id).filter { it.type == "EXPIRY_REMINDER" }.map { it.adrId },
            "the next day it is within 14")
        assertEquals(0, remindersAt(today, days = 0).run(), "0 disables")
    }

    @Test
    fun `a soft-deleted ADR is not reminded about, and a recipient who switched the bell off is skipped`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val gone = cast.activeAdr(today.plusDays(3).toString())
        val quiet = cast.activeAdr(today.plusDays(4).toString())
        cast.admin.client.delete("/api/v1/adrs/${gone.id}")
        execRaw(PREFS_INSERT + "(${cast.driver.id}, 'EXPIRY_REMINDER', 'IN_APP')")
        withAuditCapture { capture ->
            remindersAt(today).run()
            val audit = capture.awaitEvent { it.message == "adr.expiry_reminded" && it.hasKeyValue("adrId", quiet.id.toLong()) }
            assertNotNull(audit)
            assertTrue(audit.hasKeyValue("recipients", 1), "the audit counts the rows inserted: the Driver switched the bell off")
        }
        assertEquals(emptyList(), rawNotices(cast.approver.id).filter { it.adrId == gone.id && it.type == "EXPIRY_REMINDER" })
        assertEquals(emptyList(), rawNotices(cast.driver.id).filter { it.adrId == quiet.id && it.type == "EXPIRY_REMINDER" })
        assertEquals(1, rawNotices(cast.approver.id).count { it.adrId == quiet.id && it.type == "EXPIRY_REMINDER" })
    }
}
