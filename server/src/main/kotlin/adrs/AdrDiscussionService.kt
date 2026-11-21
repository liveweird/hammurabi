package ch.nokillswit.adrs

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.infra.db.SoftDeletable
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.users.UserService
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.booleanLiteral
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

val AdrDiscussionServiceKey = AttributeKey<AdrDiscussionService>("AdrDiscussionService")

/** What a post created: the entry (the route reads it back) and the ADR number the audit line carries. */
data class DiscussionPosted(val id: UInt, val number: Int, val kind: DiscussionKind)

/** What an edit did (`changed` false = the same text, nothing written). */
data class DiscussionEdited(val number: Int, val kind: DiscussionKind, val changed: Boolean)

/** What a delete removed. */
data class DiscussionDeleted(val number: Int, val kind: DiscussionKind, val byAdmin: Boolean)

/** What a state change did (null from [AdrDiscussionService.setState] = the entry was already in that state). */
data class DiscussionStateChanged(val number: Int, val kind: DiscussionKind, val from: DiscussionState, val to: DiscussionState)

/** A user's display name as it is now (kept for a soft-deleted account) and whether the account is deleted; flattened onto the entry. */
private data class PersonRef(val name: String, val deleted: Boolean)

/** The entry a route is about to write, resolved BEFORE the body is read so the guard runs first: kind, author, the caller's standing. */
data class DiscussionTarget(val kind: DiscussionKind, val authorId: UInt, val standing: AdrStanding)

/** The sortable whitelist of the entries list (an entry has nothing else worth sorting on). */
val DISCUSSION_SORT_FIELDS: Set<String> = setOf("id", "createdAt")

/**
 * The ADR discussion (`adr_discussion`, V12). Every write is ONE transaction that locks the ADR row (`FOR NO KEY UPDATE`, like every ADR
 * write: it serialises the entry with a lifecycle move, so an entry can never be accepted by a status the ADR just left), re-reads the
 * caller's role under that lock, re-runs the guards the route ran before the body, then judges the window (the typed `discussion-closed`
 * 409; a post and an edit only - answering, resolving and reopening work in every status) and writes the entry with its history
 * event. A discussion write never bumps the ADR's revision: the discussion is not part of the document. Reads are any-authenticated
 * and see tombstones; a write to a deleted entry is a 404.
 */
class AdrDiscussionService(
    private val database: R2dbcDatabase,
    private val participants: AdrParticipantService,
    private val events: AdrEventService,
) {
    object AdrDiscussion : UIntIdTable("adr_discussion"), SoftDeletable {
        val adrId = reference("adr_id", AdrService.Adrs)
        val kind = varchar("kind", length = 20)

        /** NULL for a comment and for a tombstone. */
        val status = varchar("status", length = 10).nullable()
        val body = text("body")
        val resolution = text("resolution").default("")
        val authorId = reference("author_id", UserService.Users)
        val createdAt = long("created_at")
        val editedAt = long("edited_at").nullable()
        val stateChangedBy = reference("state_changed_by", UserService.Users).nullable()
        val stateChangedAt = long("state_changed_at").nullable()
        override val markedAsDeleted = bool("marked_as_deleted").default(false)
        val deletedAt = long("deleted_at").nullable()
    }

    private val entries = AdrDiscussion
    private val adrs = AdrService.Adrs

    private val sortColumns: Map<String, Expression<*>> = mapOf("id" to entries.id, "createdAt" to entries.createdAt)

    /** The ADR's number and status, locked `FOR NO KEY UPDATE`; a missing or deleted ADR is a 404. */
    private suspend fun lockedAdr(adrId: UInt): Pair<Int, AdrStatus> {
        val row = adrs.select(adrs.number, adrs.status).where { (adrs.id eq adrId) and adrs.active() }
            .forUpdate(ForUpdateOption.PostgreSQL.ForNoKeyUpdate).toList().singleOrNull() ?: throw NotFoundException("ADR not found")
        return row[adrs.number] to AdrStatus.valueOf(row[adrs.status])
    }

    private suspend fun liveEntry(adrId: UInt, entryId: UInt): ResultRow =
        entries.selectAll().where { (entries.id eq entryId) and (entries.adrId eq adrId) and entries.active() }.toList().singleOrNull()
            ?: throw NotFoundException("Discussion entry not found")

    private suspend fun standingOf(adrId: UInt, caller: CallerPrincipal) = AdrStanding(caller, participants.roleOf(adrId, caller.userId))

    private suspend fun adrStatusOf(adrId: UInt): AdrStatus? =
        adrs.select(adrs.status).where { (adrs.id eq adrId) and adrs.active() }.toList().singleOrNull()
            ?.let { AdrStatus.valueOf(it[adrs.status]) }

    /** The entry a write is about (404 for a missing ADR, a missing or deleted entry, or one of another ADR) and the caller's standing. */
    suspend fun target(adrId: UInt, entryId: UInt, caller: CallerPrincipal): DiscussionTarget = suspendTransaction(database) {
        adrStatusOf(adrId) ?: throw NotFoundException("ADR not found")
        val row = liveEntry(adrId, entryId)
        DiscussionTarget(DiscussionKind.valueOf(row[entries.kind]), row[entries.authorId].value, standingOf(adrId, caller))
    }

    private suspend fun usersOf(ids: Collection<UInt>): Map<UInt, PersonRef> {
        if (ids.isEmpty()) return emptyMap()
        val users = UserService.Users
        return users.select(users.id, users.name, users.markedAsDeleted).where { users.id inList ids.distinct() }
            .map { it[users.id].value to PersonRef(it[users.name], it[users.markedAsDeleted]) }
            .toList().toMap()
    }

    private fun ResultRow.toResponse(
        names: Map<UInt, PersonRef>,
        standing: AdrStanding,
        adrStatus: AdrStatus,
    ): DiscussionEntryResponse {
        val kind = DiscussionKind.valueOf(this[entries.kind])
        val authorId = this[entries.authorId].value
        val deleted = this[entries.markedAsDeleted]
        // A tombstone keeps only its kind, author and creation time: the edit stamp and the last state change go with the text.
        val stateBy = this[entries.stateChangedBy]?.value.takeUnless { deleted }
        val open = !deleted && kind.acceptedIn(adrStatus)
        return DiscussionEntryResponse(
            id = this[entries.id].value,
            adrId = this[entries.adrId].value,
            kind = kind,
            state = this[entries.status]?.let(DiscussionState::valueOf),
            body = if (deleted) null else this[entries.body],
            deleted = deleted,
            authorId = authorId,
            authorName = names.getValue(authorId).name,
            authorDeleted = names.getValue(authorId).deleted,
            createdAt = this[entries.createdAt],
            editedAt = this[entries.editedAt].takeUnless { deleted },
            resolution = if (deleted) null else this[entries.resolution].takeIf { it.isNotEmpty() },
            stateChangedById = stateBy,
            stateChangedByName = stateBy?.let { names.getValue(it).name },
            stateChangedByDeleted = stateBy?.let { names.getValue(it).deleted } ?: false,
            stateChangedAt = this[entries.stateChangedAt].takeUnless { deleted },
            canEdit = open && isAuthor(standing.caller, authorId),
            canDelete = !deleted && mayDeleteEntry(standing.caller, authorId),
            canChangeState = !deleted && kind.hasState && mayChangeEntryState(standing, kind, authorId),
        )
    }

    private suspend fun toResponses(rows: List<ResultRow>, standing: AdrStanding, adrStatus: AdrStatus): List<DiscussionEntryResponse> {
        val names = usersOf(
            rows.flatMap { row ->
                listOfNotNull(row[entries.authorId].value, row[entries.stateChangedBy]?.value.takeUnless { row[entries.markedAsDeleted] })
            },
        )
        return rows.map { it.toResponse(names, standing, adrStatus) }
    }

    /** One page of an ADR's entries (tombstones included), or null when there is no such ADR. */
    suspend fun list(adrId: UInt, caller: CallerPrincipal, filter: DiscussionFilter, paging: PageRequest): DiscussionListResult? =
        suspendTransaction(database) {
            val adrStatus = adrStatusOf(adrId) ?: return@suspendTransaction null
            val standing = standingOf(adrId, caller)
            val predicate = (entries.adrId eq adrId)
                .let { op -> if (filter.kinds.isEmpty()) op else op and (entries.kind inList filter.kinds.map { it.name }) }
                .let { op -> filter.state?.let { op and (entries.status eq it.name) } ?: op }
            val total = entries.selectAll().where { predicate }.count()
            val rows = entries.selectAll().where { predicate }.applyPaging(paging, sortColumns).toList()
            DiscussionListResult(toResponses(rows, standing, adrStatus), total)
        }

    /** One entry (a tombstone too), or null when the ADR or the entry does not exist. */
    suspend fun read(adrId: UInt, entryId: UInt, caller: CallerPrincipal): DiscussionEntryResponse? = suspendTransaction(database) {
        val adrStatus = adrStatusOf(adrId) ?: return@suspendTransaction null
        val row = entries.selectAll().where { (entries.id eq entryId) and (entries.adrId eq adrId) }.toList().singleOrNull()
            ?: return@suspendTransaction null
        toResponses(listOf(row), standingOf(adrId, caller), adrStatus).single()
    }

    /**
     * What the ADRs carry of their discussion, in ONE aggregate over the partial index `idx_adr_discussion_open` (only OPEN, live entries
     * are in it): the open issues + escalations and the unanswered questions per ADR, absent = none. Runs in the CALLER's transaction (the
     * ADR read or list page, never a transaction of its own).
     */
    suspend fun countsOf(adrIds: Collection<UInt>): Map<UInt, DiscussionCounts> {
        if (adrIds.isEmpty()) return emptyMap()
        val total = entries.id.count()
        val perKind = entries.select(entries.adrId, entries.kind, total)
            // Literals, not bind parameters: a generic prepared plan cannot prove a parameter implies the partial index's predicate.
            .where {
                (entries.adrId inList adrIds) and
                    (entries.status eq stringLiteral(DiscussionState.OPEN.name)) and
                    (entries.markedAsDeleted eq booleanLiteral(false))
            }
            .groupBy(entries.adrId, entries.kind)
            .map { Triple(it[entries.adrId].value, DiscussionKind.valueOf(it[entries.kind]), it[total].toInt()) }
            .toList()
        return perKind.groupBy({ it.first }) { it.second to it.third }.mapValues { (_, kinds) ->
            DiscussionCounts(
                openIssues = kinds.filter { it.first == DiscussionKind.ISSUE || it.first == DiscussionKind.ESCALATION }.sumOf { it.second },
                unansweredQuestions = kinds.filter { it.first == DiscussionKind.QUESTION }.sumOf { it.second },
            )
        }
    }

    /** Posts an entry: the window (409 `discussion-closed`) under the ADR lock, the entry (questions and issues start OPEN), its event. */
    suspend fun post(adrId: UInt, caller: CallerPrincipal, raw: DiscussionCreateRequest): DiscussionPosted {
        val request = sanitizedDiscussionCreate(raw)
        validateDiscussionBody(request.body)
        return suspendTransaction(database) {
            events.reserveNotices(caller.userId, adrId) // the budget row before the ADR row
            val (number, status) = lockedAdr(adrId)
            request.kind.requireAcceptedIn(status)
            val stamp = nowMillis()
            val id = entries.insert {
                it[entries.adrId] = adrId
                it[kind] = request.kind.name
                it[entries.status] = request.kind.initialState?.name
                it[body] = request.body
                it[authorId] = caller.userId
                it[createdAt] = stamp
            }[entries.id].value
            events.record(adrId, caller.userId, adrDiscussionPostedEvent(id, request.kind), stamp)
            DiscussionPosted(id, number, request.kind)
        }
    }

    /** The author edits the text: guard (403), window (409), then body and `editedAt`; the same text writes nothing. No history event. */
    suspend fun edit(adrId: UInt, entryId: UInt, caller: CallerPrincipal, raw: DiscussionEditRequest): DiscussionEdited {
        val request = sanitizedDiscussionEdit(raw)
        validateDiscussionBody(request.body)
        return suspendTransaction(database) {
            val (number, status) = lockedAdr(adrId)
            val row = liveEntry(adrId, entryId)
            val kind = DiscussionKind.valueOf(row[entries.kind])
            requireMayEditEntry(caller, row[entries.authorId].value)
            kind.requireAcceptedIn(status)
            if (row[entries.body] == request.body) return@suspendTransaction DiscussionEdited(number, kind, changed = false)
            entries.update({ entries.id eq entryId }) {
                it[body] = request.body
                it[editedAt] = nowMillis()
            }
            DiscussionEdited(number, kind, changed = true)
        }
    }

    /**
     * The author or an ADMIN deletes: a soft delete that leaves a tombstone - the row, its kind, author and creation time stay, the body
     * and the resolution are wiped, the status is cleared (so no status filter and no count sees it). Allowed in EVERY ADR status: the
     * moderation path is never closed. No history event.
     */
    suspend fun delete(adrId: UInt, entryId: UInt, caller: CallerPrincipal): DiscussionDeleted = suspendTransaction(database) {
        val (number, _) = lockedAdr(adrId)
        val row = liveEntry(adrId, entryId)
        val authorId = row[entries.authorId].value
        requireMayDeleteEntry(caller, authorId)
        entries.update({ entries.id eq entryId }) {
            it[markedAsDeleted] = true
            it[deletedAt] = nowMillis()
            it[body] = ""
            it[resolution] = ""
            it[status] = null
            it[editedAt] = null
            it[stateChangedBy] = null
            it[stateChangedAt] = null
        }
        DiscussionDeleted(number, DiscussionKind.valueOf(row[entries.kind]), byAdmin = !isAuthor(caller, authorId))
    }

    /**
     * Answers, resolves or reopens an entry: the guard again (403), the state against the kind (400), then - when the state actually
     * changes - the state, who and when, the optional resolution note (cleared on a reopen) and the history event. The entry already in
     * that state is a no-op (null) - except a RESOLVED one given a DIFFERENT note: the note is replaced (an empty one clears it) and
     * who/when follow, with no history event (`from == to` in the result, for the audit line). Nothing else changes.
     */
    suspend fun setState(adrId: UInt, entryId: UInt, caller: CallerPrincipal, raw: DiscussionStateRequest): DiscussionStateChanged? {
        val request = sanitizedDiscussionState(raw)
        return suspendTransaction(database) {
            events.reserveNotices(caller.userId, adrId)
            val (number, _) = lockedAdr(adrId)
            val row = liveEntry(adrId, entryId)
            val kind = DiscussionKind.valueOf(row[entries.kind])
            requireMayChangeEntryState(standingOf(adrId, caller), kind, row[entries.authorId].value)
            validateDiscussionState(kind, request)
            val from = DiscussionState.valueOf(checkNotNull(row[entries.status]) { "A live ${kind.name} has a state" })
            val stamp = nowMillis()
            if (from == request.state) {
                val note = request.resolution.orEmpty()
                if (request.state != DiscussionState.RESOLVED || note == row[entries.resolution]) return@suspendTransaction null
                entries.update({ entries.id eq entryId }) {
                    it[resolution] = note
                    it[stateChangedBy] = caller.userId
                    it[stateChangedAt] = stamp
                }
                return@suspendTransaction DiscussionStateChanged(number, kind, from, from)
            }
            entries.update({ entries.id eq entryId }) {
                it[entries.status] = request.state.name
                it[resolution] = request.resolution.orEmpty()
                it[stateChangedBy] = caller.userId
                it[stateChangedAt] = stamp
            }
            events.record(adrId, caller.userId, adrDiscussionStateEvent(entryId, kind, request.state), stamp)
            DiscussionStateChanged(number, kind, from, request.state)
        }
    }
}
