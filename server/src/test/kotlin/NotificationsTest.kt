package ch.nokillswit

import ch.nokillswit.adrs.AdrParticipantRequest
import ch.nokillswit.adrs.AdrRole
import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.adrs.DiscussionKind
import ch.nokillswit.adrs.DiscussionStatus
import ch.nokillswit.notifications.NotificationType
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The notices an ADR write makes (`adr-model.md` "Notifications"): every notice derives from a history event recorded in the SAME
 * transaction, never for the actor, with the recipients each kind names. Reads and the seen writes are `NotificationsRoutesTest`.
 */
class NotificationsTest {

    private suspend fun AdrActor.types(adrId: UInt): List<NotificationType> =
        client.notices("?pageSize=100&sort=id").items.filter { it.adrId == adrId }.map { it.type }

    @Test
    fun `a role given on creation, by the participants PUT, and taken away notifies its person and never the actor`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        val approverNotice = cast.approver.client.noticesOf(NotificationType.ROLE_ASSIGNED, adr.id).single()
        assertEquals("APPROVER", approverNotice.detail)
        assertEquals(cast.driver.id, approverNotice.actorId)
        assertEquals(adr.key, approverNotice.adrKey)
        assertEquals(adr.name, approverNotice.adrName)
        assertEquals("CONTRIBUTOR", cast.contributor.client.noticesOf(NotificationType.ROLE_ASSIGNED, adr.id).single().detail)
        assertEquals(emptyList(), cast.driver.types(adr.id), "the creator is the actor of every creation notice")

        val newcomer = adrActor("new")
        val driverRole = AdrParticipantRequest(cast.driver.id, AdrRole.DRIVER)
        val others = listOf(
            AdrParticipantRequest(cast.approver.id, AdrRole.APPROVER),
            AdrParticipantRequest(cast.contributor.id, AdrRole.CONTRIBUTOR),
            AdrParticipantRequest(cast.informed.id, AdrRole.INFORMED),
        )
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setParticipants(adr.id,
            listOf(driverRole) + others + AdrParticipantRequest(newcomer.id, AdrRole.CONTRIBUTOR)).status)
        assertEquals("CONTRIBUTOR", newcomer.client.noticesOf(NotificationType.ROLE_ASSIGNED, adr.id).single().detail)

        // A changed role is a role given; a removal is a role taken away.
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setParticipants(adr.id, listOf(driverRole,
            AdrParticipantRequest(newcomer.id, AdrRole.APPROVER), others[2])).status)
        assertEquals(listOf("APPROVER", "CONTRIBUTOR"), newcomer.client.noticesOf(NotificationType.ROLE_ASSIGNED, adr.id)
            .mapNotNull { it.detail }.sorted())
        val removed = cast.approver.client.noticesOf(NotificationType.ROLE_REMOVED, adr.id).single()
        assertEquals("APPROVER", removed.detail)
        assertEquals(cast.driver.id, removed.actorId)
        assertEquals(1, cast.contributor.client.noticesOf(NotificationType.ROLE_REMOVED, adr.id).size)
        assertEquals(emptyList(), cast.driver.client.noticesOf(NotificationType.ROLE_REMOVED, adr.id),
            "the actor is never told of their own write")
    }

    @Test
    fun `a status change reaches every participant, Informed included, but not the person who moved it`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        cast.driver.client.moveAdrNow(adr.id, AdrStatus.IN_REVIEW)
        for (who in listOf(cast.approver, cast.contributor, cast.informed)) {
            assertEquals("IN_REVIEW", who.client.noticesOf(NotificationType.STATUS_CHANGED, adr.id).single().detail)
        }
        assertEquals(emptyList(), cast.driver.client.noticesOf(NotificationType.STATUS_CHANGED, adr.id))
        assertEquals(emptyList(), cast.stranger.client.noticesOf(NotificationType.STATUS_CHANGED, adr.id), "a stranger takes no part")
        cast.approver.client.moveAdrNow(adr.id, AdrStatus.ACTIVE)
        assertEquals(listOf("ACTIVE"), cast.driver.client.noticesOf(NotificationType.STATUS_CHANGED, adr.id).map { it.detail })
        assertEquals(emptyList(), cast.approver.client.noticesOf(NotificationType.STATUS_CHANGED, adr.id).filter { it.detail == "ACTIVE" })
        // Newest first by default.
        assertEquals(listOf("ACTIVE", "IN_REVIEW"), cast.informed.client.noticesOf(NotificationType.STATUS_CHANGED, adr.id)
            .map { it.detail })
    }

    @Test
    fun `the expiry date of an ACTIVE ADR notifies everyone but the actor, a draft's date nobody, and a removed date has no detail`() =
        testApplication {
            usePostgresTestcontainer()
            val cast = adrCast()
            val adr = cast.raise()
            val draft = cast.driver.client.getAdr(adr.id)
            assertEquals(HttpStatusCode.NoContent,
                cast.driver.client.saveAdr(adr.id, draft.revision, draft.asUpdate().copy(expiresOn = "2098-01-01")).status)
            for (who in cast.everyone.values) {
                assertEquals(emptyList(), who.client.noticesOf(NotificationType.EXPIRY_CHANGED, adr.id), "a draft's date is nobody's news")
            }

            cast.activate(adr)
            val live = cast.admin.client.getAdr(adr.id)
            assertEquals(HttpStatusCode.NoContent,
                cast.approver.client.saveAdr(adr.id, live.revision, live.asUpdate().copy(expiresOn = "2099-01-01")).status)
            for (who in listOf(cast.driver, cast.contributor, cast.informed)) {
                val notice = who.client.noticesOf(NotificationType.EXPIRY_CHANGED, adr.id).single()
                assertEquals("2099-01-01", notice.detail)
                assertEquals(cast.approver.id, notice.actorId)
            }
            assertEquals(emptyList(), cast.approver.client.noticesOf(NotificationType.EXPIRY_CHANGED, adr.id), "not the actor")
            assertEquals(emptyList(), cast.stranger.client.noticesOf(NotificationType.EXPIRY_CHANGED, adr.id), "a stranger takes no part")

            // Removing the date is its own notice with no detail; a save that changes no date makes none.
            val dated = cast.admin.client.getAdr(adr.id)
            assertEquals(HttpStatusCode.NoContent, cast.admin.client.saveAdr(adr.id, dated.revision, dated.asUpdate()).status)
            assertEquals(HttpStatusCode.NoContent,
                cast.approver.client.saveAdr(adr.id, dated.revision, dated.asUpdate().copy(expiresOn = null)).status)
            val details = cast.driver.client.noticesOf(NotificationType.EXPIRY_CHANGED, adr.id).map { it.detail }
            assertEquals(listOf(null, "2099-01-01"), details)
        }

    @Test
    fun `a comment or question reaches the leads and Contributors, an issue or escalation the leads only, never its author`() =
        testApplication {
            usePostgresTestcontainer()
            val cast = adrCast()
            val adr = cast.raise()
            cast.stranger.client.postEntryOk(adr.id, DiscussionKind.COMMENT)
            cast.stranger.client.postEntryOk(adr.id, DiscussionKind.QUESTION)
            for (who in listOf(cast.driver, cast.approver, cast.contributor)) {
                assertEquals(listOf("QUESTION", "COMMENT"), who.client.noticesOf(NotificationType.DISCUSSION_POSTED, adr.id)
                    .map { it.detail })
            }
            assertEquals(emptyList(), cast.informed.client.noticesOf(NotificationType.DISCUSSION_POSTED, adr.id))
            assertEquals(emptyList(), cast.stranger.client.noticesOf(NotificationType.DISCUSSION_POSTED, adr.id), "not its own author")
            // The author of a comment is not told of a comment by someone else on a thread they merely started: only the roles are.
            cast.contributor.client.postEntryOk(adr.id, DiscussionKind.COMMENT)
            assertEquals(3, cast.driver.client.noticesOf(NotificationType.DISCUSSION_POSTED, adr.id).size)
            assertEquals(2, cast.contributor.client.noticesOf(NotificationType.DISCUSSION_POSTED, adr.id).size,
                "not the contributor's own comment")

            cast.activate(adr)
            cast.stranger.client.postEntryOk(adr.id, DiscussionKind.ISSUE)
            cast.contributor.client.postEntryOk(adr.id, DiscussionKind.ESCALATION)
            assertEquals(listOf("ESCALATION", "ISSUE"), cast.approver.client.noticesOf(NotificationType.DISCUSSION_POSTED, adr.id)
                .map { it.detail }.take(2))
            assertEquals(2, cast.contributor.client.noticesOf(NotificationType.DISCUSSION_POSTED, adr.id).size,
                "a contributor hears of no issue")
        }

    @Test
    fun `an answered question and a resolved issue notify their author only, and a reopen or an edit notifies nobody`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        val question = cast.stranger.client.postEntryOk(adr.id, DiscussionKind.QUESTION)
        cast.driver.client.setEntryState(adr.id, question.id, DiscussionStatus.ANSWERED)
        val answered = cast.stranger.client.noticesOf(NotificationType.DISCUSSION_SETTLED, adr.id).single()
        assertEquals("QUESTION", answered.detail)
        assertEquals(cast.driver.id, answered.actorId)
        cast.driver.client.setEntryState(adr.id, question.id, DiscussionStatus.OPEN)
        cast.stranger.client.editEntry(adr.id, question.id, "A reworded question")
        assertEquals(1, cast.stranger.client.noticesOf(NotificationType.DISCUSSION_SETTLED, adr.id).size)

        cast.activate(adr)
        val issue = cast.contributor.client.postEntryOk(adr.id, DiscussionKind.ISSUE)
        cast.approver.client.setEntryState(adr.id, issue.id, DiscussionStatus.RESOLVED, "Fixed")
        assertEquals("ISSUE", cast.contributor.client.noticesOf(NotificationType.DISCUSSION_SETTLED, adr.id).single().detail)
        // A question settled by its own author tells nobody (the actor is never told).
        val own = cast.stranger.client.postEntryOk(adr.id, DiscussionKind.ESCALATION)
        cast.driver.client.setEntryState(adr.id, own.id, DiscussionStatus.RESOLVED)
        cast.stranger.client.setEntryState(adr.id, own.id, DiscussionStatus.OPEN)
        assertEquals(2, cast.stranger.client.noticesOf(NotificationType.DISCUSSION_SETTLED, adr.id).size)
    }

    @Test
    fun `an expired ADR tells every participant, the system being the actor`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        cast.activate(adr)
        forceAdrExpiry(adr.id, "2020-01-01")
        val events = ch.nokillswit.adrs.AdrEventService(sharedDatabaseForTests(), newNotificationService())
        val sweep = ch.nokillswit.adrs.AdrExpirySweep(sharedDatabaseForTests(), events)
        assertTrue(sweep.sweep() >= 1)
        for (who in listOf(cast.driver, cast.approver, cast.contributor, cast.informed)) {
            val notice = who.client.noticesOf(NotificationType.ADR_EXPIRED, adr.id).single()
            assertEquals("2020-01-01", notice.detail)
            assertNull(notice.actorId)
            assertNull(notice.actorName)
        }
        assertEquals(emptyList(), cast.stranger.client.noticesOf(NotificationType.ADR_EXPIRED, adr.id))
    }

    @Test
    fun `activating a successor tells the predecessor's participants it was superseded, the activator excepted`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val old = cast.raise()
        cast.activate(old)
        val next = cast.driver.client.createAdr(complete = true, participants = listOf(AdrParticipantRequest(cast.approver.id,
            AdrRole.APPROVER)))
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(next.id, old.id).status)
        cast.approver.client.acceptSuccessorOk(old.id, next.id)
        cast.driver.client.moveAdrNow(next.id, AdrStatus.IN_REVIEW)
        cast.approver.client.moveAdrNow(next.id, AdrStatus.ACTIVE)
        for (who in listOf(cast.driver, cast.contributor, cast.informed)) {
            val notice = who.client.noticesOf(NotificationType.ADR_SUPERSEDED, old.id).single()
            assertEquals(next.id, notice.otherAdrId)
            assertEquals(next.key, notice.otherAdrKey)
            assertEquals(cast.approver.id, notice.actorId)
        }
        assertEquals(emptyList(), cast.approver.client.noticesOf(NotificationType.ADR_SUPERSEDED, old.id), "the activator caused it")
    }

    @Test
    fun `a pending successor linked to or detached from an ADR tells the predecessor's Driver and Approver`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val old = cast.raise()
        cast.activate(old)
        val squatter = adrActor("squat")
        val parked = squatter.client.createAdr(complete = true)
        assertEquals(HttpStatusCode.NoContent, squatter.client.setSupersedes(parked.id, old.id).status)
        for (who in listOf(cast.driver, cast.approver)) {
            val linked = who.client.noticesOf(NotificationType.SUCCESSOR_LINKED, old.id).single()
            assertEquals(parked.id, linked.otherAdrId)
            assertEquals(parked.key, linked.otherAdrKey)
            assertEquals(squatter.id, linked.actorId)
        }
        assertEquals(emptyList(), cast.contributor.client.noticesOf(NotificationType.SUCCESSOR_LINKED, old.id), "only the two leads")

        assertEquals(HttpStatusCode.NoContent, cast.driver.client.detachSuccessor(old.id).status)
        assertEquals(1, cast.approver.client.noticesOf(NotificationType.SUCCESSOR_UNLINKED, old.id).size)
        assertEquals(emptyList(), cast.driver.client.noticesOf(NotificationType.SUCCESSOR_UNLINKED, old.id), "the detacher is the actor")
        assertEquals(emptyList(), squatter.client.noticesOf(NotificationType.SUCCESSOR_UNLINKED, old.id))

        // The successor's own owner dropping the link tells both leads (the actor is not one of them).
        assertEquals(HttpStatusCode.NoContent, squatter.client.setSupersedes(parked.id, old.id).status)
        assertEquals(HttpStatusCode.NoContent, squatter.client.setSupersedes(parked.id, null).status)
        assertEquals(1, cast.driver.client.noticesOf(NotificationType.SUCCESSOR_UNLINKED, old.id).size)
        assertEquals(2, cast.approver.client.noticesOf(NotificationType.SUCCESSOR_UNLINKED, old.id).size)
    }

    @Test
    fun `a refused write makes no notice, and neither does a save that changes nothing`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        val before = cast.approver.client.notices().total
        assertEquals(HttpStatusCode.Conflict, cast.driver.client.moveAdr(adr.id, adr.revision + 5, AdrStatus.IN_REVIEW).status)
        assertEquals(HttpStatusCode.Forbidden, cast.informed.client.moveAdr(adr.id, adr.revision, AdrStatus.IN_REVIEW).status)
        val sameSet = cast.driver.client.getAdr(adr.id).participants.map { AdrParticipantRequest(it.userId, it.role) }
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setParticipants(adr.id, sameSet).status)
        assertEquals(before, cast.approver.client.notices().total)
    }

    @Test
    fun `a soft-deleted ADR hides its notices, and a soft-deleted recipient is never notified`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        assertEquals(1, cast.approver.client.noticesOf(NotificationType.ROLE_ASSIGNED, adr.id).size)
        TestUsers.softDelete(cast.informed.id)
        cast.driver.client.moveAdrNow(adr.id, AdrStatus.IN_REVIEW)
        assertEquals(emptyList(), rawNotices(cast.informed.id).filter { it.type == "STATUS_CHANGED" }, "no notice for a deleted account")
        assertEquals(HttpStatusCode.NoContent, cast.admin.client.delete("/api/v1/adrs/${adr.id}").status)
        assertEquals(emptyList(), cast.approver.client.notices("?pageSize=100").items.filter { it.adrId == adr.id })
        assertNotNull(rawNotices(cast.approver.id).firstOrNull { it.adrId == adr.id }, "the rows stay until retention")
    }

    @Test
    fun `a notice the in-app switch is off for is not made`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        val off = ch.nokillswit.notifications.NotificationPreferencesUpdateRequest(
            listOf(ch.nokillswit.notifications.DisabledNotificationPreference(NotificationType.STATUS_CHANGED,
                ch.nokillswit.notifications.NotificationChannel.IN_APP)),
        )
        assertEquals(HttpStatusCode.NoContent,
            cast.approver.client.putJson("/api/v1/users/${cast.approver.id}/notification-preferences", off).status)
        cast.driver.client.moveAdrNow(adr.id, AdrStatus.IN_REVIEW)
        assertEquals(emptyList(), cast.approver.client.noticesOf(NotificationType.STATUS_CHANGED, adr.id), "switched off for the bell")
        assertEquals(1, cast.contributor.client.noticesOf(NotificationType.STATUS_CHANGED, adr.id).size, "others are unaffected")
        cast.approver.client.moveAdrNow(adr.id, AdrStatus.ACTIVE)
        assertEquals(1, cast.driver.client.noticesOf(NotificationType.STATUS_CHANGED, adr.id).size)
        // The role notice is another kind and still arrives.
        assertTrue(cast.approver.client.get("/api/v1/notifications").status == HttpStatusCode.OK)
        assertEquals(1, cast.approver.client.noticesOf(NotificationType.ROLE_ASSIGNED, adr.id).size)
    }

    @Test
    fun `the events of one write reach the notices together, and one statement makes all their rows`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        val offered = mutableListOf<Int>()
        val db = sharedDatabaseForTests()
        val service = newNotificationService()
        val events = ch.nokillswit.adrs.AdrEventService(db) { id, by, list, at -> offered += list.size; service.onEvents(id, by, list, at) }
        val role = { who: AdrActor, to: AdrRole ->
            ch.nokillswit.adrs.adrParticipantAddedEvent(ch.nokillswit.adrs.AdrParticipant(who.id, "Name", to))
        }
        val before = rawNotices(cast.stranger.id).size
        org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction(db) {
            events.recordAll(
                adr.id, cast.driver.id, listOf(role(cast.stranger, AdrRole.INFORMED), role(cast.admin, AdrRole.CONTRIBUTOR)), 1L,
            )
            events.record(adr.id, cast.driver.id, role(cast.stranger, AdrRole.APPROVER), 2L)
        }
        assertEquals(listOf(2, 1), offered, "recordAll offers its events in ONE call")
        assertEquals(before + 2, rawNotices(cast.stranger.id).size)
        assertEquals(listOf("INFORMED", "APPROVER"), rawNotices(cast.stranger.id).takeLast(2).map { it.detail })
        assertEquals("CONTRIBUTOR", rawNotices(cast.admin.id).last().detail)
        assertEquals(emptyList(), events.recordAll(adr.id, null, emptyList()), "nothing to record, nothing offered")
        assertEquals(listOf(2, 1), offered)
    }

    @Test
    fun `makeAll carries a missing detail and second ADR as nulls and answers the rows really inserted`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        val other = cast.raise()
        execRaw(PREFS_INSERT + "(${cast.contributor.id}, 'SUCCESSOR_LINKED', 'IN_APP')")
        TestUsers.softDelete(cast.informed.id)
        val plans = listOf(
            ch.nokillswit.notifications.NoticePlan(NotificationType.ADR_SUPERSEDED, adr.id,
                ch.nokillswit.notifications.Audience.Person(cast.stranger.id), otherAdrId = other.id),
            ch.nokillswit.notifications.NoticePlan(NotificationType.EXPIRY_REMINDER, adr.id,
                ch.nokillswit.notifications.Audience.Person(cast.stranger.id), detail = "2100-01-01"),
            ch.nokillswit.notifications.NoticePlan(NotificationType.SUCCESSOR_LINKED, adr.id,
                ch.nokillswit.notifications.Audience.Person(cast.contributor.id)),
            ch.nokillswit.notifications.NoticePlan(NotificationType.STATUS_CHANGED, adr.id,
                ch.nokillswit.notifications.Audience.Person(cast.informed.id)),
            ch.nokillswit.notifications.NoticePlan(NotificationType.STATUS_CHANGED, adr.id,
                ch.nokillswit.notifications.Audience.Person(cast.driver.id)),
        )
        val reached = org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction(sharedDatabaseForTests()) {
            newNotificationService().makeAll(plans, cast.driver.id, 5L)
        }
        assertEquals(listOf(cast.stranger.id, cast.stranger.id), reached,
            "the switched-off kind, the deleted account and the actor make no row")
        val rows = rawNotices(cast.stranger.id).takeLast(2)
        assertEquals(listOf("ADR_SUPERSEDED", "EXPIRY_REMINDER"), rows.map { it.type })
        assertEquals(listOf(other.id, null), rows.map { it.otherAdrId })
        assertEquals(listOf(null, "2100-01-01"), rows.map { it.detail })
        assertEquals(cast.driver.id, rows.first().actorId)
        assertEquals(emptyList(), rawNotices(cast.contributor.id).filter { it.type == "SUCCESSOR_LINKED" })
    }
}
