package ch.nokillswit.auth

import ch.nokillswit.audit.audit
import ch.nokillswit.infra.crypto.hashPassword
import ch.nokillswit.infra.crypto.verifyPassword
import ch.nokillswit.authz.ForbiddenException
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireSelfOrAdmin
import ch.nokillswit.plugins.RateLimits
import ch.nokillswit.users.PasswordUpdateRequest
import ch.nokillswit.users.UserService
import ch.nokillswit.users.UserServiceKey
import ch.nokillswit.users.Users
import ch.nokillswit.users.validatePassword
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing

/**
 * The self password change lives in `auth/` (not beside the other `/users` routes) because it is a credential check: it shares the
 * [LoginThrottle] counter with `/login`, so `users/` never imports `auth/` (the import cycle of checkup C-DRY-5). Registered in
 * `application.yaml` right after the user routes; the bucket (per-USER `password-change`, before the guards) and the guards are as
 * documented in `security.md`.
 */
fun Application.configurePasswordChangeRoutes() {
    val userService = attributes[UserServiceKey]
    // Published by configureLoginThrottle, which application.yaml loads before this module.
    val loginThrottle = attributes[LoginThrottleKey]
    routing {
        authenticate {
            // The self change verifies a bcrypt per request: per-USER bucket (RateLimits.PASSWORD_CHANGE) before the guards, and the
            // per-account lockout inside the handler.
            rateLimit(RateLimitName(RateLimits.PASSWORD_CHANGE)) { passwordChange(userService, loginThrottle) }
        }
    }
}

/** `password.change_rejected_locked`: a self password change refused because the account is locked out (`security.md`). */
private const val EVENT_PASSWORD_CHANGE_REJECTED_LOCKED = "password.change_rejected_locked"

/**
 * `PUT /users/{id}/password`. Mounted by [configurePasswordChangeRoutes] inside `authenticate {}` and the per-USER `password-change`
 * bucket.
 *
 * Changing one's OWN password verifies `currentPassword` (a bcrypt per request), so the route is a guessing oracle for whoever
 * holds a session (a stolen token) and a way to occupy the small default dispatcher. Two bounds: the bucket (per request, before
 * the guards) and the per-account lockout, the SAME counter `/login` uses ([LoginThrottle]): a wrong `currentPassword` is a failed
 * sign-in attempt, N of them (across login, the MFA code and this route) lock the account. A locked account's change answers
 * what `/login` answers, the specific `429`, BEFORE any bcrypt. An admin resetting somebody ELSE's password verifies nothing, so
 * it neither counts nor is refused by the target's lock.
 */
private fun Route.passwordChange(userService: UserService, loginThrottle: LoginThrottle) {
    put<Users.Id.Password> { route ->
        val caller = call.caller()
        requireSelfOrAdmin(caller, route.parent.id)
        val req = call.receive<PasswordUpdateRequest>()
        // Changing one's OWN password always requires the current one (even for an admin);
        // an admin resetting somebody else's does not. Read before update so a wrong
        // current password never mutates anything. Checked BEFORE the length validation
        // so 403 wins over 400 (the convention everywhere else).
        if (caller.userId == route.parent.id) {
            val existing = userService.read(route.parent.id).orNotFound("User")
            val guess = AccountGuess(
                loginThrottle,
                existing.email,
                listOf("userId" to route.parent.id.toLong(), "source" to "password_change"),
                lockedEvent = EVENT_PASSWORD_CHANGE_REJECTED_LOCKED,
                // The sibling `password.change_denied`'s fields: the caller changing their OWN password is both.
                lockedFields = listOf("targetUserId" to route.parent.id.toLong(), "byUserId" to caller.userId.toLong()),
                // The caller is signed in, so the account exists: its wrong guesses count even at a full lockout store.
                knownAccount = true,
            )
            guess.requireNotLocked()
            // A MISSING current password is no guess (no bcrypt ran, nothing was tried), so it is refused without counting;
            // only a wrong one is a failed attempt.
            val matches = req.currentPassword != null && verifyPassword(req.currentPassword, existing.passwordHash)
            if (!matches) {
                audit(
                    "password.change_denied",
                    "targetUserId" to route.parent.id.toLong(),
                    "byUserId" to caller.userId.toLong(),
                    "reason" to "wrong_current_password",
                )
                if (req.currentPassword != null) guess.recordFailure()
                throw ForbiddenException("Current password is missing or incorrect")
            }
            // A lock that tripped between the preflight and now (another request's failure) still wins, as at login.
            guess.recordSuccess()
        }
        validatePassword(req.password)
        userService.updatePassword(route.parent.id, hashPassword(req.password)).orNotFound("User")
        audit(
            "password.changed",
            "targetUserId" to route.parent.id.toLong(),
            "byUserId" to caller.userId.toLong(),
            "selfChange" to (caller.userId == route.parent.id),
        )
        call.respond(HttpStatusCode.NoContent)
    }
}
