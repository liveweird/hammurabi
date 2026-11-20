package ch.nokillswit.adrs

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.ConflictException
import ch.nokillswit.authz.ForbiddenException
import ch.nokillswit.authz.isAdmin
import ch.nokillswit.infra.ProblemTypes

/**
 * The discussion verdicts (`.claude/docs/adr-model.md` "Discussion"). Pure, like `AdrAccess.kt`: the caller's DACI role is loaded by the
 * service INSIDE the write's transaction. Anyone authenticated posts (an open decision log); the entry's author edits it; the author or an
 * ADMIN deletes it; a question is marked (un)answered by its author, the Driver, the Approver or an ADMIN, an issue or escalation is
 * resolved and reopened by the Driver, the Approver or an ADMIN.
 */

/**
 * Whether the discussion takes this kind while the ADR is in [status]: comments and questions before the decision is in force (the three
 * pre-active statuses), issues and escalations only while it is `ACTIVE`. Every other status closes the kind (`discussion-closed`).
 */
fun DiscussionKind.acceptedIn(status: AdrStatus): Boolean = when (this) {
    DiscussionKind.COMMENT, DiscussionKind.QUESTION -> status.contentEditable
    DiscussionKind.ISSUE, DiscussionKind.ESCALATION -> status == AdrStatus.ACTIVE
}

/**
 * The typed `discussion-closed` 409 for a write the ADR's status does not take (a post and an edit; answering, resolving, reopening
 * and deleting are open in every status).
 */
fun DiscussionKind.requireAcceptedIn(status: AdrStatus) {
    if (!acceptedIn(status)) throw ConflictException("A $this is not accepted while the ADR is $status", ProblemTypes.DISCUSSION_CLOSED)
}

fun isAuthor(caller: CallerPrincipal, authorId: UInt): Boolean = caller.userId == authorId

/** Only the author edits an entry (not even an ADMIN: the words are theirs; an ADMIN may delete). */
fun requireMayEditEntry(caller: CallerPrincipal, authorId: UInt) {
    if (!isAuthor(caller, authorId)) throw ForbiddenException("Only the author may edit this entry")
}

/** The author or an ADMIN removes an entry (the moderation path). */
fun mayDeleteEntry(caller: CallerPrincipal, authorId: UInt): Boolean = isAuthor(caller, authorId) || caller.isAdmin()

fun requireMayDeleteEntry(caller: CallerPrincipal, authorId: UInt) {
    if (!mayDeleteEntry(caller, authorId)) throw ForbiddenException("Only the author or an administrator may delete this entry")
}

/** A question is settled by its author or the Driver / Approver / ADMIN; an issue or escalation by the Driver / Approver / ADMIN. */
fun mayChangeEntryState(standing: AdrStanding, kind: DiscussionKind, authorId: UInt): Boolean =
    standing.holds(AdrRole.DRIVER, AdrRole.APPROVER) || (kind == DiscussionKind.QUESTION && isAuthor(standing.caller, authorId))

fun requireMayChangeEntryState(standing: AdrStanding, kind: DiscussionKind, authorId: UInt) {
    if (!mayChangeEntryState(standing, kind, authorId)) {
        throw ForbiddenException("Your role on this ADR does not allow changing the state of this entry")
    }
}
