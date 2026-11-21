package ch.nokillswit

import ch.nokillswit.adrs.AdrEventDescriptor
import ch.nokillswit.adrs.AdrEventType
import ch.nokillswit.adrs.AdrRole
import ch.nokillswit.notifications.Audience
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.notifications.noticeFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The event-to-notice map (`NotificationPlan.kt`): which history event makes which notice for whom, and that nothing malformed throws. */
class NotificationPlanTest {
    private fun plan(type: AdrEventType, vararg params: Pair<String, String>, adr: UInt = 7u) =
        noticeFor(adr, AdrEventDescriptor(type, params.toMap()))

    @Test
    fun `a participant event names its person and the role`() {
        val added = plan(AdrEventType.PARTICIPANT_ADDED, "userId" to "5", "name" to "X", "role" to "INFORMED")!!
        assertEquals(NotificationType.ROLE_ASSIGNED, added.type)
        assertEquals(Audience.Person(5u), added.audience)
        assertEquals("INFORMED", added.detail)
        assertEquals("APPROVER", plan(AdrEventType.PARTICIPANT_CHANGED, "userId" to "5", "from" to "INFORMED", "to" to "APPROVER")!!.detail)
        val removed = plan(AdrEventType.PARTICIPANT_REMOVED, "userId" to "5", "role" to "DRIVER")!!
        assertEquals(NotificationType.ROLE_REMOVED, removed.type)
        assertEquals("DRIVER", removed.detail)
        assertNull(plan(AdrEventType.PARTICIPANT_ADDED, "userId" to "junk", "role" to "DRIVER"), "a malformed id is no notice")
    }

    @Test
    fun `lifecycle events reach every role and carry their detail`() {
        val all = AdrRole.entries.toSet()
        assertEquals(Audience.Roles(7u, all), plan(AdrEventType.TRANSITIONED, "from" to "SOLUTION_DRAFT", "to" to "IN_REVIEW")!!.audience)
        assertEquals("IN_REVIEW", plan(AdrEventType.TRANSITIONED, "to" to "IN_REVIEW")!!.detail)
        // 0.7.0: a move back from review is "sent back"; the same target from the first status is a plain move; a reason never travels.
        val back = plan(AdrEventType.TRANSITIONED, "from" to "IN_REVIEW", "to" to "SOLUTION_DRAFT", "reason" to "free text")!!
        assertEquals("SENT_BACK", back.detail)
        assertEquals(Audience.Roles(7u, all), back.audience, "Informed included")
        assertEquals("SOLUTION_DRAFT", plan(AdrEventType.TRANSITIONED, "from" to "PROBLEM_RAISED", "to" to "SOLUTION_DRAFT")!!.detail)
        assertEquals("WITHDRAWN", plan(AdrEventType.TRANSITIONED, "from" to "ACTIVE", "to" to "WITHDRAWN", "reason" to "x")!!.detail)
        val expired = plan(AdrEventType.EXPIRED, "expiresOn" to "2026-01-01")!!
        assertEquals(NotificationType.ADR_EXPIRED, expired.type)
        assertEquals("2026-01-01", expired.detail)
        val superseded = plan(AdrEventType.SUPERSEDED, "successorId" to "9", "key" to "ADR-0009", "name" to "n")!!
        assertEquals(9u, superseded.otherAdrId)
        assertEquals(Audience.Roles(7u, all), superseded.audience)
        assertNull(plan(AdrEventType.SUPERSEDED, "successorId" to "x"))
    }

    @Test
    fun `the discussion window decides who hears of a post, and the author hears of a settled entry`() {
        val leadsAndContributors = setOf(AdrRole.DRIVER, AdrRole.APPROVER, AdrRole.CONTRIBUTOR)
        val leads = setOf(AdrRole.DRIVER, AdrRole.APPROVER)
        assertEquals(Audience.Roles(7u, leadsAndContributors), plan(AdrEventType.COMMENTED, "entryId" to "1",
            "kind" to "COMMENT")!!.audience)
        assertEquals(Audience.Roles(7u, leadsAndContributors), plan(AdrEventType.QUESTION_ASKED, "entryId" to "1",
            "kind" to "QUESTION")!!.audience)
        assertEquals(Audience.Roles(7u, leads), plan(AdrEventType.ISSUE_RAISED, "entryId" to "1", "kind" to "ISSUE")!!.audience)
        assertEquals(Audience.Roles(7u, leads), plan(AdrEventType.ESCALATED, "entryId" to "1", "kind" to "ESCALATION")!!.audience)
        val answered = plan(AdrEventType.QUESTION_ANSWERED, "entryId" to "4", "kind" to "QUESTION")!!
        assertEquals(NotificationType.DISCUSSION_SETTLED, answered.type)
        assertEquals(Audience.EntryAuthor(4u), answered.audience)
        assertEquals("ESCALATION", plan(AdrEventType.ISSUE_RESOLVED, "entryId" to "4", "kind" to "ESCALATION")!!.detail)
        assertNull(plan(AdrEventType.QUESTION_REOPENED, "entryId" to "4", "kind" to "QUESTION"))
        assertNull(plan(AdrEventType.ISSUE_REOPENED, "entryId" to "4", "kind" to "ISSUE"))
        assertNull(plan(AdrEventType.QUESTION_ANSWERED, "kind" to "QUESTION"), "an entry id is needed")
    }

    @Test
    fun `a successor event is the PREDECESSOR's notice, and the detach's mirror event makes none`() {
        val leads = setOf(AdrRole.DRIVER, AdrRole.APPROVER)
        val linked = plan(AdrEventType.SUCCESSOR_LINKED, "predecessorId" to "3", "key" to "ADR-0003", "name" to "n", adr = 8u)!!
        assertEquals(NotificationType.SUCCESSOR_LINKED, linked.type)
        assertEquals(3u, linked.adrId, "about the predecessor")
        assertEquals(8u, linked.otherAdrId, "the successor is the ADR the event was written on")
        assertEquals(Audience.Roles(3u, leads), linked.audience)
        assertEquals(NotificationType.SUCCESSOR_UNLINKED, plan(AdrEventType.SUCCESSOR_UNLINKED, "predecessorId" to "3", adr = 8u)!!.type)
        assertNull(plan(AdrEventType.SUCCESSOR_UNLINKED, "successorId" to "8", "key" to "k", "name" to "n", adr = 3u))
        assertNull(plan(AdrEventType.SUCCESSOR_ACTIVATED, "predecessorId" to "3", adr = 8u))
    }

    @Test
    fun `events that notify nobody are ignored`() {
        for (type in listOf(AdrEventType.CREATED, AdrEventType.UPDATED, AdrEventType.TAGS_CHANGED, AdrEventType.SUCCESSOR_ACTIVATED)) {
            assertNull(plan(type, "userId" to "1", "predecessorId" to "2"), "$type")
        }
    }
}
