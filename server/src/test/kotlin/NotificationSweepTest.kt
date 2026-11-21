package ch.nokillswit

import ch.nokillswit.notifications.ExpiryRemindersKey
import ch.nokillswit.notifications.NotificationSweep
import ch.nokillswit.notifications.NotificationSweepKey
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The notification sweep (`NotificationSweep.kt`): the digest every tick, the reminders and the retention hourly, one failure never
// stops the next.
class NotificationSweepTest {

    @Test
    fun `the digest runs every tick, the reminders and the purge once an hour and on the first tick`() = runBlocking {
        var now = 1_000_000_000L
        var digests = 0
        var reminders = 0
        val cutoffs = mutableListOf<Long>()
        val sweep = NotificationSweep(
            retentionMillis = 90 * 86_400_000L,
            clock = { now },
            purge = { cutoffs += it; 7 },
            digest = { digests++ },
            remind = { reminders++ },
        )
        assertEquals(7, sweep.tick())
        assertEquals(1 to 1, digests to reminders)
        now += 60_000
        assertNull(sweep.tick(), "no housekeeping inside the hour")
        assertEquals(2 to 1, digests to reminders)
        now += 3_600_000
        assertEquals(7, sweep.tick())
        assertEquals(3 to 2, digests to reminders)
        assertEquals(listOf(1_000_000_000L - 90 * 86_400_000L, now - 90 * 86_400_000L), cutoffs)
    }

    @Test
    fun `a failing digest or reminder pass never stops the purge, and a failing purge answers null`() = runBlocking {
        var purged = 0
        val sweep = NotificationSweep(
            retentionMillis = 1,
            clock = { 5L },
            purge = { purged++; 0 },
            digest = { error("smtp down") },
            remind = { error("db down") },
        )
        assertEquals(0, sweep.tick())
        assertEquals(1, purged)
        val failing = NotificationSweep(retentionMillis = 1, clock = { 5L }, purge = { error("db down") })
        assertNull(failing.tick())
    }

    @Test
    fun `the module boots with the sweep on, runs its first pass and publishes the handles`() = testApplication {
        configureApp("notifications.sweepEnabled" to "true", "notifications.sweepIntervalSeconds" to "3600")
        application { }
        startApplication()
        val first = withTimeout(30_000) { application.attributes[NotificationSweepKey].await() }
        assertNotNull(first)
        assertTrue(first >= 0)
        assertNotNull(application.attributes[ExpiryRemindersKey])
    }

    @Test
    fun `a malformed or out-of-range notification setting refuses to start, naming the key`() {
        for ((key, values) in listOf(
            "notifications.retentionDays" to listOf("0", "3651", "abc"),
            "notifications.sweepIntervalSeconds" to listOf("9", "3601", "abc"),
            "notifications.email.delaySeconds" to listOf("-1", "86401", "abc"),
            "notifications.email.minIntervalSeconds" to listOf("-1", "86401"),
            "notifications.email.maxPerRun" to listOf("0", "1001"),
            "adrs.expiry.reminderDays" to listOf("-1", "366", "abc"),
            "notifications.sweepEnabled" to listOf("maybe"),
        )) {
            for (value in values) {
                testApplication {
                    configureApp(key to value)
                    assertStartupFails(key) { startApplication() }
                }
            }
        }
    }

    @Test
    fun `a delay or spacing that with one tick reaches a day is refused - nothing older than a day is mailed`() = testApplication {
        configureApp("notifications.email.delaySeconds" to "86000", "notifications.sweepIntervalSeconds" to "600")
        assertStartupFails("must be under 86400 seconds") { startApplication() }
    }

    @Test
    fun `reminderDays zero is accepted - the pass is off`() = testApplication {
        configureApp("adrs.expiry.reminderDays" to "0")
        startApplication()
    }
}
