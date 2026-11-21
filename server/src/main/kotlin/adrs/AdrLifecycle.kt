package ch.nokillswit.adrs

import ch.nokillswit.authz.ConflictException
import ch.nokillswit.infra.ProblemTypes
import ch.nokillswit.infra.validation.sanitizeMultiLine
import io.ktor.server.plugins.BadRequestException
import java.time.LocalDate

/**
 * The ADR lifecycle (`.claude/docs/adr-model.md` "Lifecycle"; Covenant's `contracts/Lifecycle.kt` shape): strict on purpose. An illegal
 * move is a state-shaped `409` of the typed problem `illegal-transition`, like the last-admin rule; a move whose target requirements
 * fail is a `400` naming the missing fields ([missingForTransition]); the content freeze is a `409` of `adr-frozen`.
 *
 * ```
 * PROBLEM_RAISED -> SOLUTION_DRAFT -> IN_REVIEW -> ACTIVE -> EXPIRED (sweep) | SUPERSEDED (its successor became ACTIVE)
 *   PROBLEM_RAISED -> IN_REVIEW (a problem that arrives with its solution skips the draft)
 *   IN_REVIEW -> SOLUTION_DRAFT (0.7.0, "send back": the ADR goes back to drafting, nothing is cleared)
 *   CANCELLED from the first three; WITHDRAWN from IN_REVIEW and (0.7.0) from ACTIVE (the decision is revoked)
 * ```
 */
val ALLOWED_TRANSITIONS: Map<AdrStatus, Set<AdrStatus>> = mapOf(
    AdrStatus.PROBLEM_RAISED to setOf(AdrStatus.SOLUTION_DRAFT, AdrStatus.IN_REVIEW, AdrStatus.CANCELLED),
    AdrStatus.SOLUTION_DRAFT to setOf(AdrStatus.IN_REVIEW, AdrStatus.CANCELLED),
    AdrStatus.IN_REVIEW to setOf(AdrStatus.SOLUTION_DRAFT, AdrStatus.ACTIVE, AdrStatus.CANCELLED, AdrStatus.WITHDRAWN),
    AdrStatus.ACTIVE to setOf(AdrStatus.EXPIRED, AdrStatus.SUPERSEDED, AdrStatus.WITHDRAWN),
    AdrStatus.CANCELLED to emptySet(),
    AdrStatus.WITHDRAWN to emptySet(),
    AdrStatus.EXPIRED to emptySet(),
    AdrStatus.SUPERSEDED to emptySet(),
)

/** The statuses only the system moves an ADR into (the expiry sweep; the activation of its successor): never requested by a user. */
val SYSTEM_STATUSES: Set<AdrStatus> = setOf(AdrStatus.EXPIRED, AdrStatus.SUPERSEDED)

/** No move leaves a terminal status, and nothing in it is writable. */
val AdrStatus.terminal: Boolean
    get() = ALLOWED_TRANSITIONS.getValue(this).isEmpty()

/**
 * An ending that replaces nothing: terminal, but not `SUPERSEDED` (`CANCELLED`, `WITHDRAWN`, `EXPIRED`). The ONE rule behind "the successor
 * set to replace this ADR will never do it": the successor is told when its predecessor reaches such a status, and the predecessor stops
 * listing it (`adr-model.md` "Successors").
 */
val AdrStatus.endedUnreplaced: Boolean
    get() = terminal && this != AdrStatus.SUPERSEDED

/** The Markdown content and the name are editable only before the decision is in force (the three pre-decision states). */
val AdrStatus.contentEditable: Boolean
    get() = this == AdrStatus.PROBLEM_RAISED || this == AdrStatus.SOLUTION_DRAFT || this == AdrStatus.IN_REVIEW

fun AdrStatus.canTransitionTo(target: AdrStatus): Boolean = target in ALLOWED_TRANSITIONS.getValue(this)

/** A move a USER may attempt in the map: its edge exists and its target is not one the system alone moves into. */
fun AdrStatus.canRequest(target: AdrStatus): Boolean = canTransitionTo(target) && target !in SYSTEM_STATUSES

fun AdrStatus.requireTransitionTo(target: AdrStatus) {
    if (target in SYSTEM_STATUSES) {
        throw ConflictException("$target is reached by the system only, never requested", ProblemTypes.ILLEGAL_TRANSITION)
    }
    if (!canTransitionTo(target)) {
        throw ConflictException("An ADR in $this cannot move to $target", ProblemTypes.ILLEGAL_TRANSITION)
    }
}

/** A terminal ADR is read-only: every write is the typed `adr-frozen` 409. */
fun AdrStatus.requireWritable() {
    if (terminal) throw ConflictException("A $this ADR is read-only", ProblemTypes.ADR_FROZEN)
}

/** Once `ACTIVE` only the expiry date, the participants and the tags change, never the content (and the successor link no longer does). */
fun AdrStatus.requireContentEditable() {
    if (!contentEditable) throw ConflictException("The content of a $this ADR is frozen", ProblemTypes.ADR_FROZEN)
}

/** The successor link is set before the decision is in force: once `ACTIVE` (or terminal) it is the typed `adr-frozen` 409. */
fun AdrStatus.requireLinkable() {
    if (!contentEditable) throw ConflictException("The successor link of a $this ADR is frozen", ProblemTypes.ADR_FROZEN)
}

/**
 * What stops [record] from entering [target]: the fields it must hold first, named for the `400`. Entering `IN_REVIEW` needs the
 * context, the decision, the consequences and an Approver (the name and the problem exist from creation); entering `ACTIVE` re-checks
 * the same (the content and the participants stay editable in review), that a date already set is not in the past (the sweep would
 * expire the ADR at once) and that the ADR it replaces, if any, is `ACTIVE` (else there is nothing to supersede). Empty = it may proceed.
 */
fun missingForTransition(
    target: AdrStatus,
    record: AdrRecord,
    hasApprover: Boolean,
    today: LocalDate,
    predecessor: AdrRef? = null,
): List<String> {
    if (target != AdrStatus.IN_REVIEW && target != AdrStatus.ACTIVE) return emptyList()
    val missing = mutableListOf<String>()
    if (record.context.isBlank()) missing += "context"
    if (record.decision.isBlank()) missing += "decision"
    if (record.consequences.isBlank()) missing += "consequences"
    if (!hasApprover) missing += "approver"
    if (target == AdrStatus.ACTIVE && record.expiresOn?.let { parseExpiresOn(it).isBefore(today) } == true) {
        missing += "expiresOn (must not be in the past)"
    }
    // The ADR it replaces must still be in force, or there is nothing to supersede: unlink it (or wait for it to be activated first).
    if (target == AdrStatus.ACTIVE && predecessor != null && predecessor.status != AdrStatus.ACTIVE) {
        missing += "supersedes ${predecessor.key} (it is ${predecessor.status}, not ACTIVE: unlink it first)"
    }
    return missing
}

/**
 * A successor replaces a decision in force only with the consent of that decision's Approver (1.0.0, `adr-model.md` "Successors"): the
 * activation of [record] over an `ACTIVE` [predecessor] is the typed `409` `successor-not-accepted` until the predecessor's Approver (or
 * ADMIN) accepted the link, ADMIN included (nobody bypasses it at activation). A predecessor that is not `ACTIVE` is not this rule's
 * business (it keeps its own `400` among the requirements), and no predecessor, or a soft-deleted one that reads as none, passes.
 */
fun requireSuccessorAccepted(record: AdrRecord, predecessor: AdrRef?) {
    if (predecessor?.status == AdrStatus.ACTIVE && !record.supersedesAccepted) {
        throw ConflictException(
            "${predecessor.key} is in force: its Approver has to accept this successor before it can be activated",
            ProblemTypes.SUCCESSOR_NOT_ACCEPTED,
        )
    }
}

fun requireTransitionRequirements(
    target: AdrStatus,
    record: AdrRecord,
    hasApprover: Boolean,
    today: LocalDate,
    predecessor: AdrRef? = null,
) {
    val missing = missingForTransition(target, record, hasApprover, today, predecessor)
    if (missing.isNotEmpty()) throw BadRequestException("An ADR cannot move to $target: missing ${missing.joinToString(", ")}")
}

/** The longest reason a transition may carry, in code points (the history shows it as a dimmed line, a notice never carries it). */
const val MAX_TRANSITION_REASON_CODE_POINTS = 1000

/**
 * The optional reason of a transition (0.7.0; asked for by "send back" and "withdraw", accepted on every move): the free-text sanitiser
 * (CRLF to LF, control and hidden direction characters `400`, both ends trimmed), blank = no reason (null), more than
 * [MAX_TRANSITION_REASON_CODE_POINTS] code points `400`. Judged after the stale revision, with the move's other requirements.
 */
fun sanitizedTransitionReason(raw: String?): String? {
    // A reason is a sentence, not a code block: the first line's indentation goes too (the multi-line sanitiser keeps it).
    val reason = sanitizeMultiLine(raw ?: return null, "reason").trim().takeIf { it.isNotEmpty() } ?: return null
    if (reason.codePointCount(0, reason.length) > MAX_TRANSITION_REASON_CODE_POINTS) {
        throw BadRequestException("reason must be at most $MAX_TRANSITION_REASON_CODE_POINTS characters")
    }
    return reason
}
