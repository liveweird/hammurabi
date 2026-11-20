package ch.nokillswit.plugins

import io.r2dbc.spi.R2dbcException
import java.sql.SQLException

/**
 * What the 500 catch-all logs for a failed request (`ErrorHandling.kt`): the throwable itself, except when it may carry a database
 * failure. A database exception's MESSAGE is not safe to log: Exposed's `ExposedR2dbcException` appends the failed statements (with
 * their bound arguments expanded when the transaction's `debug` flag is on), and Postgres itself echoes values into some messages (an
 * input-syntax failure, a `RAISE`, a malformed tsquery) - an ADR text, a name or a search term is exactly such an argument
 * (`security.md` "Log hygiene"). So the throwable is replaced by [ScrubbedFailure]s that keep the CLASS NAMES, the SQLSTATE and the
 * stack frames and drop every message and every suppressed exception, whenever the cause chain holds a database exception, OR a link
 * has suppressed exceptions, OR the chain is longer than the walk's bound: failing closed, since a database exception could hide in
 * either. Any other failure is logged as it is.
 */
internal fun Throwable.scrubbedForLog(): Throwable {
    val walk = causeChain()
    val mayCarryValues = walk.truncated || walk.links.any { it.isDatabaseFailure() || it.suppressed.isNotEmpty() }
    if (!mayCarryValues) return this
    return walk.links.foldRight(null as ScrubbedFailure?) { link, cause ->
        ScrubbedFailure(link.javaClass.name, link.sqlStateOrNull(), link.stackTrace, cause)
    } ?: this
}

/**
 * [scrubbedForLog] as one line for a log message that carries no stack: the scrubbed chain's links joined `A <- B [SQLSTATE x]`, or the
 * plain `toString()` when the failure needs no scrubbing.
 */
internal fun Throwable.scrubbedSummary(): String = when (val scrubbed = scrubbedForLog()) {
    is ScrubbedFailure -> generateSequence<Throwable>(scrubbed) { it.cause }.joinToString(" <- ") { it.message.orEmpty() }
    else -> scrubbed.toString()
}

/** A failure with its message dropped: only the class name and the SQLSTATE of the original, and its stack frames. */
internal class ScrubbedFailure(className: String, sqlState: String?, frames: Array<StackTraceElement>, cause: ScrubbedFailure?) :
    RuntimeException(sqlState?.let { "$className [SQLSTATE $it]" } ?: className, cause, false, true) {
    init {
        stackTrace = frames
    }
}

private fun Throwable.isDatabaseFailure(): Boolean = this is R2dbcException || this is SQLException

private fun Throwable.sqlStateOrNull(): String? = when (this) {
    is R2dbcException -> sqlState
    is SQLException -> sqlState
    else -> null
}

private class CauseWalk(val links: List<Throwable>, val truncated: Boolean)

/** The throwable and its causes, outermost first; bounded ([truncated] says it hit the bound), and safe against a cause cycle. */
private fun Throwable.causeChain(): CauseWalk {
    val seen = java.util.IdentityHashMap<Throwable, Unit>()
    val chain = mutableListOf<Throwable>()
    var cur: Throwable? = this
    while (cur != null && chain.size < MAX_CHAIN && seen.put(cur, Unit) == null) {
        chain += cur
        cur = cur.cause
    }
    // A cycle ends the walk with `cur` already seen (the chain is complete); only a chain still going at the bound is truncated.
    return CauseWalk(chain, truncated = cur != null && cur !in seen)
}

private const val MAX_CHAIN = 32
