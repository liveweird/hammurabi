package ch.nokillswit

import ch.nokillswit.infra.ProblemTypes
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.tags.MAX_CATEGORY_DESCRIPTION_LENGTH
import ch.nokillswit.tags.MAX_CATEGORY_NAME_LENGTH
import ch.nokillswit.tags.MAX_CATEGORY_TAGS
import ch.nokillswit.tags.MAX_TAG_CATEGORIES
import ch.nokillswit.tags.MAX_TAG_NAME_LENGTH
import ch.nokillswit.tags.TagCategory
import ch.nokillswit.tags.TagCategoryList
import ch.nokillswit.tags.TagCategoryRequest
import ch.nokillswit.tags.TagCategoryService
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The tag registry (V13, `adr-model.md` "Tags"): ADMIN-only writes, any-authenticated reads, the normalised and unique tag names, one
 * category per tag, retire-not-erase, the seed and the cap. The registry is shared state: every test names its own categories and tags.
 */
class TagCategoryTest {

    private suspend fun listed(client: HttpClient): List<TagCategory> =
        client.get("/api/v1/tag-categories").body<TagCategoryList>().items

    @Test
    fun `the migration seeds the Domain and Scope categories and any authenticated user reads them`() = testApplication {
        usePostgresTestcontainer()
        val reader = seededClient("tagread")
        val byName = listed(reader).associateBy { it.name }
        assertEquals(listOf("data", "frontend", "infrastructure", "integration", "security"), byName.getValue("Domain").tags)
        assertEquals(listOf("organisation", "system", "team"), byName.getValue("Scope").tags)
        assertNotNull(byName.getValue("Domain").description)
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get("/api/v1/tag-categories").status)
    }

    @Test
    fun `a category round-trips with normalised tags, a Location and the name order`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tagcreate", UserRole.ADMIN)
        val a = uniqueTag("alpha")
        val b = uniqueTag("beta")
        val name = categoryName("  Zeta  ")
        val response = admin.postJson("/api/v1/tag-categories", TagCategoryRequest(name, "  A note  ", listOf("  ${b.uppercase()} ", a)))
        assertEquals(HttpStatusCode.Created, response.status)
        val created = response.body<TagCategory>()
        assertTrue(response.headers[HttpHeaders.Location]!!.endsWith("/api/v1/tag-categories/${created.id}"))
        assertEquals(name.trim(), created.name)
        assertEquals("A note", created.description)
        assertEquals(listOf(a, b).sorted(), created.tags, "trimmed, lower-cased, alphabetical")
        assertEquals(created, listed(admin).single { it.id == created.id })
        val blank = admin.createCategory(description = "   ")
        assertEquals(null, blank.description, "a blank description is none")
        val names = listed(admin).map { it.name.lowercase() }
        assertEquals(names.sorted(), names, "the registry reads name-ordered")
    }

    @Test
    fun `a tag name collapses inner whitespace and keeps the grammar's punctuation`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tagnorm", UserRole.ADMIN)
        val core = uniqueTag("hi")
        val created = admin.createCategory(tags = listOf("  $core   Availability ", "c++-${core.takeLast(4)}", "a.b_c/d#e:f"))
        assertEquals(setOf("$core availability", "c++-${core.takeLast(4)}", "a.b_c/d#e:f"), created.tags.toSet())
    }

    @Test
    fun `the rules - lengths, the tag grammar, duplicates after normalising, control characters`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tagbad", UserRole.ADMIN)
        fun body(name: String = categoryName(), tags: List<String> = listOf(uniqueTag()), description: String? = null) =
            TagCategoryRequest(name, description, tags)
        val bad = mapOf(
            "blank name" to body(name = "  "),
            "long name" to body(name = "n".repeat(MAX_CATEGORY_NAME_LENGTH + 1)),
            "control name" to body(name = "a\u0007b"),
            "long description" to body(description = "d".repeat(MAX_CATEGORY_DESCRIPTION_LENGTH + 1)),
            "no tags" to body(tags = emptyList()),
            "too many tags" to body(tags = (1..MAX_CATEGORY_TAGS + 1).map { "bulk-$it" }),
            "blank tag" to body(tags = listOf("   ")),
            "long tag" to body(tags = listOf("t".repeat(MAX_TAG_NAME_LENGTH + 1))),
            "a comma in a tag" to body(tags = listOf("a,b")),
            "a tag starting with punctuation" to body(tags = listOf("-lead")),
            "a tag with a disallowed character" to body(tags = listOf("a&b")),
            "a control character in a tag" to body(tags = listOf("a\u0007b")),
            "the same tag twice after normalising" to body(tags = listOf("Same", " same ")),
        )
        for ((label, request) in bad) {
            assertEquals(HttpStatusCode.BadRequest, admin.postJson("/api/v1/tag-categories", request).status, label)
        }
        val hundred = (1..MAX_CATEGORY_TAGS).map { uniqueTag("b$it").take(MAX_TAG_NAME_LENGTH) }
        assertEquals(HttpStatusCode.Created, admin.postCategory(tags = hundred).status, "a hundred tags fit")
        assertEquals(HttpStatusCode.Created, admin.postCategory(tags = listOf("żółć ${uniqueTag()}")).status, "letters of any script")
    }

    @Test
    fun `only an ADMIN writes - a 403 beats a bad body and an unknown id`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tagadmin", UserRole.ADMIN)
        val user = seededClient("taguser")
        val category = admin.createCategory()
        val invalid = TagCategoryRequest("", null, emptyList())
        assertEquals(HttpStatusCode.Forbidden, user.postJson("/api/v1/tag-categories", invalid).status)
        assertEquals(HttpStatusCode.Forbidden, user.postCategory().status)
        assertEquals(HttpStatusCode.Forbidden, user.putJson("/api/v1/tag-categories/${category.id}", invalid).status)
        assertEquals(HttpStatusCode.Forbidden, user.putJson("/api/v1/tag-categories/4000000", invalid).status, "uniform: it may not exist")
        assertEquals(HttpStatusCode.Forbidden, user.delete("/api/v1/tag-categories/${category.id}").status)
        assertEquals(HttpStatusCode.Forbidden, user.delete("/api/v1/tag-categories/4000000").status)
        // Not even a malformed body gets past the guard.
        val malformed = user.put("/api/v1/tag-categories/${category.id}") {
            contentType(ContentType.Application.Json)
            setBody("{")
        }
        assertEquals(HttpStatusCode.Forbidden, malformed.status)
        assertEquals(category, listed(user).single { it.id == category.id }, "nothing changed")
    }

    @Test
    fun `a name clash is 409 case-insensitively and a deleted category frees its name`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tagdup", UserRole.ADMIN)
        val name = categoryName("Dup")
        val first = admin.createCategory(name = name)
        val clash = admin.postJson("/api/v1/tag-categories", TagCategoryRequest(name.uppercase(), null, listOf(uniqueTag())))
        assertEquals(HttpStatusCode.Conflict, clash.status)
        assertTrue(clash.body<ProblemDetail>().detail!!.contains("tag category with this name"))
        val other = admin.createCategory()
        assertEquals(HttpStatusCode.Conflict, admin.saveCategory(other.id, other.tags, name).status, "a rename onto a live name")
        assertEquals(HttpStatusCode.NoContent, admin.deleteCategory(first.id).status)
        assertEquals(HttpStatusCode.Created, admin.postCategory(name).status)
    }

    @Test
    fun `a tag lives in exactly one category - a clash names the owner, even in a different case`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tagone", UserRole.ADMIN)
        val tag = uniqueTag("own")
        val owner = admin.createCategory(tags = listOf(tag), name = categoryName("Owner"))
        val steal = admin.postJson("/api/v1/tag-categories", TagCategoryRequest(categoryName(), null, listOf(tag.uppercase())))
        assertEquals(HttpStatusCode.Conflict, steal.status)
        assertTrue(steal.body<ProblemDetail>().detail!!.contains(owner.name))
        val other = admin.createCategory()
        assertEquals(HttpStatusCode.Conflict, admin.saveCategory(other.id, other.tags + tag, other.name).status)
        // The seeded tags are claimed too.
        assertEquals(HttpStatusCode.Conflict, admin.saveCategory(other.id, other.tags + "security", other.name).status)
        assertEquals(other, listed(admin).single { it.id == other.id }, "a refused save changes nothing")
    }

    @Test
    fun `a replace renames, re-describes and swaps the tags - a dropped tag can move on in a second save`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tagput", UserRole.ADMIN)
        val keep = uniqueTag("keep")
        val drop = uniqueTag("drop")
        val first = admin.createCategory(tags = listOf(keep, drop))
        val second = admin.createCategory()
        val renamed = categoryName("Renamed")
        assertEquals(HttpStatusCode.NoContent, admin.saveCategory(first.id, listOf(keep), renamed, "now described").status)
        val after = listed(admin).single { it.id == first.id }
        assertEquals(TagCategory(first.id, renamed, "now described", first.revision + 1, listOf(keep)), after)
        // Moving the dropped tag is its own save in the other category.
        assertEquals(HttpStatusCode.NoContent, admin.saveCategory(second.id, second.tags + drop, second.name).status)
        assertEquals((second.tags + drop).sorted(), listed(admin).single { it.id == second.id }.tags)
        assertEquals(HttpStatusCode.NotFound, admin.saveCategory(4_000_000u, listOf(uniqueTag()), categoryName()).status)
        assertEquals(
            HttpStatusCode.NotFound,
            admin.saveCategory(4_000_000u, listOf(keep), categoryName()).status,
            "a missing category is 404 even when the payload claims a tag another category holds",
        )
    }

    @Test
    fun `a save and a delete are guarded by the revision - stale is 409, no header is 400, a missing id is 404 first`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tagrev", UserRole.ADMIN)
        val category = admin.createCategory()
        assertEquals(1L, category.revision)
        val stale = admin.saveCategory(category.id, category.tags, categoryName("Stale"), revision = category.revision + 4)
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertEquals(ProblemTypes.REVISION_CONFLICT, stale.body<ProblemDetail>().type)
        assertEquals(category, listed(admin).single { it.id == category.id }, "a stale save changes nothing")
        val unguarded = admin.put("/api/v1/tag-categories/${category.id}") {
            contentType(ContentType.Application.Json)
            setBody(TagCategoryRequest(category.name, null, category.tags))
        }
        assertEquals(HttpStatusCode.BadRequest, unguarded.status, "the header is required")
        assertEquals(HttpStatusCode.BadRequest, admin.delete("/api/v1/tag-categories/${category.id}").status, "also on a delete")
        assertEquals(HttpStatusCode.NoContent, admin.saveCategory(category.id, category.tags, category.name, revision = 1).status)
        assertEquals(2L, listed(admin).single { it.id == category.id }.revision, "every PUT bumps it")
        assertEquals(HttpStatusCode.Conflict, admin.saveCategory(category.id, category.tags, category.name, revision = 1).status, "reused")
        val staleDelete = admin.deleteCategory(category.id, revision = 1)
        assertEquals(ProblemTypes.REVISION_CONFLICT, staleDelete.body<ProblemDetail>().type)
        assertTrue(listed(admin).any { it.id == category.id }, "a stale delete deletes nothing")
        assertEquals(HttpStatusCode.NotFound, admin.saveCategory(4_000_000u, category.tags, categoryName(), revision = 9).status)
        assertEquals(HttpStatusCode.NotFound, admin.deleteCategory(4_000_000u, revision = 9).status)
        assertEquals(HttpStatusCode.NoContent, admin.deleteCategory(category.id, revision = 2).status)
    }

    @Test
    fun `two admins saving the same revision at once - one wins, the other gets the 409, nobody loses the tag list silently`() =
        testApplication {
            usePostgresTestcontainer()
            val admin = seededClient("tagrevrace", UserRole.ADMIN)
            val category = admin.createCategory()
            val mine = uniqueTag("mine")
            val theirs = uniqueTag("theirs")
            val results = coroutineScope {
                listOf(mine, theirs).map { tag ->
                    async(Dispatchers.IO) { admin.saveCategory(category.id, category.tags + tag, category.name, revision = 1).status }
                }.awaitAll()
            }
            assertEquals(1, results.count { it == HttpStatusCode.NoContent }, "$results")
            assertEquals(1, results.count { it == HttpStatusCode.Conflict }, "$results")
            val stored = listed(admin).single { it.id == category.id }
            assertEquals(2L, stored.revision)
            assertEquals(1, listOf(mine, theirs).count { it in stored.tags }, "exactly one of the two edits landed")
        }

    @Test
    fun `deleting a category retires its tags and frees them for another category`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tagdel", UserRole.ADMIN)
        val tag = uniqueTag("gone")
        val category = admin.createCategory(tags = listOf(tag))
        assertEquals(HttpStatusCode.NoContent, admin.deleteCategory(category.id).status)
        assertEquals(HttpStatusCode.NotFound, admin.deleteCategory(category.id).status, "a second delete")
        assertTrue(listed(admin).none { it.id == category.id })
        assertTrue(tag !in admin.registryTags())
        val claimed = admin.createCategory(tags = listOf(tag))
        assertEquals(listOf(tag), claimed.tags, "the retired tag is claimable again")
        assertEquals(HttpStatusCode.NotFound, admin.saveCategory(category.id, listOf(uniqueTag()), categoryName()).status, "gone")
    }

    @Test
    fun `two admins claiming the same new tag at once - exactly one wins, the registry stays consistent`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tagrace", UserRole.ADMIN)
        val tag = uniqueTag("race")
        val results = coroutineScope {
            (1..4).map {
                async(Dispatchers.IO) {
                    admin.postJson("/api/v1/tag-categories", TagCategoryRequest(categoryName("Race"), null, listOf(tag))).status
                }
            }.awaitAll()
        }
        assertEquals(1, results.count { it == HttpStatusCode.Created }, "one winner: $results")
        assertEquals(3, results.count { it == HttpStatusCode.Conflict }, "the others are 409, never a 500: $results")
        assertEquals(1, listed(admin).count { tag in it.tags })
    }

    @Test
    fun `the registry is capped and a full one refuses a new category`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tagcap", UserRole.ADMIN)
        val db = sharedDatabaseForTests()
        val table = TagCategoryService.TagCategories
        val inserted = mutableListOf<UInt>()
        try {
            val live = listed(admin).size
            suspendTransaction(db) {
                repeat(MAX_TAG_CATEGORIES - live) { i ->
                    inserted += table.insert {
                        it[name] = "Filler ${java.util.UUID.randomUUID()} $i".take(MAX_CATEGORY_NAME_LENGTH)
                        it[createdAt] = 1L
                        it[updatedAt] = 1L
                    }[table.id].value
                }
            }
            val refused = admin.postJson("/api/v1/tag-categories", TagCategoryRequest(categoryName(), null, listOf(uniqueTag())))
            assertEquals(HttpStatusCode.BadRequest, refused.status)
            assertTrue(refused.body<ProblemDetail>().detail!!.contains("full"))
        } finally {
            suspendTransaction(db) {
                inserted.forEach { id -> table.update({ table.id eq id }) { it[markedAsDeleted] = true } }
            }
        }
        assertEquals(HttpStatusCode.Created, admin.postCategory().status)
    }

    @Test
    fun `every registry mutation audits and a refused one does not`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tagaudit", UserRole.ADMIN)
        withAuditCapture { capture ->
            val category = admin.createCategory(tags = listOf(uniqueTag(), uniqueTag()))
            val id = category.id.toLong()
            val created = capture.awaitEvent { it.message == "tag_category.created" && it.hasKeyValue("categoryId", id) }
            assertNotNull(created, "a create audits")
            assertTrue(created.hasKeyValue("tags", 2) && created.hasKeyValue("name", category.name))
            admin.saveCategory(category.id, listOf(uniqueTag()), category.name)
            val updated = capture.awaitEvent { it.message == "tag_category.updated" && it.hasKeyValue("categoryId", id) }
            assertNotNull(updated, "a replace audits")
            assertTrue(updated.hasKeyValue("tags", 1))
            admin.deleteCategory(category.id)
            val deleted = capture.awaitEvent { it.message == "tag_category.deleted" && it.hasKeyValue("categoryId", id) }
            assertNotNull(deleted, "a delete audits")
            val before = capture.events.count { it.message == "tag_category.created" }
            admin.postJson("/api/v1/tag-categories", TagCategoryRequest("", null, emptyList()))
            assertEquals(before, capture.events.count { it.message == "tag_category.created" }, "a refused create does not audit")
        }
    }
}
