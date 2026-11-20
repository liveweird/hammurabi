package ch.nokillswit.infra.db

import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.LikeEscapeOp
import org.jetbrains.exposed.v1.core.LowerCase
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.stringParam

/**
 * Case- and diacritics-insensitive contains-match: renders
 * `LOWER(public.unaccent(col)) LIKE (SELECT concat('%', <escaped LOWER(public.unaccent(?))>, '%')) ESCAPE '\'`, so "zolw" matches
 * "Żółw" and vice versa. Both sides fold through PG's unaccent (extension enabled in V4) — the
 * rules can't drift between query and stored text (ł→l, ß→ss, æ→ae, …). The LIKE metacharacters of the needle
 * are escaped AFTER the fold, in SQL ([containsPattern], computed ONCE per statement: an uncorrelated scalar subquery is an InitPlan, not
 * a per-row call): unaccent maps the fullwidth `％ ＿ ＼` (U+FF05, U+FF3F, U+FF3C) to the ASCII
 * `% _ \`, so an escape done before it would be undone and a typed `％` would become a wildcard. unaccent runs
 * BEFORE LOWER on purpose: unaccent maps to same-case ASCII base letters, which LOWER folds
 * correctly under any DB locale — in a C-locale database (the postgres:18-alpine default),
 * `LOWER('Ż')` alone would leave the letter uppercase and uppercase-diacritic input would
 * silently stop matching. Every per-column substring filter MUST use this — never hand-roll
 * `lowerCase() like`.
 */
fun Expression<out String?>.containsNormalized(raw: String): Op<Boolean> =
    LikeEscapeOp(LowerCase(unaccent(this)), containsPattern(raw), like = true, escapeChar = LIKE_ESCAPE)

private const val LIKE_ESCAPE = '\\'

// Schema-qualified so resolution never depends on search_path. Accepts nullable string
// expressions too: a NULL value never LIKE-matches, which is exactly what a substring filter
// over an optional column should do.
private fun unaccent(expr: Expression<*>): CustomFunction<String> =
    CustomFunction("public.unaccent", TextColumnType(), expr)

/**
 * The contains-pattern of [raw] as a SQL expression: `concat('%', escape(LOWER(public.unaccent(<raw lower-cased in Kotlin>))), '%')`, where
 * `escape` doubles `\` and prefixes `%` and `_` with `\` (backslash first). The escaping is correctness-sensitive and must not drift:
 * it runs on the FOLDED text (see [containsNormalized]: a fold after an escape can create metacharacters), never in Kotlin. Used only via
 * [containsNormalized]; internal so no filter site can bypass the diacritics folding.
 */
internal fun containsPattern(raw: String): Expression<String> {
    val folded: Expression<String> = LowerCase(unaccent(stringParam(raw.lowercase())))
    val escaped = listOf("\\" to "\\\\", "%" to "\\%", "_" to "\\_").fold(folded) { text, (from, to) ->
        CustomFunction("replace", TextColumnType(), text, stringParam(from), stringParam(to))
    }
    return ScalarSubquery(CustomFunction("concat", TextColumnType(), stringParam("%"), escaped, stringParam("%")))
}

/** `(SELECT <inner>)`: with no column in it, PostgreSQL evaluates it once per statement (an InitPlan) instead of once per row. */
private class ScalarSubquery(private val inner: Expression<String>) : Expression<String>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder {
            append("(SELECT ")
            append(inner)
            append(")")
        }
    }
}

/**
 * Post-commit read-back guard: the create/transition already committed (Location set, audit
 * emitted), so a missing re-read is a server-side anomaly (500 via [error]), never a client
 * 404. Reads `X.read(id).orVanished("Domain", id)`; transitions pass a phase like
 * "after opening".
 */
fun <T> T?.orVanished(resource: String, id: Any, phase: String = "between create and re-read"): T =
    this ?: error("$resource $id vanished $phase")
