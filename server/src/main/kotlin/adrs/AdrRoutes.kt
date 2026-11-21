package ch.nokillswit.adrs

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.infra.db.EVENT_LOG_DEFAULT_SORT
import ch.nokillswit.infra.db.EVENT_LOG_SORT_FIELDS
import ch.nokillswit.infra.paging.optionalBoolean
import ch.nokillswit.infra.paging.optionalEnum
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.optionalUInt
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.repeatedStrings
import ch.nokillswit.infra.paging.toPage
import ch.nokillswit.infra.revision.expectedRevision
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.resources.Resource
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
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
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/adrs")
class AdrsRoute {
    /** `GET /adrs/export` (0.8.0): a ZIP of Markdown files for the ADRs matching the list's filters; a literal, so it beats `{id}`. */
    @Serializable
    @Resource("export")
    class Export(val parent: AdrsRoute = AdrsRoute())

    /** `GET /adrs/overview` (0.9.0): the decision log; a literal, so it beats `{id}`. */
    @Serializable
    @Resource("overview")
    class Overview(val parent: AdrsRoute = AdrsRoute())

    @Serializable
    @Resource("{id}")
    class Id(val parent: AdrsRoute = AdrsRoute(), val id: UInt) {
        @Serializable
        @Resource("transitions")
        class Transitions(val parent: Id)

        @Serializable
        @Resource("participants")
        class Participants(val parent: Id)

        @Serializable
        @Resource("tags")
        class Tags(val parent: Id)

        @Serializable
        @Resource("supersedes")
        class Supersedes(val parent: Id)

        @Serializable
        @Resource("successor")
        class Successor(val parent: Id)

        @Serializable
        @Resource("events")
        class Events(val parent: Id)

        /** `GET /adrs/{id}/export` (0.8.0): the ADR as one Markdown file. */
        @Serializable
        @Resource("export")
        class Export(val parent: Id)

        @Serializable
        @Resource("discussion")
        class Discussion(val parent: Id) {
            @Serializable
            @Resource("{entryId}")
            class Entry(val parent: Discussion, val entryId: UInt) {
                @Serializable
                @Resource("state")
                class State(val parent: Entry)
            }
        }
    }
}

/**
 * The ADR surface (`/api/v1/adrs`, `.claude/docs/adr-model.md`, `authorization.md`): reads are any-authenticated (an open decision log, so
 * a missing ADR is a plain 404 BEFORE the guard - read-before-guard, nothing to disclose); every write takes the caller's standing
 * (404), the coarse DACI guard (403), the `X-Expected-Revision` header where it has one (400), and only then decodes the body (400), so
 * 403 wins over 400. The service repeats the guards under the row lock and adds the per-target/per-field verdicts and the state
 * conflicts. Every mutation audits after its commit, only when something changed. Declarative and flat, grouped by concern.
 */
fun Application.configureAdrRoutes() {
    val adrService = attributes[AdrServiceKey]
    val listService = attributes[AdrListServiceKey]
    val eventService = attributes[AdrEventServiceKey]

    routing {
        authenticate {
            adrReads(adrService, listService)
            adrCreate(adrService)
            adrDocument(adrService)
            adrLifecycle(adrService)
            adrParticipants(adrService)
            adrOrganise(adrService)
            adrHistory(adrService, eventService)
        }
    }
}

private fun Route.adrReads(adrService: AdrService, listService: AdrListService) {
    get<AdrsRoute> {
        val caller = call.caller()
        val params = call.request.queryParameters
        val filter = adrFilterDocument(params).normalized().toListFilter()
        // A search ranks by relevance unless the caller sorts; `sort=relevance` only means something with `text` to rank.
        val paging = call.parsePaging(
            sortable = ADR_SORT_FIELDS,
            defaultSort = if (filter.text == null) ADR_DEFAULT_SORT else ADR_SEARCH_DEFAULT_SORT,
        )
        if (filter.text == null && paging.sort.any { it.name == SORT_RELEVANCE }) throw BadRequestException("sort=relevance needs text")
        if (filter.text != null && paging.pageSize > MAX_SEARCH_PAGE_SIZE) {
            throw BadRequestException("pageSize must be between 1 and $MAX_SEARCH_PAGE_SIZE with text")
        }
        val result = listService.list(filter, paging, caller.userId)
        call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
    }
    get<AdrsRoute.Id> { route ->
        call.respond(HttpStatusCode.OK, adrService.read(route.id, call.caller()).orNotFound("ADR"))
    }
}

/** The list's query string as a filter document (repeated keys read as lists, a repeated scalar is a `400`); [normalized] validates it. */
internal fun adrFilterDocument(params: Parameters): AdrFilterDocument = AdrFilterDocument(
    status = params.repeatedStrings("status", maxCount = AdrStatus.entries.size).map(::parseStatus),
    q = params.optionalString("q"),
    text = params.optionalString("text"),
    participant = params.optionalUInt("participant"),
    role = params.optionalEnum<AdrRole>("role"),
    // Bounded in count here (`repeatedStrings`: more than MAX_TAG_FILTERS distinct values is a 400);
    // `normalized` bounds each name's length and form.
    tag = params.repeatedStrings("tag", maxCount = MAX_TAG_FILTERS),
    createdFrom = params.optionalString("createdFrom"),
    createdTo = params.optionalString("createdTo"),
    updatedFrom = params.optionalString("updatedFrom"),
    updatedTo = params.optionalString("updatedTo"),
    expiresBefore = params.optionalString("expiresBefore"),
    hasOpenIssues = params.optionalBoolean("hasOpenIssues"),
    supersedes = params.optionalBoolean("supersedes"),
    supersededBy = params.optionalBoolean("supersededBy"),
)

private fun parseStatus(raw: String): AdrStatus =
    AdrStatus.entries.firstOrNull { it.name == raw }
        ?: throw BadRequestException("Unknown status: $raw (allowed: ${AdrStatus.entries.joinToString { it.name }})")

private fun Route.adrCreate(adrService: AdrService) {
    post<AdrsRoute> {
        val caller = call.caller()
        val request = sanitizedAdrCreate(call.receive())
        validateAdrCreate(request)
        val created = adrService.create(caller, request)
        audit(
            "adr.created",
            "byUserId" to caller.userId.toLong(),
            "adrId" to created.id.toLong(),
            "number" to created.number,
            "participants" to request.participants.size,
        )
        // Read back BEFORE the Location is set: an ADMIN may delete the ADR between the commit and here, which answers 404, never 500.
        val view = adrService.read(created.id, caller).orNotFound("ADR")
        call.response.header(HttpHeaders.Location, call.application.href(AdrsRoute.Id(id = created.id)))
        call.respond(HttpStatusCode.Created, view)
    }
}

/** The document PUT (content + expiry) and the ADMIN soft delete. */
private fun Route.adrDocument(adrService: AdrService) {
    put<AdrsRoute.Id> { route ->
        val caller = call.caller()
        requireMayEdit(adrService.standing(route.id, caller).orNotFound("ADR"))
        val expected = call.expectedRevision()
        val request = sanitizedAdrUpdate(call.receive())
        validateAdrUpdate(request)
        val change = adrService.update(route.id, caller, expected, request)
        if (change != null) {
            audit(
                "adr.updated",
                "byUserId" to caller.userId.toLong(),
                "adrId" to change.id.toLong(),
                "number" to change.number,
                "revision" to change.revision,
                "fieldsChanged" to change.changed.joinToString(","),
            )
        }
        call.respond(HttpStatusCode.NoContent)
    }
    delete<AdrsRoute.Id> { route ->
        val caller = call.caller()
        requireAdmin(caller)
        adrService.delete(route.id).orNotFound("ADR")
        audit("adr.deleted", "byUserId" to caller.userId.toLong(), "adrId" to route.id.toLong())
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun Route.adrLifecycle(adrService: AdrService) {
    post<AdrsRoute.Id.Transitions> { route ->
        val id = route.parent.id
        val caller = call.caller()
        requireMayTransition(adrService.standing(id, caller).orNotFound("ADR"))
        val expected = call.expectedRevision()
        val request = call.receive<AdrTransitionRequest>()
        val outcome = adrService.transition(id, caller, expected, request.to, request.reason)
        audit(
            "adr.transitioned",
            "byUserId" to caller.userId.toLong(),
            "adrId" to outcome.id.toLong(),
            "number" to outcome.number,
            "revision" to outcome.revision,
            "from" to outcome.from.name,
            "to" to outcome.to.name,
            "reasoned" to outcome.reasoned,
        )
        // The activation's other half: the predecessor it replaced moved to SUPERSEDED in the same transaction.
        outcome.superseded?.let {
            audit(
                "adr.superseded",
                "byUserId" to caller.userId.toLong(),
                "adrId" to it.id.toLong(),
                "number" to it.number,
                "revision" to it.revision,
                "successorId" to outcome.id.toLong(),
                "successorNumber" to outcome.number,
            )
        }
        // A concurrent ADMIN delete after the commit reads as a missing ADR (404), not a server fault.
        call.respond(HttpStatusCode.OK, adrService.read(id, caller).orNotFound("ADR"))
    }
}

private fun Route.adrParticipants(adrService: AdrService) {
    put<AdrsRoute.Id.Participants> { route ->
        val id = route.parent.id
        val caller = call.caller()
        val standing = adrService.standing(id, caller).orNotFound("ADR")
        requireMayManage(standing)
        val expected = call.expectedRevision()
        val request = call.receive<AdrParticipantsRequest>()
        // The self-approval ban needs only the body, so it beats the set's shape (403 before 400); the rest is judged under the lock.
        requireNotSelfApproval(standing, request.participants)
        validateParticipantSet(request.participants)
        val change = adrService.replaceParticipants(id, caller, expected, request)
        if (change != null) auditParticipants(caller, change)
        call.respond(HttpStatusCode.NoContent)
    }
}

/**
 * The tags PUT, the successor-link PUT and the successor DETACH: the same standing / coarse guard / header / body order as the
 * participants. The detach has neither header nor body: it names the pending successor by the route (there is at most one) and the
 * service judges it under the locks.
 */
private fun Route.adrOrganise(adrService: AdrService) {
    put<AdrsRoute.Id.Tags> { route ->
        val id = route.parent.id
        val caller = call.caller()
        requireMayOrganise(adrService.standing(id, caller).orNotFound("ADR"))
        val expected = call.expectedRevision()
        val request = normalizedAdrTags(call.receive())
        validateAdrTags(request)
        adrService.replaceTags(id, caller, expected, request)?.let {
            audit(
                "adr.tags_changed",
                "byUserId" to caller.userId.toLong(),
                "adrId" to it.id.toLong(),
                "number" to it.number,
                "revision" to it.revision,
                "added" to it.diff.added.joinToString(","),
                "removed" to it.diff.removed.joinToString(","),
            )
        }
        call.respond(HttpStatusCode.NoContent)
    }
    put<AdrsRoute.Id.Supersedes> { route ->
        val id = route.parent.id
        val caller = call.caller()
        requireMayOrganise(adrService.standing(id, caller).orNotFound("ADR"))
        val expected = call.expectedRevision()
        val request = call.receive<AdrSupersedesRequest>()
        adrService.setSupersedes(id, caller, expected, request)?.let {
            audit(
                "adr.successor_changed",
                "byUserId" to caller.userId.toLong(),
                "adrId" to it.id.toLong(),
                "number" to it.number,
                "revision" to it.revision,
                "supersedesFrom" to it.change.from?.id?.toLong(),
                "supersedesTo" to it.change.to?.id?.toLong(),
            )
        }
        call.respond(HttpStatusCode.NoContent)
    }
    delete<AdrsRoute.Id.Successor> { route ->
        val id = route.parent.id
        val caller = call.caller()
        requireMayOrganise(adrService.standing(id, caller).orNotFound("ADR"))
        val change = adrService.detachSuccessor(id, caller)
        audit(
            "adr.successor_detached",
            "byUserId" to caller.userId.toLong(),
            "adrId" to change.id.toLong(),
            "number" to change.number,
            "successorId" to change.successor.id.toLong(),
            "successorNumber" to change.successorNumber,
            "successorRevision" to change.successorRevision,
        )
        call.respond(HttpStatusCode.NoContent)
    }
}

/** One line per participants write: who joined, left or changed role, as `userId:ROLE` (never a name), so a Driver handover is visible. */
private fun auditParticipants(caller: CallerPrincipal, change: AdrParticipantsChange) {
    val diff = change.diff
    audit(
        "adr.participants_changed",
        "byUserId" to caller.userId.toLong(),
        "adrId" to change.id.toLong(),
        "number" to change.number,
        "revision" to change.revision,
        "added" to diff.added.joinToString(",") { "${it.userId}:${it.role}" },
        "removed" to diff.removed.joinToString(",") { "${it.userId}:${it.role}" },
        "changed" to diff.changed.joinToString(",") { "${it.userId}:${it.from}>${it.to}" },
    )
}

private fun Route.adrHistory(adrService: AdrService, eventService: AdrEventService) {
    get<AdrsRoute.Id.Events> { route ->
        call.caller()
        val paging = call.parsePaging(sortable = EVENT_LOG_SORT_FIELDS, defaultSort = EVENT_LOG_DEFAULT_SORT)
        if (!adrService.exists(route.parent.id)) throw NotFoundException("ADR not found")
        val page = eventService.list(route.parent.id, paging)
        call.respond(HttpStatusCode.OK, paging.toPage(page.items, page.total))
    }
}
