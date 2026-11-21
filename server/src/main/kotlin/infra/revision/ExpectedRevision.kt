package ch.nokillswit.infra.revision

import ch.nokillswit.infra.ProblemTypes
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException

/**
 * Optimistic concurrency for whole-document PUTs (Toadie's `X-Expected-Revision`, ported): the client names the revision it
 * read, and the write is refused with a typed 409 when the stored revision moved on. Document writes require the header.
 * Existing v1 discussion clients may omit it. A repeated or malformed value is a 400. Lives in `infra/` because `infra`
 * may not import `authz`; the ErrorHandling mapping is `urn:hammurabi:problem:revision-conflict`.
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

/** The same strict check for a route that accepts writes from clients that predate the revision header. */
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
