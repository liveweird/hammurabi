package ch.nokillswit.lenses

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.ForbiddenException
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.infra.paging.optionalBoolean
import ch.nokillswit.infra.paging.optionalEnum
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.infra.paging.toPage
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.href
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/lenses")
class LensesRoute {
    @Serializable
    @Resource("{id}")
    class Id(val parent: LensesRoute = LensesRoute(), val id: UInt)
}

/** The hybrid disclosure policy: a foreign-PRIVATE or unknown id is uniformly 404, a foreign-PUBLIC one the honest 403. */
private fun LensVerdict.orThrow(denial: String) {
    when (this) {
        LensVerdict.OK -> Unit
        LensVerdict.NOT_FOUND -> throw NotFoundException("Lens not found")
        LensVerdict.FORBIDDEN -> throw ForbiddenException(denial)
    }
}

/**
 * `/api/v1/lenses` (V16, `authorization.md`): every route is any-authenticated - lenses are per-user content and the ownership and
 * visibility rules live in the service verdict. Writes audit after the commit with ids only: neither the name nor the filters (a search
 * term is user content) reach a log line.
 */
fun Application.configureLensRoutes() {
    val lensService = attributes[LensServiceKey]

    routing {
        authenticate {
            get<LensesRoute> {
                val caller = call.caller()
                val paging = call.parsePaging(sortable = LENS_SORT_FIELDS, defaultSort = listOf(SortField("name", descending = false)))
                val params = call.request.queryParameters
                val filter = LensListFilter(params.optionalBoolean("mine"), params.optionalEnum<LensVisibility>("visibility"))
                val (items, total) = lensService.list(caller, filter, paging)
                call.respond(HttpStatusCode.OK, paging.toPage(items, total))
            }
            get<LensesRoute.Id> { route ->
                call.respond(HttpStatusCode.OK, lensService.read(route.id, call.caller()).orNotFound("Lens"))
            }
            post<LensesRoute> {
                val caller = call.caller()
                val request = sanitizedLensRequest(call.receive())
                validateLensRequest(request)
                val id = lensService.create(request, caller)
                audit(
                    "lens.created",
                    "byUserId" to caller.userId.toLong(),
                    "lensId" to id.toLong(),
                    "visibility" to request.visibility.name,
                )
                call.response.header(HttpHeaders.Location, call.application.href(LensesRoute.Id(id = id)))
                // Read back after the commit: the creator always sees their own lens.
                call.respond(HttpStatusCode.Created, lensService.read(id, caller).orNotFound("Lens"))
            }
            put<LensesRoute.Id> { route ->
                val caller = call.caller()
                // The ownership verdict comes BEFORE the body is decoded (an undecodable body, an unknown enum or a missing field,
                // is a 400 only for a caller who may change the lens): a cheap unlocked pre-check here, then the same verdict again
                // under the row lock in the service (sanitising and validating happen after it there too, so 403/404 always win).
                lensService.verdictForUpdate(route.id, caller).orThrow("Only the creator may modify a public lens")
                val request = call.receive<LensRequest>()
                lensService.update(route.id, request, caller).orThrow("Only the creator may modify a public lens")
                audit(
                    "lens.updated",
                    "byUserId" to caller.userId.toLong(),
                    "lensId" to route.id.toLong(),
                    "visibility" to request.visibility.name,
                )
                call.respond(HttpStatusCode.NoContent)
            }
            delete<LensesRoute.Id> { route ->
                val caller = call.caller()
                val (verdict, byAdmin) = lensService.delete(route.id, caller)
                verdict.orThrow("Only the creator or an administrator may delete a public lens")
                audit("lens.deleted", "byUserId" to caller.userId.toLong(), "lensId" to route.id.toLong(), "byAdmin" to byAdmin)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
