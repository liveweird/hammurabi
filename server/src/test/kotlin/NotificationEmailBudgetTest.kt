package ch.nokillswit

import ch.nokillswit.adrs.AdrEventService
import ch.nokillswit.adrs.AdrExpirySweep
import ch.nokillswit.adrs.AdrParticipantRequest
import ch.nokillswit.adrs.AdrResponse
import ch.nokillswit.adrs.AdrRole
import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.adrs.DiscussionKind
import ch.nokillswit.adrs.DiscussionState
import ch.nokillswit.notifications.Audience
import ch.nokillswit.notifications.ExpiryReminders
import ch.nokillswit.notifications.NoticePlan
import ch.nokillswit.notifications.NotificationEmailBudget
import ch.nokillswit.notifications.NotificationService
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.users.UserRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

private const val DAY = 86_400_000L
private const val EVENT = "notification.email_budget_exceeded"

/** 2000-01-01T00:00Z: a day no other test writes notices on, so a retention pass with a cutoff here deletes nothing of theirs. */
private const val YEAR_2000 = 946_684_800_000L

/**
 * The per-actor mail budget (V21, checkup C-SEC-4; `adr-model.md` "Notifications", `security.md` "Notices"): the first N notices an
 * actor's writes make in a UTC day stay eligible for the email, later ones keep their bell notice and are never mailed; every notice
 * counts (the same person twice is twice); the day rolls over with the injected clock; the audit line is coalesced per actor and day.
 * The service is driven directly (`makeAll`, a tiny budget, a settable clock); the route-level wiring (the configured key, the
 * participants PUT) and the boot bounds close the class. Users and ADRs are shared suite state: every test mints UNIQUE rows.
 */
class NotificationEmailBudgetTest {
    /** The actor of the writes under test (an ADMIN unless said otherwise: no exemption) and the ADR the notices point at. */
    private class Scene(val actor: UInt, val adr: UInt)

    private suspend fun ApplicationTestBuilder.scene(role: UserRole = UserRole.ADMIN): Scene {
        val actor = TestUsers.seed(uniqueEmail("eb-act"), "pw", name = "Actor", role = role)
        return Scene(actor, adrActor("eb-own").client.createAdr().id)
    }

    private fun service(perDay: Int, clock: () -> Long = System::currentTimeMillis) =
        NotificationService(sharedDatabaseForTests(), NotificationEmailBudget(perDay, clock))

    private fun Scene.plan(to: UInt) = NoticePlan(NotificationType.STATUS_CHANGED, adr, Audience.Person(to), detail = "ACTIVE")

    /** One write of [by] (the scene's actor unless said otherwise; null = the system): a status notice per entry of [to], in order. */
    private suspend fun NotificationService.write(
        s: Scene,
        to: List<UInt>,
        by: UInt? = s.actor,
        at: Long = System.currentTimeMillis(),
    ): List<UInt> = suspendTransaction(sharedDatabaseForTests()) { makeAll(to.map { s.plan(it) }, by, at) }

    private fun List<RawNotice>.eligible() = count { it.seenAt == null && it.emailedAt == null }

    private suspend fun eligible(person: MailRecipient) = rawNotices(person.id).eligible()

    /** The actor's budget rows: (day, admitted, exceeded_at). */
    private fun budgetRows(actor: UInt): List<Triple<Int, Int, Long?>> = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT day, admitted, exceeded_at FROM notification_email_budget WHERE actor_id = ? ORDER BY day").use { st ->
            st.setLong(1, actor.toLong())
            st.executeQuery().use { rs ->
                generateSequence {
                    if (!rs.next()) return@generateSequence null
                    Triple(rs.getInt(1), rs.getInt(2), rs.getLong(3).takeUnless { rs.wasNull() })
                }.toList()
            }
        }
    }

    /** What the actor's account has spent today (the app's own budget shares the row with a test's, so a test measures the delta). */
    private fun admittedToday(actor: UInt): Int =
        budgetRows(actor).singleOrNull { it.first.toLong() == System.currentTimeMillis() / DAY }?.second ?: 0

    private fun ch.qos.logback.classic.spi.ILoggingEvent.isBudgetEventOf(actor: UInt) =
        message == EVENT && hasKeyValue("byUserId", actor.toLong())

    @Test
    fun `under the budget every notice is eligible and mailed`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val s = scene(UserRole.USER)
        val people = List(3) { mailRecipient("under$it") }

        withAuditCapture { capture ->
            assertEquals(people.map { it.id }, service(perDay = 3).write(s, people.map { it.id }))
            assertTrue(capture.events.none { it.isBudgetEventOf(s.actor) }, "a write that withholds nothing is not audited")
        }

        people.forEach { assertEquals(1, eligible(it)) }
        assertEquals(3, budgetRows(s.actor).single().second, "three notices admitted today")
        val mailer = RecordingMailer()
        digestOf(mailer).run()
        people.forEach { assertEquals(1, mailer.to(it.email).size, "mailed") }
    }

    @Test
    fun `over the budget the bell notice exists and is never mailed, and EVERY write that withholds is audited`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val s = scene() // an ADMIN actor: not exempt
        val people = List(4) { mailRecipient("over$it") }
        val service = service(perDay = 2)

        withAuditCapture { capture ->
            val reached = service.write(s, people.map { it.id })
            assertEquals(people.map { it.id }, reached, "every notice is made: the bell is untouched")
            val event = capture.awaitEvent { it.isBudgetEventOf(s.actor) }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("adrId", s.adr.toLong()))
            assertTrue(event.hasKeyValue("budget", 2))
            assertTrue(event.hasKeyValue("withheld", 2))
            // Every later overrun of the day is audited too (a silencer must leave a line per write), with that write's own count.
            service.write(s, people.map { it.id })
            service.write(s, listOf(people.first().id))
            val events = capture.events.filter { it.isBudgetEventOf(s.actor) }
            assertEquals(3, events.size, "one line per withholding write")
            assertEquals(listOf(true, false, false), events.map { it.hasKeyValue("withheld", 2) }, "2, then 4 (a whole write), then 1")
            assertTrue(events[1].hasKeyValue("withheld", 4) && events[2].hasKeyValue("withheld", 1))
        }

        // Only the first two notices of the day were admitted; the rest of the first write and everything after it are withheld.
        assertEquals(listOf(1, 1, 0, 0), people.map { eligible(it) }, "the first two stay eligible, the others are withheld for good")
        people.drop(2).forEach { p ->
            val rows = rawNotices(p.id)
            assertTrue(rows.isNotEmpty() && rows.all { it.seenAt == null }, "a withheld notice is still unseen: the bell shows it")
            rows.forEach { assertEquals(it.createdAt, it.emailedAt, "withheld = settled at its own instant") }
        }
        val mailer = RecordingMailer()
        digestOf(mailer).run()
        digestOf(mailer).run()
        assertEquals(listOf(1, 1, 0, 0), people.map { mailer.to(it.email).size }, "the withheld are never mailed, now or later")
    }

    @Test
    fun `every notice counts - the same person twice spends twice, in one write or across writes`() = testApplication {
        usePostgresTestcontainer()
        val s = scene()
        val (a, b) = List(2) { mailRecipient("count$it") }
        val service = service(perDay = 3)

        service.write(s, listOf(a.id, a.id, a.id, b.id, b.id))
        assertEquals(3, eligible(a), "a: three notices, all in the budget")
        assertEquals(0, eligible(b), "b: the fourth and fifth notice of the day are over it")
        assertEquals(2, rawNotices(b.id).size, "but both have their bell notice")

        service.write(s, listOf(a.id))
        assertEquals(3, eligible(a), "the day is spent: a's next notice is withheld too")
        assertEquals(4, rawNotices(a.id).size)
    }

    @Test
    fun `the budget is per actor`() = testApplication {
        usePostgresTestcontainer()
        val first = scene()
        val second = scene()
        val people = List(4) { mailRecipient("per$it") }
        val service = service(perDay = 1)

        service.write(first, listOf(people[0].id, people[1].id))
        service.write(second, listOf(people[2].id, people[3].id))

        assertEquals(1, eligible(people[0]) + eligible(people[1]))
        assertEquals(1, eligible(people[2]) + eligible(people[3]), "another actor's use of the day does not spend this one's")
    }

    @Test
    fun `the budget resets with the UTC day of the injected clock, is audited again, and retention purges old days`() = testApplication {
        usePostgresTestcontainer()
        val s = scene()
        val (a, b, c) = List(3) { mailRecipient("day$it") }
        var now = YEAR_2000 + 3_600_000L
        val budget = NotificationEmailBudget(perDay = 1) { now }
        val service = NotificationService(sharedDatabaseForTests(), budget)

        withAuditCapture { capture ->
            service.write(s, listOf(a.id, b.id), at = now)
            assertNotNull(capture.awaitEvent { it.isBudgetEventOf(s.actor) })
            now += 23 * 3_600_000L - 1_000L // still the same UTC day
            service.write(s, listOf(c.id), at = now)
            assertEquals(1, eligible(a) + eligible(b), "one of the first two")
            assertEquals(0, eligible(c), "same day: over the budget")
            assertEquals(2, capture.events.count { it.isBudgetEventOf(s.actor) }, "the second withholding write of the day is audited too")

            now += 2_000L // past midnight UTC
            service.write(s, listOf(c.id), at = now)
            assertEquals(1, eligible(c), "the new day starts with a fresh budget")
            service.write(s, listOf(a.id, b.id), at = now)
            assertEquals(3, capture.events.count { it.isBudgetEventOf(s.actor) }, "and the overrun of the new day")
        }
        val days = budgetRows(s.actor).map { it.first.toLong() }
        assertEquals(listOf(YEAR_2000 / DAY, YEAR_2000 / DAY + 1), days, "one row per actor and day")

        // The notice retention pass ends with the budget's own: the days before the cutoff's go, the cutoff's day stays.
        service.purgeOlderThan(YEAR_2000 + DAY + 1_000L)
        assertEquals(listOf(YEAR_2000 / DAY + 1), budgetRows(s.actor).map { it.first.toLong() })
    }

    @Test
    fun `one actor writing concurrently never exceeds the budget`() = testApplication {
        usePostgresTestcontainer()
        val s = scene()
        val people = List(6) { mailRecipient("conc$it") }
        val service = service(perDay = 2)
        // Every write is inside its open transaction before any of them makes its notices, so the six really contend for the row.
        val inside = AtomicInteger()
        val allInside = CompletableDeferred<Unit>()

        coroutineScope {
            people.map { person ->
                async(Dispatchers.IO) {
                    suspendTransaction(sharedDatabaseForTests()) {
                        if (inside.incrementAndGet() == people.size) allInside.complete(Unit)
                        withTimeout(30_000) { allInside.await() }
                        service.makeAll(listOf(s.plan(person.id)), s.actor, System.currentTimeMillis())
                    }
                }
            }.awaitAll()
        }
        assertEquals(people.size, inside.get())

        assertEquals(2, people.sumOf { eligible(it) }, "the actor's row lock serialises the writes: exactly the budget is admitted")
        assertEquals(6, people.sumOf { rawNotices(it.id).size })
        assertEquals(2, budgetRows(s.actor).single().second)
    }

    @Test
    fun `a system notice about an ADR with no Approver spends no budget and is always eligible`() = testApplication {
        usePostgresTestcontainer()
        val s = scene()
        val people = List(3) { mailRecipient("sys$it") }

        withAuditCapture { capture ->
            service(perDay = 1).write(s, people.map { it.id }, by = null)
            assertTrue(capture.events.none { it.message == EVENT && it.hasKeyValue("adrId", s.adr.toLong()) }, "nothing to exceed")
        }

        people.forEach { assertEquals(1, eligible(it)) }
        assertEquals(emptyList(), budgetRows(s.actor))
    }

    /** An ADR raised with its Approver, a Contributor and an Informed: the system's charge goes to its Approver. */
    @Test
    fun `a system notice is charged to the ADR's current Approver, and withheld past the budget`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        val everyone = listOf(cast.driver, cast.approver, cast.contributor, cast.informed)
        val plans = everyone.map { NoticePlan(NotificationType.ADR_EXPIRED, adr.id, Audience.Person(it.id), detail = "2000-01-01") }
        val before = everyone.map { rawNotices(it.id).size }
        val approverBefore = admittedToday(cast.approver.id)
        val driverBefore = admittedToday(cast.driver.id)

        withAuditCapture { capture ->
            suspendTransaction(sharedDatabaseForTests()) {
                val service = service(perDay = approverBefore + 2)
                service.reserve(null, adr.id) // what a system write does first: the ADR's Approver is the account charged
                service.makeAll(plans, null, System.currentTimeMillis())
            }
            val event = capture.awaitEvent { it.isBudgetEventOf(cast.approver.id) }
            assertNotNull(event, "audited against the Approver the system's notices were charged to")
            assertTrue(event.hasKeyValue("withheld", 2))
        }

        assertEquals(approverBefore + 2, admittedToday(cast.approver.id), "the Approver's day is what was spent")
        assertEquals(driverBefore, admittedToday(cast.driver.id), "not the Driver's")
        val fresh = everyone.mapIndexed { i, who ->
            rawNotices(who.id).drop(before[i]).count { it.type == "ADR_EXPIRED" && it.emailedAt == null }
        }
        assertEquals(2, fresh.sum(), "two of the four are eligible, the others keep the bell notice and are withheld")
    }

    @Test
    fun `the expiry sweep's fan-out is charged to the Approver and withheld past the budget`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        cast.activate(adr)
        forceAdrExpiry(adr.id, "2000-01-01")
        val everyone = listOf(cast.driver, cast.approver, cast.contributor, cast.informed)
        val used = admittedToday(cast.approver.id)
        val events = AdrEventService(sharedDatabaseForTests(), service(perDay = used + 2))
        val clock = Clock.fixed(LocalDate.of(2000, 1, 2).atTime(9, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

        assertTrue(AdrExpirySweep(sharedDatabaseForTests(), events, clock).sweep() >= 1)

        assertEquals("EXPIRED", rawAdrStatus(adr.id))
        val expired = everyone.map { rawNotices(it.id).filter { n -> n.type == "ADR_EXPIRED" && n.adrId == adr.id } }
        assertEquals(listOf(1, 1, 1, 1), expired.map { it.size }, "every participant has the bell notice")
        assertEquals(listOf(1, 1, 0, 0), expired.map { it.eligible() }, "the leads are the two the Approver's budget of 2 admits")
        assertEquals(used + 2, admittedToday(cast.approver.id), "charged to the Approver")
    }

    @Test
    fun `an ADR whose Approver was deleted stays exempt`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        TestUsers.softDelete(cast.approver.id)
        val plans = listOf(cast.driver, cast.contributor).map {
            NoticePlan(NotificationType.STATUS_CHANGED, adr.id, Audience.Person(it.id), detail = "ACTIVE")
        }

        suspendTransaction(sharedDatabaseForTests()) {
            val service = service(perDay = 1)
            service.reserve(null, adr.id)
            service.makeAll(plans, null, System.currentTimeMillis())
        }

        assertEquals(emptyList(), budgetRows(cast.approver.id), "no active Approver: nobody is charged")
        val made = (rawNotices(cast.driver.id) + rawNotices(cast.contributor.id))
            .filter { it.adrId == adr.id && it.type == "STATUS_CHANGED" }
        assertEquals(2, made.count { it.emailedAt == null }, "exempt: both stay eligible under a budget of one")
    }

    @Test
    fun `the reservation is final - an Approver changed after it is still the one charged, no other row is asked for`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        val before = admittedToday(cast.approver.id)
        val day = System.currentTimeMillis() / DAY
        // The NEW Approver's row is held by another transaction for the whole write: a late lock on it would hang until the timeout.
        PostgresTestSupport.connect().use { c ->
            c.prepareStatement("INSERT INTO notification_email_budget (actor_id, day) VALUES (?, ?) ON CONFLICT DO NOTHING").use {
                it.setLong(1, cast.stranger.id.toLong())
                it.setInt(2, day.toInt())
                it.executeUpdate()
            }
        }
        val holder = PostgresTestSupport.connect()
        try {
            holder.autoCommit = false
            holder.prepareStatement("SELECT 1 FROM notification_email_budget WHERE actor_id = ? AND day = ? FOR UPDATE").use {
                it.setLong(1, cast.stranger.id.toLong())
                it.setInt(2, day.toInt())
                it.executeQuery().close()
            }
            val plans = listOf(cast.driver, cast.contributor, cast.informed).map {
                NoticePlan(NotificationType.ADR_EXPIRED, adr.id, Audience.Person(it.id), detail = "2000-01-01")
            }
            withTimeout(15_000) {
                suspendTransaction(sharedDatabaseForTests()) {
                    val service = service(perDay = before + 1)
                    service.reserve(null, adr.id) // the system reserves for A1 ...
                    execRaw("UPDATE adr_participants SET user_id = ${cast.stranger.id} WHERE adr_id = ${adr.id} AND role = 'APPROVER'")
                    service.makeAll(plans, null, System.currentTimeMillis()) // ... the Approver is A2 by the time the notices are made
                }
            }
        } finally {
            holder.rollback()
            holder.close()
        }

        assertEquals(before + 1, admittedToday(cast.approver.id), "charged to the Approver of the reservation")
        assertEquals(0, admittedToday(cast.stranger.id), "the new Approver was neither charged nor asked for")
    }

    @Test
    fun `the reservation is final - a clock that passes midnight after it still charges the reserved day`() = testApplication {
        usePostgresTestcontainer()
        val s = scene()
        val person = mailRecipient("midnight")
        var now = YEAR_2000 + DAY - 1_000L // one second before midnight
        val service = NotificationService(sharedDatabaseForTests(), NotificationEmailBudget(perDay = 5) { now })

        suspendTransaction(sharedDatabaseForTests()) {
            service.reserve(s.actor, null)
            now += 2_000L // the write straddles 00:00 UTC
            service.makeAll(listOf(s.plan(person.id)), s.actor, now)
        }

        assertEquals(listOf(YEAR_2000 / DAY), budgetRows(s.actor).map { it.first.toLong() }, "only the reserved day's row exists")
        assertEquals(1, budgetRows(s.actor).single().second)
    }

    /** The routes and the system passes that make notices, driven with a capture on the budget's logger: none may charge unreserved. */
    @Test
    fun `every notice-making write reserves the budget first - none is charged late`() = testApplication {
        usePostgresTestcontainer()
        val capture = LogCapture("ch.nokillswit.notifications.NotificationEmailBudget")
        try {
            val cast = adrCast()
            val adr = cast.raise() // the creation with participants
            val others = listOf(
                AdrParticipantRequest(cast.driver.id, AdrRole.DRIVER),
                AdrParticipantRequest(cast.approver.id, AdrRole.APPROVER),
            )
            assertEquals(HttpStatusCode.NoContent, cast.driver.client.setParticipants(adr.id, others).status) // the participants PUT
            cast.activate(adr) // two transitions
            val live = cast.driver.client.getAdr(adr.id)
            val dated = cast.approver.client.saveAdr(adr.id, live.revision, live.asUpdate().copy(expiresOn = "2099-01-01")) // the date
            assertEquals(HttpStatusCode.NoContent, dated.status)
            val issue = cast.stranger.client.postEntryOk(adr.id, DiscussionKind.ISSUE) // the discussion post
            assertEquals(HttpStatusCode.OK, cast.approver.client.setEntryState(adr.id, issue.id, DiscussionState.RESOLVED).status)
            val successor = cast.raise()
            assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(successor.id, adr.id).status) // the three link writes
            cast.approver.client.acceptSuccessorOk(adr.id, successor.id)
            assertEquals(HttpStatusCode.NoContent, cast.driver.client.detachSuccessor(adr.id).status)
            // 1.2.0: an ending that tells a pending successor's leads writes notices for TWO ADRs in one transaction (the predecessor's own
            // and the successor's PREDECESSOR_ENDED), and an activation that supersedes writes the successor's and the predecessor's.
            val next = adrCast()
            suspend fun pendingOn(old: AdrResponse): AdrResponse {
                val pending = next.raise()
                assertEquals(HttpStatusCode.NoContent, next.driver.client.setSupersedes(pending.id, old.id).status)
                return pending
            }
            val withdrawn = cast.raise().also { cast.activate(it) }
            val afterWithdrawal = pendingOn(withdrawn)
            cast.approver.client.moveAdrNow(withdrawn.id, AdrStatus.WITHDRAWN)
            val toldByWithdrawal = next.driver.client.noticesOf(NotificationType.PREDECESSOR_ENDED, afterWithdrawal.id)
            assertEquals(1, toldByWithdrawal.size, "the withdrawal told the successor")
            val cancelled = cast.raise()
            val afterCancel = pendingOn(cancelled)
            cast.driver.client.moveAdrNow(cancelled.id, AdrStatus.CANCELLED)
            val toldByCancel = next.approver.client.noticesOf(NotificationType.PREDECESSOR_ENDED, afterCancel.id)
            assertEquals(1, toldByCancel.size, "the cancellation told it")
            val replaced = cast.raise().also { cast.activate(it) }
            val replacing = pendingOn(replaced)
            cast.approver.client.acceptSuccessorOk(replaced.id, replacing.id)
            next.driver.client.moveAdrNow(replacing.id, AdrStatus.IN_REVIEW)
            next.approver.client.moveAdrNow(replacing.id, AdrStatus.ACTIVE) // the supersede: two ADRs' notices in one transaction
            val toldBySupersede = cast.driver.client.noticesOf(NotificationType.ADR_SUPERSEDED, replaced.id)
            assertEquals(1, toldBySupersede.size, "the predecessor's people were told")
            // The sweep expires a predecessor with a pending successor: the successor's notices are charged to the ENDED ADR's Approver.
            val expiring = cast.raise().also { cast.activate(it) }
            val afterExpiry = pendingOn(expiring)
            // The two system passes.
            val window = LocalDate.of(2100, 9, 1)
            forceAdrExpiry(expiring.id, window.plusDays(2).toString())
            forceAdrExpiry(adr.id, window.plusDays(2).toString())
            val clock = Clock.fixed(window.atTime(9, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
            ExpiryReminders(sharedDatabaseForTests(), newNotificationService(), 14, clock).run()
            val later = Clock.fixed(window.plusDays(5).atTime(9, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
            AdrExpirySweep(sharedDatabaseForTests(), AdrEventService(sharedDatabaseForTests(), newNotificationService()), later).sweep()
            assertEquals("EXPIRED", rawAdrStatus(adr.id), "the sweep reached the ADR")
            assertEquals("EXPIRED", rawAdrStatus(expiring.id), "and the predecessor of a pending successor")
            val toldBySweep = next.driver.client.noticesOf(NotificationType.PREDECESSOR_ENDED, afterExpiry.id)
            assertEquals(1, toldBySweep.size, "the sweep told its successor")

            assertEquals(emptyList(), capture.events.map { it.formattedMessage }, "no path charged the budget without reserving first")
        } finally {
            capture.detach()
        }
    }

    @Test
    fun `an expiry reminder is charged to the Approver, and re-arming it is bounded to once a UTC day`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        cast.activate(adr)
        val today = LocalDate.of(2100, 6, 1)
        forceAdrExpiry(adr.id, today.plusDays(5).toString())
        val clock = Clock.fixed(today.atTime(9, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
        val used = admittedToday(cast.approver.id)
        val reminders = ExpiryReminders(sharedDatabaseForTests(), service(perDay = used + 1), 14, clock)

        reminders.run()
        assertEquals(used + 1, admittedToday(cast.approver.id), "the reminder's two notices spent the Approver's one remaining notice")
        val reminded = listOf(cast.driver, cast.approver)
            .map { rawNotices(it.id).single { n -> n.type == "EXPIRY_REMINDER" && n.adrId == adr.id } }
        assertEquals(1, reminded.count { it.emailedAt == null }, "one stays eligible, the other is withheld")

        // The date toggled A, B, A, B on the one UTC day re-arms nothing: one reminder in all.
        for (day in listOf(9L, 5L, 9L)) {
            forceAdrExpiry(adr.id, today.plusDays(day).toString())
            reminders.run()
        }
        assertEquals(1, cast.driver.client.noticesOf(NotificationType.EXPIRY_REMINDER, adr.id).size, "bounded to once a day")
        // The next UTC day (the marker's stamp moved back a day) it may be re-armed, once.
        execRaw("UPDATE adr_expiry_reminders SET sent_at = sent_at - 86400000 WHERE adr_id = ${adr.id}")
        reminders.run()
        assertEquals(2, cast.driver.client.noticesOf(NotificationType.EXPIRY_REMINDER, adr.id).size)
        forceAdrExpiry(adr.id, today.plusDays(5).toString())
        reminders.run()
        assertEquals(2, cast.driver.client.noticesOf(NotificationType.EXPIRY_REMINDER, adr.id).size, "and not again the same day")
    }

    @Test
    fun `a creation waiting for the actor's budget row does not hold the numbering lock`() = testApplication {
        usePostgresTestcontainer()
        val creator = adrActor("lock-c")
        val other = adrActor("lock-o")
        val member = adrActor("lock-m")
        val day = System.currentTimeMillis() / 86_400_000L
        PostgresTestSupport.connect().use { c ->
            c.prepareStatement("INSERT INTO notification_email_budget (actor_id, day) VALUES (?, ?) ON CONFLICT DO NOTHING").use {
                it.setLong(1, creator.id.toLong())
                it.setInt(2, day.toInt())
                it.executeUpdate()
            }
        }
        val holder = PostgresTestSupport.connect()
        try {
            holder.autoCommit = false
            holder.prepareStatement("SELECT 1 FROM notification_email_budget WHERE actor_id = ? AND day = ? FOR UPDATE").use {
                it.setLong(1, creator.id.toLong())
                it.setInt(2, day.toInt())
                it.executeQuery().close()
            }
            coroutineScope {
                val blocked = async(Dispatchers.IO) {
                    creator.client.createAdr(participants = listOf(AdrParticipantRequest(member.id, AdrRole.CONTRIBUTOR)))
                }
                awaitLockWait("notification_email_budget")

                // The numbering lock is free: another user's creation (with participants, its own budget row) goes through meanwhile.
                val meanwhile = withTimeout(10_000) {
                    other.client.createAdr(participants = listOf(AdrParticipantRequest(member.id, AdrRole.CONTRIBUTOR)))
                }
                assertTrue(meanwhile.id > 0u)
                assertTrue(blocked.isActive, "the first creation is still waiting for the row")
                holder.rollback()
                assertTrue(withTimeout(30_000) { blocked.await() }.id > 0u)
            }
        } finally {
            holder.close()
        }
    }

    @Test
    fun `a role audience is admitted leads first - Driver, Approver, Contributors, Informed - not by user id`() = testApplication {
        usePostgresTestcontainer()
        // The Informed and the Contributor are seeded FIRST, so a user-id order would admit them before the leads.
        val informed = mailRecipient("lead-inf")
        val contributor = mailRecipient("lead-con")
        val approver = mailRecipient("lead-apr")
        val driver = adrActor("lead-drv")
        val adr = driver.client.createAdr(
            participants = listOf(
                AdrParticipantRequest(informed.id, AdrRole.INFORMED),
                AdrParticipantRequest(contributor.id, AdrRole.CONTRIBUTOR),
                AdrParticipantRequest(approver.id, AdrRole.APPROVER),
            ),
        )
        val actor = TestUsers.seed(uniqueEmail("lead-act"), "pw", name = "Actor", role = UserRole.ADMIN)
        val before = listOf(informed, contributor, approver).map { rawNotices(it.id).size }
        val plan = NoticePlan(NotificationType.STATUS_CHANGED, adr.id, Audience.Roles(adr.id, AdrRole.entries.toSet()), detail = "ACTIVE")

        suspendTransaction(sharedDatabaseForTests()) {
            service(perDay = 2).makeAll(listOf(plan), actor, System.currentTimeMillis())
        }

        suspend fun fresh(p: MailRecipient, was: Int) = rawNotices(p.id).drop(was).eligible()
        assertEquals(1, fresh(approver, before[2]), "the Approver is a lead: admitted")
        assertEquals(0, fresh(contributor, before[1]), "the Contributor is over the budget of two (Driver and Approver came first)")
        assertEquals(0, fresh(informed, before[0]), "the Informed too, though their user id is the lowest")
        assertEquals(1, rawNotices(driver.id).filter { it.type == "STATUS_CHANGED" }.eligible(), "and the Driver, first of all")
    }

    @Test
    fun `a write that rolls back spends nothing`() = testApplication {
        usePostgresTestcontainer()
        val s = scene()
        val (a, b) = List(2) { mailRecipient("roll$it") }
        val service = service(perDay = 1)

        assertTrue(runCatching {
            suspendTransaction(sharedDatabaseForTests()) {
                service.makeAll(listOf(s.plan(a.id)), s.actor, System.currentTimeMillis())
                error("the write fails after its notices")
            }
        }.isFailure)
        assertEquals(emptyList(), budgetRows(s.actor), "the counter rolled back with the write")
        assertEquals(emptyList(), rawNotices(a.id))

        service.write(s, listOf(b.id))
        assertEquals(1, eligible(b), "the budget is whole: the failed write spent none of it")
    }

    @Test
    fun `through the routes, with the key set to 1, a removal and a re-add of a participant do not reset the budget`() = testApplication {
        configureApp("notifications.email.maxNoticesPerActorPerDay" to "1")
        startApplication()
        // The budget is a UTC day's: do not let the writes below straddle midnight (a new day would admit one more notice).
        val untilMidnight = DAY - System.currentTimeMillis() % DAY
        if (untilMidnight < 60_000L) delay(untilMidnight + 1_000L)
        val driver = adrActor("eb-http-drv")
        val approver = adrActor("eb-http-apr")
        val contributor = adrActor("eb-http-con")
        val lead = AdrParticipantRequest(driver.id, AdrRole.DRIVER)
        val adr = driver.client.createAdr(
            participants = listOf(
                AdrParticipantRequest(approver.id, AdrRole.APPROVER),
                AdrParticipantRequest(contributor.id, AdrRole.CONTRIBUTOR),
            ),
        )

        // The creation made two notices for a budget of one: both have their bell notice, exactly one is eligible.
        for (person in listOf(approver, contributor)) assertEquals(1L, person.client.notices().total, "both have the bell notice")
        assertEquals(1, (rawNotices(approver.id) + rawNotices(contributor.id)).eligible())

        // Taking the Approver off and putting them back makes two more notices for them; the budget of the day is already spent.
        val contributorRole = AdrParticipantRequest(contributor.id, AdrRole.CONTRIBUTOR)
        assertEquals(HttpStatusCode.NoContent, driver.client.setParticipants(adr.id, listOf(lead, contributorRole)).status)
        val approverRole = AdrParticipantRequest(approver.id, AdrRole.APPROVER)
        assertEquals(HttpStatusCode.NoContent, driver.client.setParticipants(adr.id, listOf(lead, contributorRole, approverRole)).status)
        assertEquals(3, rawNotices(approver.id).size, "assigned, removed, assigned again")

        assertEquals(1, (rawNotices(approver.id) + rawNotices(contributor.id)).eligible(), "still the one: nothing reset")
        val row = budgetRows(driver.id).single()
        assertEquals(1, row.second, "one notice admitted today")
        assertNotNull(row.third, "and the day is marked exceeded")
    }

    @Test
    fun `a malformed or out-of-range budget refuses to start, naming the key`() {
        for (value in listOf("0", "-1", "abc", "10001", "")) {
            testApplication {
                configureApp("notifications.email.maxNoticesPerActorPerDay" to value)
                assertStartupFails("notifications.email.maxNoticesPerActorPerDay") { startApplication() }
            }
        }
    }
}
