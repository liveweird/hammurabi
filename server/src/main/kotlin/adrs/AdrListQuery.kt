package ch.nokillswit.adrs

import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.db.ftsMatches
import ch.nokillswit.infra.db.ftsVector
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.tags.TagCategoryService
import org.jetbrains.exposed.v1.core.AbstractQuery
import org.jetbrains.exposed.v1.core.Case
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.booleanLiteral
import org.jetbrains.exposed.v1.core.castTo
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.notInSubQuery
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.r2dbc.select
import java.time.LocalDate

/**
 * The status as its position in the lifecycle (`CASE status WHEN 'PROBLEM_RAISED' THEN 0 ... END`), so `sort=status` reads in the order
 * an ADR travels rather than alphabetically. [AdrStatus.entries] is declared in lifecycle order.
 */
private fun statusOrder(): Expression<Int> {
    val status = AdrService.Adrs.status
    val entries = AdrStatus.entries
    val order = Case().When(status eq entries.first().name, intLiteral(0))
    entries.drop(1).forEachIndexed { index, entry -> order.When(status eq entry.name, intLiteral(index + 1)) }
    return order.Else(intLiteral(entries.size))
}

internal val ADR_SORT_COLUMNS: Map<String, Expression<*>> = mapOf(
    "id" to AdrService.Adrs.id,
    "number" to AdrService.Adrs.number,
    "name" to AdrService.Adrs.name,
    "updatedAt" to AdrService.Adrs.updatedAt,
    "status" to statusOrder(),
)

/** The sort name of the search's relevance (`ts_rank_cd`): accepted only with `text`, whose query it ranks. */
const val SORT_RELEVANCE = "relevance"

/** The ONE sortable whitelist: the column map plus the query-dependent relevance, so the two can never drift. */
val ADR_SORT_FIELDS: Set<String> = ADR_SORT_COLUMNS.keys + SORT_RELEVANCE

/** The default order: newest number first (the `id` ascending tiebreaker is appended by `parsePaging`). */
val ADR_DEFAULT_SORT: List<SortField> = listOf(SortField("number", descending = true))

/** The default order of a search: the most relevant first, newest number among equals. */
val ADR_SEARCH_DEFAULT_SORT: List<SortField> = listOf(SortField(SORT_RELEVANCE, descending = true), SortField("number", descending = true))

/**
 * The key as SQL text (`concat('ADR-', lpad(n, greatest(4, length(n)), '0'))`, n = `number::text`): what the `q` filter matches next to
 * the name. The width is never below the number's own length because `lpad` TRUNCATES: a fixed 4 would turn 12345 into `1234`.
 */
private fun keyExpression(): Expression<String> {
    val digits = AdrService.Adrs.number.castTo(TextColumnType())
    val width = CustomFunction("greatest", IntegerColumnType(), intLiteral(4), CustomFunction("length", IntegerColumnType(), digits))
    return CustomFunction(
        "concat",
        TextColumnType(),
        stringLiteral("ADR-"),
        CustomFunction("lpad", TextColumnType(), digits, width, stringLiteral("0")),
    )
}

/** The live ADRs (`NOT marked_as_deleted`) as a sub-select of ids, for the link presence filters. */
private fun liveAdrIds() = AdrService.Adrs.select(AdrService.Adrs.id).where { AdrService.Adrs.active() }

/** The ids that have a successor in play or in effect: the wire's `supersededBy` (never one cancelled or withdrawn before it activated). */
private fun predecessorIds() = AdrService.Adrs.select(AdrService.Adrs.supersedesId).where {
    AdrService.Adrs.supersedesId.isNotNull() and AdrService.Adrs.active() and successorInPlayOrInEffect()
}

/** The ids with an OPEN issue or escalation (`openIssueCount > 0`); the literals match the partial index `idx_adr_discussion_open`. */
private fun adrIdsWithOpenIssues(): AbstractQuery<*> {
    val entries = AdrDiscussionService.AdrDiscussion
    return entries.select(entries.adrId).where {
        (entries.status eq stringLiteral(DiscussionStatus.OPEN.name)) and (entries.markedAsDeleted eq booleanLiteral(false)) and
            (entries.kind inList listOf(DiscussionKind.ISSUE.name, DiscussionKind.ESCALATION.name))
    }
}

private fun Op<Boolean>.andDay(column: Column<Long>, from: LocalDate?, toInclusive: LocalDate?): Op<Boolean> {
    var op = this
    from?.let { op = op and (column greaterEq it.startMillis()) }
    toInclusive?.let { op = op and (column less it.plusDays(1).startMillis()) }
    return op
}

/**
 * The list's ONE predicate for the count and the rows (the caller adds `active()`): `status` is any-of, `q` matches the name OR the key
 * (case- and accent-insensitive substring), `participant` keeps the ADRs the user holds a role on (`participantRole` narrows the role),
 * `tags` keeps the ADRs carrying at least one of the named tags, the created and updated ranges are inclusive UTC days, `expiresBefore`
 * keeps the ADRs whose expiry date is strictly earlier, `hasOpenIssues`, `supersedes` and `supersededBy` are presence tests, and
 * [textQuery] (the tsquery text of the validated `text`, built by `ftsQueryText` in the caller's transaction) keeps the ADRs the
 * full-text vector matches. Every filter is an AND.
 */
internal fun adrListPredicate(filter: AdrListFilter, textQuery: String? = null): Op<Boolean> {
    val adrs = AdrService.Adrs
    var op: Op<Boolean> = Op.TRUE
    if (filter.statuses.isNotEmpty()) op = op and (adrs.status inList filter.statuses.map { it.name })
    filter.q?.takeIf { it.isNotBlank() }?.let { op = op and (adrs.name.containsNormalized(it) or keyExpression().containsNormalized(it)) }
    textQuery?.let { op = op and adrs.ftsVector().ftsMatches(it) }
    filter.participantUserId?.let { userId ->
        val roles = AdrParticipantService.AdrParticipants
        val held = roles.select(roles.adrId).where {
            val user = roles.userId eq userId
            filter.participantRole?.let { user and (roles.role eq it.name) } ?: user
        }
        op = op and (adrs.id inSubQuery held)
    }
    if (filter.tags.isNotEmpty()) {
        val carried = AdrTagService.AdrTags
        val tagNames = TagCategoryService.Tags.name
        val tagged = carried.innerJoin(TagCategoryService.Tags).select(carried.adrId).where { tagNames inList filter.tags }
        op = op and (adrs.id inSubQuery tagged)
    }
    op = op.andDay(adrs.createdAt, filter.createdFrom, filter.createdTo).andDay(adrs.updatedAt, filter.updatedFrom, filter.updatedTo)
    filter.expiresBefore?.let { op = op and adrs.expiresOn.isNotNull() and (adrs.expiresOn less it.toString()) }
    // An absence is the complement; the sub-selects never hold a NULL, so NOT IN is safe, and a NULL `supersedes_id` is absent by hand.
    filter.hasOpenIssues?.let { op = op and adrs.id.within(adrIdsWithOpenIssues(), it) }
    filter.supersededBy?.let { op = op and adrs.id.within(predecessorIds(), it) }
    filter.supersedes?.let { op = op and (adrs.supersedesId.within(liveAdrIds(), it)) }
    return op
}

/** `column IN (ids)` for a wanted presence, its complement for an absence (a NULL column is absent: it is in no set). */
private fun Column<out EntityID<UInt>?>.within(ids: AbstractQuery<*>, present: Boolean): Op<Boolean> =
    if (present) this inSubQuery ids else this.isNull() or (this notInSubQuery ids)
