package ch.nokillswit.infra.revision

import ch.nokillswit.infra.ProblemTypes
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException

/**
 * Optimistic concurrency (Toadie's `X-Expected-Revision`, ported): the client names the revision it read, and the write is refused
 * with a typed 409 when the stored revision moved on. The header is REQUIRED ([expectedRevision]) on the ADR writes (the document PUT,
 * the transitions, participants, tags and supersedes) and the tag-category PUT and DELETE: a stale editor there would overwrite what
 * another saved, and every client sends it.
 * It is OPTIONAL ([optionalExpectedRevision]) on the discussion entry edit, delete and state writes: the token is per ENTRY (V22
 * `adr_discussion.revision`, 1.2.2) and these routes were published in 0.3.0 without it, so the v1 contract cannot start
 * rejecting a client that omits it (an API consumer that never read an entry's revision); an absent header skips the check, a present one
 * is enforced. A repeated or malformed value is a 400 either way. Lives in `infra/` because `infra` may not import `authz`; the
 * ErrorHandling mapping is `urn:hammurabi:problem:revision-conflict`.
 */
const val EXPECTED_REVISION_HEADER = "X-Expected-Revision"

/** The RFC 7807 `type` of a stale-revision conflict; the SPA tells it apart from every other 409 by this URI. */
const val REVISION_CONFLICT_PROBLEM_TYPE = ProblemTypes.REVISION_CONFLICT

// A positive decimal integer without a sign, leading zeros or whitespace; the overflow check is the toLongOrNull below.
private val POSITIVE_REVISION = Regex("[1-9]\\d*")

/**
 * The revision the caller based its edit on: exactly one `X-Expected-Revision` header carrying a positive int64, else a 400.
 * Call it after the guard (403 wins over 400) and before the body is decoded.
 */
fun ApplicationCall.expectedRevision(): Long =
    optionalExpectedRevision() ?: throw BadRequestException("$EXPECTED_REVISION_HEADER header is required")

/** The same strict check for a route that must keep accepting writes from clients that predate the header (the discussion writes). */
fun ApplicationCall.optionalExpectedRevision(): Long? {
    val values = request.headers.getAll(EXPECTED_REVISION_HEADER)
    if (values.isNullOrEmpty()) return null
    val raw = values.singleOrNull()
    if (raw == null || !POSITIVE_REVISION.matches(raw)) {
        throw BadRequestException("$EXPECTED_REVISION_HEADER must be one positive integer")
    }
    return raw.toLongOrNull()
        ?: throw BadRequestException("$EXPECTED_REVISION_HEADER must be one positive integer")
}

/**
 * A guarded mutation named a revision other than the stored one. Deliberately separate from the identity/unique conflicts so
 * the transport exposes a stable problem type and the SPA can offer reload-and-retry without parsing the detail text.
 */
class RevisionConflictException(
    val expected: Long,
    val current: Long,
) : RuntimeException("Revision conflict: expected $expected but the current revision is $current")

/** The comparison under the row lock: throws [RevisionConflictException] unless [expected] is the [current] stored revision. */
fun requireRevision(expected: Long, current: Long) {
    if (expected != current) throw RevisionConflictException(expected, current)
}
