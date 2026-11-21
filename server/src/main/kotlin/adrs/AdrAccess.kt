package ch.nokillswit.adrs

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.ForbiddenException
import ch.nokillswit.authz.isAdmin

/**
 * The DACI permission verdicts (`.claude/docs/adr-model.md` "DACI roles and verdicts"; Covenant's `ContractAccess.kt` shape): "the
 * Driver drives, the Approver decides". Pure - the caller's role is loaded by the service INSIDE the write's transaction (never from the
 * JWT: a participant change takes effect on the next request), and the route runs the coarse guards before the body decodes so 403 wins
 * over 400. Everyone authenticated reads every ADR and creates one; ADMIN overrides every verdict below.
 */
data class AdrStanding(
    val caller: CallerPrincipal,
    /** The caller's DACI role on THIS ADR, null = not a participant. */
    val role: AdrRole?,
) {
    val isAdmin: Boolean get() = caller.isAdmin()

    fun holds(vararg roles: AdrRole): Boolean = isAdmin || role in roles
}

/**
 * Who may edit the document at all (coarse, before the body): the Driver, a Contributor, the Approver (the expiry date), ADMIN. The
 * per-field verdicts are [canEditContent] and [canEditExpiry].
 */
fun requireMayEdit(standing: AdrStanding) {
    if (!standing.holds(AdrRole.DRIVER, AdrRole.CONTRIBUTOR, AdrRole.APPROVER)) {
        throw ForbiddenException("Only the Driver, a Contributor, the Approver or an administrator may edit this ADR")
    }
}

/** Who may move the lifecycle at all (coarse, before the body); [requireMayRequest] is the per-move verdict. */
fun requireMayTransition(standing: AdrStanding) {
    if (!standing.holds(AdrRole.DRIVER, AdrRole.APPROVER)) {
        throw ForbiddenException("Only the Driver, the Approver or an administrator may change this ADR's status")
    }
}

/**
 * Who may replace the participants: the Driver, the Approver, ADMIN. The expiry date has its own, narrower verdict ([canEditExpiry]): once
 * the ADR is `ACTIVE` only the Approver or ADMIN may change it.
 */
fun requireMayManage(standing: AdrStanding) {
    if (!standing.holds(AdrRole.DRIVER, AdrRole.APPROVER)) {
        throw ForbiddenException("Only the Driver, the Approver or an administrator may change this ADR's participants")
    }
}

/** Who may change the tags and the successor link at all (coarse, before the body): the Driver, the Approver, ADMIN. */
fun requireMayOrganise(standing: AdrStanding) {
    if (!standing.holds(AdrRole.DRIVER, AdrRole.APPROVER)) {
        throw ForbiddenException("Only the Driver, the Approver or an administrator may change this ADR's tags or successor")
    }
}

/**
 * The roles that may REQUEST the move [from] to [target] (ADMIN always may); null = no role rule (the lifecycle map decides, a 409). The
 * Driver moves forward and cancels, the Approver activates and withdraws (an `IN_REVIEW` or an `ACTIVE` ADR alike: the Approver decides).
 * The one move judged by its source too is the SEND BACK (`IN_REVIEW` to `SOLUTION_DRAFT`, 0.7.0): the Approver returns it for rework and
 * the Driver pulls it back, while the same target from `PROBLEM_RAISED` stays the Driver's alone.
 */
private fun rolesToRequest(from: AdrStatus, target: AdrStatus): Set<AdrRole>? = when {
    from == AdrStatus.IN_REVIEW && target == AdrStatus.SOLUTION_DRAFT -> setOf(AdrRole.DRIVER, AdrRole.APPROVER)
    target == AdrStatus.SOLUTION_DRAFT || target == AdrStatus.IN_REVIEW || target == AdrStatus.CANCELLED -> setOf(AdrRole.DRIVER)
    target == AdrStatus.ACTIVE || target == AdrStatus.WITHDRAWN -> setOf(AdrRole.APPROVER)
    else -> null
}

private fun mayRequest(standing: AdrStanding, from: AdrStatus, target: AdrStatus): Boolean =
    standing.isAdmin || rolesToRequest(from, target).let { it == null || standing.role in it }

/** The per-move verdict: the Driver moves forward and cancels, the Approver activates or withdraws, both send back; ADMIN any. */
fun requireMayRequest(standing: AdrStanding, from: AdrStatus, target: AdrStatus) {
    if (!mayRequest(standing, from, target)) {
        throw ForbiddenException("Your role on this ADR does not allow moving it to $target")
    }
}

/** The moves THIS caller may request from [from]: the map's edges the system does not own, filtered by the verdict. */
fun allowedTransitions(standing: AdrStanding, from: AdrStatus): List<AdrStatus> =
    AdrStatus.entries.filter { from.canRequest(it) && mayRequest(standing, from, it) }

/** Content (name, Markdown fields) is editable in a pre-active status by the Driver, a Contributor or ADMIN. */
fun canEditContent(standing: AdrStanding, status: AdrStatus): Boolean =
    status.contentEditable && standing.holds(AdrRole.DRIVER, AdrRole.CONTRIBUTOR)

/**
 * The expiry date changes in any non-terminal status, but WHO depends on whether the decision is in force: before it is, the Driver, the
 * Approver or ADMIN; once `ACTIVE` only the Approver or ADMIN (the Approver decides, so the Driver cannot end a decision in force by
 * dating it to today). One verdict for the document PUT's 403 and the served `canEditExpiry` flag.
 */
fun canEditExpiry(standing: AdrStanding, status: AdrStatus): Boolean = when {
    status.terminal -> false
    status == AdrStatus.ACTIVE -> standing.holds(AdrRole.APPROVER)
    else -> standing.holds(AdrRole.DRIVER, AdrRole.APPROVER)
}

/** Tags change in any non-terminal status (they are not part of the content freeze), by the Driver, the Approver or ADMIN. */
fun canEditTags(standing: AdrStanding, status: AdrStatus): Boolean =
    !status.terminal && standing.holds(AdrRole.DRIVER, AdrRole.APPROVER)

/** The predecessor link changes only while the ADR is pre-active (it takes effect at activation), by the Driver, the Approver or ADMIN. */
fun canEditSuccessor(standing: AdrStanding, status: AdrStatus): Boolean =
    status.contentEditable && standing.holds(AdrRole.DRIVER, AdrRole.APPROVER)

/**
 * The predecessor's owners may DETACH its pending successor (`DELETE /adrs/{id}/successor`, 0.4.0): linking is the successor's, so a
 * stranger could otherwise park a link on an ADR and block it for everyone else. The Driver, the Approver or ADMIN of the PREDECESSOR,
 * on a non-terminal ADR that has a successor still pre-active ([pending]; one that activated already took effect).
 */
fun canDetachSuccessor(standing: AdrStanding, status: AdrStatus, pending: AdrRef?): Boolean =
    !status.terminal && pending?.status?.contentEditable == true && standing.holds(AdrRole.DRIVER, AdrRole.APPROVER)

/** Who may accept a pending successor (coarse, before the body): the Approver of the PREDECESSOR (this ADR) or ADMIN. */
fun requireMayAcceptSuccessor(standing: AdrStanding) {
    if (!standing.holds(AdrRole.APPROVER)) {
        throw ForbiddenException("Only the Approver or an administrator may accept a successor of this ADR")
    }
}

/**
 * The predecessor's Approver (or ADMIN) accepts the pending successor (`POST /adrs/{id}/successor/accept`, 1.0.0): a successor replaces a
 * decision in force only with the consent of the one who decides it. Offered on an `ACTIVE` ADR whose successor is still pre-active
 * ([pending]) and not yet [accepted]; the activation then goes through.
 */
fun canAcceptSuccessor(standing: AdrStanding, status: AdrStatus, pending: AdrRef?, accepted: Boolean): Boolean =
    status == AdrStatus.ACTIVE && pending?.status?.contentEditable == true && !accepted && standing.holds(AdrRole.APPROVER)

/** The participants are replaceable on any non-terminal ADR by the Driver, the Approver or ADMIN. */
fun canManageParticipants(standing: AdrStanding, status: AdrStatus): Boolean =
    !status.terminal && standing.holds(AdrRole.DRIVER, AdrRole.APPROVER)

/** The statuses in which the Approver seat is guarded whatever the history: the ADR is in review or the decision is in force. */
private val APPROVER_SEAT_GUARDED = setOf(AdrStatus.IN_REVIEW, AdrStatus.ACTIVE)

/**
 * The Approver seat may be added, removed or replaced by this caller now: ADMIN always; before the ADR has ever been in review the
 * Driver and the Approver; from then on (in `IN_REVIEW` or `ACTIVE`, or in a draft again after a send back: [everInReview]) only the
 * CURRENT Approver, so a send back cannot be used to swap the Approver out. (Nobody but ADMIN may name THEMSELVES, whatever the
 * status: [requireNotSelfApproval], which the SPA also applies by leaving the caller out of the picker.)
 */
fun canChangeApprover(standing: AdrStanding, status: AdrStatus, everInReview: Boolean): Boolean =
    canManageParticipants(standing, status) && mayHandApproverSeat(standing, status, everInReview)

/**
 * The seat rule alone, without [canManageParticipants]' terminal check: [requireMayChangeApproverSeat] runs it AFTER the terminal 409
 * and the stale revision, where the terminal ADR has already been answered.
 */
private fun mayHandApproverSeat(standing: AdrStanding, status: AdrStatus, everInReview: Boolean): Boolean =
    standing.isAdmin || !(everInReview || status in APPROVER_SEAT_GUARDED) || standing.role == AdrRole.APPROVER

/** Whoever holds the Approver seat in [participants] (null = none). */
private fun List<AdrParticipantRequest>.approverId(): UInt? = firstOrNull { it.role == AdrRole.APPROVER }?.userId

/**
 * No self-approval (403, ADMIN excepted): a non-ADMIN caller who is not already the Approver cannot ask for the Approver seat for
 * themselves, at any status. Pure on the request and the caller, so the route judges it before the body's shape (403 before 400) and
 * the service before the terminal 409 and the revision.
 */
fun requireNotSelfApproval(standing: AdrStanding, requested: List<AdrParticipantRequest>) {
    if (!standing.isAdmin && standing.role != AdrRole.APPROVER && requested.approverId() == standing.caller.userId) {
        throw ForbiddenException("Nobody but an administrator can make themselves the Approver")
    }
}

/**
 * The participants PUT's verdict on WHO holds the Approver seat, judged under the row lock against the LOCKED [stored] set, AFTER the
 * terminal 409 and the stale revision (a stale editor that did not touch the seat must get the `409`, never a false `403`): once the
 * ADR has ever been in review ([everInReview]) only the current Approver or ADMIN may add, remove or replace the seat; a PUT that
 * leaves it as it is (a Driver handover, a Contributor added) is not judged. The self-approval ban is [requireNotSelfApproval], earlier.
 */
fun requireMayChangeApproverSeat(
    standing: AdrStanding,
    status: AdrStatus,
    everInReview: Boolean,
    stored: List<AdrParticipant>,
    requested: List<AdrParticipantRequest>,
) {
    val unchanged = stored.firstOrNull { it.role == AdrRole.APPROVER }?.userId == requested.approverId()
    if (!unchanged && !mayHandApproverSeat(standing, status, everInReview)) {
        throw ForbiddenException("Only the Approver or an administrator may change who the Approver is once the ADR has been in review")
    }
}
