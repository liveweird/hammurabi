package ch.nokillswit

import ch.nokillswit.adrs.AdrFilterDocument
import ch.nokillswit.adrs.AdrRole
import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.lenses.LensPageResponse
import ch.nokillswit.lenses.LensRequest
import ch.nokillswit.lenses.LensService
import ch.nokillswit.lenses.LensResponse
import ch.nokillswit.lenses.LensVisibility
import ch.nokillswit.lenses.MAX_LENSES_PER_USER
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.tags.MAX_TAG_NAME_LENGTH
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lenses (`/api/v1/lenses`, V16, `authorization.md`): per-user saved ADR-list filters. PRIVATE to the creator or PUBLIC to everyone,
 * creator-only mutable, an ADMIN may delete a PUBLIC one; the ownership verdict (404 for a foreign PRIVATE or unknown id, 403 for a foreign
 * PUBLIC one) comes before the body's 400; the filters are validated by the list's own rules. Every user is fresh, but PUBLIC lenses are
 * shared suite state: a test looks at its own users' lenses.
 */
class LensTest {
    private val path = "/api/v1/lenses"

    private fun lensName() = "Lens ${UUID.randomUUID().toString().take(8)}"

    private fun request(
        name: String = lensName(),
        visibility: LensVisibility = LensVisibility.PRIVATE,
        filters: AdrFilterDocument = AdrFilterDocument(status = listOf(AdrStatus.ACTIVE)),
    ) = LensRequest(name, visibility, filters)

    private suspend fun HttpClient.save(request: LensRequest = request()): LensResponse {
        val response = postJson(path, request)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText().take(200))
        return response.body()
    }

    private suspend fun HttpClient.lenses(query: String = ""): LensPageResponse = get("$path?pageSize=100&$query").body()

    private suspend fun HttpClient.postRaw(json: String): HttpResponse = post(path) {
        contentType(ContentType.Application.Json)
        setBody(json)
    }

    private suspend fun HttpClient.putRaw(lensPath: String, json: String): HttpResponse = put(lensPath) {
        contentType(ContentType.Application.Json)
        setBody(json)
    }

    @Test
    fun `a lens is saved with its canonical filters and read back by its creator`() = testApplication {
        usePostgresTestcontainer()
        val me = adrActor("lens-own")
        val owner = me.client
        val filters = AdrFilterDocument(
            status = listOf(AdrStatus.ACTIVE, AdrStatus.ACTIVE, AdrStatus.IN_REVIEW),
            q = "  budget  ",
            text = "  Żółw  ",
            participantId = 7u,
            participantRole = AdrRole.DRIVER,
            tag = listOf("  Data ", "data", "SECURITY"),
            createdFrom = "2024-01-01",
            expiresBefore = "2030-12-31",
            hasOpenIssues = true,
            hasSuccessor = false,
        )
        val response = owner.postJson(path, request(filters = filters))
        assertEquals(HttpStatusCode.Created, response.status)
        val saved = response.body<LensResponse>()
        assertEquals("$path/${saved.id}", response.headers["Location"])
        assertEquals(
            AdrFilterDocument(
                status = listOf(AdrStatus.ACTIVE, AdrStatus.IN_REVIEW),
                q = "budget",
                text = "Żółw",
                participantId = 7u,
                participantRole = AdrRole.DRIVER,
                tag = listOf("data", "security"),
                createdFrom = "2024-01-01",
                expiresBefore = "2030-12-31",
                hasOpenIssues = true,
                hasSuccessor = false,
            ),
            saved.filters,
        )
        assertTrue(saved.canEdit && saved.canDelete)
        assertEquals(saved, owner.get("$path/${saved.id}").body<LensResponse>())
        assertEquals(listOf(saved.id), owner.lenses("mine=true").items.map { it.id })
    }

    @Test
    fun `a PRIVATE lens is the creator's secret and a PUBLIC one is everyone's to read and nobody else's to change`() = testApplication {
        usePostgresTestcontainer()
        val owner = adrActor("lens-vis-o")
        val other = adrActor("lens-vis-x")
        val private = owner.client.save(request(visibility = LensVisibility.PRIVATE))
        val public = owner.client.save(request(visibility = LensVisibility.PUBLIC))

        assertEquals(setOf(private.id, public.id), owner.client.lenses("mine=true").items.map { it.id }.toSet())
        assertEquals(listOf(public.id), other.client.lenses("mine=false").items.filter { it.createdById == owner.id }.map { it.id })
        assertEquals(HttpStatusCode.NotFound, other.client.get("$path/${private.id}").status)
        val seen = other.client.get("$path/${public.id}").body<LensResponse>()
        assertEquals(owner.id, seen.createdById)
        assertTrue(!seen.canEdit && !seen.canDelete)
        assertEquals("Lens-vis-o", seen.createdByName)
    }

    @Test
    fun `the list can be narrowed to mine or to the shared ones, by visibility and sorted by name`() = testApplication {
        usePostgresTestcontainer()
        val owner = adrActor("lens-list-o")
        val other = adrActor("lens-list-x")
        val sharedByOther = other.client.save(request(name = "Zed shared", visibility = LensVisibility.PUBLIC))
        val mineB = owner.client.save(request(name = "bravo mine"))
        val mineA = owner.client.save(request(name = "Alpha mine", visibility = LensVisibility.PUBLIC))
        other.client.save(request(name = "hidden", visibility = LensVisibility.PRIVATE))

        assertEquals(listOf(mineA.id, mineB.id), owner.client.lenses("mine=true").items.map { it.id }, "case-insensitive name order")
        assertEquals(listOf(sharedByOther.id), owner.client.lenses("mine=false").items.filter { it.createdById == other.id }.map { it.id })
        assertEquals(listOf(mineA.id), owner.client.lenses("mine=true&visibility=PUBLIC").items.map { it.id })
        assertEquals(
            listOf(mineA.id, mineB.id, sharedByOther.id),
            owner.client.lenses().items.filter { it.createdById == owner.id || it.createdById == other.id }.map { it.id },
        )
        val page = owner.client.get("$path?pageSize=1&page=2&sort=-name&mine=true").body<LensPageResponse>()
        assertEquals(2, page.total)
        assertEquals(listOf(mineA.id), page.items.map { it.id })
        assertEquals(HttpStatusCode.BadRequest, owner.client.get("$path?mine=maybe").status)
        assertEquals(HttpStatusCode.BadRequest, owner.client.get("$path?sort=createdByName").status)
    }

    @Test
    fun `the name is unique per owner, the cap is fifty and the payload follows the list's rules`() = testApplication {
        usePostgresTestcontainer()
        val owner = adrActor("lens-rules-o")
        val other = adrActor("lens-rules-x")
        val saved = owner.client.save(request(name = "My Filter"))
        assertEquals(HttpStatusCode.Conflict, owner.client.postJson(path, request(name = "my filter ")).status, "case-insensitive, trimmed")
        assertEquals(HttpStatusCode.Created, other.client.postJson(path, request(name = "My Filter")).status, "another owner may reuse it")

        for (bad in listOf(
            request(name = "  "),
            request(name = "x".repeat(101)),
            request(name = "bad\u0001name"),
            request(filters = AdrFilterDocument(text = "a")),
            request(filters = AdrFilterDocument(participantRole = AdrRole.DRIVER)),
            request(filters = AdrFilterDocument(createdFrom = "2024-02-30")),
            request(filters = AdrFilterDocument(createdFrom = "2024-02-02", createdTo = "2024-02-01")),
            request(filters = AdrFilterDocument(q = "q".repeat(101))),
            request(filters = AdrFilterDocument(tag = List(21) { "tag$it" })),
        )) {
            assertEquals(HttpStatusCode.BadRequest, owner.client.postJson(path, bad).status, bad.toString().take(80))
        }
        val secret = "secretterm" + UUID.randomUUID().toString().take(8)
        val rejected = owner.client.postJson(path, request(filters = AdrFilterDocument(text = "$secret\u0001")))
        assertEquals(HttpStatusCode.BadRequest, rejected.status)
        assertTrue(secret !in rejected.bodyAsText(), "the problem never echoes the term")
        assertEquals(
            HttpStatusCode.BadRequest,
            owner.client.postRaw("""{"name":"x","visibility":"PRIVATE","filters":{"status":["NOPE"]}}""").status,
            "an unknown status",
        )
        assertEquals(HttpStatusCode.BadRequest, owner.client.postRaw("""{"name":"x","visibility":"SECRET","filters":{}}""").status)

        val crowd = adrActor("lens-cap")
        repeat(MAX_LENSES_PER_USER) { crowd.client.save(request(name = "Lens number $it")) }
        assertEquals(HttpStatusCode.Conflict, crowd.client.postJson(path, request(name = "One too many")).status)
        // A deleted lens frees both its name and its place.
        val first = crowd.client.lenses("mine=true").items.first()
        assertEquals(HttpStatusCode.NoContent, crowd.client.delete("$path/${first.id}").status)
        assertEquals(HttpStatusCode.Created, crowd.client.postJson(path, request(name = first.name)).status)
        assertNotNull(saved)
    }

    @Test
    fun `only the creator replaces a lens and the verdict comes before the body`() = testApplication {
        usePostgresTestcontainer()
        val owner = adrActor("lens-put-o")
        val other = adrActor("lens-put-x")
        val admin = adrActor("lens-put-a", UserRole.ADMIN)
        val private = owner.client.save(request(visibility = LensVisibility.PRIVATE))
        val public = owner.client.save(request(visibility = LensVisibility.PUBLIC))
        val taken = owner.client.save(request(name = "Taken name"))
        val invalid = request(name = " ", filters = AdrFilterDocument(text = "a"))

        // 404 for a foreign PRIVATE or an unknown id, 403 for a foreign PUBLIC (an ADMIN's too), whatever the body is.
        assertEquals(HttpStatusCode.NotFound, other.client.putJson("$path/${private.id}", invalid).status)
        assertEquals(HttpStatusCode.NotFound, admin.client.putJson("$path/${private.id}", request()).status)
        assertEquals(HttpStatusCode.NotFound, other.client.putJson("$path/999999", invalid).status)
        assertEquals(HttpStatusCode.Forbidden, other.client.putJson("$path/${public.id}", invalid).status)
        assertEquals(HttpStatusCode.Forbidden, admin.client.putJson("$path/${public.id}", request()).status)
        // The creator: a bad body is 400, a clash is 409, a good one rewrites everything (rename, visibility, filters).
        assertEquals(HttpStatusCode.BadRequest, owner.client.putJson("$path/${public.id}", invalid).status)
        assertEquals(HttpStatusCode.Conflict, owner.client.putJson("$path/${public.id}", request(name = taken.name.uppercase())).status)
        val replacement = request(name = "Renamed", visibility = LensVisibility.PRIVATE, filters = AdrFilterDocument(hasOpenIssues = true))
        assertEquals(HttpStatusCode.NoContent, owner.client.putJson("$path/${public.id}", replacement).status)
        val after = owner.client.get("$path/${public.id}").body<LensResponse>()
        assertEquals("Renamed", after.name)
        assertEquals(LensVisibility.PRIVATE, after.visibility)
        assertEquals(AdrFilterDocument(hasOpenIssues = true), after.filters)
        assertTrue(after.updatedAt >= public.updatedAt)
        assertEquals(HttpStatusCode.NotFound, other.client.get("$path/${public.id}").status, "now private to its creator")
        // Saving the same name onto itself is not a clash.
        assertEquals(HttpStatusCode.NoContent, owner.client.putJson("$path/${public.id}", replacement).status)
    }

    @Test
    fun `a body that does not decode never beats the ownership verdict`() = testApplication {
        usePostgresTestcontainer()
        val owner = adrActor("lens-undec-o")
        val other = adrActor("lens-undec-x")
        val private = owner.client.save(request(visibility = LensVisibility.PRIVATE))
        val public = owner.client.save(request(visibility = LensVisibility.PUBLIC))
        val undecodable = listOf(
            """{"name":"x","visibility":"SECRET","filters":{}}""",
            """{"name":"x","visibility":"PUBLIC","filters":{"status":["NOPE"]}}""",
            """{"name":{"a":1},"visibility":"PUBLIC","filters":{}}""",
            """{"name":"x"}""",
            """{ not json""",
            """[]""",
        )
        for (body in undecodable) {
            assertEquals(HttpStatusCode.Forbidden, other.client.putRaw("$path/${public.id}", body).status, "foreign PUBLIC: $body")
            assertEquals(HttpStatusCode.NotFound, other.client.putRaw("$path/${private.id}", body).status, "foreign PRIVATE: $body")
            assertEquals(HttpStatusCode.NotFound, other.client.putRaw("$path/999999", body).status, "unknown id: $body")
            assertEquals(HttpStatusCode.BadRequest, owner.client.putRaw("$path/${public.id}", body).status, "the creator: $body")
        }
    }

    @Test
    fun `the tag and q rules of the list hold for a lens too`() = testApplication {
        usePostgresTestcontainer()
        val owner = adrActor("lens-tag")
        val longest = request(filters = AdrFilterDocument(tag = listOf("t".repeat(MAX_TAG_NAME_LENGTH))))
        assertEquals(HttpStatusCode.Created, owner.client.postJson(path, longest).status)
        for (bad in listOf(
            AdrFilterDocument(tag = listOf("t".repeat(MAX_TAG_NAME_LENGTH + 1))),
            AdrFilterDocument(tag = listOf("t".repeat(5000))),
            AdrFilterDocument(q = "a\u0001b"),
            AdrFilterDocument(q = "a\u202Eb"),
        )) {
            assertEquals(HttpStatusCode.BadRequest, owner.client.postJson(path, request(filters = bad)).status, bad.toString().take(60))
        }
    }

    @Test
    fun `a lens written by an older release lists, minus the values this release no longer knows`() = testApplication {
        usePostgresTestcontainer()
        val owner = adrActor("lens-old")
        val old = owner.client.save(request(filters = AdrFilterDocument(text = "queue")))
        val onlyGone = owner.client.save(request(filters = AdrFilterDocument(text = "queue")))
        plantFilters(
            old.id,
            """{"status":["ACTIVE","RETIRED_BY_A_LATER_RELEASE"],"text":"queue","tag":["data"],"somethingNew":{"a":1},""" +
                """"participantRole":"EMPEROR","participantId":7}""",
        )
        plantFilters(onlyGone.id, """{"status":["RETIRED_BY_A_LATER_RELEASE"],"q":"budget"}""")

        val listed = owner.client.lenses("mine=true").items.associateBy { it.id }
        assertEquals(
            AdrFilterDocument(status = listOf(AdrStatus.ACTIVE), text = "queue", tag = listOf("data"), participantId = 7u),
            listed.getValue(old.id).filters,
        )
        assertEquals(AdrFilterDocument(q = "budget"), listed.getValue(onlyGone.id).filters)
        assertEquals(listed.getValue(old.id), owner.client.get("$path/${old.id}").body<LensResponse>())
        // A save writes the lens whole again, now in this release's shape.
        val resave = request(filters = listed.getValue(old.id).filters)
        assertEquals(HttpStatusCode.NoContent, owner.client.putJson("$path/${old.id}", resave).status)
        assertTrue("somethingNew" !in rawFilters(old.id) && "RETIRED_BY_A_LATER_RELEASE" !in rawFilters(old.id))
    }

    private suspend fun plantFilters(id: UInt, json: String) {
        suspendTransaction(sharedDatabaseForTests()) { LensService.Lenses.update({ LensService.Lenses.id eq id }) { it[filters] = json } }
    }

    private suspend fun rawFilters(id: UInt): String = suspendTransaction(sharedDatabaseForTests()) {
        LensService.Lenses.select(LensService.Lenses.filters).where { LensService.Lenses.id eq id }.toList().single()[LensService.Lenses.filters]
    }

    @Test
    fun `two creates racing for the last place of a user leave exactly one winner`() = testApplication {
        usePostgresTestcontainer()
        val crowd = adrActor("lens-race")
        repeat(MAX_LENSES_PER_USER - 1) { crowd.client.save(request(name = "Lens number $it")) }
        val statuses = coroutineScope {
            listOf("Racer A", "Racer B").map { name ->
                async(Dispatchers.IO) { crowd.client.postJson(path, request(name = name)).status }
            }.awaitAll()
        }
        assertEquals(listOf(HttpStatusCode.Created, HttpStatusCode.Conflict), statuses.sortedBy { it.value }, "one place left, one winner")
        assertEquals(MAX_LENSES_PER_USER, crowd.client.lenses("mine=true").total.toInt())
    }

    @Test
    fun `delete is the creator's, or an ADMIN's for a PUBLIC lens, and it frees the name`() = testApplication {
        usePostgresTestcontainer()
        val owner = adrActor("lens-del-o")
        val other = adrActor("lens-del-x")
        val admin = adrActor("lens-del-a", UserRole.ADMIN)
        val private = owner.client.save(request(visibility = LensVisibility.PRIVATE))
        val public = owner.client.save(request(visibility = LensVisibility.PUBLIC))
        val own = owner.client.save(request(name = "Reusable"))

        assertEquals(HttpStatusCode.NotFound, other.client.delete("$path/${private.id}").status)
        val secretFromAdmin = admin.client.delete("$path/${private.id}")
        assertEquals(HttpStatusCode.NotFound, secretFromAdmin.status, "a private lens stays secret from an ADMIN")
        assertEquals(HttpStatusCode.Forbidden, other.client.delete("$path/${public.id}").status)
        assertEquals(HttpStatusCode.NotFound, other.client.delete("$path/999999").status)
        val seenByAdmin = admin.client.get("$path/${public.id}").body<LensResponse>()
        assertTrue(!seenByAdmin.canEdit && seenByAdmin.canDelete)
        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.NoContent, admin.client.delete("$path/${public.id}").status)
            val event = assertNotNull(capture.awaitEvent { it.message == "lens.deleted" && it.hasKeyValue("lensId", public.id.toLong()) })
            assertTrue(event.hasKeyValue("byAdmin", true) && event.hasKeyValue("byUserId", admin.id.toLong()))
        }
        assertEquals(HttpStatusCode.NotFound, owner.client.get("$path/${public.id}").status)
        assertEquals(HttpStatusCode.NotFound, admin.client.delete("$path/${public.id}").status, "gone")

        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.NoContent, owner.client.delete("$path/${own.id}").status)
            val event = assertNotNull(capture.awaitEvent { it.message == "lens.deleted" && it.hasKeyValue("lensId", own.id.toLong()) })
            assertTrue(event.hasKeyValue("byAdmin", false))
        }
        assertEquals(HttpStatusCode.Created, owner.client.postJson(path, request(name = "Reusable")).status, "the name is free again")
        assertEquals(HttpStatusCode.NoContent, owner.client.delete("$path/${private.id}").status)
    }

    @Test
    fun `the audit trail names ids and the visibility, never the name or the filters`() = testApplication {
        usePostgresTestcontainer()
        val owner = adrActor("lens-audit")
        val marker = "auditterm" + UUID.randomUUID().toString().replace("-", "").take(10)
        val capture = LogCapture(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME)
        try {
            val saved = owner.client.save(request(name = "Name $marker", filters = AdrFilterDocument(text = marker, q = marker)))
            owner.client.putJson("$path/${saved.id}", request(name = "Again $marker", filters = AdrFilterDocument(text = marker)))
            assertNotNull(capture.awaitEvent { it.message == "lens.created" && it.hasKeyValue("lensId", saved.id.toLong()) })
            assertNotNull(capture.awaitEvent { it.message == "lens.updated" && it.hasKeyValue("lensId", saved.id.toLong()) })
            val leaked = capture.events.filter { event ->
                marker in (event.formattedMessage + event.keyValuePairs.orEmpty().joinToString { "${it.key}=${it.value}" })
            }
            assertEquals(emptyList(), leaked.map { it.formattedMessage })
        } finally {
            capture.detach()
        }
    }

    @Test
    fun `a body that is not a lens is a 400 and ProblemDetail stays typed`() = testApplication {
        usePostgresTestcontainer()
        val owner = adrActor("lens-bad")
        val response = owner.client.postRaw("""{"name":"x"}""")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertNull(response.body<ProblemDetail>().type.takeIf { it != "about:blank" })
    }
}
