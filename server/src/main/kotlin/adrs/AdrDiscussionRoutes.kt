package ch.nokillswit.adrs

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.infra.paging.optionalEnum
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.repeatedStrings
import ch.nokillswit.infra.paging.toPage
import ch.nokillswit.plugins.RateLimits
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.href
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing

/** Oldest first: a discussion reads in the order it was said (the `id` ascending tiebreaker is appended by `parsePaging`). */
private val DISCUSSION_DEFAULT_SORT = listOf(SortField("createdAt", descending = false))

/**
 * The ADR discussion surface (`/api/v1/adrs/{id}/discussion`, `.claude/docs/adr-model.md` "Discussion", `authorization.md`). Reads and
 * posts
 * are any-authenticated (an open decision log: a missing ADR is a plain 404 before anything else). A write to an entry resolves the
 * entry (404), runs the coarse guard (403) and only then decodes the body (400), so 403 wins over 400; the service repeats the guard
 * under the ADR row lock and adds the window (409 `discussion-closed`). Every mutation audits after its commit, only when something
 * changed; a denial is the generic `authz.denied`. Declarative and flat, grouped by concern.
 */
fun Application.configureAdrDiscussionRoutes() {
    val adrService = attributes[AdrServiceKey]
    val discussion = attributes[AdrDiscussionServiceKey]

    routing {
        authenticate {
            discussionReads(discussion)
            // The four writes ride the same per-user `adr-write` bucket as the ADR writes (checkup C-SEC-4), before every guard.
            rateLimit(RateLimitName(RateLimits.ADR_WRITE)) {
                discussionPost(adrService, discussion)
                discussionEdit(discussion)
                discussionState(discussion)
            }
        }
    }
}

private fun parseKind(raw: String): DiscussionKind =
    DiscussionKind.entries.firstOrNull { it.name == raw }
        ?: throw BadRequestException("Unknown kind: $raw (allowed: ${DiscussionKind.entries.joinToString { it.name }})")

private fun Route.discussionReads(discussion: AdrDiscussionService) {
    get<AdrsRoute.Id.Discussion> { route ->
        val caller = call.caller()
        val paging = call.parsePaging(sortable = DISCUSSION_SORT_FIELDS, defaultSort = DISCUSSION_DEFAULT_SORT)
        val params = call.request.queryParameters
        val filter = DiscussionFilter(
            kinds = params.repeatedStrings("kind", maxCount = DiscussionKind.entries.size).map(::parseKind),
            state = params.optionalEnum<DiscussionState>("state"),
        )
        val result = discussion.list(route.parent.id, caller, filter, paging) ?: throw NotFoundException("ADR not found")
        call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
    }
    get<AdrsRoute.Id.Discussion.Entry> { route ->
        val entry = discussion.read(route.parent.parent.id, route.entryId, call.caller()).orNotFound("Discussion entry")
        call.respond(HttpStatusCode.OK, entry)
    }
}

private fun Route.discussionPost(adrService: AdrService, discussion: AdrDiscussionService) {
    post<AdrsRoute.Id.Discussion> { route ->
        val adrId = route.parent.id
        val caller = call.caller()
        // Any authenticated user posts: the only guard is that the ADR exists (404), then the body (400), then the window under the
        // lock (409).
        if (!adrService.exists(adrId)) throw NotFoundException("ADR not found")
        val request = sanitizedDiscussionCreate(call.receive())
        validateDiscussionBody(request.body)
        val posted = discussion.post(adrId, caller, request)
        audit(
            "adr.discussion_posted",
            "byUserId" to caller.userId.toLong(),
            "adrId" to adrId.toLong(),
            "number" to posted.number,
            "entryId" to posted.id.toLong(),
            "kind" to posted.kind.name,
        )
        // Read back BEFORE the Location is set: an ADMIN may delete the ADR between the commit and here, which answers 404, never 500.
        val entry = discussion.read(adrId, posted.id, caller).orNotFound("Discussion entry")
        call.response.header(
            HttpHeaders.Location,
            call.application.href(AdrsRoute.Id.Discussion.Entry(AdrsRoute.Id.Discussion(route.parent), posted.id)),
        )
        call.respond(HttpStatusCode.Created, entry)
    }
}

/** The author's edit and the author's or ADMIN's delete (a tombstone). */
private fun Route.discussionEdit(discussion: AdrDiscussionService) {
    put<AdrsRoute.Id.Discussion.Entry> { route ->
        val adrId = route.parent.parent.id
        val caller = call.caller()
        val target = discussion.target(adrId, route.entryId, caller)
        requireMayEditEntry(caller, target.authorId)
        val request = sanitizedDiscussionEdit(call.receive())
        validateDiscussionBody(request.body)
        val edited = discussion.edit(adrId, route.entryId, caller, request)
        if (edited.changed) {
            audit(
                "adr.discussion_edited",
                "byUserId" to caller.userId.toLong(),
                "adrId" to adrId.toLong(),
                "number" to edited.number,
                "entryId" to route.entryId.toLong(),
                "kind" to edited.kind.name,
            )
        }
        call.respond(HttpStatusCode.OK, discussion.read(adrId, route.entryId, caller).orNotFound("Discussion entry"))
    }
    delete<AdrsRoute.Id.Discussion.Entry> { route ->
        val adrId = route.parent.parent.id
        val caller = call.caller()
        val target = discussion.target(adrId, route.entryId, caller)
        requireMayDeleteEntry(caller, target.authorId)
        val deleted = discussion.delete(adrId, route.entryId, caller)
        audit(
            "adr.discussion_deleted",
            "byUserId" to caller.userId.toLong(),
            "adrId" to adrId.toLong(),
            "number" to deleted.number,
            "entryId" to route.entryId.toLong(),
            "kind" to deleted.kind.name,
            "byAdmin" to deleted.byAdmin,
        )
        call.respond(HttpStatusCode.NoContent)
    }
}

/** Answer / resolve / reopen: `PUT .../state` sets the target state (idempotent), the optional note rides a resolution. */
private fun Route.discussionState(discussion: AdrDiscussionService) {
    put<AdrsRoute.Id.Discussion.Entry.State> { route ->
        val adrId = route.parent.parent.parent.id
        val entryId = route.parent.entryId
        val caller = call.caller()
        val target = discussion.target(adrId, entryId, caller)
        requireMayChangeEntryState(target.standing, target.kind, target.authorId)
        val request = sanitizedDiscussionState(call.receive())
        validateDiscussionState(target.kind, request)
        val changed = discussion.setState(adrId, entryId, caller, request)
        if (changed != null) {
            // from == to is a resolution whose NOTE changed (no state move, no history event).
            audit(
                "adr.discussion_state_changed",
                "byUserId" to caller.userId.toLong(),
                "adrId" to adrId.toLong(),
                "number" to changed.number,
                "entryId" to entryId.toLong(),
                "kind" to changed.kind.name,
                "from" to changed.from.name,
                "to" to changed.to.name,
            )
        }
        call.respond(HttpStatusCode.OK, discussion.read(adrId, entryId, caller).orNotFound("Discussion entry"))
    }
}
