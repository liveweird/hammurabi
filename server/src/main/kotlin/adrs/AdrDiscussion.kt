package ch.nokillswit.adrs

import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.infra.validation.sanitizeMultiLine
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * The ADR discussion (V12, `.claude/docs/adr-model.md` "Discussion"; Covenant's version reviews, ported): comments and questions BEFORE
 * the decision is in force, issues and escalations while it is. One open decision log - any authenticated user may post.
 */
const val MAX_DISCUSSION_BODY_LENGTH = 10_000

/** The optional note a resolution carries (why an issue is settled); shorter than a body on purpose. */
const val MAX_DISCUSSION_RESOLUTION_LENGTH = 2_000

/** The persisted kinds (`adr_discussion.kind`, frozen: a name is never renamed or removed; the Kotlin enum is the whitelist, no CHECK). */
@Serializable
enum class DiscussionKind { COMMENT, QUESTION, ISSUE, ESCALATION }

/**
 * The persisted state of an entry (`adr_discussion.status`, frozen like [DiscussionKind]): a QUESTION is `OPEN` or `ANSWERED`, an ISSUE or
 * ESCALATION is `OPEN` or `RESOLVED`; a COMMENT and a deleted entry have none (null).
 */
@Serializable
enum class DiscussionState { OPEN, ANSWERED, RESOLVED }

/** Whether entries of this kind carry a state at all (a comment is just said). */
val DiscussionKind.hasState: Boolean
    get() = this != DiscussionKind.COMMENT

/** The state a new entry of this kind starts in; null for a comment. */
val DiscussionKind.initialState: DiscussionState?
    get() = if (hasState) DiscussionState.OPEN else null

/** The state that closes an entry of this kind: `ANSWERED` for a question, `RESOLVED` for an issue or escalation. */
val DiscussionKind.settledState: DiscussionState?
    get() = when (this) {
        DiscussionKind.COMMENT -> null
        DiscussionKind.QUESTION -> DiscussionState.ANSWERED
        DiscussionKind.ISSUE, DiscussionKind.ESCALATION -> DiscussionState.RESOLVED
    }

@Serializable
data class DiscussionCreateRequest(val kind: DiscussionKind, val body: String)

@Serializable
data class DiscussionEditRequest(val body: String)

/** The target state of an entry; the optional [resolution] note travels only with `RESOLVED`. */
@Serializable
data class DiscussionStateRequest(val state: DiscussionState, val resolution: String? = null)

/**
 * One entry as the API shows it. A deleted entry is a TOMBSTONE: `deleted` true, `body`, `state` and `resolution` null (the text is gone
 * from the wire and from the table), the kind, author and creation time kept. `canEdit` / `canDelete` / `canChangeState` are THIS caller's
 * verdicts now (the SPA never re-derives them).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DiscussionEntryResponse(
    val id: UInt,
    val adrId: UInt,
    val kind: DiscussionKind,
    /**
     * Absent from the JSON for a comment and for a tombstone: the spec cannot say "nullable enum" in a way every tool accepts (the
     * response validator rejects a null against the enum, Spectral crashes on `null` in an enum), so the property is optional and
     * `NEVER` encodes its default.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val state: DiscussionState? = null,
    val body: String?,
    val deleted: Boolean,
    val authorId: UInt,
    /** The author's display name as it is now (kept for a soft-deleted account); [authorDeleted] marks the account as deleted. */
    val authorName: String,
    val authorDeleted: Boolean,
    val createdAt: Long,
    val editedAt: Long?,
    val resolution: String?,
    val stateChangedById: UInt?,
    val stateChangedByName: String?,
    /** False when nobody changed the state (and for a tombstone). */
    val stateChangedByDeleted: Boolean,
    val stateChangedAt: Long?,
    val canEdit: Boolean,
    val canDelete: Boolean,
    val canChangeState: Boolean,
)

typealias DiscussionPageResponse = PageResponse<DiscussionEntryResponse>

data class DiscussionListResult(val items: List<DiscussionEntryResponse>, val total: Long)

/** The list filters: any-of kinds and one state (both optional, whitelisted by the route). */
data class DiscussionFilter(val kinds: List<DiscussionKind> = emptyList(), val state: DiscussionState? = null)

/** What an ADR carries of its discussion: the OPEN issues + escalations and the OPEN (unanswered) questions, deleted entries excluded. */
data class DiscussionCounts(val openIssues: Int = 0, val unansweredQuestions: Int = 0)

/** Trims and normalises the body (the sanitiser convention: control characters are a 400). */
fun sanitizedDiscussionCreate(request: DiscussionCreateRequest) =
    DiscussionCreateRequest(request.kind, sanitizeMultiLine(request.body, "Body"))

fun sanitizedDiscussionEdit(request: DiscussionEditRequest) = DiscussionEditRequest(sanitizeMultiLine(request.body, "Body"))

fun sanitizedDiscussionState(request: DiscussionStateRequest) =
    DiscussionStateRequest(request.state, request.resolution?.let { sanitizeMultiLine(it, "Resolution") }?.takeIf { it.isNotEmpty() })

/** The body rules - enforced by the route AND re-checked by the service. */
fun validateDiscussionBody(body: String) {
    if (body.isBlank()) throw BadRequestException("Body is required")
    if (body.length > MAX_DISCUSSION_BODY_LENGTH) throw BadRequestException("Body must be at most $MAX_DISCUSSION_BODY_LENGTH characters")
}

/**
 * The state rules for an entry of [kind]: a comment has no state, a question moves between `OPEN` and `ANSWERED`, an issue or
 * escalation between `OPEN` and `RESOLVED`; a note rides only a resolution. The kind never changes, so the route may check it before the
 * service's lock.
 */
fun validateDiscussionState(kind: DiscussionKind, request: DiscussionStateRequest) {
    if (!kind.hasState) throw BadRequestException("A comment has no state")
    if (request.state != DiscussionState.OPEN && request.state != kind.settledState) {
        throw BadRequestException("A $kind is ${DiscussionState.OPEN} or ${kind.settledState}, not ${request.state}")
    }
    val resolution = request.resolution
    if (resolution != null) {
        if (request.state != DiscussionState.RESOLVED) throw BadRequestException("Only a resolution carries a note")
        if (resolution.length > MAX_DISCUSSION_RESOLUTION_LENGTH) {
            throw BadRequestException("Resolution must be at most $MAX_DISCUSSION_RESOLUTION_LENGTH characters")
        }
    }
}
