package ch.nokillswit

import ch.nokillswit.adrs.ADR_DEFAULT_SORT
import ch.nokillswit.adrs.AdrDiscussionService
import ch.nokillswit.adrs.AdrEventService
import ch.nokillswit.adrs.AdrListFilter
import ch.nokillswit.adrs.AdrListService
import ch.nokillswit.adrs.AdrPageResponse
import ch.nokillswit.adrs.AdrParticipantService
import ch.nokillswit.adrs.AdrParticipantRequest
import ch.nokillswit.adrs.AdrRole
import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.adrs.AdrTagService
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.SortField
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `GET /api/v1/adrs` (list-endpoints.md): the filters, the sorts, the envelope and the 400s. ADRs are shared state: mark each. */
class AdrListTest {

    @Test
    fun `the list filters by status, any-of, and by free text on the name and the key`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val marker = java.util.UUID.randomUUID().toString().take(8)
        val raised = cast.driver.client.createAdr(name = "Alpha $marker", problem = "p")
        val draft = cast.driver.client.createAdr(name = "Bravo $marker", problem = "p")
        cast.driver.client.moveAdrNow(draft.id, AdrStatus.SOLUTION_DRAFT)
        val cancelled = cast.driver.client.createAdr(name = "Charlie $marker", problem = "p")
        cast.driver.client.moveAdrNow(cancelled.id, AdrStatus.CANCELLED)

        suspend fun page(query: String) = cast.stranger.client.get("/api/v1/adrs?$query").body<AdrPageResponse>()
        assertEquals(3, page("q=$marker").total)
        assertEquals(listOf("Alpha $marker"), page("q=$marker&status=PROBLEM_RAISED").items.map { it.name })
        val either = page("q=$marker&status=PROBLEM_RAISED&status=CANCELLED")
        assertEquals(setOf("Alpha $marker", "Charlie $marker"), either.items.map { it.name }.toSet())
        assertEquals(0, page("q=$marker&status=ACTIVE").total)
        // Case- and accent-insensitive on the name ...
        assertEquals(listOf("Bravo $marker"), page("q=BRAVO%20${marker.uppercase()}").items.map { it.name })
        // ... and on the key, with or without the prefix and its padding.
        assertTrue(page("q=${raised.key}").items.any { it.id == raised.id })
        assertTrue(page("q=${raised.key.lowercase()}").items.any { it.id == raised.id })
        assertTrue(page("q=${raised.number.toString().padStart(4, '0')}").items.any { it.id == raised.id })
        // LIKE metacharacters are literal.
        assertEquals(0, page("q=%25$marker").total)
    }

    @Test
    fun `the key of a number beyond four digits is searchable and q counts code points`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.driver.client.createAdr()
        val number = forceAdrNumberAbove(adr.id)
        assertTrue(number >= 10_001)
        assertEquals("ADR-$number", cast.stranger.client.getAdr(adr.id).key)
        suspend fun ids(query: String) = cast.stranger.client.get("/api/v1/adrs?q=$query").body<AdrPageResponse>().items.map { it.id }
        assertEquals(listOf(adr.id), ids("ADR-$number"), "the whole key")
        assertEquals(listOf(adr.id), ids("adr-$number".lowercase()), "any case")
        assertEquals(listOf(adr.id), ids(number.toString()), "the bare number")
        // A hundred supplementary-plane characters are 100 code points (200 UTF-16 units): accepted; 101 are not.
        val hundred = java.net.URLEncoder.encode("\uD83D\uDE00".repeat(100), Charsets.UTF_8)
        assertEquals(HttpStatusCode.OK, cast.stranger.client.get("/api/v1/adrs?q=$hundred").status)
        val tooMany = java.net.URLEncoder.encode("\uD83D\uDE00".repeat(101), Charsets.UTF_8)
        assertEquals(HttpStatusCode.BadRequest, cast.stranger.client.get("/api/v1/adrs?q=$tooMany").status)
    }

    @Test
    fun `the participant filter keeps the ADRs a user holds a role on and the role narrows it`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val mine = cast.raise()
        val theirs = cast.driver.client.createAdr()
        val asInformed = cast.stranger.client.createAdr(
            participants = listOf(AdrParticipantRequest(cast.informed.id, AdrRole.CONTRIBUTOR)),
        )
        suspend fun ids(query: String) =
            cast.admin.client.get("/api/v1/adrs?pageSize=100&$query").body<AdrPageResponse>().items.map { it.id }
        val informed = ids("participant=${cast.informed.id}")
        assertEquals(setOf(mine.id, asInformed.id), informed.toSet())
        assertEquals(listOf(mine.id), ids("participant=${cast.informed.id}&role=INFORMED"))
        assertEquals(listOf(asInformed.id), ids("participant=${cast.informed.id}&role=CONTRIBUTOR"))
        assertEquals(emptyList(), ids("participant=${cast.informed.id}&role=DRIVER"))
        assertTrue(theirs.id in ids("participant=${cast.driver.id}&role=DRIVER"))
        assertEquals(HttpStatusCode.BadRequest, cast.admin.client.get("/api/v1/adrs?role=DRIVER").status, "role needs participant")
    }

    @Test
    fun `a row carries the Driver and the Approver and the page the filtered total`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val a = cast.raise()
        val b = cast.driver.client.createAdr()
        val query = "participant=${cast.driver.id}&role=DRIVER&pageSize=1&sort=-number"
        val page = cast.stranger.client.get("/api/v1/adrs?$query").body<AdrPageResponse>()
        assertEquals(1, page.pageSize)
        assertTrue(page.total >= 2)
        assertEquals(1, page.items.size)
        val newest = page.items.single()
        assertEquals(b.id, newest.id, "-number is newest first")
        assertEquals("Drv", newest.driver?.name)
        assertEquals(null, newest.approver)
        val withApprover = cast.stranger.client.get("/api/v1/adrs?q=${a.key}").body<AdrPageResponse>().items.single { it.id == a.id }
        assertEquals("Apr", withApprover.approver?.name)
        assertEquals(a.key, withApprover.key)
    }

    @Test
    fun `sorting - by number, name, updatedAt and status in lifecycle order, and unknown fields are 400`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val marker = java.util.UUID.randomUUID().toString().take(8)
        val c = cast.driver.client.createAdr(name = "C $marker")
        val a = cast.driver.client.createAdr(name = "A $marker")
        val b = cast.driver.client.createAdr(name = "B $marker")
        cast.driver.client.moveAdrNow(a.id, AdrStatus.SOLUTION_DRAFT)
        cast.driver.client.moveAdrNow(b.id, AdrStatus.CANCELLED)
        suspend fun names(sort: String) =
            cast.stranger.client.get("/api/v1/adrs?q=$marker&sort=$sort").body<AdrPageResponse>().items.map { it.name.substringBefore(' ') }
        assertEquals(listOf("C", "A", "B"), names("number"))
        assertEquals(listOf("B", "A", "C"), names("-number"))
        assertEquals(listOf("A", "B", "C"), names("name"))
        assertEquals(listOf("C", "B", "A"), names("-name"))
        // Lifecycle order: raised, draft, ..., cancelled.
        assertEquals(listOf("C", "A", "B"), names("status"))
        assertEquals(listOf("B", "A", "C"), names("-status"))
        assertEquals(listOf("B", "A", "C"), names("-updatedAt"))
        assertEquals(listOf("C", "A", "B"), names("updatedAt"))
        assertEquals(AdrStatus.PROBLEM_RAISED, c.status)
        for (bad in listOf("sort=problem", "sort=name,name", "sort=nope", "status=NOPE", "participant=x", "pageSize=0", "page=0")) {
            assertEquals(HttpStatusCode.BadRequest, cast.stranger.client.get("/api/v1/adrs?$bad").status, bad)
        }
        assertEquals(HttpStatusCode.BadRequest, cast.stranger.client.get("/api/v1/adrs?q=${"x".repeat(101)}").status)
        assertEquals(HttpStatusCode.BadRequest, cast.stranger.client.get("/api/v1/adrs?q=a&q=b").status, "a repeated scalar key")
    }

    @Test
    fun `the list reads the headline columns only - no statement selects a Markdown body`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        val recorder = AdrStatementRecorder()
        val database = recorder.database
        val events = AdrEventService(database)
        val participants = AdrParticipantService(events)
        val service = AdrListService(
            database, participants, AdrDiscussionService(database, participants, events), AdrTagService(events),
            timeoutSeconds = 5, maxConcurrent = 2,
        )
        val page = service.list(AdrListFilter(), PageRequest(1, 100, ADR_DEFAULT_SORT + SortField("id", descending = false)), cast.admin.id)
        assertTrue(page.items.any { it.id == adr.id })

        // Every statement of the request: what it SELECTs (the text before FROM) names no body column of the adrs table.
        val selectLists = recorder.statements.map { it.substringBefore(" FROM ").replace("\"", "") }
        val pageStatement = recorder.statements.single { it.contains("FROM adrs") && it.contains(" LIMIT ") }
        assertTrue(pageStatement.substringBefore(" FROM ").contains("adrs.id"), "the page statement selects the list columns")
        for (body in listOf("problem", "context", "decision", "alternatives", "consequences")) {
            assertTrue(selectLists.none { "adrs.$body" in it }, "a statement selects adrs.$body: $selectLists")
        }
    }
}
