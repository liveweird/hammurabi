package ch.nokillswit

import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserDirectoryPageResponse
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.net.URLEncoder
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * The user directory (`GET /api/v1/users/directory`): the one user read open to any signed-in user, a three-field projection of
 * ACTIVE users behind the per-user `directory` bucket. The ADMIN-only `GET /users` is untouched. Users are SHARED suite state,
 * so every test mints rows carrying a UNIQUE marker and filters by it.
 */
class UserDirectoryTest {

    private fun marker() = UUID.randomUUID().toString().substring(0, 8)

    private suspend fun seed(
        marker: String,
        name: String,
        emailLocal: String = "dir",
        role: UserRole = UserRole.USER,
    ): UInt = TestUsers.seed(
        email = "$emailLocal-$marker@test",
        password = "pw-123456789",
        name = name,
        role = role,
    )

    private suspend fun io.ktor.client.HttpClient.directory(query: String): UserDirectoryPageResponse {
        val response = get("/api/v1/users/directory?$query")
        assertEquals(HttpStatusCode.OK, response.status, query)
        return response.body()
    }

    /** One `q` value, percent-encoded (spaces, accents and `%` included). */
    private fun q(raw: String) = "q=" + URLEncoder.encode(raw, Charsets.UTF_8)

    @Test
    fun `a regular user reads the directory and the projection has exactly id, name and email`() = testApplication {
        usePostgresTestcontainer()
        val m = marker()
        val regularId = seed(m, "Plain $m")
        val adminId = seed(m, "Boss $m", emailLocal = "boss", role = UserRole.ADMIN)
        val caller = seededClient("dir-caller", UserRole.USER)

        val response = caller.get("/api/v1/users/directory?q=$m&sort=id")
        assertEquals(HttpStatusCode.OK, response.status)
        val root = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(setOf("items", "page", "pageSize", "total"), root.keys)
        val items = root.getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(2, items.size)
        items.forEach { assertEquals(setOf("id", "name", "email"), it.keys, "nothing but the three fields: no roles, flags or language") }

        val page = caller.directory("q=$m&sort=id")
        assertEquals(listOf(regularId, adminId), page.items.map { it.id })
        assertEquals(listOf("Plain $m", "Boss $m"), page.items.map { it.name })
        assertEquals(listOf("dir-$m@test", "boss-$m@test"), page.items.map { it.email })
        assertEquals(2L, page.total)
    }

    @Test
    fun `q matches name and email and folds case and accents`() = testApplication {
        usePostgresTestcontainer()
        val m = marker()
        val byName = seed(m, "José Álvarez $m", emailLocal = "alpha")
        val byEmail = seed(m, "Nobody $m", emailLocal = "zygmunt")
        val upper = seed(m, "ŻÓŁĆ $m", emailLocal = "gamma")
        val caller = seededClient("dir-q", UserRole.USER)

        assertEquals(listOf(byName), caller.directory(q("jose alvarez $m")).items.map { it.id }, "accent-insensitive")
        assertEquals(listOf(byName), caller.directory(q("JOSÉ ÁLVAREZ $m")).items.map { it.id }, "case-insensitive")
        assertEquals(listOf(upper), caller.directory(q("zolc $m")).items.map { it.id }, "uppercase diacritics fold too")
        assertEquals(listOf(byEmail), caller.directory(q("ZYGMUNT-$m")).items.map { it.id }, "email matches as well as name")
        assertEquals(3L, caller.directory("q=$m").total, "the marker is in every name and email")
        assertEquals(0L, caller.directory("q=no-such-$m").total)
        assertEquals(3L, caller.directory(q(" $m ")).total, "q is trimmed")
    }

    @Test
    fun `a soft-deleted user is absent from the rows and the total`() = testApplication {
        usePostgresTestcontainer()
        val m = marker()
        val kept = seed(m, "Kept $m", emailLocal = "kept")
        val gone = seed(m, "Gone $m", emailLocal = "gone")
        val caller = seededClient("dir-deleted", UserRole.USER)
        assertEquals(2L, caller.directory("q=$m").total)

        TestUsers.softDelete(gone)

        val page = caller.directory("q=$m")
        assertEquals(listOf(kept), page.items.map { it.id })
        assertEquals(1L, page.total)
    }

    @Test
    fun `sorting defaults to name, flips with a minus and is whitelisted to name and id`() = testApplication {
        usePostgresTestcontainer()
        val m = marker()
        // Seeded out of name order so an id-order default would fail the assertion.
        val c = seed(m, "Cora $m", emailLocal = "c")
        val a = seed(m, "Abel $m", emailLocal = "a")
        val b = seed(m, "Bela $m", emailLocal = "b")
        val caller = seededClient("dir-sort", UserRole.USER)

        assertEquals(listOf(a, b, c), caller.directory("q=$m").items.map { it.id }, "default: name ascending")
        assertEquals(listOf(c, b, a), caller.directory("q=$m&sort=-name").items.map { it.id })
        assertEquals(listOf(c, a, b), caller.directory("q=$m&sort=id").items.map { it.id })

        for (bad in listOf("email", "-role", "password", "name,language")) {
            assertEquals(HttpStatusCode.BadRequest, caller.get("/api/v1/users/directory?q=$m&sort=$bad").status, bad)
        }
    }

    @Test
    fun `paging follows the shared envelope, a page holds at most 20 rows and bad parameters are 400`() = testApplication {
        usePostgresTestcontainer()
        val m = marker()
        repeat(3) { seed(m, "Page$it $m", emailLocal = "p$it") }
        val caller = seededClient("dir-page", UserRole.USER)

        val second = caller.directory("q=$m&pageSize=2&page=2")
        assertEquals(2, second.page)
        assertEquals(2, second.pageSize)
        assertEquals(3L, second.total)
        assertEquals(listOf("Page2 $m"), second.items.map { it.name })
        assertEquals(20, caller.directory("q=$m").pageSize, "the default page is 20 rows")
        assertEquals(20, caller.directory("q=$m&pageSize=20").pageSize, "20 is the ceiling")

        for (bad in listOf("page=0", "pageSize=21", "pageSize=100", "pageSize=0", "page=x", "pageSize=x")) {
            assertEquals(HttpStatusCode.BadRequest, caller.get("/api/v1/users/directory?q=$m&$bad").status, bad)
        }
        assertEquals(HttpStatusCode.BadRequest, caller.get("/api/v1/users/directory?q=ab&q=cd").status, "a repeated key")
    }

    @Test
    fun `q is required, trimmed and bounded to 2 to 100 code points`() = testApplication {
        usePostgresTestcontainer()
        val caller = seededClient("dir-qbounds", UserRole.USER)
        val emoji = "\uD83D\uDC0D" // one code point, two UTF-16 units

        for (bad in listOf("", "q=", "q=%20%20%20", q("a"), q(" a "), q(emoji), q("x".repeat(101)), q(emoji.repeat(101)))) {
            val path = "/api/v1/users/directory" + if (bad.isEmpty()) "" else "?$bad"
            assertEquals(HttpStatusCode.BadRequest, caller.get(path).status, "'$bad'")
        }
        for (ok in listOf(q("ab"), q(" ab "), q(emoji.repeat(2)), q("x".repeat(100)), q(emoji.repeat(100)))) {
            assertEquals(HttpStatusCode.OK, caller.get("/api/v1/users/directory?$ok").status, ok.take(40))
        }
    }

    @Test
    fun `LIKE wildcards in q are literal and match no one`() = testApplication {
        usePostgresTestcontainer()
        val m = marker()
        seed(m, "Wild $m")
        val caller = seededClient("dir-wild", UserRole.USER)

        assertEquals(1L, caller.directory(q(m)).total, "the marker alone finds the row")
        assertEquals(0L, caller.directory(q("%_$m")).total)
        assertEquals(0L, caller.directory(q("%$m")).total)
        assertEquals(0L, caller.directory(q("_$m")).total)
        assertEquals(0L, caller.directory(q("%%")).total, "a bare wildcard is not 'everyone'")
        assertEquals(0L, caller.directory(q("__")).total)
    }

    @Test
    fun `the literal path wins over the id route and the admin list stays admin-only`() = testApplication {
        usePostgresTestcontainer()
        val m = marker()
        seed(m, "Routed $m")
        val caller = seededClient("dir-route", UserRole.USER)
        val admin = seededClient("dir-route-admin", UserRole.ADMIN)

        // `{id}` is a UInt (a non-number would be a 400) and self-or-ADMIN (a stranger would be 403):
        // a 200 proves the literal route answered.
        assertEquals(HttpStatusCode.OK, caller.get("/api/v1/users/directory?q=$m").status)
        assertEquals(HttpStatusCode.OK, admin.get("/api/v1/users/directory?q=$m").status)
        assertEquals(HttpStatusCode.Forbidden, caller.get("/api/v1/users").status, "the management list is unchanged")
        assertEquals(HttpStatusCode.OK, admin.get("/api/v1/users").status)
    }

    @Test
    fun `the directory rides a per-user bucket of 120 a minute, answers 429 beyond it and audits the rejection`() = testApplication {
        usePostgresTestcontainer()
        val limitedEmail = uniqueEmail("dir-bucket")
        val limitedId = TestUsers.seed(email = limitedEmail, password = "pw-123456789", role = UserRole.USER)
        val limited = authedClient(limitedEmail, "pw-123456789")
        val other = seededClient("dir-bucket-other", UserRole.USER)
        val path = "/api/v1/users/directory?pageSize=1&q=ab"

        withAuditCapture { capture ->
            repeat(120) { n ->
                assertEquals(HttpStatusCode.OK, limited.get(path).status, "request ${n + 1}")
            }
            val rejected = limited.get(path)
            assertEquals(HttpStatusCode.TooManyRequests, rejected.status)
            assertEquals(429, rejected.body<ProblemDetail>().status, "the generic 429 problem body")
            assertEquals(HttpStatusCode.OK, other.get(path).status, "keyed by the user, not the address")

            val event = capture.awaitEvent { it.message == "rate_limit.exceeded" && it.hasKeyValue("bucket", "directory") }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("byUserId", limitedId.toLong()))
            assertTrue(event.hasKeyValue("path", "/api/v1/users/directory"), "the path carries no query string")
        }
    }
}
