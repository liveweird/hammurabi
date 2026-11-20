package ch.nokillswit

import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.teams.TEAM_SORT_FIELDS
import ch.nokillswit.users.USER_SORT_FIELDS
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bound of the `sort` list: one entry per whitelisted field, so a field named twice (with or without the `-`) and a list longer
 * than the whitelist are a `400` on every list. The paging pipeline's other rules are the list endpoints' own tests.
 */
class SortListBoundsTest {
    private suspend fun HttpClient.sorted(url: String, sort: String): HttpResponse = get("$url?sort=$sort")

    private suspend fun HttpResponse.assertRefused(part: String) {
        assertEquals(HttpStatusCode.BadRequest, status)
        val detail = body<ProblemDetail>().detail.orEmpty()
        assertTrue(part in detail, "the 400 names the rule: $detail")
    }

    @Test
    fun `the teams list refuses a repeated sort field, with or without the minus`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("sortb-team")
        client.sorted("/api/v1/teams", "name,name").assertRefused("twice")
        client.sorted("/api/v1/teams", "name,-name").assertRefused("twice")
        // `id` is the tiebreaker the paging helper appends: naming it twice is the same refusal.
        client.sorted("/api/v1/teams", "id,-id").assertRefused("twice")
    }

    @Test
    fun `the teams list refuses a sort longer than its whitelist - an attack-sized one never reaches the SQL`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("sortb-long")
        val fields = TEAM_SORT_FIELDS.toList()
        // One entry more than the whitelist holds: every name is valid, one is a repeat.
        client.sorted("/api/v1/teams", (fields + fields.first()).joinToString(",")).assertRefused("at most ${fields.size}")
        client.sorted("/api/v1/teams", List(570) { "name" }.joinToString(",")).assertRefused("at most ${fields.size}")
    }

    @Test
    fun `an unknown sort field is echoed bounded, never whole`() = testApplication {
        usePostgresTestcontainer()
        val response = seededClient("sortb-echo").sorted("/api/v1/teams", "x".repeat(3_000))
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val detail = response.body<ProblemDetail>().detail.orEmpty()
        assertTrue(detail.startsWith("Unknown sort field: ") && detail.length < 300, "a long field is cut: ${detail.length}")
    }

    @Test
    fun `valid multi-field sorts still pass on the teams list, the whole whitelist at once included`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("sortb-ok")
        assertEquals(HttpStatusCode.OK, client.sorted("/api/v1/teams", "-createdAt,name").status)
        assertEquals(HttpStatusCode.OK, client.sorted("/api/v1/teams", TEAM_SORT_FIELDS.joinToString(",")).status)
        assertEquals(HttpStatusCode.OK, client.sorted("/api/v1/teams", TEAM_SORT_FIELDS.joinToString(",") { "-$it" }).status)
    }

    @Test
    fun `the users list refuses a repeated field and an over-long sort, and takes a valid multi-field one`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("sortb-adm", UserRole.ADMIN)
        val url = "/api/v1/users"
        val fields = USER_SORT_FIELDS.toList()
        admin.sorted(url, "${fields.first()},-${fields.first()}").assertRefused("twice")
        admin.sorted(url, (fields + fields.first()).joinToString(",")).assertRefused("at most ${fields.size}")
        assertEquals(HttpStatusCode.OK, admin.sorted(url, fields.joinToString(",")).status)
        assertEquals(HttpStatusCode.OK, admin.sorted(url, "${fields[0]},-${fields[1]}").status)
    }
}
