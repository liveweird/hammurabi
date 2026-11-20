package ch.nokillswit.adrs

import ch.nokillswit.authz.ConflictException
import ch.nokillswit.infra.ProblemTypes
import io.ktor.server.plugins.BadRequestException
import java.time.LocalDate

/**
 * The ADR lifecycle (`.claude/docs/adr-model.md` "Lifecycle"; Covenant's `contracts/Lifecycle.kt` shape): strict on purpose. An illegal
 * move is a state-shaped `409` of the typed problem `illegal-transition`, like the last-admin rule; a move whose target requirements
 * fail is a `400` naming the missing fields ([missingForTransition]); the content freeze is a `409` of `adr-frozen`.
 *
 * ```
 * PROBLEM_RAISED -> SOLUTION_DRAFT -> IN_REVIEW -> ACTIVE -> EXPIRED (sweep) | SUPERSEDED (successor, 0.4.0)
 *   PROBLEM_RAISED -> IN_REVIEW (a problem that arrives with its solution skips the draft)
 *   CANCELLED from the first three; WITHDRAWN from IN_REVIEW
 * ```
 */
val ALLOWED_TRANSITIONS: Map<AdrStatus, Set<AdrStatus>> = mapOf(
    AdrStatus.PROBLEM_RAISED to setOf(AdrStatus.SOLUTION_DRAFT, AdrStatus.IN_REVIEW, AdrStatus.CANCELLED),
    AdrStatus.SOLUTION_DRAFT to setOf(AdrStatus.IN_REVIEW, AdrStatus.CANCELLED),
    AdrStatus.IN_REVIEW to setOf(AdrStatus.ACTIVE, AdrStatus.CANCELLED, AdrStatus.WITHDRAWN),
    AdrStatus.ACTIVE to setOf(AdrStatus.EXPIRED, AdrStatus.SUPERSEDED),
    AdrStatus.CANCELLED to emptySet(),
    AdrStatus.WITHDRAWN to emptySet(),
    AdrStatus.EXPIRED to emptySet(),
    AdrStatus.SUPERSEDED to emptySet(),
)

/** The statuses only the system moves an ADR into (the expiry sweep; the successor activation of 0.4.0): never requested by a user. */
val SYSTEM_STATUSES: Set<AdrStatus> = setOf(AdrStatus.EXPIRED, AdrStatus.SUPERSEDED)

/** No move leaves a terminal status, and nothing in it is writable. */
val AdrStatus.terminal: Boolean
    get() = ALLOWED_TRANSITIONS.getValue(this).isEmpty()

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

/** Once `ACTIVE` only the expiry date, the participants (and later tags and the successor link) change, never the content. */
fun AdrStatus.requireContentEditable() {
    if (!contentEditable) throw ConflictException("The content of a $this ADR is frozen", ProblemTypes.ADR_FROZEN)
}

/**
 * What stops [record] from entering [target]: the fields it must hold first, named for the `400`. Entering `IN_REVIEW` needs the
 * context, the decision, the consequences and an Approver (the name and the problem exist from creation); entering `ACTIVE` re-checks
 * the same (the content and the participants stay editable in review) and that a date already set is not in the past (the sweep would
 * expire the ADR at once). Empty = the move may proceed.
 */
fun missingForTransition(target: AdrStatus, record: AdrRecord, hasApprover: Boolean, today: LocalDate): List<String> {
    if (target != AdrStatus.IN_REVIEW && target != AdrStatus.ACTIVE) return emptyList()
    val missing = mutableListOf<String>()
    if (record.context.isBlank()) missing += "context"
    if (record.decision.isBlank()) missing += "decision"
    if (record.consequences.isBlank()) missing += "consequences"
    if (!hasApprover) missing += "approver"
    if (target == AdrStatus.ACTIVE && record.expiresOn?.let { parseExpiresOn(it).isBefore(today) } == true) {
        missing += "expiresOn (must not be in the past)"
    }
    return missing
}

fun requireTransitionRequirements(target: AdrStatus, record: AdrRecord, hasApprover: Boolean, today: LocalDate) {
    val missing = missingForTransition(target, record, hasApprover, today)
    if (missing.isNotEmpty()) throw BadRequestException("An ADR cannot move to $target: missing ${missing.joinToString(", ")}")
}
