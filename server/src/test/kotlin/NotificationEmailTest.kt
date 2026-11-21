package ch.nokillswit

import ch.nokillswit.notifications.DIGEST_MAX_ENTRIES
import ch.nokillswit.notifications.DigestEntry
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.notifications.mailSafe
import ch.nokillswit.notifications.notificationEmail
import ch.nokillswit.notifications.plainName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The digest text (`NotificationEmail.kt`): the words of every kind in both languages, the plural subjects, the links and the safe
// names.
class NotificationEmailTest {
    private fun entry(type: NotificationType, detail: String? = null, actor: String? = "Ala", other: String? = null) =
        DigestEntry(type, actor, 12u, "ADR-0042", other, detail)

    private fun body(language: String, vararg entries: DigestEntry, appUrl: String? = "https://h.example") =
        notificationEmail("Ola", entries.toList(), appUrl, language).body

    @Test
    fun `every kind has a sentence in both languages naming the actor and the key`() {
        val kinds = listOf(
            entry(NotificationType.ROLE_ASSIGNED, "APPROVER"),
            entry(NotificationType.ROLE_REMOVED, "CONTRIBUTOR"),
            entry(NotificationType.STATUS_CHANGED, "ACTIVE"),
            entry(NotificationType.DISCUSSION_POSTED, "ESCALATION"),
            entry(NotificationType.DISCUSSION_SETTLED, "QUESTION"),
            entry(NotificationType.DISCUSSION_SETTLED, "ISSUE"),
            entry(NotificationType.ADR_EXPIRED, "2026-12-01", actor = null),
            entry(NotificationType.ADR_SUPERSEDED, other = "ADR-0050"),
            entry(NotificationType.SUCCESSOR_LINKED, other = "ADR-0050"),
            entry(NotificationType.SUCCESSOR_UNLINKED, other = "ADR-0050"),
            entry(NotificationType.SUCCESSOR_ACCEPTED, other = "ADR-0050"),
            entry(NotificationType.EXPIRY_REMINDER, "2026-12-01", actor = null),
            entry(NotificationType.PREDECESSOR_ENDED, "WITHDRAWN", other = "ADR-0050", actor = null),
        )
        for (language in listOf("en", "pl")) {
            val lines = body(language, *kinds.toTypedArray()).lines().filter { it.startsWith("- ") }
            assertEquals(DIGEST_MAX_ENTRIES, lines.size, "$language lists at most the cap")
            assertTrue(lines.all { "ADR-0042" in it }, "$language: $lines")
        }
        val en = body("en", kinds[0], kinds[2], kinds[3], kinds[4], kinds[6])
        assertTrue("Ala made you Approver of ADR-0042" in en, en)
        assertTrue("Ala moved ADR-0042 to \"Active\"" in en, en)
        assertTrue("Ala added a escalation on ADR-0042" in en, en)
        assertTrue("Ala answered your question on ADR-0042" in en, en)
        assertTrue("ADR-0042 has expired (its last day in force was 2026-12-01)" in en, en)
        val linked = entry(NotificationType.SUCCESSOR_LINKED, other = "ADR-0050")
        val accepted = entry(NotificationType.SUCCESSOR_ACCEPTED, other = "ADR-0050")
        assertTrue("Ala proposed ADR-0050 to replace ADR-0042" in body("en", linked), "a link is only a proposal")
        assertTrue("Ala accepted ADR-0042 to replace ADR-0050" in body("en", accepted))
        assertTrue("Ala akceptuje ADR-0042 jako następcę ADR-0050" in body("pl", accepted))
        val ended = entry(NotificationType.PREDECESSOR_ENDED, "WITHDRAWN", other = "ADR-0050", actor = null)
        assertTrue("ADR-0050, which ADR-0042 was set to replace, is now \"Withdrawn\"" in body("en", ended), body("en", ended))
        val plEnded = body("pl", ended)
        assertTrue("ADR-0050, który miał zostać zastąpiony przez ADR-0042, ma teraz status „Wycofany”" in plEnded, plEnded)
        val sentBack = entry(NotificationType.STATUS_CHANGED, "SENT_BACK")
        assertTrue("Ala sent ADR-0042 back to draft" in body("en", sentBack))
        assertTrue("Ala odsyła ADR-0042 do szkicu" in body("pl", sentBack))
        val pl = body("pl", kinds[0], kinds[5], kinds[6])
        assertTrue("Ala przypisuje Ci rolę „Zatwierdzający/a” w ADR-0042" in pl, pl)
        assertTrue("Ala rozwiązuje Twój wpis typu „zgłoszenie” w ADR-0042" in pl, pl)
        assertTrue("ADR-0042 wygasł (ostatni dzień obowiązywania: 2026-12-01)" in pl, pl)
        assertTrue("Cześć Ola," in pl && "W Hammurabim" in pl)
    }

    @Test
    fun `a changed expiry date is told with its date, a removed one without, in both languages`() {
        val changed = entry(NotificationType.EXPIRY_CHANGED, "2027-03-31")
        val removed = entry(NotificationType.EXPIRY_CHANGED, null)
        val en = body("en", changed, removed)
        assertTrue("Ala changed the expiry date of ADR-0042 to 2027-03-31 (the last day in force)" in en, en)
        assertTrue("Ala removed the expiry date of ADR-0042" in en, en)
        val pl = body("pl", changed, removed)
        assertTrue("Ala zmienia datę wygaśnięcia ADR-0042 na 2027-03-31 (ostatni dzień obowiązywania)" in pl, pl)
        assertTrue("Ala usuwa datę wygaśnięcia ADR-0042" in pl, pl)
    }

    @Test
    fun `the system is named when there is no actor, an unknown detail is written as stored`() {
        assertTrue("Hammurabi" in body("en", entry(NotificationType.STATUS_CHANGED, "FUTURE_STATE", actor = null)))
        assertTrue("\"FUTURE_STATE\"" in body("en", entry(NotificationType.STATUS_CHANGED, "FUTURE_STATE")))
        assertTrue("FUTURE_ROLE of ADR-0042" in body("en", entry(NotificationType.ROLE_ASSIGNED, "FUTURE_ROLE")))
        assertTrue("Hi Ola," in body("de", entry(NotificationType.EXPIRY_REMINDER, "2026-12-01")), "an unknown language reads English")
    }

    @Test
    fun `the subject counts with the plural forms of each language and holds no name`() {
        fun subject(language: String, count: Int) =
            notificationEmail("Ola", List(count) { entry(NotificationType.STATUS_CHANGED, "ACTIVE") }, null, language).subject
        assertEquals("Hammurabi: 1 unseen notification", subject("en", 1))
        assertEquals("Hammurabi: 3 unseen notifications", subject("en", 3))
        assertEquals("Hammurabi: 1 nieprzeczytane powiadomienie", subject("pl", 1))
        assertEquals("Hammurabi: 3 nieprzeczytane powiadomienia", subject("pl", 3))
        assertEquals("Hammurabi: 5 nieprzeczytanych powiadomień", subject("pl", 5))
        assertEquals("Hammurabi: 12 nieprzeczytanych powiadomień", subject("pl", 12))
        assertEquals("Hammurabi: 22 nieprzeczytane powiadomienia", subject("pl", 22))
        assertFalse(subject("en", 3).contains("Ala"))
    }

    @Test
    fun `links come from the configured origin and the ADR id only, the list is capped and the rest counted`() {
        val many = List(DIGEST_MAX_ENTRIES + 3) { entry(NotificationType.STATUS_CHANGED, "ACTIVE") }
        val withLinks = notificationEmail("Ola", many, "https://h.example", "en", unlisted = 2)
        assertTrue("  https://h.example/adrs/12" in withLinks.body)
        assertTrue("...and 5 more" in withLinks.body)
        assertTrue("Change which emails you get: https://h.example/notification-preferences" in withLinks.body)
        assertEquals("Hammurabi: 15 unseen notifications", withLinks.subject)
        val none = notificationEmail("Ola", many.take(1), null, "en")
        assertFalse("http" in none.body, "no origin, no link")
        assertFalse("more" in none.body)
    }

    @Test
    fun `an expiry reminder links directly to the ADR validity section`() {
        val reminder = entry(NotificationType.EXPIRY_REMINDER, "2026-12-01", actor = null)
        assertTrue("  https://h.example/adrs/12#validity" in body("en", reminder))
        assertTrue("  https://h.example/adrs/12\n" in body("en", entry(NotificationType.ADR_EXPIRED, "2026-12-01", actor = null)))
    }

    @Test
    fun `an actor's name cannot make a link, an address, a new line or reorder the text`() {
        val hostile = "Evil\n- fake entry evil.example.com a@b.c http://x\\\\host‮reversed​z"
        val text = body("en", entry(NotificationType.STATUS_CHANGED, "ACTIVE", actor = hostile))
        val line = text.lines().first { it.startsWith("- ") }
        assertFalse(Regex("[a-z0-9]\\.[a-z]").containsMatchIn(line.replace("․", "")) && "evil.example.com" in line, line)
        assertFalse("@" in line || "://" in line || "\\" in line, line)
        assertEquals(1, text.lines().count { it.startsWith("- ") }, "a name cannot start a new entry")
        assertEquals("a b", plainName("a\u0000b"))
        assertEquals("ab", plainName("a‮b"))
        assertEquals("J․K. Rowling", mailSafe("J.K. Rowling"))
        assertEquals("end. Next", mailSafe("end. Next"))
    }
}
