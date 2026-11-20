package ch.nokillswit.adrs

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.infra.db.EVENT_LOG_DEFAULT_SORT
import ch.nokillswit.infra.db.EVENT_LOG_SORT_FIELDS
import ch.nokillswit.infra.paging.optionalEnum
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.optionalUInt
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.repeatedStrings
import ch.nokillswit.infra.paging.toPage
import ch.nokillswit.infra.revision.expectedRevision
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
        @Resource("events")
        class Events(val parent: Id)
    }
}

/** The longest `q` the list takes, in characters (the shared free-text bound, API-LIST-005). */
const val MAX_ADR_QUERY_LENGTH = 100

/**
 * The ADR surface (`/api/v1/adrs`, `.claude/docs/adr-model.md`, `authorization.md`): reads are any-authenticated (an open decision log, so
 * a missing ADR is a plain 404 BEFORE the guard - read-before-guard, nothing to disclose); every write takes the caller's standing
 * (404), the coarse DACI guard (403), the `X-Expected-Revision` header where it has one (400), and only then decodes the body (400), so
 * 403 wins over 400. The service repeats the guards under the row lock and adds the per-target/per-field verdicts and the state
 * conflicts. Every mutation audits after its commit, only when something changed. Declarative and flat, grouped by concern.
 */
fun Application.configureAdrRoutes() {
    val adrService = attributes[AdrServiceKey]
    val eventService = attributes[AdrEventServiceKey]

    routing {
        authenticate {
            adrReads(adrService)
            adrCreate(adrService)
            adrDocument(adrService)
            adrLifecycle(adrService)
            adrParticipants(adrService)
            adrHistory(adrService, eventService)
        }
    }
}

private fun Route.adrReads(adrService: AdrService) {
    get<AdrsRoute> {
        call.caller()
        val paging = call.parsePaging(sortable = ADR_SORT_FIELDS, defaultSort = ADR_DEFAULT_SORT)
        val params = call.request.queryParameters
        val participantUserId = params.optionalUInt("participant")
        val participantRole = params.optionalEnum<AdrRole>("role")
        if (participantRole != null && participantUserId == null) throw BadRequestException("role needs participant")
        val q = params.optionalString("q")?.trim()
        if (q != null && q.codePointCount(0, q.length) > MAX_ADR_QUERY_LENGTH) {
            throw BadRequestException("q must be at most $MAX_ADR_QUERY_LENGTH characters")
        }
        val filter = AdrListFilter(
            statuses = params.repeatedStrings("status", maxCount = AdrStatus.entries.size).map(::parseStatus),
            q = q,
            participantUserId = participantUserId,
            participantRole = participantRole,
        )
        val result = adrService.list(filter, paging)
        call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
    }
    get<AdrsRoute.Id> { route ->
        call.respond(HttpStatusCode.OK, adrService.read(route.id, call.caller()).orNotFound("ADR"))
    }
}

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
        val outcome = adrService.transition(id, caller, expected, request.to)
        audit(
            "adr.transitioned",
            "byUserId" to caller.userId.toLong(),
            "adrId" to outcome.id.toLong(),
            "number" to outcome.number,
            "revision" to outcome.revision,
            "from" to outcome.from.name,
            "to" to outcome.to.name,
        )
        // A concurrent ADMIN delete after the commit reads as a missing ADR (404), not a server fault.
        call.respond(HttpStatusCode.OK, adrService.read(id, caller).orNotFound("ADR"))
    }
}

private fun Route.adrParticipants(adrService: AdrService) {
    put<AdrsRoute.Id.Participants> { route ->
        val id = route.parent.id
        val caller = call.caller()
        requireMayManage(adrService.standing(id, caller).orNotFound("ADR"))
        val expected = call.expectedRevision()
        val request = call.receive<AdrParticipantsRequest>()
        validateParticipantSet(request.participants)
        val change = adrService.replaceParticipants(id, caller, expected, request)
        if (change != null) auditParticipants(caller, change)
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
