package ch.nokillswit.tags

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.infra.revision.expectedRevision
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
@Resource("/api/v1/tag-categories")
class TagCategoriesRoute {
    @Serializable
    @Resource("{id}")
    class Id(val parent: TagCategoriesRoute = TagCategoriesRoute(), val id: UInt)
}

/**
 * The tag registry (`/api/v1/tag-categories`, Toadie's): the read is any-authenticated (the ADR editor's tag picker and the Tags page),
 * every mutation ADMIN-only with the guard BEFORE the header, the body and the id lookup, so a non-admin probe gets a uniform 403 whether
 * or not the id exists (guard-before-read, as for teams). The PUT and the DELETE take `X-Expected-Revision` (400 missing, 404 unknown id,
 * then 409 stale). Every mutation audits after its commit.
 */
fun Application.configureTagCategoryRoutes() {
    val service = attributes[TagCategoryServiceKey]

    routing {
        authenticate {
            get<TagCategoriesRoute> {
                call.caller()
                call.respond(HttpStatusCode.OK, TagCategoryList(service.list()))
            }
            post<TagCategoriesRoute> {
                val caller = call.caller()
                requireAdmin(caller)
                val request = sanitizedTagCategoryRequest(call.receive())
                validateTagCategoryRequest(request)
                val created = service.create(request)
                audit(
                    "tag_category.created",
                    "byUserId" to caller.userId.toLong(),
                    "categoryId" to created.id.toLong(),
                    "name" to created.name,
                    "tags" to created.tags.size,
                )
                call.response.header(HttpHeaders.Location, call.application.href(TagCategoriesRoute.Id(id = created.id)))
                call.respond(HttpStatusCode.Created, created)
            }
            put<TagCategoriesRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val expected = call.expectedRevision()
                val request = sanitizedTagCategoryRequest(call.receive())
                validateTagCategoryRequest(request)
                service.update(route.id, expected, request).orNotFound("Tag category")
                audit(
                    "tag_category.updated",
                    "byUserId" to caller.userId.toLong(),
                    "categoryId" to route.id.toLong(),
                    "name" to request.name,
                    "tags" to request.tags.size,
                )
                call.respond(HttpStatusCode.NoContent)
            }
            delete<TagCategoriesRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                service.delete(route.id, call.expectedRevision()).orNotFound("Tag category")
                audit("tag_category.deleted", "byUserId" to caller.userId.toLong(), "categoryId" to route.id.toLong())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
