package ch.nokillswit

import ch.nokillswit.adrs.AdrResponse
import ch.nokillswit.adrs.AdrService
import ch.nokillswit.adrs.AdrSuccessorAcceptRequest
import ch.nokillswit.adrs.AdrSupersedesRequest
import ch.nokillswit.adrs.AdrTagsRequest
import ch.nokillswit.infra.revision.EXPECTED_REVISION_HEADER
import ch.nokillswit.tags.TagCategory
import ch.nokillswit.tags.TagCategoryList
import ch.nokillswit.tags.TagCategoryRequest
import ch.nokillswit.tags.TagCategoryService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.util.UUID
import kotlin.test.assertEquals

/** A category name no other test uses (the active-name uniqueness is registry-wide and the registry is shared state). */
fun categoryName(prefix: String = "Category") = "$prefix ${UUID.randomUUID()}"

/** A tag no other test uses (a tag lives in ONE category, registry-wide, retired rows included). Short: tags are at most 40 characters. */
fun uniqueTag(label: String = "t") = "$label-${UUID.randomUUID().toString().take(8)}"

/** POSTs a category as [this] (asserting 201) and returns it. */
suspend fun HttpClient.createCategory(
    tags: List<String> = listOf(uniqueTag()),
    name: String = categoryName(),
    description: String? = null,
): TagCategory {
    val response = postJson("/api/v1/tag-categories", TagCategoryRequest(name, description, tags))
    assertEquals(HttpStatusCode.Created, response.status, "create category: ${response.status}")
    return response.body()
}

/** POSTs a category (any status code); the defaults are a fresh name and one fresh tag. */
suspend fun HttpClient.postCategory(name: String = categoryName(), tags: List<String> = listOf(uniqueTag())): HttpResponse =
    postJson("/api/v1/tag-categories", TagCategoryRequest(name, null, tags))

/** The stored revision of the category [id] as [this] reads it now (1 for an unknown id, so a 404 test still sends a valid header). */
suspend fun HttpClient.categoryRevision(id: UInt): Long =
    get("/api/v1/tag-categories").body<TagCategoryList>().items.firstOrNull { it.id == id }?.revision ?: 1L

/** Replaces the category [id] as [this] under [revision] (default: its current one; any status code). */
suspend fun HttpClient.saveCategory(
    id: UInt,
    tags: List<String>,
    name: String,
    description: String? = null,
    revision: Long? = null,
): HttpResponse = put("/api/v1/tag-categories/$id") {
    header(EXPECTED_REVISION_HEADER, (revision ?: categoryRevision(id)).toString())
    contentType(ContentType.Application.Json)
    setBody(TagCategoryRequest(name, description, tags))
}

/** Deletes the category [id] as [this] under [revision] (default: its current one; any status code). */
suspend fun HttpClient.deleteCategory(id: UInt, revision: Long? = null): HttpResponse = delete("/api/v1/tag-categories/$id") {
    header(EXPECTED_REVISION_HEADER, (revision ?: categoryRevision(id)).toString())
}

/** Replaces the tags of the ADR under [revision] (default: its current one). */
suspend fun HttpClient.setTags(id: UInt, tags: List<String>, revision: Long? = null): HttpResponse {
    val expected = revision ?: get("/api/v1/adrs/$id").let { if (it.status == HttpStatusCode.OK) it.body<AdrResponse>().revision else 1L }
    return put("/api/v1/adrs/$id/tags") {
        header(EXPECTED_REVISION_HEADER, expected.toString())
        contentType(ContentType.Application.Json)
        setBody(AdrTagsRequest(tags))
    }
}

/** Detaches the pending successor of the ADR [id] as [this] (any status code). */
suspend fun HttpClient.detachSuccessor(id: UInt): HttpResponse = delete("/api/v1/adrs/$id/successor")

/** Accepts [successorId] as the pending successor of the ADR [id] as [this] - the predecessor's Approver or ADMIN (any status code). */
suspend fun HttpClient.acceptSuccessor(id: UInt, successorId: UInt): HttpResponse =
    post("/api/v1/adrs/$id/successor/accept") {
        contentType(ContentType.Application.Json)
        setBody(AdrSuccessorAcceptRequest(successorId))
    }

/**
 * The accept step of a successor that activates over an ACTIVE predecessor (1.0.0): [this] is the predecessor's Approver or ADMIN, and the
 * answer is asserted `204`. A test that does not care about the acceptance calls this between the link and the activation.
 */
suspend fun HttpClient.acceptSuccessorOk(id: UInt, successorId: UInt) {
    val response = acceptSuccessor(id, successorId)
    assertEquals(HttpStatusCode.NoContent, response.status, "accept $successorId on $id: ${response.status}")
}

/** Sets (or, null, removes) the predecessor of the ADR under [revision] (default: its current one). */
suspend fun HttpClient.setSupersedes(id: UInt, supersedesId: UInt?, revision: Long? = null): HttpResponse {
    val expected = revision ?: get("/api/v1/adrs/$id").let { if (it.status == HttpStatusCode.OK) it.body<AdrResponse>().revision else 1L }
    return put("/api/v1/adrs/$id/supersedes") {
        header(EXPECTED_REVISION_HEADER, expected.toString())
        contentType(ContentType.Application.Json)
        setBody(AdrSupersedesRequest(supersedesId))
    }
}

/** The stored `supersedes_id` of [id], straight from the table. */
suspend fun rawSupersedesId(id: UInt): UInt? = suspendTransaction(sharedDatabaseForTests()) {
    val adrs = AdrService.Adrs
    adrs.select(adrs.supersedesId).where { adrs.id eq id }.toList().single()[adrs.supersedesId]?.value
}

/** The stored `supersedes_accepted_id` of [id], straight from the table. */
suspend fun rawSupersedesAcceptedId(id: UInt): UInt? = suspendTransaction(sharedDatabaseForTests()) {
    val adrs = AdrService.Adrs
    adrs.select(adrs.supersedesAcceptedId).where { adrs.id eq id }.toList().single()[adrs.supersedesAcceptedId]?.value
}

/** The live tags of every category [this] can read, flattened. */
suspend fun HttpClient.registryTags(): Set<String> =
    get("/api/v1/tag-categories").body<ch.nokillswit.tags.TagCategoryList>().items.flatMap { it.tags }.toSet()

/** The stored categories (the registry service's own table), for the cap test's direct inserts. */
val categoryTable = TagCategoryService.TagCategories
