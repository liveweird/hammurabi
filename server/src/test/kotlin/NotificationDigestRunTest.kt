package ch.nokillswit

import ch.nokillswit.adrs.AdrResponse
import ch.nokillswit.infra.mail.LogMailer
import ch.nokillswit.infra.mail.Mailer
import ch.nokillswit.notifications.DIGEST_MAX_ENTRIES
import ch.nokillswit.notifications.DIGEST_MAX_NOTICES_PER_RECIPIENT
import ch.nokillswit.notifications.DigestBounds
import ch.nokillswit.users.UserRole
import io.ktor.client.request.delete
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** An age (ms) just under a day: the oldest a notice can be and still be mailed. Smaller ages are younger, so older = bigger. */
private const val NEARLY_A_DAY = 80_000_000L
private const val SECOND = 1_000L

/**
 * The email digest's concurrency, release and bound rules (`NotificationDigest.kt`; Adder's `NotificationDigestTest`, ported onto
 * Hammurabi's harness: `settleNoticeBacklog()` first, then raw-inserted notices of chosen age on a live ADR, a `RecordingMailer`, and the
 * digest built directly). Every assertion is about this test's own throwaway recipients.
 */
class NotificationDigestRunTest {
    /** A live ADR to hang notices on (its driver is nobody a test mails). */
    private suspend fun ApplicationTestBuilder.liveAdr(): AdrResponse = adrActor("drv").client.createAdr()

    private suspend fun ApplicationTestBuilder.softDeleted(): AdrResponse {
        val adr = liveAdr()
        assertEquals(204, adrActor("adm", UserRole.ADMIN).client.delete("/api/v1/adrs/${adr.id}").status.value)
        return adr
    }

    private suspend fun emailedAt(recipient: MailRecipient): List<Long?> = rawNotices(recipient.id).map { it.emailedAt }

    private fun ch.qos.logback.classic.spi.ILoggingEvent.everything() = "$formattedMessage $keyValuePairs $throwableProxy"

    @Test
    fun `two runs at once never mail one notice twice`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val adr = liveAdr()
        val people = List(6) { mailRecipient("two$it") }
        people.forEach { insertNotices(it.id, adr.id, 1) }
        val mailer = RecordingMailer()
        val a = digestOf(mailer)
        val b = digestOf(mailer)

        coroutineScope { listOf(async(Dispatchers.Default) { a.run() }, async(Dispatchers.Default) { b.run() }).awaitAll() }

        people.forEach { assertEquals(1, mailer.to(it.email).size, "one notice, one email: ${it.email}") }
    }

    @Test
    fun `a row another process holds locked is skipped, not waited for`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val adr = liveAdr()
        val held = mailRecipient("held")
        val free = mailRecipient("free")
        insertNotices(held.id, adr.id, 1, ageMillis = NEARLY_A_DAY)
        insertNotices(free.id, adr.id, 1)
        val mailer = RecordingMailer()
        val holder = PostgresTestSupport.connect()
        try {
            holder.autoCommit = false
            holder.createStatement().use { it.execute("SELECT id FROM notifications WHERE recipient_id = ${held.id} FOR UPDATE") }

            // Without SKIP LOCKED the claim waits for the holder, outlives its 2 s budget and claims nothing.
            val run = digestOf(mailer, bounds = DigestBounds(statementTimeoutSeconds = 2)).run()

            assertEquals(1, run.sent, "$run")
            assertEquals(1, mailer.to(free.email).size)
            assertTrue(mailer.to(held.email).isEmpty())
            assertEquals(listOf<Long?>(null), emailedAt(held), "the locked row was neither claimed nor lost")
        } finally {
            holder.rollback()
            holder.close()
        }
        digestOf(mailer).run()
        assertEquals(1, mailer.to(held.email).size, "mailed once the lock is gone")
    }

    @Test
    fun `a cancelled run releases the claims of recipients it never tried`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val adr = liveAdr()
        val people = List(3) { mailRecipient("can$it") }
        people.forEach { insertNotices(it.id, adr.id, 1) }
        val mailer = RecordingMailer()
        val claimed = CompletableDeferred<Unit>()

        coroutineScope {
            val job = async(Dispatchers.Default) {
                digestOf(mailer, afterClaim = { claimed.complete(Unit); awaitCancellation() }).run()
            }
            claimed.await()
            assertTrue(people.all { p -> emailedAt(p).all { it != null } }, "claimed before the stop")
            job.cancelAndJoin()
        }

        assertTrue(people.all { p -> emailedAt(p).all { it == null } }, "nothing was attempted, so nothing stays claimed")
        assertTrue(mailer.sent.isEmpty())
        digestOf(mailer).run()
        people.forEach { assertEquals(1, mailer.to(it.email).size, "mailed by the next run") }
    }

    @Test
    fun `a run cancelled mid-send keeps the claim of that recipient and releases the rest`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val adr = liveAdr()
        val first = mailRecipient("mid1")
        val second = mailRecipient("mid2")
        // The older notice is claimed first, so it is the one being sent when the stop comes.
        insertNotices(first.id, adr.id, 1, ageMillis = NEARLY_A_DAY)
        insertNotices(second.id, adr.id, 1)
        val started = CompletableDeferred<Unit>()
        val stuck = object : Mailer {
            override suspend fun send(to: String, subject: String, body: String) {
                started.complete(Unit)
                awaitCancellation()
            }
        }

        coroutineScope {
            val job = async(Dispatchers.Default) { digestOf(stuck).run() }
            started.await()
            job.cancelAndJoin()
        }

        assertTrue(emailedAt(first).all { it != null }, "a send that may have left stays claimed: at most once")
        assertTrue(emailedAt(second).all { it == null }, "the recipient never attempted is released")
    }

    @Test
    fun `a claim past its statement budget is logged scrubbed and the run ends with nothing claimed`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val adr = liveAdr()
        val ann = mailRecipient("budget")
        insertNotices(ann.id, adr.id, 1)
        val mailer = RecordingMailer()
        val capture = LogCapture("ch.nokillswit.notifications.NotificationDigest")
        val blocker = PostgresTestSupport.connect()
        try {
            blocker.autoCommit = false
            blocker.createStatement().use { it.execute("LOCK TABLE notifications IN ACCESS EXCLUSIVE MODE") }

            val run = digestOf(mailer, bounds = DigestBounds(statementTimeoutSeconds = 1)).run()

            assertEquals(0, run.claimed)
            assertTrue(mailer.sent.isEmpty())
            val line = capture.events.single { it.formattedMessage.contains("could not claim") }.everything()
            assertFalse(line.contains(ann.email) || line.contains("UPDATE notifications"), "scrubbed: no value, no SQL: $line")
        } finally {
            blocker.rollback()
            blocker.close()
            capture.detach()
        }
        assertTrue(emailedAt(ann).all { it == null }, "nothing was stamped")
        digestOf(mailer).run()
        assertEquals(1, mailer.to(ann.email).size, "the next tick mails it")
    }

    @Test
    fun `a person with 120 due notices gets one mail of 10 listed and 110 more, and the next run finds none`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val adr = liveAdr()
        val flooded = mailRecipient("flood")
        insertNotices(flooded.id, adr.id, 120, ageMillis = NEARLY_A_DAY)
        val mailer = RecordingMailer()
        val digest = digestOf(mailer, interval = 900 * SECOND)

        val first = digest.run()

        val mail = mailer.to(flooded.email).single()
        assertEquals(120, first.claimed)
        assertEquals("Hammurabi: 120 unseen notifications", mail.subject)
        assertEquals(DIGEST_MAX_ENTRIES, mail.body.lines().count { it.startsWith("- ") }, "ten listed")
        assertTrue(mail.body.lines().contains("...and 110 more"), mail.body)
        assertTrue(emailedAt(flooded).all { it != null }, "the whole backlog is stamped in the same claim")

        insertNotices(flooded.id, adr.id, 1)
        assertEquals(0, digest.run().claimed, "inside the interval a newer notice waits for its own mail")
        assertEquals(1, mailer.to(flooded.email).size)
    }

    @Test
    fun `a run claims at most one batch`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val adr = liveAdr()
        val people = List(5) { mailRecipient("bat$it") }
        // Oldest first, so the two oldest are the ones a batch of two takes.
        people.forEachIndexed { i, p -> insertNotices(p.id, adr.id, 1, ageMillis = NEARLY_A_DAY - i * 10 * SECOND) }
        val mailer = RecordingMailer()

        val run = digestOf(mailer, batchSize = 2).run()

        assertEquals(2, run.claimed, "the batch is the run's cap")
        assertEquals(listOf(true, true, false, false, false), people.map { p -> emailedAt(p).all { it != null } })
    }

    @Test
    fun `a claim picks per person, oldest first, so one flooded backlog does not crowd out the rest`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val adr = liveAdr()
        val flooded = mailRecipient("fl")
        val second = mailRecipient("sec")
        val third = mailRecipient("thi")
        val floodSize = DIGEST_MAX_NOTICES_PER_RECIPIENT + 10
        insertNotices(flooded.id, adr.id, floodSize, ageMillis = NEARLY_A_DAY)
        insertNotices(second.id, adr.id, 1, ageMillis = NEARLY_A_DAY - 20 * SECOND)
        insertNotices(third.id, adr.id, 1, ageMillis = NEARLY_A_DAY - 30 * SECOND)
        val mailer = RecordingMailer()
        val digest = digestOf(mailer, interval = 900 * SECOND, batchSize = 2)

        val first = digest.run()

        assertEquals(floodSize + 1, first.claimed, "the flooded person's whole backlog and the next person's one")
        assertEquals("Hammurabi: $floodSize unseen notifications", mailer.to(flooded.email).single().subject)
        assertEquals(1, mailer.to(second.email).size)
        assertTrue(mailer.to(third.email).isEmpty() && emailedAt(third).all { it == null }, "the third person waits for the next run")
        assertTrue(emailedAt(flooded).all { it != null }, "the backlog is stamped with the digest, not left for one more mail a tick")

        digest.run()
        assertEquals(1, mailer.to(third.email).size, "the next run carries on with the person who waited")
        assertEquals(1, mailer.to(flooded.email).size)
    }

    @Test
    fun `a scan reads at most the cap, oldest first, and the rest waits for a later tick`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val adr = liveAdr()
        val flooded = mailRecipient("cap")
        val other = mailRecipient("capo")
        insertNotices(flooded.id, adr.id, 6, ageMillis = NEARLY_A_DAY)
        insertNotices(other.id, adr.id, 1, ageMillis = NEARLY_A_DAY - 20 * SECOND)
        val mailer = RecordingMailer()

        val run = digestOf(mailer, bounds = DigestBounds(scanCap = 4)).run()

        assertEquals(4, run.claimed, "the cap bounds what one claim stamps")
        assertEquals(4, emailedAt(flooded).count { it != null })
        assertTrue(emailedAt(other).all { it == null }, "a person behind the cap waits for a later tick")
        assertEquals("Hammurabi: 4 unseen notifications", mailer.to(flooded.email).single().subject)
    }

    @Test
    fun `a claim takes the send budget plus a margin of people, not the whole batch`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val adr = liveAdr()
        val people = List(15) { mailRecipient("mar$it") }
        people.forEachIndexed { i, p -> insertNotices(p.id, adr.id, 1, ageMillis = NEARLY_A_DAY - i * 10 * SECOND) }
        val mailer = RecordingMailer()

        val run = digestOf(mailer, maxPerRun = 2).run()

        assertEquals(12, run.claimed, "2 sends plus the margin of 10, not all 15 people")
        assertEquals(2, run.sent)
        assertEquals(10, run.released, "the margin goes back")
        assertEquals(2, people.count { p -> emailedAt(p).all { it != null } })
    }

    @Test
    fun `a run sends at most the budget and releases the claims beyond it`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val adr = liveAdr()
        val people = List(5) { mailRecipient("bud$it") }
        people.forEachIndexed { i, p -> insertNotices(p.id, adr.id, 1, ageMillis = NEARLY_A_DAY - i * 10 * SECOND) }
        val mailer = RecordingMailer()

        val run = digestOf(mailer, batchSize = 5, maxPerRun = 2).run()

        assertEquals(2, mailer.attempts, "the budget is the run's cap")
        assertEquals(5, run.claimed)
        assertEquals(2, run.sent)
        assertEquals(3, run.released)
        assertEquals(listOf(true, true, false, false, false), people.map { p -> emailedAt(p).all { it != null } },
            "the untried claims are released")

        val next = digestOf(mailer, batchSize = 5, maxPerRun = 2).run()
        assertEquals(2, next.sent, "the next tick carries on with the rest")
        assertEquals(listOf(true, true, true, true, false), people.map { p -> emailedAt(p).all { it != null } })
    }

    @Test
    fun `three failures in a row end the run and release the untried claims`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val adr = liveAdr()
        val people = List(5) { mailRecipient("tri$it") }
        people.forEachIndexed { i, p -> insertNotices(p.id, adr.id, 1, ageMillis = NEARLY_A_DAY - i * 10 * SECOND) }
        val mailer = RecordingMailer(failWith = IllegalStateException("smtp down"))

        val run = digestOf(mailer, batchSize = 5).run()

        assertEquals(3, mailer.attempts, "the run stops after the third failure in a row")
        assertEquals(3, run.failed)
        assertEquals(2, run.released)
        assertEquals(listOf(true, true, true, false, false), people.map { p -> emailedAt(p).all { it != null } },
            "the attempted ones stay claimed, the untried ones are released")
    }

    @Test
    fun `a success resets the count of failures`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val adr = liveAdr()
        val people = List(5) { mailRecipient("rst$it") }
        people.forEachIndexed { i, p -> insertNotices(p.id, adr.id, 1, ageMillis = NEARLY_A_DAY - i * 10 * SECOND) }
        var calls = 0
        val flaky = object : Mailer {
            override suspend fun send(to: String, subject: String, body: String) {
                calls++
                // 1 and 2 fail, 3 succeeds, 4 and 5 fail: never three in a row.
                if (calls != 3) error("blip")
            }
        }

        val run = digestOf(flaky, batchSize = 5).run()

        assertEquals(5, calls)
        assertEquals(0, run.released)
        assertEquals(1, run.sent)
        assertEquals(4, run.failed)
    }

    @Test
    fun `notices of a switched-off kind or a deleted account are settled and never fill a batch`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val adr = liveAdr()
        val off = mailRecipient("off")
        val gone = mailRecipient("gone")
        val live = mailRecipient("live")
        execRaw(PREFS_INSERT + "(${off.id}, 'STATUS_CHANGED', 'EMAIL')")
        // The oldest notices there are: they would be the whole batch of one, if they could be claimed.
        insertNotices(off.id, adr.id, 5, ageMillis = NEARLY_A_DAY)
        insertNotices(gone.id, adr.id, 1, ageMillis = NEARLY_A_DAY - 20 * SECOND)
        TestUsers.softDelete(gone.id)
        insertNotices(live.id, adr.id, 1, ageMillis = NEARLY_A_DAY - 30 * SECOND)
        val mailer = RecordingMailer()

        val run = digestOf(mailer, batchSize = 1).run()

        assertEquals(1, run.claimed, "the one slot goes to the person who can be mailed")
        assertEquals(1, mailer.to(live.email).size)
        assertTrue(mailer.to(off.email).isEmpty() && mailer.to(gone.email).isEmpty())
        assertTrue(emailedAt(off).all { it != null }, "the switched-off kind is settled in the claim's transaction")
        assertTrue(emailedAt(gone).all { it != null }, "a deleted account's notice is settled")
    }

    @Test
    fun `a recipient skipped at send time is remembered and does not claim a slot again`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val adr = liveAdr()
        val ann = mailRecipient("skip")
        insertNotices(ann.id, adr.id, 1)
        val mailer = RecordingMailer()
        var clock = System.currentTimeMillis()
        var armed = true
        val digest = digestOf(mailer, interval = 900 * SECOND, clock = { clock }, afterClaim = {
            // Seen between the claim and the send: nothing to mail.
            if (armed) { armed = false; execRaw("UPDATE notifications SET seen_at = 1 WHERE recipient_id = ${ann.id}") }
        })

        digest.run()
        assertTrue(mailer.to(ann.email).isEmpty())
        insertNotices(ann.id, adr.id, 1)

        digest.run()
        assertEquals(listOf<Long?>(null), emailedAt(ann).takeLast(1), "inside the interval the skipped person is not a candidate")

        clock += 901 * SECOND
        digest.run()
        assertEquals(1, mailer.to(ann.email).size, "after the interval the new notice is mailed")
    }

    @Test
    fun `notices of a deleted ADR at the head do not hide the live ones behind them`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val dead = softDeleted()
        val live = liveAdr()
        val ann = mailRecipient("head")
        insertNotices(ann.id, dead.id, 60, ageMillis = NEARLY_A_DAY)
        insertNotices(ann.id, live.id, 3, ageMillis = NEARLY_A_DAY - 600 * SECOND)
        val mailer = RecordingMailer()

        val run = digestOf(mailer).run()

        assertEquals(63, run.claimed)
        val mail = mailer.to(ann.email).single()
        assertEquals("Hammurabi: 3 unseen notifications", mail.subject)
        assertEquals(3, mail.body.lines().count { it.startsWith("- ") }, mail.body)
        assertTrue("/adrs/${live.id}" in mail.body && "/adrs/${dead.id}" !in mail.body)
        assertFalse(mail.body.contains("more"), "nothing is unlisted")
        assertTrue(emailedAt(ann).all { it != null }, "the whole claim stays stamped")
    }

    @Test
    fun `the count of the unlisted excludes notices of a deleted ADR and notices seen meanwhile`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val live = liveAdr()
        val dead = softDeleted()
        val ann = mailRecipient("unl")
        insertNotices(ann.id, live.id, 12, ageMillis = NEARLY_A_DAY)
        insertNotices(ann.id, dead.id, 40, ageMillis = NEARLY_A_DAY - 600 * SECOND)
        val mailer = RecordingMailer()

        digestOf(mailer, afterClaim = {
            execRaw("UPDATE notifications SET seen_at = 1 WHERE id = (SELECT MAX(id) FROM notifications " +
                "WHERE recipient_id = ${ann.id} AND adr_id = ${live.id})")
        }).run()

        val mail = mailer.to(ann.email).single()
        assertEquals("Hammurabi: 11 unseen notifications", mail.subject, "12 live, one seen meanwhile, 40 dead not counted")
        assertEquals(DIGEST_MAX_ENTRIES, mail.body.lines().count { it.startsWith("- ") }, mail.body)
        assertTrue(mail.body.lines().contains("...and 1 more"), mail.body)
    }

    @Test
    fun `a notice whose successor was soft-deleted is not mailed, never as a sentence that names nothing`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val predecessor = liveAdr()
        val successor = softDeleted()
        val ann = mailRecipient("succ")
        execRaw(
            "INSERT INTO notifications (recipient_id, notification_type, adr_id, other_adr_id, created_at) VALUES " +
                "(${ann.id}, 'ADR_SUPERSEDED', ${predecessor.id}, ${successor.id}, ${System.currentTimeMillis() - 60 * SECOND})",
        )
        insertNotices(ann.id, predecessor.id, 1)
        val mailer = RecordingMailer()

        digestOf(mailer).run()

        val mail = mailer.to(ann.email).single()
        assertEquals("Hammurabi: 1 unseen notification", mail.subject, "the half-sentence notice is dropped, not counted")
        assertFalse(mail.body.contains("superseded by"), mail.body)
        assertTrue(emailedAt(ann).all { it != null })
    }

    @Test
    fun `a person in the preferred language gets that language and an unknown one gets English`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val adr = liveAdr()
        val pl = mailRecipient("lang", "pl")
        val xx = mailRecipient("xx")
        // An unsupported code cannot be created through the service; a stored one (a retired language) is raw.
        execRaw("UPDATE users SET language = 'xx' WHERE id = ${xx.id}")
        insertNotices(pl.id, adr.id, 1)
        insertNotices(xx.id, adr.id, 1)
        val mailer = RecordingMailer()

        digestOf(mailer).run()

        assertEquals("Hammurabi: 1 nieprzeczytane powiadomienie", mailer.to(pl.email).single().subject)
        assertEquals("Hammurabi: 1 unseen notification", mailer.to(xx.email).single().subject)
    }

    @Test
    fun `without a public origin the email carries no link`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val adr = liveAdr()
        val ann = mailRecipient("nolink")
        insertNotices(ann.id, adr.id, 1)
        val mailer = RecordingMailer()

        digestOf(mailer, appUrl = null).run()

        assertFalse(mailer.to(ann.email).single().body.contains("http"))
    }

    @Test
    fun `with the log transport the digest prints no ADR name and no ADR text`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val secret = "SECRET-${java.util.UUID.randomUUID()}"
        val adr = adrActor("drv").client.createAdr(name = adrName(secret), problem = "Problem $secret")
        val ann = mailRecipient("logged")
        insertNotices(ann.id, adr.id, 1)
        val mailLog = LogCapture("ch.nokillswit.mail")
        val root = LogCapture(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME)
        try {
            digestOf(LogMailer()).run()
            val lines = (mailLog.events + root.events).joinToString("\n") { it.everything() }
            assertTrue(lines.contains("Outbound email (log transport"), "the mail was logged by the dev transport")
            assertFalse(lines.contains(secret), "no ADR name and no text in any line")
        } finally {
            mailLog.detach()
            root.detach()
        }
    }

    @Test
    fun `a failed send audits the class name only and the rest of the batch is still mailed`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val adr = liveAdr()
        val broken = mailRecipient("brk")
        val fine = mailRecipient("fin")
        insertNotices(broken.id, adr.id, 1, ageMillis = NEARLY_A_DAY)
        insertNotices(fine.id, adr.id, 1)
        val mailer = RecordingMailer(failing = setOf(broken.email))
        withAuditCapture { audit ->
            val run = digestOf(mailer).run()
            assertEquals(1, run.failed)
            assertEquals(1, run.sent)
            val event = audit.awaitEvent { it.message == "notification.email_failed" && it.hasKeyValue("userId", broken.id.toLong()) }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("errorType", "IllegalStateException"))
            assertFalse(audit.events.any { it.keyValuePairs.toString().contains("secret") })
        }
        assertEquals(1, mailer.to(fine.email).size)
        assertTrue(mailer.to(broken.email).isEmpty())
    }
}
