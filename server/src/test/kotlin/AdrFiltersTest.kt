package ch.nokillswit

import ch.nokillswit.adrs.AdrPageResponse
import ch.nokillswit.adrs.AdrParticipantRequest
import ch.nokillswit.adrs.AdrRole
import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.adrs.DiscussionKind
import ch.nokillswit.adrs.DiscussionState
import ch.nokillswit.tags.MAX_TAG_NAME_LENGTH
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The remaining multi-criteria filters of `GET /api/v1/adrs` (0.5.0, `list-endpoints.md`): the created and updated day ranges,
 * `expiresBefore`, `hasOpenIssues`, `supersedes` and `supersededBy`, and that all of them combine with the older ones. ADRs are shared
 * suite state: every test names its rows with a unique marker and filters by it through `q`.
 */
class AdrFiltersTest {
    private fun marker() = UUID.randomUUID().toString().replace("-", "").take(10)

    private fun millis(date: String, time: String = "00:00:00.000") =
        java.time.LocalDateTime.of(LocalDate.parse(date), java.time.LocalTime.parse(time)).toInstant(ZoneOffset.UTC).toEpochMilli()

    private suspend fun HttpClient.ids(marker: String, filter: String): Set<UInt> {
        val response = get("/api/v1/adrs?pageSize=20&q=$marker&$filter")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText().take(200))
        return response.body<AdrPageResponse>().items.map { it.id }.toSet()
    }

    @Test
    fun `the created range is inclusive on both UTC days`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val m = marker()
        suspend fun at(label: String, day: String, time: String) =
            cast.driver.client.createAdr(name = "Rng $m $label").also { forceAdrTimes(it.id, millis(day, time)) }
        val before = at("before", "2020-03-10", "23:59:59.999")
        val first = at("first", "2020-03-11", "00:00:00.000")
        val last = at("last", "2020-03-11", "23:59:59.999")
        val after = at("after", "2020-03-12", "00:00:00.000")

        val reader = cast.stranger.client
        assertEquals(setOf(first.id, last.id), reader.ids(m, "createdFrom=2020-03-11&createdTo=2020-03-11"))
        assertEquals(setOf(first.id, last.id, after.id), reader.ids(m, "createdFrom=2020-03-11"))
        assertEquals(setOf(before.id, first.id, last.id), reader.ids(m, "createdTo=2020-03-11"))
        assertEquals(setOf(before.id, first.id, last.id, after.id), reader.ids(m, "createdFrom=2020-03-01&createdTo=2020-03-31"))
        assertEquals(emptySet(), reader.ids(m, "createdFrom=2021-01-01"))
    }

    @Test
    fun `the updated range reads the last change, not the creation`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val m = marker()
        val created = millis("2019-01-01")
        val old = cast.driver.client.createAdr(name = "Upd $m old")
            .also { forceAdrTimes(it.id, created, millis("2019-06-15", "12:00:00.000")) }
        val fresh = cast.driver.client.createAdr(name = "Upd $m fresh").also { forceAdrTimes(it.id, created, millis("2022-02-02")) }

        val reader = cast.stranger.client
        assertEquals(setOf(old.id), reader.ids(m, "updatedFrom=2019-06-15&updatedTo=2019-06-15"))
        assertEquals(setOf(fresh.id), reader.ids(m, "updatedFrom=2020-01-01"))
        assertEquals(setOf(old.id, fresh.id), reader.ids(m, "createdTo=2019-01-01"))
        assertEquals(setOf(old.id), reader.ids(m, "updatedTo=2019-12-31"))
    }

    @Test
    fun `dates must be real days and ranges ordered`() = testApplication {
        usePostgresTestcontainer()
        val client = adrActor("flt-dates").client
        for (bad in listOf(
            "createdFrom=2020-02-30", "createdTo=2020-3-1", "updatedFrom=yesterday", "expiresBefore=2020-13-01",
            "createdFrom=2020-03-02&createdTo=2020-03-01", "updatedFrom=2021-01-01&updatedTo=2020-01-01", "createdFrom=%2B12020-01-01",
        )) {
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/adrs?$bad").status, bad)
        }
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/adrs?createdFrom=2020-01-01&createdFrom=2020-01-02").status, "repeated")
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/adrs?hasOpenIssues=yes").status, "strict booleans")
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/adrs?createdFrom=2020-03-01&createdTo=2020-03-01").status, "one day")
        // Any year of four digits is a date, the first centuries included (the SPA's date check takes them literally too).
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/adrs?createdFrom=0050-01-01&updatedTo=0001-12-31").status, "year below 100")
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/adrs?createdFrom=0001-02-29").status, "0001 is not a leap year")
    }

    @Test
    fun `a tag longer than any tag name and a q with control characters are refused`() = testApplication {
        usePostgresTestcontainer()
        val client = adrActor("flt-tag").client
        val aliases = (0 until 21).joinToString("&") { bits ->
            "tag=" + "alias".mapIndexed { index, character ->
                if (bits and (1 shl index) == 0) character else character.uppercaseChar()
            }.joinToString("")
        }
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/adrs?tag=${"t".repeat(MAX_TAG_NAME_LENGTH)}").status, "the longest name")
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/adrs?tag=${"t".repeat(MAX_TAG_NAME_LENGTH + 1)}").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/adrs?tag=${"t".repeat(5000)}").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/adrs?tag=%3F%3F%3F").status, "the registry's tag grammar")
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/adrs?tag=ok&tag=%20ok%20").status, "normalised and de-duplicated")
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/adrs?$aliases").status, "the cap applies after canonical de-duplication")
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/adrs?q=a%01b").status, "control character")
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/adrs?q=a%E2%80%AEb").status, "hidden direction override")
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/adrs?q=%20ok%20").status, "trimmed")
    }

    @Test
    fun `expiresBefore is strictly before the day and never matches an ADR without a date`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val m = marker()
        val soon = cast.driver.client.createAdr(name = "Exp $m soon").also { forceAdrExpiry(it.id, "2031-05-31") }
        val onDay = cast.driver.client.createAdr(name = "Exp $m day").also { forceAdrExpiry(it.id, "2031-06-01") }
        val later = cast.driver.client.createAdr(name = "Exp $m later").also { forceAdrExpiry(it.id, "2031-06-02") }
        cast.driver.client.createAdr(name = "Exp $m never")
        val expired = cast.driver.client.createAdr(name = "Exp $m expired").also {
            forceAdrExpiry(it.id, "2001-01-01")
            forceAdrStatus(it.id, AdrStatus.EXPIRED)
        }

        val reader = cast.stranger.client
        assertEquals(setOf(soon.id, expired.id), reader.ids(m, "expiresBefore=2031-06-01"))
        assertEquals(setOf(soon.id, onDay.id, expired.id), reader.ids(m, "expiresBefore=2031-06-02"))
        assertEquals(setOf(soon.id, onDay.id, later.id, expired.id), reader.ids(m, "expiresBefore=2099-01-01"))
        assertEquals(setOf(expired.id), reader.ids(m, "expiresBefore=2031-06-01&status=EXPIRED"))
    }

    @Test
    fun `hasOpenIssues keeps the ADRs with an open issue or escalation`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val m = marker()
        suspend fun active(label: String) = cast.driver.client.createAdr(
            name = "Iss $m $label",
            complete = true,
            participants = listOf(AdrParticipantRequest(cast.approver.id, AdrRole.APPROVER)),
        ).also { cast.activate(it) }
        val withIssue = active("issue")
        val resolved = active("resolved")
        val clean = active("clean")
        val draft = cast.driver.client.createAdr(name = "Iss $m draft")
        cast.stranger.client.postEntryOk(withIssue.id, DiscussionKind.ISSUE)
        val done = cast.stranger.client.postEntryOk(resolved.id, DiscussionKind.ESCALATION)
        cast.driver.client.setEntryState(resolved.id, done.id, DiscussionState.RESOLVED)

        val reader = cast.informed.client
        assertEquals(setOf(withIssue.id), reader.ids(m, "hasOpenIssues=true"))
        assertEquals(setOf(resolved.id, clean.id, draft.id), reader.ids(m, "hasOpenIssues=false"))
        assertEquals(setOf(withIssue.id, resolved.id, clean.id, draft.id), reader.ids(m, ""))
    }

    @Test
    fun `supersedes and supersededBy are presence tests on the two ends of a link`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val m = marker()
        val old = cast.driver.client.createAdr(name = "Lnk $m old")
        val next = cast.driver.client.createAdr(name = "Lnk $m next")
        val alone = cast.driver.client.createAdr(name = "Lnk $m alone")
        val dropped = cast.driver.client.createAdr(name = "Lnk $m dropped")
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(next.id, old.id).status)
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(dropped.id, alone.id).status)
        cast.driver.client.moveAdrNow(dropped.id, AdrStatus.CANCELLED)

        val reader = cast.informed.client
        assertEquals(setOf(next.id, dropped.id), reader.ids(m, "hasPredecessor=true"))
        assertEquals(setOf(old.id, alone.id), reader.ids(m, "hasPredecessor=false"))
        assertEquals(setOf(old.id), reader.ids(m, "hasSuccessor=true"), "a cancelled successor does not count")
        assertEquals(setOf(next.id, alone.id, dropped.id), reader.ids(m, "hasSuccessor=false"))
        assertEquals(setOf(next.id), reader.ids(m, "hasPredecessor=true&hasSuccessor=false&status=PROBLEM_RAISED"))
        // 1.2.0: a predecessor that ended unreplaced (here cancelled) no longer has its pending successor; the successor keeps its link.
        cast.driver.client.moveAdrNow(old.id, AdrStatus.CANCELLED)
        assertEquals(emptySet(), reader.ids(m, "hasSuccessor=true"), "a pending successor of an ended predecessor does not count")
        assertEquals(setOf(old.id, next.id, alone.id, dropped.id), reader.ids(m, "hasSuccessor=false"))
        assertEquals(setOf(next.id, dropped.id), reader.ids(m, "hasPredecessor=true"), "the successor still has its predecessor")
    }

    @Test
    fun `every filter combines with the older ones`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val m = marker()
        val hit = cast.driver.client.createAdr(name = "All $m hit", problem = "Combined $m").also {
            forceAdrTimes(it.id, millis("2024-05-05", "10:00:00.000"))
            forceAdrExpiry(it.id, "2031-01-01")
        }
        val wrongStatus = cast.driver.client.createAdr(name = "All $m status").also {
            forceAdrTimes(it.id, millis("2024-05-05", "10:00:00.000"))
            forceAdrExpiry(it.id, "2031-01-01")
            cast.driver.client.moveAdrNow(it.id, AdrStatus.CANCELLED)
        }
        val wrongDay = cast.driver.client.createAdr(name = "All $m day").also {
            forceAdrTimes(it.id, millis("2024-06-06"))
            forceAdrExpiry(it.id, "2031-01-01")
        }
        val wrongDriver = cast.stranger.client.createAdr(name = "All $m driver").also {
            forceAdrTimes(it.id, millis("2024-05-05", "10:00:00.000"))
            forceAdrExpiry(it.id, "2031-01-01")
        }

        val reader = cast.informed.client
        val all = setOf(hit.id, wrongStatus.id, wrongDay.id, wrongDriver.id)
        assertEquals(all, reader.ids(m, "expiresBefore=2032-01-01"))
        val narrowed = "status=PROBLEM_RAISED&createdFrom=2024-05-05&createdTo=2024-05-05&expiresBefore=2032-01-01" +
            "&participantId=${cast.driver.id}&participantRole=DRIVER&hasPredecessor=false&hasOpenIssues=false&text=Combined%20$m"
        assertEquals(setOf(hit.id), reader.ids(m, narrowed))
        assertTrue(wrongDriver.id !in reader.ids(m, "participantId=${cast.driver.id}"))
    }
}
