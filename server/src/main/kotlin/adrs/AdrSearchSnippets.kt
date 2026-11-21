package ch.nokillswit.adrs

import ch.nokillswit.infra.db.FTS_FRAGMENT_OPTIONS
import ch.nokillswit.infra.db.FTS_INT_ARRAY
import ch.nokillswit.infra.db.FTS_WHOLE_OPTIONS
import ch.nokillswit.infra.db.ftsHeadlineSql
import ch.nokillswit.infra.db.splitHeadline
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction

/** The body text a snippet is cut from, in the order the fragments are preferred: the decision first, the side effects last. */
private const val BODY_SQL = "concat_ws(E'\\n', a.decision, a.problem, a.context, a.alternatives, a.consequences)"

/**
 * The highlighted name and body fragment of each of [adrIds] (the page's ids ONLY: `ts_headline` is the expensive call and is never run
 * over a row that is not shown). A raw statement because the vector and the headline have no Exposed spelling; every argument is bound,
 * the markers and options are `infra/db/Fts.kt`'s, and the result is typed segments, never the marked-up string. A body fragment with
 * no hit (only the name matched) is dropped: `ts_headline` then returns the start of the text unmarked, which says nothing about the match.
 */
internal suspend fun R2dbcTransaction.adrMatches(adrIds: List<UInt>, queryText: String): Map<UInt, AdrSearchMatch> {
    val sql = "SELECT a.id, ${ftsHeadlineSql("a.name")}, ${ftsHeadlineSql(BODY_SQL)} FROM adrs a WHERE a.id = ANY(CAST(? AS int4[]))"
    val arguments = listOf(
        TextColumnType() to queryText,
        TextColumnType() to FTS_WHOLE_OPTIONS,
        TextColumnType() to queryText,
        TextColumnType() to FTS_FRAGMENT_OPTIONS,
        FTS_INT_ARRAY to adrIds.map { it.toInt() },
    )
    return exec(sql, arguments, explicitStatementType = StatementType.SELECT) { row ->
        val body = splitHeadline(row.get(2, String::class.java).orEmpty()).map { it.toWire() }
        row.get(0, Int::class.javaObjectType)!!.toUInt() to AdrSearchMatch(
            name = splitHeadline(row.get(1, String::class.java).orEmpty()).map { it.toWire() },
            snippet = if (body.any { it.hit }) body else emptyList(),
        )
    }?.toList().orEmpty().filterNotNull().toMap()
}
