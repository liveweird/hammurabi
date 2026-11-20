package ch.nokillswit.infra.paging

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.r2dbc.Query

const val DEFAULT_PAGE_SIZE = 20
const val MAX_PAGE_SIZE = 100

data class SortField(val name: String, val descending: Boolean)

data class PageRequest(
    val page: Int,
    val pageSize: Int,
    val sort: List<SortField>,
)

fun ApplicationCall.parsePaging(
    sortable: Set<String>,
    defaultSort: List<SortField> = listOf(SortField("id", descending = false)),
): PageRequest {
    val params = request.queryParameters

    val page = params.singleValue("page")?.let { raw ->
        val parsed = raw.toIntOrNull() ?: throw BadRequestException("page must be a positive integer")
        if (parsed < 1) throw BadRequestException("page must be >= 1")
        parsed
    } ?: 1

    val pageSize = params.singleValue("pageSize")?.let { raw ->
        val parsed = raw.toIntOrNull() ?: throw BadRequestException("pageSize must be an integer")
        if (parsed < 1 || parsed > MAX_PAGE_SIZE) {
            throw BadRequestException("pageSize must be between 1 and $MAX_PAGE_SIZE")
        }
        parsed
    } ?: DEFAULT_PAGE_SIZE

    val sortParam = params.singleValue("sort")
    val requested = if (sortParam.isNullOrBlank()) {
        defaultSort
    } else {
        parseSortList(sortParam, sortable)
    }
    val withTiebreaker = if (requested.any { it.name == "id" }) requested else requested + SortField("id", false)
    return PageRequest(page = page, pageSize = pageSize, sort = withTiebreaker)
}

/** The longest rejected sort field echoed back in a `400`, in code points: a megabyte "field" must not become a megabyte error body. */
private const val MAX_SHOWN_SORT_FIELD = 40

/** [this] cut to [MAX_SHOWN_SORT_FIELD] code points (never splitting a surrogate pair) and marked with an ellipsis. */
private fun String.shown(): String =
    if (codePointCount(0, length) <= MAX_SHOWN_SORT_FIELD) this else substring(0, offsetByCodePoints(0, MAX_SHOWN_SORT_FIELD)) + "\u2026"

/**
 * The `sort` list: at most one entry per whitelisted field, so a list longer than [sortable] and a field named twice (with or without
 * the `-`) are a `400`. The bound matters because a computed sort costs SQL text and bind parameters per entry, and every distinct
 * statement text is cached per connection: an unbounded list is an allocation lever.
 */
private fun parseSortList(sortParam: String, sortable: Set<String>): List<SortField> {
    // Counted on the commas first, so an oversized list never allocates its parts.
    if (sortParam.count { it == ',' } >= sortable.size) {
        throw BadRequestException("sort names at most ${sortable.size} fields (one per sortable field)")
    }
    val seen = HashSet<String>()
    return sortParam.split(',').map { raw ->
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw BadRequestException("sort contains an empty field")
        val descending = trimmed.startsWith('-')
        val field = if (descending) trimmed.drop(1) else trimmed
        if (field !in sortable) {
            throw BadRequestException("Unknown sort field: ${field.shown()} (allowed: ${sortable.sorted().joinToString()})")
        }
        if (!seen.add(field)) throw BadRequestException("sort names a field twice: $field")
        SortField(field, descending)
    }
}

fun Query.applyPaging(req: PageRequest, columns: Map<String, Column<*>>): Query {
    val order = req.sort.map { sf ->
        val column = columns[sf.name]
            ?: error("Sort field '${sf.name}' has no mapped column (paging helper invariant violated)")
        column to if (sf.descending) SortOrder.DESC else SortOrder.ASC
    }
    return orderBy(*order.toTypedArray())
        .limit(req.pageSize)
        .offset(((req.page - 1).toLong()) * req.pageSize)
}
