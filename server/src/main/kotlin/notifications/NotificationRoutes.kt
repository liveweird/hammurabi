package ch.nokillswit.notifications

import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.caller
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.infra.paging.optionalBoolean
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.toPage
import ch.nokillswit.plugins.RateLimits
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

/** `/api/v1/notifications`: the caller's own notices (a list), and the two writes that mark them seen. */
@Serializable
@Resource("/api/v1/notifications")
class NotificationsRoute {
    /** `/api/v1/notifications/{id}`: ONE notice of the caller's; only its `seen` child is a route. */
    @Serializable
    @Resource("{id}")
    class Id(val parent: NotificationsRoute = NotificationsRoute(), val id: UInt) {
        @Serializable
        @Resource("seen")
        class Seen(val parent: Id)
    }

    @Serializable
    @Resource("seen-all")
    class SeenAll(val parent: NotificationsRoute = NotificationsRoute())
}

/**
 * The three notification routes, ALL on the per-USER `read-state` bucket (240/min, `security.md`), mounted INSIDE `authenticate {}` and
 * BEFORE every other check, so a runaway poll or an id sweep spends tokens:
 *
 * - `GET /notifications` (any authenticated caller, no guard): the caller's OWN notices only, so there is nothing to disclose and no ADR
 * to hide; the scope (own rows on live ADRs) is in the service's predicate. A bad `page`, `pageSize`, `sort` or `seen` is a `400`. A
 * read:
 *   no audit line. The `total` is capped at [MAX_COUNTED_NOTICES].
 * - `POST /notifications/{id}/seen`: the update is scoped to the caller's own row. Someone else's notice is the same `404` as one that does
 *   not exist (no `403` to probe with); seen again is a `204`.
 * - `POST /notifications/seen-all`: every unseen notice of the caller, nobody else's; `204` even if none was unseen.
 *
 * The two writes move only the caller's own view of what they have read: view state, not audited (`observability.md`).
 */
fun Application.configureNotificationRoutes() {
    val notifications = attributes[NotificationServiceKey]
    routing {
        authenticate {
            rateLimit(RateLimitName(RateLimits.READ_STATE)) {
                get<NotificationsRoute> {
                    val caller = call.caller()
                    val paging = call.parsePaging(sortable = NOTIFICATION_SORT_FIELDS, defaultSort = listOf(SortField("id",
                        descending = true)))
                    val seen = call.request.queryParameters.optionalBoolean("seen")
                    val result = notifications.list(caller.userId, seen, paging)
                    call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
                }
                post<NotificationsRoute.Id.Seen> { route ->
                    if (!notifications.markSeen(call.caller().userId, route.parent.id, nowMillis())) {
                        throw NotFoundException("Notification not found")
                    }
                    call.respond(HttpStatusCode.NoContent)
                }
                post<NotificationsRoute.SeenAll> {
                    notifications.markAllSeen(call.caller().userId, nowMillis())
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}
