package ch.nokillswit

import ch.nokillswit.adrs.AdrParticipantRequest
import ch.nokillswit.adrs.AdrRole
import ch.nokillswit.infra.mail.Mailer
import ch.nokillswit.notifications.DigestBounds
import ch.nokillswit.notifications.NotificationChannel
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.notifications.digestBatchSize
import ch.nokillswit.notifications.peopleToClaim
import ch.nokillswit.users.UserRole
import io.ktor.client.request.delete
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The email digest (`NotificationDigest.kt`, Adder's port): the claim, the send-time re-check, the language, the preferences, the failure
 * and release rules. Built directly with a recording mailer; the shared database's earlier notices are settled first so the claim (oldest
 * people first) sees only this test's.
 */
class NotificationDigestTest {
    private suspend fun person(prefix: String, language: String = "en") = mailRecipient(prefix, language)

    private fun digest(
        mailer: Mailer?,
        delay: Long = 0,
        interval: Long = 0,
        maxPerRun: Int = 100,
        clock: () -> Long = System::currentTimeMillis,
        afterClaim: suspend () -> Unit = {},
    ) = digestOf(mailer, delay = delay, interval = interval, maxPerRun = maxPerRun, clock = clock, afterClaim = afterClaim)

    /** A driver, an ADR and one role notice for [who] (they hold the Contributor role, given by the driver). */
    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.noticeFor(who: MailRecipient): Pair<AdrActor, UInt> {
        val driver = adrActor("drv")
        val adr = driver.client.createAdr(participants = listOf(AdrParticipantRequest(who.id, AdrRole.CONTRIBUTOR)))
        return driver to adr.id
    }

    @Test
    fun `one mail per person in their language, listing the notices with a link, and a notice is mailed once`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val ola = person("ola", "pl")
        val ann = person("ann")
        val (driver, adrId) = noticeFor(ola)
        driver.client.createAdr(participants = listOf(AdrParticipantRequest(ola.id, AdrRole.APPROVER), AdrParticipantRequest(ann.id,
            AdrRole.INFORMED)))
        val mailer = RecordingMailer()
        val run = digest(mailer).run()
        assertTrue(run.claimed >= 3 && run.sent >= 2 && run.failed == 0, "$run")
        val olaMail = mailer.to(ola.email).single()
        assertEquals("Hammurabi: 2 nieprzeczytane powiadomienia", olaMail.subject)
        assertTrue("Cześć Ola," in olaMail.body && "https://h.example/adrs/$adrId" in olaMail.body
            && "/notification-preferences" in olaMail.body, olaMail.body)
        val annMail = mailer.to(ann.email).single()
        assertEquals("Hammurabi: 1 unseen notification", annMail.subject)
        assertTrue("made you Informed of ADR-" in annMail.body, annMail.body)
        assertTrue(rawNotices(ola.id).all { it.emailedAt != null })
        assertEquals(0, digest(mailer).run().sent, "a claimed notice is never mailed again")
        assertEquals(1, mailer.to(ola.email).size)
    }

    @Test
    fun `a seen notice, a young one, a switched-off kind and a deleted account are not mailed`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val seen = person("seen")
        val off = person("off")
        val gone = person("gone")
        val young = person("young")
        val (driver, _) = noticeFor(seen)
        driver.client.createAdr(participants = listOf(AdrParticipantRequest(off.id, AdrRole.CONTRIBUTOR), AdrParticipantRequest(gone.id,
            AdrRole.CONTRIBUTOR)))
        val seenClient = authedClient(seen.email, "pw")
        seenClient.markAllSeen()
        execRaw(PREFS_INSERT + "(${off.id}, 'ROLE_ASSIGNED', 'EMAIL')")
        TestUsers.softDelete(gone.id)
        driver.client.createAdr(participants = listOf(AdrParticipantRequest(young.id, AdrRole.CONTRIBUTOR)))
        val mailer = RecordingMailer()
        val now = System.currentTimeMillis()
        // The delay is longer than the age of any notice made just now, except that the test moves the clock past it for everybody
        // but the "young" person's own notice: so run once at "now" with a delay that excludes all of them.
        assertEquals(0, digest(mailer, delay = 60_000, clock = { now }).run().sent, "nothing is old enough yet")
        digest(mailer, delay = 60_000, clock = { now + 61_000 }).run()
        assertEquals(emptyList(), mailer.to(seen.email), "seen")
        assertEquals(emptyList(), mailer.to(off.email), "the kind is switched off for email")
        assertEquals(emptyList(), mailer.to(gone.email), "deleted")
        assertEquals(1, mailer.to(young.email).size)
        assertTrue(rawNotices(off.id).all { it.emailedAt != null }, "the switched-off notice is settled, so it never fills a scan again")
    }

    @Test
    fun `nothing older than a day is mailed, and a person inside the interval waits and gets one later mail`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val late = person("late")
        val (driver, _) = noticeFor(late)
        val mailer = RecordingMailer()
        val now = System.currentTimeMillis()
        assertEquals(0, digest(mailer, clock = { now + 90_000_000 }).run().sent, "a day and an hour old")

        settleNoticeBacklog()
        val spaced = person("spaced")
        driver.client.createAdr(participants = listOf(AdrParticipantRequest(spaced.id, AdrRole.CONTRIBUTOR)))
        val spacedDigest = digest(mailer, interval = 900_000)
        assertEquals(1, spacedDigest.run().sent)
        driver.client.createAdr(participants = listOf(AdrParticipantRequest(spaced.id, AdrRole.INFORMED)))
        assertEquals(0, spacedDigest.run().sent, "inside the interval: left unclaimed")
        assertTrue(rawNotices(spaced.id).last().emailedAt == null, "still waiting")
        assertEquals(1, digest(mailer).run().sent, "a process that forgot the interval mails the waiting notice")
    }

    @Test
    fun `a notice seen after the claim and an ADR deleted after the claim are skipped silently at send time`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val reader = person("reader")
        val (driver, _) = noticeFor(reader)
        val client = authedClient(reader.email, "pw")
        val doomed = driver.client.createAdr(participants = listOf(AdrParticipantRequest(reader.id, AdrRole.INFORMED)))
        val mailer = RecordingMailer()
        val admin = adrActor("adm", UserRole.ADMIN)
        val run = digest(mailer, afterClaim = {
            client.markAllSeen()
            admin.client.delete("/api/v1/adrs/${doomed.id}")
        }).run()
        assertTrue(run.claimed >= 2 && run.sent == 0 && run.failed == 0, "$run")
        assertEquals(emptyList(), mailer.to(reader.email))
        assertTrue(rawNotices(reader.id).all { it.emailedAt != null }, "skipped means settled: never mailed later")
    }

    @Test
    fun `a notice of a soft-deleted ADR is not mailed while the person's other notices are`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val who = person("both")
        val (driver, keptId) = noticeFor(who)
        val doomed = driver.client.createAdr(participants = listOf(AdrParticipantRequest(who.id, AdrRole.INFORMED)))
        adrActor("adm", UserRole.ADMIN).client.delete("/api/v1/adrs/${doomed.id}")
        val mailer = RecordingMailer()
        digest(mailer).run()
        val mail = mailer.to(who.email).single()
        assertEquals("Hammurabi: 1 unseen notification", mail.subject)
        assertTrue("/adrs/$keptId" in mail.body && "/adrs/${doomed.id}" !in mail.body)
    }

    @Test
    fun `a failed send is audited with the class name only, not retried, and three in a row release the rest`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val people = List(5) { person("p$it") }
        val driver = adrActor("drv")
        driver.client.createAdr(participants = people.map { AdrParticipantRequest(it.id, AdrRole.CONTRIBUTOR) })
        val mailer = RecordingMailer(failing = people.map { it.email }.toSet())
        withAuditCapture { capture ->
            val run = digest(mailer).run()
            assertEquals(3, run.failed, "three failures in a row end the run")
            assertEquals(2, run.released, "the two never attempted go back to the queue")
            val failed = capture.awaitEvent { it.message == "notification.email_failed" }
            assertNotNull(failed)
            assertTrue(failed.hasKeyValue("errorType", "IllegalStateException"))
            assertFalse(capture.events.any { e -> e.keyValuePairs.orEmpty().any { "secret" in it.value.toString() } },
                "the provider's message never reaches the trail")
        }
        val waiting = people.count { p -> rawNotices(p.id).any { it.emailedAt == null } }
        assertEquals(2, waiting, "released claims are mailed by a later run")
    }

    @Test
    fun `a sent mail is audited and a run claims at most maxPerRun mails, releasing the rest`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val people = List(4) { person("m$it") }
        val driver = adrActor("drv")
        driver.client.createAdr(participants = people.map { AdrParticipantRequest(it.id, AdrRole.CONTRIBUTOR) })
        val mailer = RecordingMailer()
        withAuditCapture { capture ->
            val run = digest(mailer, maxPerRun = 2).run()
            assertEquals(2, run.sent)
            val sent = capture.awaitEvent { it.message == "notification.email_sent" }
            assertNotNull(sent)
            assertTrue(sent.hasKeyValue("count", 1))
        }
        assertEquals(2, digest(mailer, maxPerRun = 2).run().sent, "the next run mails the rest")
        assertEquals(4, people.sumOf { mailer.to(it.email).size })
    }

    @Test
    fun `no mailer means no claim - the bell keeps working and the notices wait`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val who = person("nomail")
        noticeFor(who)
        val quiet = digest(null)
        assertEquals(0, quiet.run().claimed)
        assertEquals(0, quiet.run().claimed)
        assertTrue(rawNotices(who.id).all { it.emailedAt == null })
        assertEquals(1, authedClient(who.email, "pw").notices().total.toInt())
    }

    @Test
    fun `the claim is bounded and the people to claim follow the send budget`() {
        assertEquals(120, peopleToClaim(500, 100))
        assertEquals(12, peopleToClaim(500, 2))
        assertEquals(5, peopleToClaim(5, 100))
        assertEquals(500, digestBatchSize(100))
        assertEquals(1_200, digestBatchSize(1_000), "the configured maximum remains effective above the default claim batch")
        assertEquals(5_000, DigestBounds().scanCap)
    }

    @Test
    fun `a switch back on covers the notices made from then on`() = testApplication {
        usePostgresTestcontainer()
        settleNoticeBacklog()
        val who = person("flip")
        val driver = adrActor("drv")
        execRaw(PREFS_INSERT + "(${who.id}, '${NotificationType.ROLE_ASSIGNED}', '${NotificationChannel.EMAIL}')")
        driver.client.createAdr(participants = listOf(AdrParticipantRequest(who.id, AdrRole.CONTRIBUTOR)))
        val mailer = RecordingMailer()
        digest(mailer).run()
        assertEquals(emptyList(), mailer.to(who.email))
        execRaw("DELETE FROM user_notification_preferences WHERE user_id = ${who.id}")
        driver.client.createAdr(participants = listOf(AdrParticipantRequest(who.id, AdrRole.CONTRIBUTOR)))
        digest(mailer).run()
        assertEquals(1, mailer.to(who.email).size)
    }
}
