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

/** Who may edit the document at all (coarse, before the body): the Driver, a Contributor, the Approver (the expiry date), ADMIN. */
fun requireMayEdit(standing: AdrStanding) {
    if (!standing.holds(AdrRole.DRIVER, AdrRole.CONTRIBUTOR, AdrRole.APPROVER)) {
        throw ForbiddenException("Only the Driver, a Contributor, the Approver or an administrator may edit this ADR")
    }
}

/** Who may move the lifecycle at all (coarse, before the body); [requireMayRequest] is the per-target verdict. */
fun requireMayTransition(standing: AdrStanding) {
    if (!standing.holds(AdrRole.DRIVER, AdrRole.APPROVER)) {
        throw ForbiddenException("Only the Driver, the Approver or an administrator may change this ADR's status")
    }
}

/** Who may replace the participants (and, with `canEditExpiry`, set the expiry date): the Driver, the Approver, ADMIN. */
fun requireMayManage(standing: AdrStanding) {
    if (!standing.holds(AdrRole.DRIVER, AdrRole.APPROVER)) {
        throw ForbiddenException("Only the Driver, the Approver or an administrator may change this ADR's participants")
    }
}

/** The role a user needs to REQUEST a move into [target]; null = no role rule (the lifecycle map decides, a 409). */
private fun roleToRequest(target: AdrStatus): AdrRole? = when (target) {
    AdrStatus.SOLUTION_DRAFT, AdrStatus.IN_REVIEW, AdrStatus.CANCELLED -> AdrRole.DRIVER
    AdrStatus.ACTIVE, AdrStatus.WITHDRAWN -> AdrRole.APPROVER
    AdrStatus.PROBLEM_RAISED, AdrStatus.EXPIRED, AdrStatus.SUPERSEDED -> null
}

private fun mayRequest(standing: AdrStanding, target: AdrStatus): Boolean =
    standing.isAdmin || roleToRequest(target).let { it == null || it == standing.role }

/** The per-target verdict: the Driver (or ADMIN) moves forward and cancels, the Approver (or ADMIN) activates or withdraws. */
fun requireMayRequest(standing: AdrStanding, target: AdrStatus) {
    if (!mayRequest(standing, target)) {
        throw ForbiddenException("Your role on this ADR does not allow moving it to $target")
    }
}

/** The moves THIS caller may request from [from]: the map's edges the system does not own, filtered by the verdict. */
fun allowedTransitions(standing: AdrStanding, from: AdrStatus): List<AdrStatus> =
    AdrStatus.entries.filter { from.canRequest(it) && mayRequest(standing, it) }

/** Content (name, Markdown fields) is editable in a pre-active status by the Driver, a Contributor or ADMIN. */
fun canEditContent(standing: AdrStanding, status: AdrStatus): Boolean =
    status.contentEditable && standing.holds(AdrRole.DRIVER, AdrRole.CONTRIBUTOR)

/** The expiry date changes in any non-terminal status, by the Driver, the Approver or ADMIN. */
fun canEditExpiry(standing: AdrStanding, status: AdrStatus): Boolean =
    !status.terminal && standing.holds(AdrRole.DRIVER, AdrRole.APPROVER)

fun canEdit(standing: AdrStanding, status: AdrStatus): Boolean = canEditContent(standing, status) || canEditExpiry(standing, status)

/** The participants are replaceable on any non-terminal ADR by the Driver, the Approver or ADMIN. */
fun canManageParticipants(standing: AdrStanding, status: AdrStatus): Boolean =
    !status.terminal && standing.holds(AdrRole.DRIVER, AdrRole.APPROVER)
