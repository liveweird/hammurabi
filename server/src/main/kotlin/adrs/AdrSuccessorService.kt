package ch.nokillswit.adrs

import ch.nokillswit.authz.ConflictException
import ch.nokillswit.infra.db.active
import io.ktor.server.plugins.BadRequestException
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll

/**
 * The advisory lock every successor-link write takes first (`pg_advisory_xact_lock`, the single-`bigint` form): the ASCII bytes of
 * "ADRLINKS". A cycle spans several ADRs, none of which one row lock can cover, so two concurrent links (A replaces B, B replaces A) would
 * both pass the cycle check; this lock serialises them. Taken BEFORE the ADR row lock, and by nothing that holds a row lock already, so it
 * cannot deadlock with the activation path (which takes row locks only). Transaction-scoped. `persistence.md` "ADRs (V10...)".
 */
internal const val ADR_LINK_LOCK_KEY = 0x4144524C494E4B53L

/** What a link write changed: the predecessor before and after (null = none); the audit line and the events read it. */
data class SuccessorChange(val from: AdrRef?, val to: AdrRef?)

/** The statuses a predecessor may be in when a link is made: in force, or still on its way (a terminal one has nothing left to replace). */
private val LINKABLE_PREDECESSORS: Set<AdrStatus> = AdrStatus.entries.filter { !it.terminal }.toSet()

/** The statuses a successor may end in WITHOUT having superseded anything: it was cancelled or withdrawn before it became `ACTIVE`. */
private val NEVER_ACTIVE: List<String> = listOf(AdrStatus.CANCELLED.name, AdrStatus.WITHDRAWN.name)

/**
 * A successor "in play or in effect": any status but cancelled or withdrawn, OR a withdrawn one that DID activate first (0.7.0: an
 * `ACTIVE` decision can be withdrawn, after which its predecessor stays `SUPERSEDED` and must keep pointing at it). The activation
 * is told apart by its `SUCCESSOR_ACTIVATED` event on the successor, written in the same transaction as the supersede; a withdrawn
 * successor without it never superseded anything. Correlated to the `adrs` row of the query it is added to.
 */
internal fun successorInPlayOrInEffect(): Op<Boolean> {
    val adrs = AdrService.Adrs
    val events = AdrEventService.AdrEvents
    val activated = exists(
        events.select(events.id).where { (events.ownerId eq adrs.id) and (events.eventType eq AdrEventType.SUCCESSOR_ACTIVATED.name) },
    )
    return (adrs.status notInList NEVER_ACTIVE) or ((adrs.status eq AdrStatus.WITHDRAWN.name) and activated)
}

/** The statuses in which a successor link is pending: not in effect yet (it takes effect at activation) and still removable. */
private val PRE_ACTIVE: List<String> = AdrStatus.entries.filter { it.contentEditable }.map { it.name }

/**
 * The successor link of an ADR (`adrs.supersedes_id` on the successor, V14). Every function runs in the CALLER's transaction (the ADR
 * write's): the link, its events and the revision bump commit or roll back together. The link is set while the successor is pre-active and
 * takes effect when it becomes `ACTIVE` ([AdrService.transition] supersedes the predecessor then).
 */
class AdrSuccessorService(private val events: AdrEventService) {
    private val adrs = AdrService.Adrs

    private fun ResultRow.toRef() =
        AdrRef(this[adrs.id].value, adrKey(this[adrs.number]), this[adrs.name], AdrStatus.valueOf(this[adrs.status]))

    /** The live ADR [id], or null when it is missing or soft-deleted. */
    suspend fun refOf(id: UInt?): AdrRef? {
        if (id == null) return null
        return adrs.selectAll().where { (adrs.id eq id) and adrs.active() }.toList().singleOrNull()?.toRef()
    }

    /** The ADR [id] whatever its state, soft-deleted included (the old end of a link being removed still deserves its event). */
    private suspend fun refOfAny(id: UInt?): AdrRef? {
        if (id == null) return null
        return adrs.selectAll().where { adrs.id eq id }.toList().singleOrNull()?.toRef()
    }

    /**
     * The ADR replacing [id]: its successor still in play, or the one that replaced it once it is `SUPERSEDED` - never one that was
     * cancelled or withdrawn before it activated. At most one exists (the unique index, and a superseded ADR takes no new link).
     */
    suspend fun successorOf(id: UInt): AdrRef? = adrs.selectAll()
        .where { (adrs.supersedesId eq id) and adrs.active() and successorInPlayOrInEffect() }
        .orderBy(adrs.id).limit(1).toList().singleOrNull()?.toRef()

    /** The id of [id]'s successor that is still pre-active (a pending link, not yet in effect), or null: a plain read, no lock. */
    suspend fun pendingSuccessorIdOf(id: UInt): UInt? = adrs.select(adrs.id)
        .where { (adrs.supersedesId eq id) and adrs.active() and (adrs.status inList PRE_ACTIVE) }
        .orderBy(adrs.id).limit(1).toList().singleOrNull()?.get(adrs.id)?.value

    /** The ADR [id] replaces, as the cycle walk reads it: none for a missing, deleted, cancelled or withdrawn ADR (a dead link). */
    private suspend fun linkedPredecessorOf(id: UInt): UInt? = adrs.select(adrs.supersedesId, adrs.status)
        .where { (adrs.id eq id) and adrs.active() }.toList().singleOrNull()
        ?.takeIf { it[adrs.status] !in NEVER_ACTIVE }?.get(adrs.supersedesId)?.value

    /** The predecessor id stored on [id] (a plain read, no lock): the activation reads it to lock both rows in id order. */
    suspend fun predecessorIdOf(id: UInt): UInt? =
        adrs.select(adrs.supersedesId).where { adrs.id eq id }.toList().singleOrNull()?.get(adrs.supersedesId)?.value

    /** Serialises the link writers ([ADR_LINK_LOCK_KEY]); call it first, before the ADR row lock. */
    suspend fun lockLinkWrites(transaction: R2dbcTransaction) {
        val lock = "SELECT pg_advisory_xact_lock($ADR_LINK_LOCK_KEY)"
        transaction.exec(lock, explicitStatementType = StatementType.SELECT) { true }?.toList()
    }

    /**
     * Validates and records a change of [record]'s predecessor to [requested] (null removes the link). Under the link lock and the
     * successor's row lock; the successor is known pre-active and writable. Unchanged = null (no write, no event). A predecessor must be
     * a live ADR other than this one (400), not terminal (400: a cancelled, withdrawn, expired or superseded decision has nothing left
     * to replace), must not close a cycle (409, [SuccessorGraph]) and must have no other successor in play (409). The caller writes
     * `supersedes_id` and bumps the revision; this records `SUCCESSOR_UNLINKED` for the old end and `SUCCESSOR_LINKED` for the new.
     */
    suspend fun replace(record: AdrRecord, requested: UInt?, byUserId: UInt, stamp: Long): SuccessorChange? {
        if (requested == record.supersedesId) return null
        val target = requested?.let { requireLinkablePredecessor(record, it) }
        val old = refOfAny(record.supersedesId)
        events.recordAll(
            record.id, byUserId,
            listOfNotNull(old?.let { adrSuccessorUnlinkedEvent(it) }, target?.let { adrSuccessorLinkedEvent(it) }),
            stamp,
        )
        return SuccessorChange(old, target)
    }

    /**
     * Records the two `SUCCESSOR_UNLINKED` events of a DETACH (the predecessor's owner removed the pending [successor]): on the successor
     * (`predecessorId`, as for any unlink) and on the [predecessor] (`successorId`). The caller clears `supersedes_id` and bumps the
     * successor's revision.
     */
    suspend fun recordDetached(predecessor: AdrRef, successor: AdrRef, byUserId: UInt, stamp: Long) {
        events.record(successor.id, byUserId, adrSuccessorUnlinkedEvent(predecessor), stamp)
        events.record(predecessor.id, byUserId, adrSuccessorDetachedEvent(successor), stamp)
    }

    private suspend fun requireLinkablePredecessor(record: AdrRecord, predecessorId: UInt): AdrRef {
        if (predecessorId == record.id) throw BadRequestException("An ADR cannot supersede itself")
        val predecessor = refOf(predecessorId) ?: throw BadRequestException("Unknown or deleted ADR id: $predecessorId")
        if (predecessor.status !in LINKABLE_PREDECESSORS) {
            throw BadRequestException("${predecessor.key} is ${predecessor.status} and cannot be superseded")
        }
        val link = SupersedesLink(predecessor = predecessorId, successor = record.id)
        SuccessorGraph.cycleClosedBy(link, ::linkedPredecessorOf)?.let { cycle ->
            val keys = adrs.select(adrs.id, adrs.number).where { adrs.id inList cycle.distinct() }.toList()
                .associate { it[adrs.id].value to adrKey(it[adrs.number]) }
            throw ConflictException("This link would close a cycle: ${cycle.joinToString(" -> ") { keys.getValue(it) }}")
        }
        val rival = adrs.selectAll().where {
            (adrs.supersedesId eq predecessorId) and adrs.active() and (adrs.id neq record.id) and
                (adrs.status notInList AdrStatus.entries.filter { it.terminal }.map { it.name })
        }.toList().firstOrNull()
        if (rival != null) throw ConflictException("${predecessor.key} already has the successor ${rival.toRef().key}")
        return predecessor
    }
}
