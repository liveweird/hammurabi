package ch.nokillswit

import ch.nokillswit.adrs.AdrEventType
import ch.nokillswit.adrs.AdrPageResponse
import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.adrs.AdrTag
import ch.nokillswit.adrs.MAX_ADR_TAGS
import ch.nokillswit.adrs.MAX_TAG_FILTERS
import ch.nokillswit.infra.ProblemTypes
import ch.nokillswit.plugins.ProblemDetail
import io.ktor.client.call.body
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
 * The ADR's tags (V13): the wholesale-replace PUT as a diff against the registry (strict: an unknown or retired tag is 400), the
 * verdict rows (Driver, Approver, ADMIN; editable once ACTIVE, frozen once terminal), the TAGS_CHANGED event, the retired display, and
 * the list's `tag` filter.
 */
class AdrTagsTest {

    private fun live(vararg names: String) = names.sorted().map { AdrTag(it, retired = false) }

    @Test
    fun `a set is applied as a diff - added and removed leave one event, a response and a row carry the tags`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val admin = cast.admin.client
        val (a, b, c) = List(3) { uniqueTag() }
        admin.createCategory(tags = listOf(a, b, c))
        val adr = cast.raise()
        assertEquals(emptyList(), adr.tags)
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setTags(adr.id, listOf(b, a)).status)
        val first = cast.stranger.client.getAdr(adr.id)
        assertEquals(live(a, b), first.tags)
        assertEquals(adr.revision + 1, first.revision, "a real change bumps the revision")
        assertEquals(HttpStatusCode.NoContent, cast.approver.client.setTags(adr.id, listOf(b, c)).status)
        assertEquals(live(b, c), cast.stranger.client.getAdr(adr.id).tags)
        val events = cast.driver.client.adrEventsOldestFirst(adr.id).filter { it.type == AdrEventType.TAGS_CHANGED }
        val expected = listOf(mapOf("added" to listOf(a, b).sorted().joinToString(",")), mapOf("added" to c, "removed" to a))
        assertEquals(expected, events.map { it.params })
        assertEquals(cast.approver.id, events.last().userId)
        val row = cast.stranger.client.get("/api/v1/adrs?pageSize=100&tag=$b").body<AdrPageResponse>().items.single { it.id == adr.id }
        assertEquals(live(b, c), row.tags)
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setTags(adr.id, emptyList()).status, "an empty set clears the tags")
        assertEquals(emptyList(), cast.driver.client.getAdr(adr.id).tags)
    }

    @Test
    fun `the same set again changes nothing - no revision bump, no event, no audit`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val tag = uniqueTag()
        cast.admin.client.createCategory(tags = listOf(tag))
        val adr = cast.raise()
        cast.driver.client.setTags(adr.id, listOf(tag))
        val before = cast.driver.client.getAdr(adr.id)
        val events = cast.driver.client.adrEvents(adr.id).total
        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.NoContent, cast.driver.client.setTags(adr.id, listOf(" ${tag.uppercase()} ")).status)
            kotlinx.coroutines.delay(300)
            assertTrue(capture.events.none { it.message == "adr.tags_changed" && it.hasKeyValue("adrId", adr.id.toLong()) })
        }
        assertEquals(before.revision, cast.driver.client.getAdr(adr.id).revision)
        assertEquals(events, cast.driver.client.adrEvents(adr.id).total)
    }

    @Test
    fun `strict - an unknown tag, a duplicate, too many or a badly shaped one is a 400 and nothing changes`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val tag = uniqueTag()
        cast.admin.client.createCategory(tags = listOf(tag))
        val adr = cast.raise()
        val bad = mapOf(
            "unknown" to listOf(tag, uniqueTag("nope")),
            "duplicate" to listOf(tag, tag.uppercase()),
            "badly shaped" to listOf("a,b"),
            "blank" to listOf("  "),
            "too many" to (1..MAX_ADR_TAGS + 1).map { "tag-$it" },
        )
        for ((label, tags) in bad) {
            assertEquals(HttpStatusCode.BadRequest, cast.driver.client.setTags(adr.id, tags).status, label)
        }
        assertEquals(emptyList(), cast.driver.client.getAdr(adr.id).tags)
        val message = cast.driver.client.setTags(adr.id, listOf(uniqueTag("ghost"))).body<ProblemDetail>().detail!!
        assertTrue(message.contains("Unknown or retired"), message)
    }

    @Test
    fun `an ADR takes at most twenty tags`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val tags = List(MAX_ADR_TAGS) { uniqueTag("m$it") }
        cast.admin.client.createCategory(tags = tags.take(100))
        val adr = cast.raise()
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setTags(adr.id, tags).status)
        assertEquals(MAX_ADR_TAGS, cast.driver.client.getAdr(adr.id).tags.size)
    }

    @Test
    fun `who may tag - the Driver, the Approver and ADMIN, and a 403 beats a bad body`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val tag = uniqueTag()
        cast.admin.client.createCategory(tags = listOf(tag))
        val adr = cast.raise()
        val allowed = setOf("admin", "driver", "approver")
        for ((who, actor) in cast.everyone) {
            val expected = if (who in allowed) HttpStatusCode.NoContent else HttpStatusCode.Forbidden
            assertEquals(expected, actor.client.setTags(adr.id, listOf(tag)).status, "$who sets a tag")
            actor.client.setTags(adr.id, emptyList())
        }
        for ((who, actor) in cast.everyone - allowed) {
            val malformed = actor.client.put("/api/v1/adrs/${adr.id}/tags") {
                contentType(ContentType.Application.Json)
                setBody("{")
            }
            assertEquals(HttpStatusCode.Forbidden, malformed.status, "$who: the guard comes before the header and the body")
            assertEquals(HttpStatusCode.Forbidden, actor.client.setTags(adr.id, listOf("bad,tag")).status, who)
        }
        assertEquals(HttpStatusCode.NotFound, cast.admin.client.setTags(4_000_000u, listOf(tag), revision = 1).status)
    }

    @Test
    fun `the verdict flags - who is offered the tag editor and in which status`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        val editors = setOf("admin", "driver", "approver")
        for ((who, actor) in cast.everyone) {
            assertEquals(who in editors, actor.client.getAdr(adr.id).canEditTags, "$who canEditTags")
        }
        cast.activate(adr)
        assertTrue(cast.driver.client.getAdr(adr.id).canEditTags, "tags are not part of the content freeze")
        forceAdrStatus(adr.id, AdrStatus.EXPIRED)
        assertEquals(false, cast.admin.client.getAdr(adr.id).canEditTags, "a terminal ADR is read-only")
    }

    @Test
    fun `an ACTIVE ADR takes tags, a terminal one is frozen and a stale revision is a 409`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val tag = uniqueTag()
        cast.admin.client.createCategory(tags = listOf(tag))
        val adr = cast.raise(complete = true)
        cast.activate(adr)
        val active = cast.driver.client.getAdr(adr.id)
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setTags(adr.id, listOf(tag)).status)
        val stale = cast.driver.client.setTags(adr.id, emptyList(), revision = active.revision)
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertEquals(ProblemTypes.REVISION_CONFLICT, stale.body<ProblemDetail>().type)
        forceAdrStatus(adr.id, AdrStatus.CANCELLED)
        val frozen = cast.driver.client.setTags(adr.id, emptyList())
        assertEquals(HttpStatusCode.Conflict, frozen.status)
        assertEquals(ProblemTypes.ADR_FROZEN, frozen.body<ProblemDetail>().type)
        assertEquals(HttpStatusCode.BadRequest, cast.driver.client.put("/api/v1/adrs/${adr.id}/tags") {
            contentType(ContentType.Application.Json)
            setBody("""{"tags":[]}""")
        }.status, "the revision header is required")
    }

    @Test
    fun `a tag the registry dropped stays as retired until a write removes it, and a write carrying it is a 400`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val admin = cast.admin.client
        val (keep, drop) = List(2) { uniqueTag() }
        val category = admin.createCategory(tags = listOf(keep, drop))
        val adr = cast.raise()
        cast.driver.client.setTags(adr.id, listOf(keep, drop))
        assertEquals(HttpStatusCode.NoContent, admin.saveCategory(category.id, listOf(keep), category.name).status)
        assertEquals(listOf(AdrTag(drop, true), AdrTag(keep, false)).sortedBy { it.name }, cast.stranger.client.getAdr(adr.id).tags)
        val row = cast.stranger.client.get("/api/v1/adrs?pageSize=100&tag=$drop").body<AdrPageResponse>().items.single { it.id == adr.id }
        assertTrue(row.tags.single { it.name == drop }.retired, "the list marks it too, and the filter still finds it")
        assertEquals(HttpStatusCode.BadRequest, cast.driver.client.setTags(adr.id, listOf(keep, drop)).status, "keeping it is refused")
        assertEquals(HttpStatusCode.BadRequest, cast.driver.client.setTags(adr.id, listOf(drop)).status)
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setTags(adr.id, listOf(keep)).status, "dropping it is the edit")
        assertEquals(live(keep), cast.driver.client.getAdr(adr.id).tags)
        val last = cast.driver.client.adrEventsOldestFirst(adr.id).last { it.type == AdrEventType.TAGS_CHANGED }
        assertEquals(mapOf("removed" to drop), last.params)
    }

    @Test
    fun `putting a retired tag back revives it for the ADRs that kept it, and a deleted category retires its tags`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val admin = cast.admin.client
        val tag = uniqueTag()
        val category = admin.createCategory(tags = listOf(tag))
        val adr = cast.raise()
        cast.driver.client.setTags(adr.id, listOf(tag))
        assertEquals(HttpStatusCode.NoContent, admin.deleteCategory(category.id).status)
        assertEquals(listOf(AdrTag(tag, true)), cast.driver.client.getAdr(adr.id).tags, "a deleted category retires its tags")
        val reborn = admin.createCategory(tags = listOf(tag))
        assertEquals(listOf(tag), reborn.tags)
        assertEquals(live(tag), cast.driver.client.getAdr(adr.id).tags, "the same tag row came back, so the ADR shows it live again")
    }

    @Test
    fun `the tag filter is any-of, case-insensitive, matches nothing for an unknown name and is bounded`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val (a, b, c) = List(3) { uniqueTag() }
        cast.admin.client.createCategory(tags = listOf(a, b, c))
        val withA = cast.driver.client.createAdr()
        val withB = cast.driver.client.createAdr()
        val withNone = cast.driver.client.createAdr()
        cast.driver.client.setTags(withA.id, listOf(a, c))
        cast.driver.client.setTags(withB.id, listOf(b))
        suspend fun ids(query: String) =
            cast.stranger.client.get("/api/v1/adrs?pageSize=100&$query").body<AdrPageResponse>().items.map { it.id }.toSet()
        assertEquals(setOf(withA.id), ids("tag=$a"))
        assertEquals(setOf(withA.id, withB.id), ids("tag=$a&tag=$b"))
        assertEquals(setOf(withA.id), ids("tag=${a.uppercase()}&tag=$a"), "normalised and collapsed")
        assertEquals(setOf(withA.id), ids("tag=$c&tag=$c"))
        assertEquals(emptySet(), ids("tag=${uniqueTag("nobody")}"))
        assertTrue(withNone.id !in ids("tag=$a&tag=$b&tag=$c"))
        val tooMany = (1..MAX_TAG_FILTERS + 1).joinToString("&") { "tag=x$it" }
        assertEquals(HttpStatusCode.BadRequest, cast.stranger.client.get("/api/v1/adrs?$tooMany").status)
        assertEquals(HttpStatusCode.BadRequest, cast.stranger.client.get("/api/v1/adrs?tag=a,b%00").status)
    }

    @Test
    fun `a tag write audits with the names and a refused one does not`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val (a, b) = List(2) { uniqueTag() }
        cast.admin.client.createCategory(tags = listOf(a, b))
        val adr = cast.raise()
        withAuditCapture { capture ->
            cast.driver.client.setTags(adr.id, listOf(a, b))
            val event = capture.awaitEvent { it.message == "adr.tags_changed" && it.hasKeyValue("adrId", adr.id.toLong()) }
            assertNotNull(event, "a tag write audits")
            assertTrue(event.hasKeyValue("added", listOf(a, b).sorted().joinToString(",")) && event.hasKeyValue("removed", ""))
            assertTrue(event.hasKeyValue("byUserId", cast.driver.id.toLong()) && event.hasKeyValue("revision", adr.revision + 1))
            val count = capture.events.count { it.message == "adr.tags_changed" }
            cast.driver.client.setTags(adr.id, listOf("unregistered-${a}"))
            kotlinx.coroutines.delay(200)
            assertEquals(count, capture.events.count { it.message == "adr.tags_changed" }, "a refused write does not audit")
            cast.stranger.client.setTags(adr.id, emptyList())
            assertNotNull(capture.awaitEvent { it.message == "authz.denied" }, "a denial is the generic authz.denied")
        }
    }
}
