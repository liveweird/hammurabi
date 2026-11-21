package ch.nokillswit

import ch.nokillswit.adrs.DiscussionKind
import ch.nokillswit.adrs.DiscussionState
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The discussion verdicts (`authorization.md`): anyone authenticated reads and posts, the author edits, the author or ADMIN deletes, a
 * question is settled by its author / Driver / Approver / ADMIN and an issue by the Driver / Approver / ADMIN; 403 beats 400.
 */
class AdrDiscussionAccessTest {

    @Test
    fun `everyone authenticated reads and posts - an open decision log`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        for ((who, actor) in cast.everyone) {
            actor.client.postEntryOk(adr.id, DiscussionKind.COMMENT, "Said by $who")
        }
        for ((who, actor) in cast.everyone) {
            assertEquals(cast.everyone.size.toLong(), actor.client.discussion(adr.id).total, "$who reads")
        }
    }

    @Test
    fun `only the author edits - not the Driver, not even an ADMIN`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        val entry = cast.contributor.client.postEntryOk(adr.id, DiscussionKind.COMMENT, "Mine")
        for ((who, actor) in cast.everyone - "contributor") {
            assertEquals(HttpStatusCode.Forbidden, actor.client.editEntry(adr.id, entry.id, "Hijacked by $who").status, "$who edits")
        }
        assertEquals("Mine", cast.informed.client.entry(adr.id, entry.id).body)
        assertEquals(HttpStatusCode.OK, cast.contributor.client.editEntry(adr.id, entry.id, "Still mine").status)
    }

    @Test
    fun `the author or an ADMIN deletes, nobody else - not even the Driver`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        val entry = cast.contributor.client.postEntryOk(adr.id, DiscussionKind.COMMENT)
        for ((who, actor) in cast.everyone - setOf("contributor", "admin")) {
            val refused = actor.client.delete("/api/v1/adrs/${adr.id}/discussion/${entry.id}")
            assertEquals(HttpStatusCode.Forbidden, refused.status, "$who deletes")
        }
        assertFalse(cast.informed.client.entry(adr.id, entry.id).deleted)
        assertEquals(HttpStatusCode.NoContent, cast.admin.client.delete("/api/v1/adrs/${adr.id}/discussion/${entry.id}").status)
        val own = cast.stranger.client.postEntryOk(adr.id, DiscussionKind.QUESTION)
        assertEquals(HttpStatusCode.NoContent, cast.stranger.client.delete("/api/v1/adrs/${adr.id}/discussion/${own.id}").status)
    }

    @Test
    fun `a question is answered by its author, the Driver, the Approver or ADMIN`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val allowed = setOf("stranger", "driver", "approver", "admin")
        for ((who, actor) in cast.everyone) {
            val adr = cast.raise(complete = true)
            val question = cast.stranger.client.postEntryOk(adr.id, DiscussionKind.QUESTION)
            val expected = if (who in allowed) HttpStatusCode.OK else HttpStatusCode.Forbidden
            assertEquals(expected, actor.client.setEntryState(adr.id, question.id, DiscussionState.ANSWERED).status, "$who answers")
            assertEquals(who in allowed, actor.client.entry(adr.id, question.id).canChangeState, "$who verdict")
            val answered = cast.informed.client.entry(adr.id, question.id).state == DiscussionState.ANSWERED
            assertEquals(who in allowed, answered, "$who outcome")
        }
    }

    @Test
    fun `an issue is resolved by the Driver, the Approver or ADMIN - not by its author`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val allowed = setOf("driver", "approver", "admin")
        for ((who, actor) in cast.everyone) {
            val adr = cast.raise(complete = true)
            cast.activate(adr)
            // The stranger authors the issue, so "stranger" shows that authorship alone does not settle an issue.
            val issue = cast.stranger.client.postEntryOk(adr.id, DiscussionKind.ISSUE)
            assertEquals(issue.canChangeState, false, "the author of an issue cannot resolve it")
            val expected = if (who in allowed) HttpStatusCode.OK else HttpStatusCode.Forbidden
            assertEquals(expected, actor.client.setEntryState(adr.id, issue.id, DiscussionState.RESOLVED).status, "$who resolves")
            assertEquals(who in allowed, cast.informed.client.entry(adr.id, issue.id).state == DiscussionState.RESOLVED, "$who outcome")
            // The verdict served with the entry agrees with the guard.
            val fresh = cast.stranger.client.postEntryOk(adr.id, DiscussionKind.ESCALATION)
            assertEquals(who in allowed, actor.client.entry(adr.id, fresh.id).canChangeState, "$who canChangeState")
        }
    }

    @Test
    fun `403 wins over 400 and 409 - a refused caller learns nothing about the body or the window`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        val entry = cast.contributor.client.postEntryOk(adr.id, DiscussionKind.QUESTION)
        // An invalid body from someone who may not edit / settle it is 403, not 400.
        assertEquals(HttpStatusCode.Forbidden, cast.stranger.client.editEntry(adr.id, entry.id, "").status)
        val garbage = cast.informed.client.put("/api/v1/adrs/${adr.id}/discussion/${entry.id}/state") {
            contentType(ContentType.Application.Json)
            setBody("{ not json")
        }
        assertEquals(HttpStatusCode.Forbidden, garbage.status)
        val garbageEdit = cast.stranger.client.put("/api/v1/adrs/${adr.id}/discussion/${entry.id}") {
            contentType(ContentType.Application.Json)
            setBody("{ not json")
        }
        assertEquals(HttpStatusCode.Forbidden, garbageEdit.status)
        // The same requests from someone who may are judged: 400 first, then 409.
        assertEquals(HttpStatusCode.BadRequest, cast.contributor.client.editEntry(adr.id, entry.id, "").status)
        cast.activate(adr)
        val late = cast.stranger.client.editEntry(adr.id, entry.id, "late")
        assertEquals(HttpStatusCode.Forbidden, late.status, "403 before the closed window")
        assertEquals(HttpStatusCode.Conflict, cast.contributor.client.editEntry(adr.id, entry.id, "late").status)
        // A missing entry is 404 before any guard; so is a missing ADR.
        assertEquals(HttpStatusCode.NotFound, cast.stranger.client.editEntry(adr.id, 999999u, "x").status)
        assertEquals(HttpStatusCode.NotFound, cast.stranger.client.get("/api/v1/adrs/999999/discussion/1").status)
    }

    @Test
    fun `the entry carries the caller's verdicts`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        val entry = cast.contributor.client.postEntryOk(adr.id, DiscussionKind.QUESTION)
        // canEdit, canDelete, canChangeState per caller.
        val expected = mapOf(
            "admin" to Triple(false, true, true),
            "driver" to Triple(false, false, true),
            "approver" to Triple(false, false, true),
            "contributor" to Triple(true, true, true),
            "informed" to Triple(false, false, false),
            "stranger" to Triple(false, false, false),
        )
        for ((who, flags) in expected) {
            val seen = cast.everyone.getValue(who).client.entry(adr.id, entry.id)
            assertEquals(flags, Triple(seen.canEdit, seen.canDelete, seen.canChangeState), "$who verdicts")
        }
        assertTrue(cast.informed.client.get("/api/v1/adrs/${adr.id}/discussion").status == HttpStatusCode.OK)
        assertEquals(HttpStatusCode.NotFound, cast.informed.client.delete("/api/v1/adrs/${adr.id}/discussion/999999").status)
    }
}
