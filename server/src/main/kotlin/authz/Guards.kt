package ch.nokillswit.authz

import ch.nokillswit.users.UserRole

fun CallerPrincipal.isAdmin(): Boolean = UserRole.ADMIN in roles

fun requireAdmin(caller: CallerPrincipal) {
    if (!caller.isAdmin()) throw ForbiddenException("Admin role required")
}

fun requireSelfOrAdmin(caller: CallerPrincipal, targetUserId: UInt) {
    if (caller.isAdmin()) return
    if (caller.userId != targetUserId) throw ForbiddenException("Caller may only act on their own user")
}

/**
 * ADMIN, or the team's current ACTIVE manager (V6). [isManager] answers false for a missing or deleted team too,
 * so run this as the FIRST guard of a team-scoped write, before any read: every other caller gets a uniform 403
 * whether or not the id exists. The lookup is injected so the guard stays DB-free (`GuardsTest` is pure). No route consumes it
 * yet; a later team-scoped write will, re-checking a non-admin's grant inside its locked write transaction.
 */
suspend fun requireTeamManagerOrAdmin(
    caller: CallerPrincipal,
    teamId: UInt,
    isManager: suspend (teamId: UInt, userId: UInt) -> Boolean,
) {
    if (caller.isAdmin()) return
    if (!isManager(teamId, caller.userId)) throw ForbiddenException("Team manager or admin role required")
}
