package ch.nokillswit.infra.db

import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction

/**
 * Full-text search (V15, `persistence.md` "Search and filters (V15)"; Adder's `Fts.kt`, ported): the ONE place that names the text search
 * configuration and every search expression, and it names no feature. A stored generated `tsvector` column `search` sits on each
 * searchable table; the column is never declared in an Exposed table object (the driver has no `tsvector` codec, and `selectAll()`
 * would read a vector on every row), so it is a free expression here ([ftsVector]).
 *
 * The query is built from `to_tsvector`'s OWN lexemes ([ftsQueryText]): documents and queries share one parser and one folding, so no
 * input is a syntax error. A term of [FTS_PREFIX_FROM] characters or more is a prefix (`simple` does not stem), shorter ones match
 * exactly, every term must match. Input with no lexeme gives no query at all (`!!!` never gets here: the caller makes it a `400`;
 * tag-shaped input such as `<script>` does, and matches nothing).
 *
 * `ts_headline` returns its input's markup untouched, so a snippet never travels as a marked-up string: the headline is requested with
 * the control characters [FTS_HIT_START] and [FTS_HIT_STOP] as its markers (unforgeable: every name and body refuses control
 * characters) and [splitHeadline] turns it into typed [FtsSegment]s. A search term, a lexeme or a headline reaches no log line.
 */
const val FTS_CONFIG = "public.hammurabi_search"

/** A term this long (in characters) or longer matches as a prefix; a shorter one matches exactly. */
const val FTS_PREFIX_FROM = 3

/** The markers `ts_headline` is asked to put around a hit. Control characters no name or body can hold. */
const val FTS_HIT_START = '\u0002'
const val FTS_HIT_STOP = '\u0003'

/** A run of a snippet: [text] verbatim from the source (never markup), [hit] when it matched the query. */
data class FtsSegment(val text: String, val hit: Boolean)

/**
 * The tsquery TEXT of the search input: every lexeme `to_tsvector` finds in it, quoted, a prefix from [FTS_PREFIX_FROM] characters,
 * joined with `&`. The statement quotes in SQL and casts to `tsquery` and back, so PostgreSQL's own parser validates it. NULL when the
 * input has no lexeme: the caller answers an empty page without a search. Runs in the caller's transaction.
 */
internal const val FTS_QUERY_TEXT_SQL = """
SELECT string_agg(
         '''' || replace(replace(lexeme, '\', '\\'), '''', '''''')
              || CASE WHEN char_length(lexeme) >= $FTS_PREFIX_FROM THEN ''':*' ELSE '''' END,
         ' & ')::tsquery::text
FROM unnest(to_tsvector('$FTS_CONFIG'::regconfig, ?))"""

suspend fun R2dbcTransaction.ftsQueryText(raw: String): String? =
    exec(FTS_QUERY_TEXT_SQL, listOf(TextColumnType() to raw), explicitStatementType = StatementType.SELECT) { row ->
        row.get(0, String::class.java)
    }?.toList()?.singleOrNull()

/** The generated `search` column of [this] table as an expression: `<table>.search`. */
fun Table.ftsVector(): Expression<*> = FtsVector(this)

private class FtsVector(private val table: Table) : Expression<Any>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("${table.nameInDatabaseCase()}.search")
    }
}

/** The match operator: renders `<vector> @@ CAST(? AS tsquery)`, the query text bound as an argument, never concatenated. */
fun Expression<*>.ftsMatches(queryText: String): Op<Boolean> = FtsMatches(this, queryText)

private class FtsMatches(private val vector: Expression<*>, private val queryText: String) : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append(vector)
        queryBuilder.append(" @@ CAST(")
        queryBuilder.registerArgument(TextColumnType(), queryText)
        queryBuilder.append(" AS tsquery)")
    }
}

/** The relevance of [this] vector for the query text: `ts_rank_cd(<vector>, CAST(? AS tsquery))` (cover density, the weights A-D). */
fun Expression<*>.ftsRank(queryText: String): Expression<Double> = FtsRank(this, queryText)

private class FtsRank(private val vector: Expression<*>, private val queryText: String) : Expression<Double>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("ts_rank_cd(")
        queryBuilder.append(vector)
        queryBuilder.append(", CAST(")
        queryBuilder.registerArgument(TextColumnType(), queryText)
        queryBuilder.append(" AS tsquery))")
    }
}

/** The raw-SQL spelling of the match, for a hand-written statement: `<vector> @@ CAST(? AS tsquery)`. */
fun ftsMatchSql(vector: String): String = "$vector @@ CAST(? AS tsquery)"

/** The raw-SQL headline of [text] against the query text bound as the first `?`, then the options text as the second. */
fun ftsHeadlineSql(text: String): String = "ts_headline('$FTS_CONFIG'::regconfig, $text, CAST(? AS tsquery), ?)"

private const val MARKERS = "StartSel=$FTS_HIT_START, StopSel=$FTS_HIT_STOP"

/** `ts_headline` options for a fragment of a long text: at most two fragments of 6 to 18 words, joined with an ellipsis. */
const val FTS_FRAGMENT_OPTIONS = "$MARKERS, MaxFragments=2, MaxWords=18, MinWords=6, FragmentDelimiter=\" … \""

/** `ts_headline` options for a short text shown whole (a name): every hit marked, nothing cut. */
const val FTS_WHOLE_OPTIONS = "$MARKERS, HighlightAll=true"

/** The array parameter type of a list of ids, for `= ANY(CAST(? AS int4[]))`. */
val FTS_INT_ARRAY = ArrayColumnType<Int, List<Int>>(IntegerColumnType())

/**
 * Splits a headline written with [FTS_HIT_START] and [FTS_HIT_STOP] into segments, collapsing every run of whitespace to one space
 * (a body is multi-line Markdown; a snippet is one line), trimming the ends and dropping empty runs. Unbalanced markers are
 * tolerated (a stray stop opens nothing, an unclosed start ends with the text). The markers never survive into a segment, and the text
 * is passed through as it is: `<img onerror=...>` stays a plain string for the client to render as text.
 */
fun splitHeadline(headline: String): List<FtsSegment> {
    val runs = mutableListOf<FtsSegment>()
    val current = StringBuilder()
    var hit = false
    fun flush() {
        if (current.isNotEmpty()) runs += FtsSegment(current.toString(), hit)
        current.setLength(0)
    }
    for (ch in headline) {
        when (ch) {
            FTS_HIT_START -> {
                flush()
                hit = true
            }
            FTS_HIT_STOP -> {
                flush()
                hit = false
            }
            else -> current.append(ch)
        }
    }
    flush()
    return collapse(runs)
}

private val WHITESPACE = Regex("\\s+")

/** Collapses whitespace across run boundaries, merges neighbours that are both hits or both not, and trims the ends. */
private fun collapse(runs: List<FtsSegment>): List<FtsSegment> {
    val out = mutableListOf<FtsSegment>()
    for (run in runs) {
        var text = run.text.replace(WHITESPACE, " ")
        if (out.isNotEmpty() && out.last().text.endsWith(' ') && text.startsWith(' ')) text = text.removePrefix(" ")
        if (out.isEmpty()) text = text.trimStart()
        if (text.isEmpty()) continue
        val last = out.lastOrNull()
        if (last != null && last.hit == run.hit) {
            out[out.size - 1] = last.copy(text = last.text + text)
        } else {
            out += FtsSegment(text, run.hit)
        }
    }
    if (out.isNotEmpty()) {
        val tail = out.last().text.trimEnd()
        if (tail.isEmpty()) out.removeAt(out.size - 1) else out[out.size - 1] = out.last().copy(text = tail)
    }
    return out
}
