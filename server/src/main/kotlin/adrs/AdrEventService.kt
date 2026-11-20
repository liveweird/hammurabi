package ch.nokillswit.adrs

import ch.nokillswit.infra.db.EventLog
import ch.nokillswit.infra.db.EventLogActor
import ch.nokillswit.infra.db.EventLogTable
import ch.nokillswit.infra.db.FieldChange
import ch.nokillswit.infra.db.encodeChanges
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.users.UserService
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

val AdrEventServiceKey = AttributeKey<AdrEventService>("AdrEventService")

/**
 * The kinds of history an ADR keeps, PERSISTED and frozen (`adr_events.event_type`; no CHECK, this enum is the whitelist, the V5 rule): a
 * value is never renamed or removed. Each carries a string-map `params` the SPA renders in the viewer's language (`adr-model.md`
 * "History"): keys, ids and snapshots of names, never rendered text.
 */
@Serializable
enum class AdrEventType {
    /** `{key, name}`; the actor is the Driver. */
    CREATED,

    /** `{changed: "name,context", <field>.from?, <field>.to?}`: Markdown by NAME only, `name` and `expiresOn` with their sides. */
    UPDATED,

    /** `{from, to}` */
    TRANSITIONED,

    /** `{userId, name, role}` */
    PARTICIPANT_ADDED,

    /** `{userId, name, role}` */
    PARTICIPANT_REMOVED,

    /** `{userId, name, from, to}` */
    PARTICIPANT_CHANGED,

    /** `{expiresOn}`: the sweep moved an ACTIVE ADR whose date passed; no actor (the system). */
    EXPIRED,

    /** Reserved for the successor activation of 0.4.0 (a system event); nothing writes it yet. */
    SUPERSEDED,
}

/** One event to append: the [type] and its string-map [params]. Built by the pure `adr*Event` functions below. */
data class AdrEventDescriptor(val type: AdrEventType, val params: Map<String, String>)

fun adrCreatedEvent(number: Int, name: String) = AdrEventDescriptor(AdrEventType.CREATED, mapOf("key" to adrKey(number), "name" to name))

/** An empty [changes] is no event at all: the caller records nothing for a no-op save. */
fun adrUpdatedEvent(changes: List<FieldChange>) = AdrEventDescriptor(AdrEventType.UPDATED, encodeChanges(changes))

fun adrTransitionedEvent(from: AdrStatus, to: AdrStatus) =
    AdrEventDescriptor(AdrEventType.TRANSITIONED, mapOf("from" to from.name, "to" to to.name))

fun adrParticipantAddedEvent(participant: AdrParticipant) = AdrEventDescriptor(
    AdrEventType.PARTICIPANT_ADDED,
    mapOf("userId" to participant.userId.toString(), "name" to participant.name, "role" to participant.role.name),
)

fun adrParticipantRemovedEvent(participant: AdrParticipant) = AdrEventDescriptor(
    AdrEventType.PARTICIPANT_REMOVED,
    mapOf("userId" to participant.userId.toString(), "name" to participant.name, "role" to participant.role.name),
)

fun adrParticipantChangedEvent(userId: UInt, name: String, from: AdrRole, to: AdrRole) = AdrEventDescriptor(
    AdrEventType.PARTICIPANT_CHANGED,
    mapOf("userId" to userId.toString(), "name" to name, "from" to from.name, "to" to to.name),
)

fun adrExpiredEvent(expiresOn: String) = AdrEventDescriptor(AdrEventType.EXPIRED, mapOf("expiresOn" to expiresOn))

/** One history entry: a null `userId` is the system; `userDeleted` marks an actor soft-deleted since (the recorded name stays). */
@Serializable
data class AdrEventResponse(
    val id: UInt,
    val userId: UInt?,
    val userName: String?,
    val userDeleted: Boolean,
    val type: AdrEventType,
    val params: Map<String, String>,
    val createdAt: Long,
)

typealias AdrEventPageResponse = PageResponse<AdrEventResponse>

data class AdrEventListResult(val items: List<AdrEventResponse>, val total: Long)

/**
 * The ADR's history (`adr_events`, V11, append-only): a thin wrapper over the shared [EventLog] mechanics (`infra/db/EventLog.kt`),
 * which takes the acting-user table as a parameter because `infra/` may not name `users/`. An ADR write appends through [record] INSIDE
 * its own transaction, so each event commits or rolls back with the change it describes. There is no create endpoint and nothing here
 * ever updates or deletes a row.
 */
class AdrEventService(database: R2dbcDatabase) {
    object AdrEvents : EventLogTable(
        "adr_events",
        "adr_id",
        AdrService.Adrs,
        EventLogActor(UserService.Users, UserService.Users.name, UserService.Users.markedAsDeleted),
    )

    private val log = EventLog(database, AdrEvents)

    /** Appends one event inside the caller's transaction, stamped [at] (the write's own instant); a null [byUserId] is the system. */
    suspend fun record(adrId: UInt, byUserId: UInt?, event: AdrEventDescriptor, at: Long = nowMillis()): UInt =
        log.createInTransaction(adrId, byUserId, event.type.name, event.params, at)

    /** The ADR's history, newest first by default (the id descending as the same-instant tiebreaker). */
    suspend fun list(adrId: UInt, paging: PageRequest): AdrEventListResult {
        val page = log.listFor(adrId, paging)
        return AdrEventListResult(
            items = page.items.map {
                AdrEventResponse(
                    id = it.id,
                    userId = it.userId,
                    userName = it.userName,
                    userDeleted = it.userDeleted,
                    type = AdrEventType.valueOf(it.type),
                    params = it.params,
                    createdAt = it.createdAt,
                )
            },
            total = page.total,
        )
    }
}
