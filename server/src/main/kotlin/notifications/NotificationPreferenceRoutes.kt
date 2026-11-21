package ch.nokillswit.notifications

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireSelfOrAdmin
import ch.nokillswit.infra.db.R2dbcDatabaseKey
import ch.nokillswit.infra.mail.mailer
import ch.nokillswit.plugins.RateLimits
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

// `/api/v1/users/{id}/notification-preferences`: a person's switches for the bell and the digest email (one table, so the route lives
// here).
@Serializable
@Resource("/api/v1/users/{id}/notification-preferences")
class UserNotificationPreferencesRoute(val id: UInt)

/**
 * `GET` and `PUT /users/{id}/notification-preferences`, both on the per-USER `read-state` bucket (mounted before the guards, so a refused
 * call spends a token). Both: `requireSelfOrAdmin` FIRST (`403` before anything is read), then the body (`400`: an unknown kind or channel
 * fails to decode, a repeated pair), then the target (`404` for an unknown or soft-deleted user, so an ADMIN asking for an id learns the
 * same as for any other). The PUT is a wholesale replace and a `204` whether or not it changed anything; it is audited only when the stored
 * set really changed, with the set before and after (`from` is the one read under the row lock). The in-app switch changes which notices
 * are MADE from then on, the email switch what the next digest does; neither deletes a notice already made.
 */
fun Application.configureNotificationPreferenceRoutes() {
    // Built here, not in `Database.kt`: nothing else needs the instance (the digest reads the switches through `emailDisabledKinds`).
    val preferences = NotificationPreferenceService(attributes[R2dbcDatabaseKey])
    val emailAvailable = mailer() != null
    routing {
        authenticate {
            rateLimit(RateLimitName(RateLimits.READ_STATE)) {
                get<UserNotificationPreferencesRoute> { route ->
                    requireSelfOrAdmin(call.caller(), route.id)
                    val disabled = preferences.read(route.id).orNotFound("User")
                    call.respond(HttpStatusCode.OK, preferencesResponse(disabled, emailAvailable))
                }
                put<UserNotificationPreferencesRoute> { route ->
                    val caller = call.caller()
                    requireSelfOrAdmin(caller, route.id)
                    val requested = call.receive<NotificationPreferencesUpdateRequest>().disabled
                    // Validated here (a 400 wins over the target's 404) and again by the service, which takes the raw list.
                    val wanted = validateNotificationPreferences(requested)
                    val before = preferences.replace(route.id, requested).orNotFound("User")
                    if (before != wanted) {
                        audit(
                            "user.notification_preferences_changed",
                            "byUserId" to caller.userId.toLong(),
                            "targetUserId" to route.id.toLong(),
                            "from" to before.joinedForAudit(),
                            "to" to wanted.joinedForAudit(),
                        )
                    }
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}
