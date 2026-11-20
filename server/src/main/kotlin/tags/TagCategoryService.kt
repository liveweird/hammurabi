package ch.nokillswit.tags

import ch.nokillswit.authz.ConflictException
import ch.nokillswit.infra.db.SoftDeletable
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.lockingTransaction
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.revision.requireRevision
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

val TagCategoryServiceKey = AttributeKey<TagCategoryService>("TagCategoryService")

/** The lock every registry write runs under (`persistence.md` "Tag categories (V13)"); ordinary SELECTs stay available meanwhile. */
private const val LOCK_TAG_CATEGORIES_SHARE_ROW_EXCLUSIVE = "LOCK TABLE tag_categories IN SHARE ROW EXCLUSIVE MODE"

/**
 * The tag registry (`tag_categories` + `tags`, V13). Every mutation is a [lockingTransaction] (a table lock that also covers an empty
 * registry, Toadie's protocol), so two admins editing at once queue instead of both claiming the same tag. A tag is a row: removing it from
 * a category, or deleting the category, RETIRES it (`marked_as_deleted`) rather than deleting it, so the ADRs that carry it keep a
 * real row; putting the name in a category again revives that row. Reads are plain transactions.
 */
class TagCategoryService(private val database: R2dbcDatabase) {
    object TagCategories : UIntIdTable("tag_categories"), SoftDeletable {
        /** Unique among ACTIVE rows, case-insensitively, by `uq_tag_categories_name_active` (V13): not an Exposed index. */
        val name = varchar("name", length = MAX_CATEGORY_NAME_LENGTH)
        val description = varchar("description", length = MAX_CATEGORY_DESCRIPTION_LENGTH).nullable()
        val revision = long("revision").default(1L)
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
        override val markedAsDeleted = bool("marked_as_deleted").default(false)
    }

    object Tags : UIntIdTable("tags"), SoftDeletable {
        val categoryId = reference("category_id", TagCategories)

        /** Normalised ([normalizedTagName]) and unique over every row, retired ones included, by `uq_tags_name`. */
        val name = varchar("name", length = MAX_TAG_NAME_LENGTH)
        override val markedAsDeleted = bool("marked_as_deleted").default(false)
    }

    private val categories = TagCategories
    private val tags = Tags

    private suspend fun <T> writeTransaction(block: suspend R2dbcTransaction.() -> T): T =
        lockingTransaction(database, LOCK_TAG_CATEGORIES_SHARE_ROW_EXCLUSIVE, block = block)

    /** Every active category, name-ordered case-insensitively (the id as the tiebreaker), with its live tags alphabetical. */
    suspend fun list(): List<TagCategory> = suspendTransaction(database) {
        val rows = categories.selectAll().where { categories.active() }
            .orderBy(categories.name.lowerCase() to SortOrder.ASC, categories.id to SortOrder.ASC).toList()
        val tagsByCategory = tags.selectAll().where { tags.active() and (tags.categoryId inList rows.map { it[categories.id] }) }
            .orderBy(tags.name to SortOrder.ASC).toList()
            .groupBy({ it[tags.categoryId].value }, { it[tags.name] })
        rows.map {
            val id = it[categories.id].value
            TagCategory(id, it[categories.name], it[categories.description], it[categories.revision], tagsByCategory[id].orEmpty())
        }
    }

    /**
     * Claims [names] for the category: a name no row holds is inserted, a RETIRED one (or a live one of this category) is revived into
     * it, a LIVE one of another category is a 409 naming the owner; every live tag of the category missing from [names] is retired.
     */
    private suspend fun applyTags(categoryId: UInt, names: List<String>) {
        val held = tags.selectAll().where { tags.name inList names }.toList().associateBy { it[tags.name] }
        for (name in names) {
            val row = held[name]
            when {
                row == null -> tags.insert {
                    it[tags.categoryId] = categoryId
                    it[tags.name] = name
                }
                !row[tags.markedAsDeleted] && row[tags.categoryId].value != categoryId -> {
                    val owner = categories.selectAll().where { categories.id eq row[tags.categoryId] }.toList().single()[categories.name]
                    throw ConflictException("Tag '$name' already belongs to category '$owner'")
                }
                row[tags.markedAsDeleted] || row[tags.categoryId].value != categoryId -> tags.update({ tags.id eq row[tags.id] }) {
                    it[tags.categoryId] = categoryId
                    it[tags.markedAsDeleted] = false
                }
            }
        }
        val dropped = (tags.categoryId eq categoryId) and tags.active() and (tags.name notInList names)
        tags.update({ dropped }) { it[tags.markedAsDeleted] = true }
    }

    /** Creates the category with its tags in ONE locked transaction; a name clash with an active category is the central 23505 to 409. */
    suspend fun create(request: TagCategoryRequest): TagCategory {
        validateTagCategoryRequest(request) // re-checked service-side so a direct caller stays guarded
        return writeTransaction {
            if (categories.selectAll().where { categories.active() }.count() >= MAX_TAG_CATEGORIES) {
                throw BadRequestException("The tag registry is full ($MAX_TAG_CATEGORIES categories)")
            }
            val stamp = nowMillis()
            val id = categories.insert {
                it[name] = request.name
                it[description] = request.description
                it[createdAt] = stamp
                it[updatedAt] = stamp
            }[categories.id].value
            applyTags(id, request.tags)
            TagCategory(id, request.name, request.description, 1L, request.tags.sorted())
        }
    }

    /** The active category [id]'s stored revision, or null when it is missing or deleted; runs inside the registry lock. */
    private suspend fun revisionOf(id: UInt): Long? =
        categories.selectAll().where { (categories.id eq id) and categories.active() }.toList().singleOrNull()?.get(categories.revision)

    /**
     * A full replace of the category, rename and tag list included, under `X-Expected-Revision`. Existence comes first (a missing category
     * is `0`, the route's 404, even when the payload claims a tag another category holds), then the stale revision (`409`, judged under
     * the registry lock, so an admin who loaded the category before another admin saved it conflicts instead of overwriting the tag
     * list), then the claims. Every PUT bumps the revision. A tag dropped from the list is retired, not erased; moving a tag between
     * categories is two saves (drop it from the first, then add it to the second, which revives it with its ADRs).
     */
    suspend fun update(id: UInt, expectedRevision: Long, request: TagCategoryRequest): Int {
        validateTagCategoryRequest(request)
        return writeTransaction {
            val current = revisionOf(id) ?: return@writeTransaction 0
            requireRevision(expectedRevision, current)
            categories.update({ (categories.id eq id) and categories.active() }) {
                it[name] = request.name
                it[description] = request.description
                it[revision] = current + 1
                it[updatedAt] = nowMillis()
            }
            applyTags(id, request.tags)
            1
        }
    }

    /**
     * Soft delete under `X-Expected-Revision`: the category frees its name and its tags are retired (ADRs keep showing them as retired).
     * A stale revision is `409` (the admin confirmed deleting a tag list that has changed since). `0` = missing (404).
     */
    suspend fun delete(id: UInt, expectedRevision: Long): Int = writeTransaction {
        val current = revisionOf(id) ?: return@writeTransaction 0
        requireRevision(expectedRevision, current)
        categories.update({ (categories.id eq id) and categories.active() }) {
            it[markedAsDeleted] = true
            it[revision] = current + 1
            it[updatedAt] = nowMillis()
        }
        tags.update({ (tags.categoryId eq id) and tags.active() }) { it[tags.markedAsDeleted] = true }
        1
    }
}
