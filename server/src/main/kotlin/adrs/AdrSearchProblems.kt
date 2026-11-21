package ch.nokillswit.adrs

import ch.nokillswit.authz.TooManyRequestsException
import ch.nokillswit.infra.ProblemTypes
import ch.nokillswit.infra.db.FtsSegment
import kotlinx.serialization.Serializable

/** One run of a snippet: plain [text], never markup (the client renders it as text), and [hit] when it matched the query. */
@Serializable
data class SnippetSegment(val text: String, val hit: Boolean)

/**
 * What the full-text `text` filter matched in a row: the whole [name] with its hits marked (a name that did not match comes back as one
 * plain segment) and a [snippet], one or two fragments of the body (decision, problem, context, alternatives, consequences, in that
 * order) with the hits marked; empty when only the name matched.
 */
@Serializable
data class AdrSearchMatch(val name: List<SnippetSegment>, val snippet: List<SnippetSegment>)

internal fun FtsSegment.toWire() = SnippetSegment(text, hit)

/** Seconds the `Retry-After` header of a [searchTimeout] answer names: a heavy search is not helped by an instant retry. */
const val SEARCH_TIMEOUT_RETRY_AFTER_SECONDS = 2

/**
 * A search outran its deadline (`search.timeoutSeconds`): a `429` with `Retry-After`, typed so a client tells it apart from a
 * rate-limit bucket by the URI. The detail never names the term.
 */
fun searchTimeout(): TooManyRequestsException = TooManyRequestsException(
    "The search took too long - narrow it and retry in a moment",
    problemType = ProblemTypes.SEARCH_TIMEOUT,
    retryAfterSeconds = SEARCH_TIMEOUT_RETRY_AFTER_SECONDS,
)

/** Seconds the `Retry-After` header of a [searchBusy] answer names: a slot frees within a search's own budget, usually in milliseconds. */
const val SEARCH_BUSY_RETRY_AFTER_SECONDS = 1

/**
 * The process already runs its maximum of concurrent searches (`search.maxConcurrent`): a `429` with `Retry-After`, typed APART from
 * [searchTimeout] because the remedy differs (the server's load, not the query: retry shortly, nothing to narrow). Sent at once, never
 * after a wait, so a flood cannot hold pool connections. The detail never names the term.
 */
fun searchBusy(): TooManyRequestsException = TooManyRequestsException(
    "Too many searches are running - retry in a moment",
    problemType = ProblemTypes.SEARCH_BUSY,
    retryAfterSeconds = SEARCH_BUSY_RETRY_AFTER_SECONDS,
)
