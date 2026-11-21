package ch.nokillswit.adrs

import ch.nokillswit.infra.db.active
import ch.nokillswit.tags.TagCategoryService
import io.ktor.util.AttributeKey
import io.r2dbc.spi.IsolationLevel
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.notInSubQuery
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.Query
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.time.Clock
import java.time.LocalDate

val AdrOverviewServiceKey = AttributeKey<AdrOverviewService>("AdrOverviewService")

/** The columns a log row needs: never the Markdown bodies (up to 20 000 characters each). */
private val LOG_COLUMNS = with(AdrService.Adrs) { listOf(id, number, name, expiresOn, statusChangedAt) }

/**
 * `GET /adrs/overview` (0.9.0, `adr-model.md` "Overview"): the decision log in ONE read transaction of a constant number of statements
 * (the status counts; the in-force groups: one windowed statement for the tags, a count and a page for the untagged; the recent, expiring
 * and awaiting lists; the Approvers of the awaiting page; the caller's own awaiting count), never one per ADR or per tag. READS `adrs`,
 * `adr_tags`, `tags`, `tag_categories`, `adr_participants` and (`activeAmong`) `users`; writes nothing. Every section is capped
 * (`AdrOverview.kt`) and says how many it left out.
 *
 * Time facts come from the row: an ACTIVE ADR cannot have left that status, so `status_changed_at` IS its activation (the instant of its
 * `TRANSITIONED` event, the same stamp), and for an ADR in review it is when it was submitted - no history scan.
 */
class AdrOverviewService(
    private val database: R2dbcDatabase,
    private val participants: AdrParticipantService,
    /** "Expiring soon" reaches this many days past today (`adrs.overview.expiringSoonDays`, boot-validated by the composition root). */
    private val expiringDays: Int,
    /** The tag groups shown: [OVERVIEW_TAG_GROUPS]; a constructor argument so a test can make the hidden count deterministic. */
    private val tagGroupCap: Int = OVERVIEW_TAG_GROUPS,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val adrs = AdrService.Adrs
    private val tags = TagCategoryService.Tags

    /**
     * REPEATABLE READ, read-only: every statement sees ONE snapshot, so a total and the rows cut from it always agree (under READ COMMITTED
     * an ADR activated between two statements could be counted in one and missing from the other).
     */
    suspend fun overview(callerUserId: UInt): AdrOverviewResponse = suspendTransaction(
        database,
        transactionIsolation = IsolationLevel.REPEATABLE_READ,
        readOnly = true,
    ) {
        val today = LocalDate.now(clock)
        val counts = statusCounts()
        val active = counts.getValue(AdrStatus.ACTIVE)
        val awaiting = awaiting(callerUserId, counts.getValue(AdrStatus.IN_REVIEW))
        val recent = logSection(adrs.status eq AdrStatus.ACTIVE.name, OVERVIEW_RECENT, active) {
            orderBy(adrs.statusChangedAt to SortOrder.DESC, adrs.id to SortOrder.DESC)
        }
        AdrOverviewResponse(
            generatedAt = clock.millis(),
            counts = AdrStatus.entries.map { AdrStatusCount(it, counts.getValue(it)) },
            inForce = inForce(active),
            recentlyDecided = recent,
            expiringSoon = expiring(today.plusDays(expiringDays.toLong()), expiringDays),
            awaitingDecision = awaiting,
        )
    }

    /** Every status with its live ADR count, zero included, in ONE grouped statement. */
    private suspend fun R2dbcTransaction.statusCounts(): Map<AdrStatus, Long> {
        val total = adrs.id.count()
        val found = adrs.select(adrs.status, total).where { adrs.active() }.groupBy(adrs.status).toList()
            .associate { AdrStatus.valueOf(it[adrs.status]) to it[total] }
        return AdrStatus.entries.associateWith { found[it] ?: 0L }
    }

    /** How many live ADRs satisfy [where]. */
    private suspend fun R2dbcTransaction.countOf(where: Op<Boolean>): Long {
        val total = adrs.id.count()
        return adrs.select(total).where { where and adrs.active() }.toList().single()[total]
    }

    /** The first [limit] live ADRs under [where] in the order [order] sets, and the [total] they were cut from. */
    private suspend fun R2dbcTransaction.logSection(
        where: Op<Boolean>,
        limit: Int,
        total: Long,
        order: Query.() -> Query,
    ): AdrLogSection {
        val items = adrs.select(LOG_COLUMNS).where { where and adrs.active() }.order().limit(limit).toList().map { it.toLogItem() }
        return AdrLogSection(total, items, (total - items.size).coerceAtLeast(0))
    }

    private suspend fun R2dbcTransaction.expiring(horizon: LocalDate, days: Int): AdrExpiringSection {
        // No lower bound: an ADR whose date has passed but which the sweep has not expired yet (or a deployment with the sweep off) is the
        // most urgent entry of the list, so it leads it.
        val where = (adrs.status eq AdrStatus.ACTIVE.name) and adrs.expiresOn.isNotNull() and (adrs.expiresOn lessEq horizon.toString())
        val section = logSection(where, OVERVIEW_EXPIRING, countOf(where)) {
            orderBy(adrs.expiresOn to SortOrder.ASC, adrs.number to SortOrder.ASC)
        }
        return AdrExpiringSection(days, section.total, section.items, section.more)
    }

    /** The oldest submissions first, with their Approvers (one batched read) and the caller's own count across the whole queue. */
    private suspend fun R2dbcTransaction.awaiting(callerUserId: UInt, total: Long): AdrAwaitingSection {
        val queue = logSection(adrs.status eq AdrStatus.IN_REVIEW.name, OVERVIEW_AWAITING, total) {
            orderBy(adrs.statusChangedAt to SortOrder.ASC, adrs.id to SortOrder.ASC)
        }
        val leads = participants.leadsOf(queue.items.map { it.id })
        // A deleted account keeps its seat row (and its name) but cannot decide: the row reads as "no Approver" so the gap surfaces.
        val approvers = leads.values.flatten().filter { it.role == AdrRole.APPROVER }
        val deciding = participants.activeAmong(approvers.map { it.userId })
        val items = queue.items.map { item ->
            val approver = leads[item.id].orEmpty().firstOrNull { it.role == AdrRole.APPROVER && it.userId in deciding }
            AdrAwaitingItem(
                item.id, item.number, item.key, item.name, item.expiresOn, item.statusChangedAt,
                approver, mine = approver?.userId == callerUserId,
            )
        }
        val seats = AdrParticipantService.AdrParticipants
        val mineCount = seats.adrId.count()
        val mine = seats.join(adrs, JoinType.INNER, onColumn = seats.adrId, otherColumn = adrs.id)
            .select(mineCount)
            .where {
                (seats.userId eq callerUserId) and (seats.role eq AdrRole.APPROVER.name) and
                    (adrs.status eq AdrStatus.IN_REVIEW.name) and adrs.active()
            }
            .toList().single()[mineCount]
        return AdrAwaitingSection(queue.total, items, queue.more, mine)
    }

    private suspend fun R2dbcTransaction.inForce(active: Long): AdrInForce {
        val tagged = taggedGroups()
        // "No live tag": no `adr_tags` row whose tag is still in the registry (a retired tag does not group an ADR).
        val liveTagged = AdrTagService.AdrTags.innerJoin(tags).select(AdrTagService.AdrTags.adrId).where { tags.active() }
        val untaggedWhere = (adrs.status eq AdrStatus.ACTIVE.name) and (adrs.id notInSubQuery liveTagged)
        val untagged = logSection(untaggedWhere, OVERVIEW_PER_TAG, countOf(untaggedWhere)) {
            orderBy(adrs.number to SortOrder.DESC)
        }
        return AdrInForce(active, tagged.categories, tagged.hiddenTags, untagged)
    }

    private class TaggedGroups(val categories: List<AdrCategoryGroup>, val hiddenTags: Int)

    /**
     * The ACTIVE ADRs per live tag in ONE statement: `count(*) OVER` gives each tag's total, `row_number() OVER` keeps the newest
     * [OVERVIEW_PER_TAG] of a tag, and `dense_rank() OVER` over (category, tag) names keeps the first [tagGroupCap] tags,
     * `max(...) OVER ()` (one level further out, before the filter) telling how many there were. A raw statement because Exposed has
     * no window-function spelling; every argument is bound and the live ADR, tag and category predicates are the tables' own
     * `marked_as_deleted` columns.
     */
    private suspend fun R2dbcTransaction.taggedGroups(): TaggedGroups {
        val sql = """
            WITH tagged AS (
                SELECT t.id AS tag_id, t.name AS tag_name, c.id AS category_id, c.name AS category_name,
                       a.id, a."number", a.name, a.expires_on, a.status_changed_at,
                       row_number() OVER (PARTITION BY t.id ORDER BY a."number" DESC) AS rn,
                       count(*) OVER (PARTITION BY t.id) AS total
                FROM adr_tags x
                JOIN adrs a ON a.id = x.adr_id AND a.status = ? AND NOT a.marked_as_deleted
                JOIN tags t ON t.id = x.tag_id AND NOT t.marked_as_deleted
                JOIN tag_categories c ON c.id = t.category_id AND NOT c.marked_as_deleted
            ), kept AS (
                SELECT *, dense_rank() OVER (ORDER BY lower(category_name), category_id, lower(tag_name), tag_id) AS group_rank
                FROM tagged WHERE rn <= ?
            ), counted AS (
                SELECT *, max(group_rank) OVER () AS group_count FROM kept
            )
            SELECT tag_id, tag_name, category_id, category_name, total, id, "number", name, expires_on, status_changed_at, group_count
            FROM counted WHERE group_rank <= ?
            ORDER BY group_rank, rn
        """.trimIndent()
        val arguments = listOf(
            TextColumnType() to AdrStatus.ACTIVE.name,
            IntegerColumnType() to OVERVIEW_PER_TAG,
            IntegerColumnType() to tagGroupCap,
        )
        val rows = exec(sql, arguments, explicitStatementType = StatementType.SELECT) { row ->
            TaggedRow(
                tagName = row.get("tag_name", String::class.java)!!,
                categoryId = row.get("category_id", Int::class.javaObjectType)!!.toUInt(),
                categoryName = row.get("category_name", String::class.java)!!,
                total = row.get("total", Long::class.javaObjectType)!!,
                groups = row.get("group_count", Long::class.javaObjectType)!!,
                item = adrLogItem(
                    row.get("id", Int::class.javaObjectType)!!.toUInt(),
                    row.get("number", Int::class.javaObjectType)!!,
                    row.get("name", String::class.java)!!,
                    row.get("expires_on", String::class.java),
                    row.get("status_changed_at", Long::class.javaObjectType)!!,
                ),
            )
        }?.toList().orEmpty().filterNotNull()
        return TaggedGroups(rows.toCategories(), ((rows.firstOrNull()?.groups ?: 0L) - tagGroupCap).coerceAtLeast(0).toInt())
    }

    private class TaggedRow(
        val tagName: String,
        val categoryId: UInt,
        val categoryName: String,
        val total: Long,
        val groups: Long,
        val item: AdrLogItem,
    )

    /** Rows arrive grouped by (category, tag) in display order: fold them into categories of tag groups, keeping that order. */
    private fun List<TaggedRow>.toCategories(): List<AdrCategoryGroup> =
        groupBy { it.categoryId to it.categoryName }.map { (category, inCategory) ->
            AdrCategoryGroup(
                id = category.first,
                name = category.second,
                tags = inCategory.groupBy { it.tagName }.map { (tagName, rows) ->
                    AdrTagGroup(tagName, rows.first().total, rows.map { it.item }, (rows.first().total - rows.size).coerceAtLeast(0))
                },
            )
        }

    private fun ResultRow.toLogItem() =
        adrLogItem(this[adrs.id].value, this[adrs.number], this[adrs.name], this[adrs.expiresOn], this[adrs.statusChangedAt])
}

private fun adrLogItem(id: UInt, number: Int, name: String, expiresOn: String?, statusChangedAt: Long) =
    AdrLogItem(id, number, adrKey(number), name, expiresOn, statusChangedAt)
