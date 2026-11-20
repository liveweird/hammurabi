package ch.nokillswit.infra

/**
 * The ONE registry of the typed RFC 7807 problems (`ProblemDetail.type`, `urn:hammurabi:problem:<name>`): every throw site that types a
 * problem takes its URI from here (a per-feature `*_PROBLEM_TYPE` constant is an alias of these), so the set the SPA branches on is
 * one list. `ProblemTypesTest` scans the main sources for a `urn:hammurabi:problem:` literal outside this file and diffs [ALL]
 * against the list `ProblemDetail.type` documents in `openapi/documentation.yaml`; a new type lands here, in [ALL] and in that list
 * in the same commit. Every other problem is `about:blank`. Adder's fifteen types are chat problems (room-busy, search-timeout, ...):
 * the ADR increments add their own here as they arrive (0.2.0: the illegal lifecycle move and the content freeze).
 */
object ProblemTypes {
    /** A stale `X-Expected-Revision` (409). */
    const val REVISION_CONFLICT = "urn:hammurabi:problem:revision-conflict"

    /** A lifecycle move the map does not allow, or one only the system makes (409; `adrs/AdrLifecycle.kt`). */
    const val ILLEGAL_TRANSITION = "urn:hammurabi:problem:illegal-transition"

    /** A write to an ADR the content freeze closes: content once `ACTIVE`, anything once terminal (409; `adrs/AdrLifecycle.kt`). */
    const val ADR_FROZEN = "urn:hammurabi:problem:adr-frozen"

    /** Every registered URI; the spec's list and the main sources are diffed against it. */
    val ALL: Set<String> = setOf(REVISION_CONFLICT, ILLEGAL_TRANSITION, ADR_FROZEN)
}
