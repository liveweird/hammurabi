package ch.nokillswit

import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.notifications.MAX_COUNTED_NOTICES
import ch.nokillswit.notifications.NotificationPreferenceKind
import ch.nokillswit.notifications.NotificationPreferencesResponse
import ch.nokillswit.notifications.NotificationPreferencesUpdateRequest
import ch.nokillswit.notifications.NotificationService
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `/api/v1/notifications` and `/users/{id}/notification-preferences` (`security.md` "Notices", `authorization.md`): the caller's own rows
 * only, the capped total, the seen writes, the switches and their audit line, and the per-user `read-state` bucket.
 */
class NotificationRoutesTest {

    private fun off(type: NotificationType, inApp: Boolean = true, email: Boolean = true) =
        NotificationPreferencesUpdateRequest(listOf(NotificationPreferenceKind(type, inApp, email)))

    @Test
    fun `the list is the caller's own, newest first, filterable by seen and sortable`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        cast.driver.client.moveAdrNow(adr.id, AdrStatus.IN_REVIEW)
        val all = cast.approver.client.notices("?pageSize=100")
        assertEquals(listOf(NotificationType.STATUS_CHANGED, NotificationType.ROLE_ASSIGNED),
            all.items.filter { it.adrId == adr.id }.map { it.type })
        assertTrue(all.items.none { it.seen })
        assertEquals(all.items.map { it.id }.sortedDescending(), all.items.map { it.id }, "newest first")
        val oldestFirst = cast.approver.client.notices("?sort=id&pageSize=100").items
        assertEquals(oldestFirst.map { it.id }, oldestFirst.map { it.id }.sorted())
        assertEquals(cast.driver.id, all.items.first().actorId)
        assertEquals(cast.driver.client.getAdr(adr.id).participants.first { it.userId == cast.driver.id }.name, all.items.first().actorName)

        val first = all.items.first()
        assertEquals(HttpStatusCode.NoContent, cast.approver.client.markSeen(first.id).status)
        assertEquals(HttpStatusCode.NoContent, cast.approver.client.markSeen(first.id).status, "seen again is 204")
        assertTrue(cast.approver.client.notices("?seen=true").items.single().seen)
        assertEquals(all.total - 1, cast.approver.client.notices("?seen=false").total)
        assertEquals(emptyList(), cast.stranger.client.notices("?pageSize=100").items.filter { it.adrId == adr.id }, "nobody else's rows")
        val paged = cast.approver.client.notices("?page=2&pageSize=1&sort=id")
        assertEquals(2, paged.page)
        assertEquals(1, paged.items.size)
    }

    @Test
    fun `a bad page, size, sort or seen is 400`() = testApplication {
        usePostgresTestcontainer()
        val who = adrActor("bad")
        for (query in listOf("?page=0", "?pageSize=101", "?sort=actorName", "?seen=maybe", "?seen=true&seen=false")) {
            assertEquals(HttpStatusCode.BadRequest, who.client.get("/api/v1/notifications$query").status, query)
        }
    }

    @Test
    fun `seen on another person's or an unknown notice is one 404, and seen-all touches only the caller's rows`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        cast.driver.client.moveAdrNow(adr.id, AdrStatus.IN_REVIEW)
        val theirs = cast.approver.client.notices("?pageSize=100").items.first { it.adrId == adr.id }
        val foreign = cast.contributor.client.markSeen(theirs.id)
        assertEquals(HttpStatusCode.NotFound, foreign.status)
        assertEquals(HttpStatusCode.NotFound, cast.contributor.client.markSeen(2_000_000_000u).status)
        assertTrue(cast.approver.client.notices("?seen=false&pageSize=100").items.any { it.id == theirs.id },
            "the foreign attempt changed nothing")

        assertEquals(HttpStatusCode.NoContent, cast.approver.client.markAllSeen().status)
        assertEquals(0, cast.approver.client.notices("?seen=false").total)
        assertTrue(cast.contributor.client.notices("?seen=false").total > 0, "the contributor's own rows stay unseen")
        assertEquals(HttpStatusCode.NoContent, cast.approver.client.markAllSeen().status, "nothing unseen is still 204")
    }

    @Test
    fun `the total is capped at 1000 and a page starting past the cap is empty`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        insertNotices(cast.approver.id, adr.id, MAX_COUNTED_NOTICES + 50)
        val page = cast.approver.client.notices("?pageSize=100&page=1")
        assertEquals(MAX_COUNTED_NOTICES.toLong(), page.total)
        assertEquals(100, page.items.size)
        assertEquals(emptyList(), cast.approver.client.notices("?pageSize=100&page=11").items, "row 1000 and beyond are never read")
        assertEquals(100, cast.approver.client.notices("?pageSize=100&page=10").items.size)
        assertEquals(MAX_COUNTED_NOTICES.toLong(), cast.approver.client.notices("?seen=false&pageSize=1").total)
        assertEquals(HttpStatusCode.NoContent, cast.approver.client.markAllSeen().status)
        assertEquals(0, cast.approver.client.notices("?seen=false&pageSize=1").total, "seen-all works in chunks over the whole backlog")
    }

    @Test
    fun `markAllSeen works in chunks and loses no notice another request marked between a chunk's select and update`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        insertNotices(cast.informed.id, adr.id, 25)
        val service = NotificationService(sharedDatabaseForTests())
        var raced = false
        val moved = service.markAllSeen(cast.informed.id, System.currentTimeMillis(), chunk = 10) { ids ->
            if (!raced && ids.isNotEmpty()) {
                raced = true
                // Someone else marks the first row of the chunk seen after it was selected: the update comes up short, the loop goes on.
                assertEquals(HttpStatusCode.NoContent, cast.informed.client.markSeen(ids.first()).status)
            }
        }
        assertTrue(raced)
        assertTrue(moved in 24..25, "moved=$moved")
        assertEquals(0, cast.informed.client.notices("?seen=false&pageSize=1").total)
    }

    @Test
    fun `purge deletes the old notices in batches and leaves the young ones`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        val day = 86_400_000L
        insertNotices(cast.contributor.id, adr.id, 25, ageMillis = 100 * day)
        val before = rawNotices(cast.contributor.id)
        val old = before.count { it.createdAt < System.currentTimeMillis() - 90 * day }
        assertEquals(25, old)
        val batches = mutableListOf<Int>()
        val deleted = NotificationService(sharedDatabaseForTests()).purgeOlderThan(System.currentTimeMillis() - 90 * day,
            batchSize = 10) { batches += it }
        assertTrue(deleted >= 25)
        assertTrue(batches.all { it <= 10 } && batches.size >= 3, "$batches")
        assertEquals(before.size - 25, rawNotices(cast.contributor.id).size, "the young role notice stays")
    }

    @Test
    fun `the switches default to on, replace wholesale, and audit only a real change`() = testApplication {
        usePostgresTestcontainer()
        val me = adrActor("prefs")
        val path = "/api/v1/users/${me.id}/notification-preferences"
        val fresh = me.client.get(path).body<NotificationPreferencesResponse>()
        assertEquals(NotificationType.entries, fresh.kinds.map { it.type })
        assertTrue(fresh.kinds.all { it.inApp && it.email })
        withAuditCapture { capture ->
            val body = NotificationPreferencesUpdateRequest(
                listOf(
                    NotificationPreferenceKind(NotificationType.ROLE_ASSIGNED, inApp = true, email = false),
                    NotificationPreferenceKind(NotificationType.STATUS_CHANGED, inApp = false, email = true),
                ),
            )
            assertEquals(HttpStatusCode.NoContent, me.client.putJson(path, body).status)
            val changed = capture.awaitEvent { it.message == "user.notification_preferences_changed" && it.hasKeyValue("targetUserId",
                me.id.toLong()) }
            assertNotNull(changed)
            assertTrue(changed.hasKeyValue("byUserId", me.id.toLong()) && changed.hasKeyValue("from", ""))
            assertTrue(changed.hasKeyValue("to", "ROLE_ASSIGNED:EMAIL,STATUS_CHANGED:IN_APP"))
            val after = me.client.get(path).body<NotificationPreferencesResponse>()
            assertEquals(false, after.kinds.first { it.type == NotificationType.ROLE_ASSIGNED }.email)
            assertEquals(true, after.kinds.first { it.type == NotificationType.ROLE_ASSIGNED }.inApp)
            assertEquals(false, after.kinds.first { it.type == NotificationType.STATUS_CHANGED }.inApp)

            val before = capture.events.count { it.message == "user.notification_preferences_changed" }
            assertEquals(HttpStatusCode.NoContent, me.client.putJson(path, body).status, "the same set again")
            assertEquals(before, capture.events.count { it.message == "user.notification_preferences_changed" }, "a no-op audits nothing")
        }
        assertEquals(HttpStatusCode.NoContent, me.client.putJson(path, NotificationPreferencesUpdateRequest(emptyList())).status)
        assertTrue(me.client.get(path).body<NotificationPreferencesResponse>().kinds.all { it.inApp && it.email })
    }

    @Test
    fun `what the GET answers is what the PUT takes - a round trip is a no-op, and a kind left out is on`() = testApplication {
        usePostgresTestcontainer()
        val me = adrActor("roundtrip")
        val path = "/api/v1/users/${me.id}/notification-preferences"
        assertEquals(HttpStatusCode.NoContent, me.client.putJson(path, off(NotificationType.ROLE_ASSIGNED, email = false)).status)
        val read = me.client.get(path).body<NotificationPreferencesResponse>()
        withAuditCapture { capture ->
            // The answer's own kinds, sent back unchanged: nothing moves and nothing is audited.
            assertEquals(HttpStatusCode.NoContent, me.client.putJson(path, NotificationPreferencesUpdateRequest(read.kinds)).status)
            assertEquals(read, me.client.get(path).body<NotificationPreferencesResponse>())
            assertEquals(0, capture.events.count { it.message == "user.notification_preferences_changed" }, "a round trip audits nothing")
        }
        // A wholesale replace naming one kind: every kind it leaves out reads as on again.
        assertEquals(HttpStatusCode.NoContent, me.client.putJson(path, off(NotificationType.STATUS_CHANGED, inApp = false)).status)
        val after = me.client.get(path).body<NotificationPreferencesResponse>().kinds.associateBy { it.type }
        assertEquals(false, after.getValue(NotificationType.STATUS_CHANGED).inApp)
        assertTrue(after.getValue(NotificationType.ROLE_ASSIGNED).let { it.inApp && it.email }, "an omitted kind is on")
    }

    @Test
    fun `the spec bounds the PUT body at one entry per kind`() {
        val kinds = OpenApiSpec.parsed.components.schemas.getValue("NotificationPreferencesUpdateRequest").properties.getValue("kinds")
        assertEquals(NotificationType.entries.size, kinds.maxItems)
    }

    @Test
    fun `the switches are the target's or an ADMIN's - 403 before the body, 400 for a repeat, 404 for an unknown user`() = testApplication {
        usePostgresTestcontainer()
        val owner = adrActor("owner")
        val other = adrActor("other")
        val admin = adrActor("adm", UserRole.ADMIN)
        val path = "/api/v1/users/${owner.id}/notification-preferences"
        assertEquals(HttpStatusCode.Forbidden, other.client.get(path).status)
        // An INVALID body (not the update's shape): the 403 must still win over the 400 the decode would raise.
        val invalid = other.client.put(path) {
            contentType(ContentType.Application.Json)
            setBody("""{"disabled":[]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, invalid.status)
        assertEquals(HttpStatusCode.OK, admin.client.get(path).status)
        assertEquals(HttpStatusCode.NoContent, admin.client.putJson(path, off(NotificationType.EXPIRY_REMINDER,
            email = false)).status)
        val twice = NotificationPreferencesUpdateRequest(
            listOf(
                NotificationPreferenceKind(NotificationType.ADR_EXPIRED, inApp = true, email = false),
                NotificationPreferenceKind(NotificationType.ADR_EXPIRED, inApp = false, email = true),
            ),
        )
        assertEquals(HttpStatusCode.BadRequest, owner.client.putJson(path, twice).status)
        assertEquals(HttpStatusCode.NotFound, admin.client.get("/api/v1/users/2000000000/notification-preferences").status)
        assertEquals(HttpStatusCode.NotFound, admin.client.putJson("/api/v1/users/2000000000/notification-preferences",
            twice.copy(kinds = emptyList())).status)
        val ghost = adrActor("ghost")
        TestUsers.softDelete(ghost.id)
        assertEquals(HttpStatusCode.NotFound, admin.client.get("/api/v1/users/${ghost.id}/notification-preferences").status)
    }

    @Test
    fun `a stored kind or channel this build does not know is ignored by every reader`() = testApplication {
        usePostgresTestcontainer()
        val me = adrActor("future")
        execRaw(
            PREFS_INSERT + "(${me.id}, 'FROM_THE_FUTURE', 'EMAIL'), (${me.id}, 'ADR_EXPIRED', 'SMOKE')",
        )
        val body = me.client.get("/api/v1/users/${me.id}/notification-preferences").body<NotificationPreferencesResponse>()
        assertTrue(body.kinds.all { it.inApp && it.email })
    }

    @Test
    fun `the notification routes ride the per-user read-state bucket`() = testApplication {
        usePostgresTestcontainer()
        val me = adrActor("flood")
        val calm = adrActor("calm")
        var refused = 0
        repeat(260) { if (me.client.get("/api/v1/notifications?pageSize=1").status == HttpStatusCode.TooManyRequests) refused++ }
        assertTrue(refused >= 10, "refused=$refused")
        assertEquals(HttpStatusCode.OK, calm.client.get("/api/v1/notifications?pageSize=1").status, "another user's bucket is untouched")
    }

    @Test
    fun `a notice the list hides - of a deleted ADR or an unknown kind - is a 404 for seen, like a missing one`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        val hidden = cast.raise()
        val notice = cast.approver.client.noticesOf(ch.nokillswit.notifications.NotificationType.ROLE_ASSIGNED, hidden.id).single()
        execRaw("INSERT INTO notifications (recipient_id, notification_type, adr_id, created_at) VALUES " +
            "(${cast.approver.id}, 'A_KIND_FROM_THE_FUTURE', ${adr.id}, 1)")
        val unknownKind = rawNotices(cast.approver.id).last { it.type == "A_KIND_FROM_THE_FUTURE" }
        val visible = cast.approver.client.noticesOf(ch.nokillswit.notifications.NotificationType.ROLE_ASSIGNED, adr.id).single()
        assertEquals(HttpStatusCode.NotFound, cast.approver.client.markSeen(unknownKind.id).status)
        assertEquals(HttpStatusCode.NoContent, cast.approver.client.markSeen(visible.id).status)
        assertEquals(HttpStatusCode.NoContent, cast.approver.client.markSeen(visible.id).status, "seen again is still 204")

        assertEquals(HttpStatusCode.NoContent, cast.admin.client.delete("/api/v1/adrs/${hidden.id}").status)
        assertEquals(HttpStatusCode.NotFound, cast.approver.client.markSeen(notice.id).status)
        assertEquals(null, rawNotices(cast.approver.id).first { it.id == notice.id }.seenAt, "a hidden notice is left as it was")
        assertEquals(null, rawNotices(cast.approver.id).first { it.id == unknownKind.id }.seenAt)
    }

    @Test
    fun `the second ADR of a notice shows its key only while it still exists`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val predecessor = cast.raise()
        val successor = cast.raise()
        execRaw("INSERT INTO notifications (recipient_id, notification_type, adr_id, other_adr_id, created_at) VALUES " +
            "(${cast.informed.id}, 'ADR_SUPERSEDED', ${predecessor.id}, ${successor.id}, ${System.currentTimeMillis()})")
        val type = ch.nokillswit.notifications.NotificationType.ADR_SUPERSEDED
        assertEquals(successor.key, cast.informed.client.noticesOf(type, predecessor.id).single().otherAdrKey)
        assertEquals(HttpStatusCode.NoContent, cast.admin.client.delete("/api/v1/adrs/${successor.id}").status)
        val shown = cast.informed.client.noticesOf(type, predecessor.id).single()
        assertEquals(successor.id, shown.otherAdrId)
        assertEquals(null, shown.otherAdrKey, "a soft-deleted ADR's key is not read")
    }
}
