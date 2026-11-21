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

    /**
     * `{successorId, key, name}`: written on the PREDECESSOR when its successor became `ACTIVE`; the actor is the user who activated the
     * successor (the move is automatic, but a person caused it).
     */
    SUPERSEDED,

    /** `{added: "a,b", removed: "c"}` (each key only when non-empty): the tag names a save added and removed. */
    TAGS_CHANGED,

    /** `{predecessorId, key, name}`: the ADR now replaces that one (set while pre-active; it takes effect on activation). */
    SUCCESSOR_LINKED,

    /**
     * `{predecessorId, key, name}`: the ADR no longer replaces that one. Written on the PREDECESSOR as well when its owner detached the
     * pending successor, then `{successorId, key, name}` (the SPA tells the two sides apart by the param).
     */
    SUCCESSOR_UNLINKED,

    /** `{predecessorId, key, name}`: written on the SUCCESSOR in the same transaction that superseded the ADR it replaces. */
    SUCCESSOR_ACTIVATED,

    /**
     * The discussion (0.3.0, `adr-model.md` "Discussion"), each `{entryId, kind}` (never the text; the entry is read through the discussion
     * endpoint): a comment, a question asked, an issue raised, an escalation raised; a question answered or reopened; an issue or
     * escalation resolved or reopened. Edits and deletes of an entry are audit-only (the entry carries `editedAt` / the tombstone).
     */
    COMMENTED,
    QUESTION_ASKED,
    QUESTION_ANSWERED,
    QUESTION_REOPENED,
    ISSUE_RAISED,
    ESCALATED,
    ISSUE_RESOLVED,
    ISSUE_REOPENED,
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

fun adrTagsChangedEvent(added: List<String>, removed: List<String>) = AdrEventDescriptor(
    AdrEventType.TAGS_CHANGED,
    buildMap {
        if (added.isNotEmpty()) put("added", added.joinToString(","))
        if (removed.isNotEmpty()) put("removed", removed.joinToString(","))
    },
)

private fun successorEvent(type: AdrEventType, other: AdrRef, otherIsSuccessor: Boolean = false) = AdrEventDescriptor(
    type,
    mapOf(
        (if (otherIsSuccessor) "successorId" else "predecessorId") to other.id.toString(),
        "key" to other.key,
        "name" to other.name,
    ),
)

fun adrSuccessorLinkedEvent(predecessor: AdrRef) = successorEvent(AdrEventType.SUCCESSOR_LINKED, predecessor)

fun adrSuccessorUnlinkedEvent(predecessor: AdrRef) = successorEvent(AdrEventType.SUCCESSOR_UNLINKED, predecessor)

/** The event on the PREDECESSOR when its owner detached [successor], the pending ADR that was set to replace it. */
fun adrSuccessorDetachedEvent(successor: AdrRef) = successorEvent(AdrEventType.SUCCESSOR_UNLINKED, successor, otherIsSuccessor = true)

fun adrSuccessorActivatedEvent(predecessor: AdrRef) = successorEvent(AdrEventType.SUCCESSOR_ACTIVATED, predecessor)

/** The event on the superseded ADR: [successor] is the one that just became `ACTIVE`. */
fun adrSupersededEvent(successor: AdrRef) = successorEvent(AdrEventType.SUPERSEDED, successor, otherIsSuccessor = true)

fun adrExpiredEvent(expiresOn: String) = AdrEventDescriptor(AdrEventType.EXPIRED, mapOf("expiresOn" to expiresOn))

private fun discussionEvent(type: AdrEventType, entryId: UInt, kind: DiscussionKind) =
    AdrEventDescriptor(type, mapOf("entryId" to entryId.toString(), "kind" to kind.name))

/** An entry was posted: the event type follows its kind. */
fun adrDiscussionPostedEvent(entryId: UInt, kind: DiscussionKind) = discussionEvent(
    when (kind) {
        DiscussionKind.COMMENT -> AdrEventType.COMMENTED
        DiscussionKind.QUESTION -> AdrEventType.QUESTION_ASKED
        DiscussionKind.ISSUE -> AdrEventType.ISSUE_RAISED
        DiscussionKind.ESCALATION -> AdrEventType.ESCALATED
    },
    entryId,
    kind,
)

/** An entry moved to [to] (`ANSWERED` / `RESOLVED` settle it, `OPEN` reopens it): the type follows the kind and the direction. */
fun adrDiscussionStateEvent(entryId: UInt, kind: DiscussionKind, to: DiscussionStatus) = discussionEvent(
    when {
        kind == DiscussionKind.QUESTION && to == DiscussionStatus.OPEN -> AdrEventType.QUESTION_REOPENED
        kind == DiscussionKind.QUESTION -> AdrEventType.QUESTION_ANSWERED
        to == DiscussionStatus.OPEN -> AdrEventType.ISSUE_REOPENED
        else -> AdrEventType.ISSUE_RESOLVED
    },
    entryId,
    kind,
)

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
 * ever updates or deletes a row. The notices ([AdrNotices], 0.6.0) hang off [record]: every notice derives from an event of its own
 * transaction.
 */
class AdrEventService(database: R2dbcDatabase, private val notices: AdrNotices = AdrNotices.NONE) {
    object AdrEvents : EventLogTable(
        "adr_events",
        "adr_id",
        AdrService.Adrs,
        EventLogActor(UserService.Users, UserService.Users.name, UserService.Users.markedAsDeleted),
    )

    private val log = EventLog(database, AdrEvents)

    /**
     * Appends one event inside the caller's transaction, stamped [at] (the write's own instant); a null [byUserId] is the system. The
     * event is then offered to the notices ([AdrNotices]) in the same transaction.
     */
    suspend fun record(adrId: UInt, byUserId: UInt?, event: AdrEventDescriptor, at: Long = nowMillis()): UInt =
        recordAll(adrId, byUserId, listOf(event), at).single()

    /**
     * Appends the [events] of ONE write on ONE ADR in order, inside the caller's transaction, then offers them to the notices TOGETHER,
     * so a participants PUT that moves many people makes its notices with one statement (the creation holds the numbering advisory lock,
     * and a statement per participant would hold it longer). Answers the event ids in order.
     */
    suspend fun recordAll(adrId: UInt, byUserId: UInt?, events: List<AdrEventDescriptor>, at: Long = nowMillis()): List<UInt> {
        if (events.isEmpty()) return emptyList()
        val ids = events.map { log.createInTransaction(adrId, byUserId, it.type.name, it.params, at) }
        notices.onEvents(adrId, byUserId, events, at)
        return ids
    }

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
