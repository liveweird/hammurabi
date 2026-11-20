package ch.nokillswit.adrs

import ch.nokillswit.infra.db.active
import ch.nokillswit.tags.TagCategoryService
import ch.nokillswit.tags.normalizedTagName
import ch.nokillswit.tags.requireValidTagName
import ch.nokillswit.users.UserService
import io.ktor.server.plugins.BadRequestException
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll

/** What one tags write changed (the audit line and the event both read it); both lists are alphabetical. */
data class TagDiff(val added: List<String>, val removed: List<String>) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty()
}

/**
 * The tag set's shape rules - normalised names, each once, at most [MAX_ADR_TAGS]; enforced by the route AND re-checked by the service.
 * The count is judged BEFORE any name is normalised, so an oversized list never costs a pass of sanitising.
 */
fun normalizedAdrTags(request: AdrTagsRequest): AdrTagsRequest {
    requireTagCount(request.tags)
    return AdrTagsRequest(request.tags.map(::normalizedTagName))
}

private fun requireTagCount(tags: List<String>) {
    if (tags.size > MAX_ADR_TAGS) throw BadRequestException("An ADR carries at most $MAX_ADR_TAGS tags")
}

fun validateAdrTags(request: AdrTagsRequest) {
    requireTagCount(request.tags)
    request.tags.forEach(::requireValidTagName)
    if (request.tags.toSet().size != request.tags.size) throw BadRequestException("Tags must not repeat")
}

/**
 * The tags of an ADR (`adr_tags`, V13). Every function runs in the CALLER's transaction (the ADR write's, under the ADR row lock): a tag
 * change and its event commit or roll back together, and nothing here opens a transaction of its own. The cross-feature read
 * (persistence.md): the registry's `tags` rows are joined or batched in that same transaction.
 */
class AdrTagService(private val events: AdrEventService) {
    object AdrTags : Table("adr_tags") {
        val adrId = reference("adr_id", AdrService.Adrs)
        val tagId = reference("tag_id", TagCategoryService.Tags)
        val addedAt = long("added_at")
        val addedBy = reference("added_by", UserService.Users)
        override val primaryKey = PrimaryKey(adrId, tagId)
    }

    private val tags = TagCategoryService.Tags

    /** The tags of every ADR in [adrIds] in ONE query (a list page), alphabetical per ADR; a retired tag is marked. */
    suspend fun forAdrs(adrIds: Collection<UInt>): Map<UInt, List<AdrTag>> {
        if (adrIds.isEmpty()) return emptyMap()
        return AdrTags.innerJoin(tags).select(AdrTags.adrId, tags.name, tags.markedAsDeleted)
            .where { AdrTags.adrId inList adrIds.distinct() }.toList()
            .groupBy({ it[AdrTags.adrId].value }) { AdrTag(it[tags.name], it[tags.markedAsDeleted]) }
            .mapValues { (_, list) -> list.sortedBy { it.name } }
    }

    /**
     * Replaces the ADR's whole tag set with [requested] (shape checked by [validateAdrTags]) as a DIFF. Strict: every name must be a LIVE
     * tag of the registry, else a `400` listing the offenders - a retired tag the ADR still carries included, so a save that keeps it
     * is refused until it is dropped. One `TAGS_CHANGED` event; an empty diff writes and records nothing. Returns the diff.
     */
    suspend fun replace(adrId: UInt, byUserId: UInt, requested: List<String>, stamp: Long): TagDiff {
        val live = if (requested.isEmpty()) {
            emptyMap()
        } else {
            tags.selectAll().where { tags.active() and (tags.name inList requested) }.toList()
                .associate { it[tags.name] to it[tags.id].value }
        }
        val unknown = requested.filterNot { it in live }
        if (unknown.isNotEmpty()) throw BadRequestException("Unknown or retired tags: ${unknown.sorted().joinToString(", ")}")
        val current = AdrTags.innerJoin(tags).select(AdrTags.tagId, tags.name).where { AdrTags.adrId eq adrId }.toList()
            .associate { it[tags.name] to it[AdrTags.tagId].value }
        val diff = TagDiff(
            added = requested.filter { it !in current }.sorted(),
            removed = current.keys.filter { it !in requested }.sorted(),
        )
        if (diff.isEmpty) return diff
        if (diff.removed.isNotEmpty()) {
            val dropped = diff.removed.map { current.getValue(it) }
            AdrTags.deleteWhere { (AdrTags.adrId eq adrId) and (AdrTags.tagId inList dropped) }
        }
        diff.added.forEach { name ->
            AdrTags.insert {
                it[AdrTags.adrId] = adrId
                it[tagId] = live.getValue(name)
                it[addedAt] = stamp
                it[addedBy] = byUserId
            }
        }
        events.record(adrId, byUserId, adrTagsChangedEvent(diff.added, diff.removed), stamp)
        return diff
    }
}
