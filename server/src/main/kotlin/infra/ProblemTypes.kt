package ch.nokillswit.infra

/**
 * The ONE registry of the typed RFC 7807 problems (`ProblemDetail.type`, `urn:hammurabi:problem:<name>`): every throw site that types a
 * problem takes its URI from here (a per-feature `*_PROBLEM_TYPE` constant is an alias of these), so the set the SPA branches on is
 * one list. `ProblemTypesTest` scans the main sources for a `urn:hammurabi:problem:` literal outside this file and diffs [ALL]
 * against the list `ProblemDetail.type` documents in `openapi/documentation.yaml`; a new type lands here, in [ALL] and in that list
 * in the same commit. Every other problem is `about:blank`. Adder's fifteen types are chat problems (room-busy, search-timeout, ...):
 * the ADR increments add their own here as they arrive (0.2.0: the illegal lifecycle move and the content freeze; 0.3.0: a closed
 * discussion; 0.5.0: the search's timeout and busy).
 */
object ProblemTypes {
    /** A stale `X-Expected-Revision` (409). */
    const val REVISION_CONFLICT = "urn:hammurabi:problem:revision-conflict"

    /** A lifecycle move the map does not allow, or one only the system makes (409; `adrs/AdrLifecycle.kt`). */
    const val ILLEGAL_TRANSITION = "urn:hammurabi:problem:illegal-transition"

    /** A write to an ADR the content freeze closes: content once `ACTIVE`, anything once terminal (409; `adrs/AdrLifecycle.kt`). */
    const val ADR_FROZEN = "urn:hammurabi:problem:adr-frozen"

    /**
     * A discussion write the ADR's status does not take: comments and questions once `ACTIVE`, issues and escalations before it
     * (409; `adrs/AdrDiscussionAccess.kt`).
     */
    const val DISCUSSION_CLOSED = "urn:hammurabi:problem:discussion-closed"

    /** A search outran its deadline (429 + `Retry-After`; `adrs/AdrSearchProblems.kt`): narrow it. */
    const val SEARCH_TIMEOUT = "urn:hammurabi:problem:search-timeout"

    /** The process already runs its maximum of concurrent searches (429 + `Retry-After`; `adrs/AdrSearchProblems.kt`): retry shortly. */
    const val SEARCH_BUSY = "urn:hammurabi:problem:search-busy"

    /** The process already runs its maximum of concurrent bulk exports (429 + `Retry-After`; `adrs/AdrExportRoutes.kt`): retry shortly. */
    const val EXPORT_BUSY = "urn:hammurabi:problem:export-busy"

    /**
     * A successor cannot replace a decision in force until that decision's Approver (or an administrator) accepted the link (409;
     * `adrs/AdrLifecycle.kt`): ask the predecessor's Approver to open the predecessor and accept it.
     */
    const val SUCCESSOR_NOT_ACCEPTED = "urn:hammurabi:problem:successor-not-accepted"

    /** Every registered URI; the spec's list and the main sources are diffed against it. */
    val ALL: Set<String> = setOf(
        REVISION_CONFLICT, ILLEGAL_TRANSITION, ADR_FROZEN, DISCUSSION_CLOSED, SEARCH_TIMEOUT, SEARCH_BUSY, EXPORT_BUSY,
        SUCCESSOR_NOT_ACCEPTED,
    )
}
