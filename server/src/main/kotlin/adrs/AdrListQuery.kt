package ch.nokillswit.adrs

import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.tags.TagCategoryService
import org.jetbrains.exposed.v1.core.Case
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.castTo
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.r2dbc.select

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

/** The ONE sortable whitelist: derived from the column map so the two can never drift. */
val ADR_SORT_FIELDS: Set<String> = ADR_SORT_COLUMNS.keys

/** The default order: newest number first (the `id` ascending tiebreaker is appended by `parsePaging`). */
val ADR_DEFAULT_SORT: List<SortField> = listOf(SortField("number", descending = true))

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

/**
 * The list's ONE predicate for the count and the rows (the caller adds `active()`): `status` is any-of, `q` matches the name OR the key
 * (case- and accent-insensitive substring), `participant` keeps the ADRs the user holds a role on (`participantRole` narrows the role),
 * `tags` keeps the ADRs carrying at least one of the named tags.
 */
internal fun adrListPredicate(filter: AdrListFilter): Op<Boolean> {
    val adrs = AdrService.Adrs
    var op: Op<Boolean> = Op.TRUE
    if (filter.statuses.isNotEmpty()) op = op and (adrs.status inList filter.statuses.map { it.name })
    filter.q?.takeIf { it.isNotBlank() }?.let { op = op and (adrs.name.containsNormalized(it) or keyExpression().containsNormalized(it)) }
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
    return op
}
