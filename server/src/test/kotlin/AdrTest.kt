package ch.nokillswit

import ch.nokillswit.adrs.AdrCreateRequest
import ch.nokillswit.adrs.AdrParticipant
import ch.nokillswit.adrs.AdrPageResponse
import ch.nokillswit.adrs.AdrParticipantRequest
import ch.nokillswit.adrs.AdrResponse
import ch.nokillswit.adrs.AdrRole
import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.adrs.MAX_ADR_TEXT_LENGTH
import ch.nokillswit.infra.ProblemTypes
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.plugins.MAX_ADR_REQUEST_BODY_BYTES
import ch.nokillswit.plugins.MAX_SMALL_REQUEST_BODY_BYTES
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The ADR record (V10): create (the caller is the Driver, only the problem is required, the name defaults), read, the number counter
 * (gapless, never reused, no duplicate under concurrency), name uniqueness, the document PUT with its revision, no-op and content freeze,
 * the soft delete, the payload ceilings. ADRs are SHARED suite state - every test mints unique names.
 */
class AdrTest {

    @Test
    fun `an ADR starts raised, its creator is the Driver and the name defaults to the first line of the problem`() = testApplication {
        usePostgresTestcontainer()
        val creator = adrActor("creator")
        val problem = "  \nShould we log decisions?\nSecond line  "
        val created = creator.client.postJson("/api/v1/adrs", AdrCreateRequest(problem = problem))
        assertEquals(HttpStatusCode.Created, created.status)
        assertNotNull(created.headers["Location"])
        val adr = created.body<AdrResponse>()
        assertEquals("Should we log decisions?", adr.name)
        assertEquals("Should we log decisions?\nSecond line", adr.problem, "leading blank lines and trailing blanks are normalised away")
        assertEquals(AdrStatus.PROBLEM_RAISED, adr.status)
        assertEquals(1L, adr.revision)
        assertEquals("ADR-" + adr.number.toString().padStart(4, '0'), adr.key)
        assertEquals(listOf(AdrParticipant(creator.id, "Creator", AdrRole.DRIVER)), adr.participants)
        assertEquals(creator.id, adr.createdBy)
        assertEquals(null, adr.expiresOn)
        assertEquals("", adr.context)
        assertEquals(adr, creator.client.getAdr(adr.id))
        assertTrue(created.headers["Location"]!!.endsWith("/api/v1/adrs/${adr.id}"))
    }

    @Test
    fun `create validates - problem required, lengths, control characters, dates, participants`() = testApplication {
        usePostgresTestcontainer()
        val creator = adrActor("badcreate")
        val other = adrActor("other")
        val ok = AdrCreateRequest(name = adrName(), problem = "p")
        val bad = listOf(
            "blank problem" to ok.copy(problem = "  \n "),
            "overlong name" to ok.copy(name = "x".repeat(121)),
            "overlong problem" to ok.copy(problem = "p".repeat(MAX_ADR_TEXT_LENGTH + 1)),
            "overlong context" to ok.copy(context = "c".repeat(MAX_ADR_TEXT_LENGTH + 1)),
            "control character in the name" to ok.copy(name = "tab\tname"),
            "control character in the problem" to ok.copy(problem = "bell\u0007"),
            "a date that is not one" to ok.copy(expiresOn = "2026-02-30"),
            "a date with a sign" to ok.copy(expiresOn = "+20260-01-01"),
            "a date in the past" to ok.copy(expiresOn = "2000-01-01"),
            "a Driver among the participants" to ok.copy(participants = listOf(AdrParticipantRequest(other.id, AdrRole.DRIVER))),
            "the creator among the participants" to ok.copy(participants = listOf(AdrParticipantRequest(creator.id, AdrRole.INFORMED))),
            "a user twice" to ok.copy(
                participants = listOf(
                    AdrParticipantRequest(other.id, AdrRole.INFORMED),
                    AdrParticipantRequest(other.id, AdrRole.CONTRIBUTOR),
                ),
            ),
            "two Approvers" to ok.copy(
                participants = listOf(
                    AdrParticipantRequest(other.id, AdrRole.APPROVER),
                    AdrParticipantRequest(adrActor("o2").id, AdrRole.APPROVER),
                ),
            ),
            "an unknown user" to ok.copy(participants = listOf(AdrParticipantRequest(4_000_000_000u, AdrRole.INFORMED))),
            "too many participants" to ok.copy(
                participants = (1..100).map { AdrParticipantRequest(other.id + it.toUInt(), AdrRole.INFORMED) },
            ),
        )
        for ((label, request) in bad) {
            assertEquals(HttpStatusCode.BadRequest, creator.client.postJson("/api/v1/adrs", request).status, label)
        }
        val gone = adrActor("gone")
        TestUsers.softDelete(gone.id)
        val withGone = ok.copy(participants = listOf(AdrParticipantRequest(gone.id, AdrRole.INFORMED)))
        val deleted = creator.client.postJson("/api/v1/adrs", withGone)
        assertEquals(HttpStatusCode.BadRequest, deleted.status, "a soft-deleted user cannot be named")
    }

    @Test
    fun `numbers are gapless and never reused, a deleted ADR keeps its number`() = testApplication {
        usePostgresTestcontainer()
        val admin = adrActor("numadmin", UserRole.ADMIN)
        val first = admin.client.createAdr()
        val second = admin.client.createAdr()
        assertEquals(first.number + 1, second.number)
        assertEquals(HttpStatusCode.NoContent, admin.client.delete("/api/v1/adrs/${second.id}").status)
        val third = admin.client.createAdr()
        assertEquals(second.number + 1, third.number, "the deleted ADR's number is not handed out again")
    }

    @Test
    fun `concurrent creates never duplicate a number and leave no gap`() = testApplication {
        usePostgresTestcontainer()
        val creator = adrActor("racer")
        val created = coroutineScope {
            (1..12).map { async(Dispatchers.IO) { creator.client.createAdr() } }.awaitAll()
        }
        val numbers = created.map { it.number }.sorted()
        assertEquals(12, numbers.toSet().size, "every number is distinct: $numbers")
        assertEquals((numbers.first()..numbers.last()).toList(), numbers, "the counter is gapless: $numbers")
        assertEquals(12, created.map { it.id }.toSet().size)
    }

    @Test
    fun `a name is unique among active ADRs, case- and accent-insensitively, and a deleted ADR frees it`() = testApplication {
        usePostgresTestcontainer()
        val admin = adrActor("nameadmin", UserRole.ADMIN)
        val suffix = java.util.UUID.randomUUID().toString()
        val first = admin.client.createAdr(name = "Żółw $suffix")
        val clash = admin.client.postJson("/api/v1/adrs", AdrCreateRequest(name = "zolw $suffix".uppercase(), problem = "p"))
        assertEquals(HttpStatusCode.Conflict, clash.status)
        assertTrue("ADR with this name" in clash.body<ProblemDetail>().detail.orEmpty())
        // A rename into an active name clashes too.
        val other = admin.client.createAdr()
        val rename = admin.client.saveAdr(other.id, other.revision, other.asUpdate().copy(name = "zolw $suffix"))
        assertEquals(HttpStatusCode.Conflict, rename.status)
        // The defaulted name clashes like any other.
        val line = "Defaulted $suffix"
        admin.client.createAdr(name = null, problem = line)
        assertEquals(HttpStatusCode.Conflict, admin.client.postJson("/api/v1/adrs", AdrCreateRequest(problem = line)).status)
        assertEquals(HttpStatusCode.NoContent, admin.client.delete("/api/v1/adrs/${first.id}").status)
        val reused = admin.client.postJson("/api/v1/adrs", AdrCreateRequest(name = "Zółw $suffix", problem = "p"))
        assertEquals(HttpStatusCode.Created, reused.status)
    }

    @Test
    fun `the PUT replaces the document, bumps the revision and sanitises`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        val update = adr.asUpdate().copy(
            name = "  ${adrName("Renamed")}  ",
            context = "  \nWhy it matters\r\nline two  ",
            alternatives = "Option B",
            expiresOn = "2099-12-31",
        )
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.saveAdr(adr.id, adr.revision, update).status)
        val saved = cast.driver.client.getAdr(adr.id)
        assertEquals(update.name.trim(), saved.name)
        assertEquals("Why it matters\nline two", saved.context)
        assertEquals("Option B", saved.alternatives)
        assertEquals("2099-12-31", saved.expiresOn)
        assertEquals(adr.revision + 1, saved.revision)
        assertTrue(saved.updatedAt >= adr.updatedAt)
        // PUT is a full replace: an omitted date clears it.
        val cleared = cast.driver.client.saveAdr(adr.id, saved.revision, saved.asUpdate().copy(expiresOn = null))
        assertEquals(HttpStatusCode.NoContent, cleared.status)
        assertEquals(null, cast.driver.client.getAdr(adr.id).expiresOn)
    }

    @Test
    fun `a save that changes nothing records nothing and does not bump the revision`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        val before = cast.driver.client.adrEvents(adr.id).total
        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.NoContent, cast.driver.client.saveAdr(adr.id, adr.revision, adr.asUpdate()).status)
            delay(300)
            val audited = capture.events.any { it.message == "adr.updated" && it.hasKeyValue("adrId", adr.id.toLong()) }
            assertTrue(!audited, "a no-op does not audit")
        }
        assertEquals(adr.revision, cast.driver.client.getAdr(adr.id).revision)
        assertEquals(before, cast.driver.client.adrEvents(adr.id).total, "a no-op records no event")
    }

    @Test
    fun `X-Expected-Revision - missing, malformed and stale`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        val noHeader = cast.driver.client.put("/api/v1/adrs/${adr.id}") {
            contentType(ContentType.Application.Json)
            setBody(adr.asUpdate())
        }
        assertEquals(HttpStatusCode.BadRequest, noHeader.status)
        val junk = cast.driver.client.put("/api/v1/adrs/${adr.id}") {
            header("X-Expected-Revision", "0")
            contentType(ContentType.Application.Json)
            setBody(adr.asUpdate())
        }
        assertEquals(HttpStatusCode.BadRequest, junk.status)
        val first = cast.driver.client.saveAdr(adr.id, adr.revision, adr.asUpdate().copy(problem = "New problem"))
        assertEquals(HttpStatusCode.NoContent, first.status)
        // The same (now stale) revision again: the typed conflict, and the document is unchanged by it.
        val stale = cast.contributor.client.saveAdr(adr.id, adr.revision, adr.asUpdate().copy(problem = "Lost update"))
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertEquals(ProblemTypes.REVISION_CONFLICT, stale.body<ProblemDetail>().type)
        assertEquals("New problem", cast.driver.client.getAdr(adr.id).problem)
        // A stale transition is the same typed conflict; a real participants change moves the revision, a no-op does not.
        val move = cast.driver.client.moveAdr(adr.id, adr.revision, AdrStatus.SOLUTION_DRAFT)
        assertEquals(HttpStatusCode.Conflict, move.status)
        assertEquals(ProblemTypes.REVISION_CONFLICT, move.body<ProblemDetail>().type)
        val current = cast.driver.client.getAdr(adr.id).revision
        val unchanged = cast.driver.client.getAdr(adr.id).participants.map { AdrParticipantRequest(it.userId, it.role) }
        cast.driver.client.setParticipants(adr.id, unchanged)
        assertEquals(current, cast.driver.client.getAdr(adr.id).revision)
        cast.driver.client.setParticipants(adr.id, unchanged.filter { it.role != AdrRole.INFORMED })
        assertEquals(current + 1, cast.driver.client.getAdr(adr.id).revision)
    }

    @Test
    fun `the content freeze - content only before the decision is in force, expiry until the end`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        cast.driver.client.moveAdrNow(adr.id, AdrStatus.IN_REVIEW)
        // IN_REVIEW is still editable content.
        val review = cast.driver.client.getAdr(adr.id)
        val revised = cast.driver.client.saveAdr(adr.id, review.revision, review.asUpdate().copy(decision = "Revised"))
        assertEquals(HttpStatusCode.NoContent, revised.status)
        val active = cast.approver.client.moveAdrNow(adr.id, AdrStatus.ACTIVE)
        // ACTIVE: content is frozen (a typed 409, even for ADMIN) ...
        for (actor in listOf(cast.driver, cast.contributor, cast.admin)) {
            val frozen = actor.client.saveAdr(adr.id, active.revision, active.asUpdate().copy(decision = "Rewritten"))
            assertEquals(HttpStatusCode.Conflict, frozen.status)
            assertEquals(ProblemTypes.ADR_FROZEN, frozen.body<ProblemDetail>().type)
        }
        // ... the expiry date still moves, and so do the participants.
        val dated = cast.driver.client.saveAdr(adr.id, active.revision, active.asUpdate().copy(expiresOn = "2099-06-30"))
        assertEquals(HttpStatusCode.NoContent, dated.status)
        val extended = cast.driver.client.getAdr(adr.id)
        assertEquals("2099-06-30", extended.expiresOn)
        val undated = cast.approver.client.saveAdr(adr.id, extended.revision, extended.asUpdate().copy(expiresOn = null))
        assertEquals(HttpStatusCode.NoContent, undated.status)
        val alone = listOf(AdrParticipantRequest(cast.driver.id, AdrRole.DRIVER))
        assertEquals(HttpStatusCode.NoContent, cast.approver.client.setParticipants(adr.id, alone).status)
        // A date in the past is a 400 even when the actor may change it.
        val now = cast.driver.client.getAdr(adr.id)
        val past = cast.driver.client.saveAdr(adr.id, now.revision, now.asUpdate().copy(expiresOn = "2000-01-01"))
        assertEquals(HttpStatusCode.BadRequest, past.status)
    }

    @Test
    fun `a terminal ADR is read-only - every write is the typed frozen conflict`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        cast.driver.client.moveAdrNow(adr.id, AdrStatus.CANCELLED)
        val terminal = cast.driver.client.getAdr(adr.id)
        val save = cast.admin.client.saveAdr(adr.id, terminal.revision, terminal.asUpdate())
        assertEquals(HttpStatusCode.Conflict, save.status, "even a no-op save of a terminal ADR")
        assertEquals(ProblemTypes.ADR_FROZEN, save.body<ProblemDetail>().type)
        val expiry = cast.admin.client.saveAdr(adr.id, terminal.revision, terminal.asUpdate().copy(expiresOn = "2099-01-01"))
        assertEquals(ProblemTypes.ADR_FROZEN, expiry.body<ProblemDetail>().type)
        val people = cast.admin.client.setParticipants(adr.id, listOf(AdrParticipantRequest(cast.driver.id, AdrRole.DRIVER)))
        assertEquals(HttpStatusCode.Conflict, people.status)
        assertEquals(ProblemTypes.ADR_FROZEN, people.body<ProblemDetail>().type)
        // ... but it stays readable and ADMIN may still delete it.
        assertEquals(HttpStatusCode.OK, cast.stranger.client.get("/api/v1/adrs/${adr.id}").status)
        assertEquals(HttpStatusCode.NoContent, cast.admin.client.delete("/api/v1/adrs/${adr.id}").status)
    }

    @Test
    fun `a soft-deleted ADR behaves like a missing one everywhere and a second delete is 404`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        assertEquals(HttpStatusCode.NoContent, cast.admin.client.delete("/api/v1/adrs/${adr.id}").status)
        assertEquals(HttpStatusCode.NotFound, cast.admin.client.delete("/api/v1/adrs/${adr.id}").status)
        assertEquals(HttpStatusCode.NotFound, cast.driver.client.get("/api/v1/adrs/${adr.id}").status)
        assertEquals(HttpStatusCode.NotFound, cast.driver.client.get("/api/v1/adrs/${adr.id}/events").status)
        assertEquals(HttpStatusCode.NotFound, cast.driver.client.saveAdr(adr.id, adr.revision, adr.asUpdate()).status)
        assertEquals(HttpStatusCode.NotFound, cast.driver.client.moveAdr(adr.id, adr.revision, AdrStatus.CANCELLED).status)
        assertEquals(0L, cast.admin.client.get("/api/v1/adrs?q=${adr.key}&status=PROBLEM_RAISED").body<AdrPageResponse>().total)
    }

    @Test
    fun `the largest legitimate document fits the ADR ceiling and an oversized body is 413`() = testApplication {
        usePostgresTestcontainer()
        val creator = adrActor("bigdoc")
        // Five fields of 20,000 three-byte characters: about 300 KB of UTF-8, under the 384 KiB ADR ceiling.
        val wide = "…".repeat(MAX_ADR_TEXT_LENGTH)
        val big = creator.client.postJson(
            "/api/v1/adrs",
            AdrCreateRequest(
                name = adrName(), problem = wide, context = wide, decision = wide, alternatives = wide, consequences = wide,
            ),
        )
        assertEquals(HttpStatusCode.Created, big.status)
        val adr = big.body<AdrResponse>()
        assertEquals(wide, adr.consequences)
        assertEquals(HttpStatusCode.NoContent, creator.client.saveAdr(adr.id, adr.revision, adr.asUpdate().copy(context = "x")).status)

        val oversize = """{"name":"n","problem":"""" + "x".repeat(MAX_ADR_REQUEST_BODY_BYTES.toInt()) + "\"}"
        assertEquals(
            HttpStatusCode.PayloadTooLarge,
            creator.client.post("/api/v1/adrs") { contentType(ContentType.Application.Json); setBody(oversize) }.status,
        )
        assertEquals(
            HttpStatusCode.PayloadTooLarge,
            creator.client.put("/api/v1/adrs/${adr.id}") {
                header("X-Expected-Revision", "1")
                contentType(ContentType.Application.Json)
                setBody(oversize)
            }.status,
        )
        // The transition and participants writes carry a few bytes: they ride the small ceiling.
        val smallOversize = """{"to":"""" + "x".repeat(MAX_SMALL_REQUEST_BODY_BYTES.toInt()) + "\"}"
        assertEquals(
            HttpStatusCode.PayloadTooLarge,
            creator.client.post("/api/v1/adrs/${adr.id}/transitions") {
                header("X-Expected-Revision", "1")
                contentType(ContentType.Application.Json)
                setBody(smallOversize)
            }.status,
        )
        assertEquals(
            HttpStatusCode.PayloadTooLarge,
            creator.client.put("/api/v1/adrs/${adr.id}/participants") {
                contentType(ContentType.Application.Json)
                setBody(smallOversize)
            }.status,
        )
    }

    @Test
    fun `a stale save is a 409 revision conflict, never a false 403 or 400, and writes no denial`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        // The Driver edits the content (revision 2). The Approver, still on revision 1, sends a document whose content is the OLD one
        // and changes only the expiry date: judged against the new content that diff looks like a content edit (403) - but it is stale.
        val newer = cast.driver.client.saveAdr(adr.id, adr.revision, adr.asUpdate().copy(context = "Newer"))
        assertEquals(HttpStatusCode.NoContent, newer.status)
        withAuditCapture { capture ->
            val stale = cast.approver.client.saveAdr(adr.id, adr.revision, adr.asUpdate().copy(expiresOn = "2099-01-01"))
            assertEquals(HttpStatusCode.Conflict, stale.status)
            assertEquals(ProblemTypes.REVISION_CONFLICT, stale.body<ProblemDetail>().type)
            // Nor is a stale save with a past date a 400.
            val past = cast.approver.client.saveAdr(adr.id, adr.revision, adr.asUpdate().copy(expiresOn = "2000-01-01"))
            assertEquals(ProblemTypes.REVISION_CONFLICT, past.body<ProblemDetail>().type)
            delay(300)
            val denied = capture.events.any { it.message == "authz.denied" && it.hasKeyValue("byUserId", cast.approver.id.toLong()) }
            assertTrue(!denied, "a stale save leaves no false denial on the trail")
        }
        // With the current revision the same Approver's content-carrying document IS a denial.
        val current = cast.approver.client.getAdr(adr.id)
        val content = cast.approver.client.saveAdr(adr.id, current.revision, current.asUpdate().copy(context = "x"))
        assertEquals(HttpStatusCode.Forbidden, content.status)
        val expiry = cast.approver.client.saveAdr(adr.id, current.revision, current.asUpdate().copy(expiresOn = "2099-01-01"))
        assertEquals(HttpStatusCode.NoContent, expiry.status)
    }

    @Test
    fun `a defaulted name is single-line - tabs collapse - so a PUT echoing it passes`() = testApplication {
        usePostgresTestcontainer()
        val creator = adrActor("tabby")
        val marker = java.util.UUID.randomUUID()
        val adr = creator.client.createAdr(name = null, problem = "Use\tPostgres \t $marker\u2026\nSecond line")
        assertEquals("Use Postgres $marker\u2026", adr.name)
        val echoed = creator.client.saveAdr(adr.id, adr.revision, adr.asUpdate().copy(context = "Why"))
        assertEquals(HttpStatusCode.NoContent, echoed.status)
        assertEquals(adr.name, creator.client.getAdr(adr.id).name)
    }
}
