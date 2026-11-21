package ch.nokillswit.adrs

import ch.nokillswit.infra.db.active
import ch.nokillswit.users.UserService
import io.ktor.server.plugins.BadRequestException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll

/** What one participants write changed (the audit line and the events both read it). Each list is ordered by user id. */
data class ParticipantDiff(
    val added: List<AdrParticipant>,
    val removed: List<AdrParticipant>,
    val changed: List<ParticipantRoleChange>,
) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
}

data class ParticipantRoleChange(val userId: UInt, val name: String, val from: AdrRole, val to: AdrRole)

/** The role order a participant list reads in: Driver, Approver, Contributors, Informed. */
private val ROLE_ORDER: Map<AdrRole, Int> = AdrRole.entries.associateWith { it.ordinal }

/**
 * The DACI participants of an ADR (`adr_participants`, V10). Every function runs in the CALLER's transaction (the ADR write's, under the
 * ADR row lock): a participant change and its events commit or roll back together, and nothing here opens a transaction of its own.
 * The cross-feature read (persistence.md): names and liveness come straight from `users`, joined or batched in that same transaction.
 */
class AdrParticipantService(private val events: AdrEventService) {
    object AdrParticipants : Table("adr_participants") {
        val adrId = reference("adr_id", AdrService.Adrs)
        val userId = reference("user_id", UserService.Users)
        val role = varchar("role", length = 20)
        val addedAt = long("added_at")
        val addedBy = reference("added_by", UserService.Users)
        override val primaryKey = PrimaryKey(adrId, userId)
    }

    private val users = UserService.Users

    /** The participants of [adrId] with the display names as they are now (a soft-deleted user keeps the name), in role order. */
    suspend fun forAdr(adrId: UInt): List<AdrParticipant> = rowsOf(listOf(adrId)).values.firstOrNull().orEmpty().sortedForDisplay()

    /** Every stored row of one ADR, by user id: the diff's baseline (role plus who added it and when, which a role change keeps). */
    private data class Stored(val participant: AdrParticipant, val addedAt: Long, val addedBy: UInt)

    private suspend fun storedOf(adrId: UInt): Map<UInt, Stored> =
        AdrParticipants.join(users, JoinType.INNER, onColumn = AdrParticipants.userId, otherColumn = users.id)
            .selectAll().where { AdrParticipants.adrId eq adrId }
            .map {
                val userId = it[AdrParticipants.userId].value
                userId to Stored(
                    AdrParticipant(userId, it[users.name], AdrRole.valueOf(it[AdrParticipants.role])),
                    it[AdrParticipants.addedAt],
                    it[AdrParticipants.addedBy].value,
                )
            }
            .toList().toMap()

    private suspend fun rowsOf(adrIds: List<UInt>, roles: List<AdrRole>? = null): Map<UInt, List<AdrParticipant>> {
        if (adrIds.isEmpty()) return emptyMap()
        val join = AdrParticipants.join(users, JoinType.INNER, onColumn = AdrParticipants.userId, otherColumn = users.id)
        val rows = join.selectAll().where {
            val inAdrs = AdrParticipants.adrId inList adrIds
            if (roles == null) inAdrs else inAdrs and (AdrParticipants.role inList roles.map { it.name })
        }.toList()
        return rows.groupBy({ it[AdrParticipants.adrId].value }) {
            AdrParticipant(it[AdrParticipants.userId].value, it[users.name], AdrRole.valueOf(it[AdrParticipants.role]))
        }
    }

    /** The Driver and the Approver of every ADR in [adrIds] in ONE query (the list rows). */
    suspend fun leadsOf(adrIds: List<UInt>): Map<UInt, List<AdrParticipant>> =
        rowsOf(adrIds, listOf(AdrRole.DRIVER, AdrRole.APPROVER))

    /** The caller's role on [adrId], or null when they hold none. */
    suspend fun roleOf(adrId: UInt, userId: UInt): AdrRole? =
        AdrParticipants.select(AdrParticipants.role)
            .where { (AdrParticipants.adrId eq adrId) and (AdrParticipants.userId eq userId) }
            .toList().singleOrNull()?.let { AdrRole.valueOf(it[AdrParticipants.role]) }

    /** Whether [adrId] has an ACTIVE Approver account (the `IN_REVIEW` / `ACTIVE` requirement): a deleted account cannot approve. */
    suspend fun hasApprover(adrId: UInt): Boolean =
        AdrParticipants.join(users, JoinType.INNER, onColumn = AdrParticipants.userId, otherColumn = users.id)
            .select(AdrParticipants.userId)
            .where { (AdrParticipants.adrId eq adrId) and (AdrParticipants.role eq AdrRole.APPROVER.name) and users.active() }
            .toList().isNotEmpty()

    /**
     * Rejects (400) a user id that is not an ACTIVE account: a client-supplied FK is never a 500 from the constraint. Returns the names
     * of [userIds] (every id of them is active). Only users NEW to a set are checked by [replace]; creation checks all of them.
     */
    suspend fun requireActiveUsers(userIds: Collection<UInt>): Map<UInt, String> {
        if (userIds.isEmpty()) return emptyMap()
        val wanted = userIds.distinct()
        val found = users.select(users.id, users.name).where { (users.id inList wanted) and users.active() }
            .map { it[users.id].value to it[users.name] }.toList().toMap()
        val missing = wanted.filterNot { it in found }
        if (missing.isNotEmpty()) throw BadRequestException("Unknown or deleted user ids: ${missing.sorted().joinToString(",")}")
        return found
    }

    /**
     * The creation write: the creator becomes the Driver and [initial] join (a `PARTICIPANT_ADDED` event each; the Driver's own entry is
     * the `CREATED` event's actor). The caller validated the shape ([validateAdrCreate]) and resolved [names] ([requireActiveUsers]).
     */
    suspend fun insertForCreation(
        adrId: UInt,
        driverId: UInt,
        initial: List<AdrParticipantRequest>,
        names: Map<UInt, String>,
        stamp: Long,
    ) {
        insertRow(adrId, driverId, AdrRole.DRIVER, stamp, driverId)
        val ordered = initial.sortedBy { it.userId }
        ordered.forEach { insertRow(adrId, it.userId, it.role, stamp, driverId) }
        events.recordAll(
            adrId, driverId,
            ordered.map { adrParticipantAddedEvent(AdrParticipant(it.userId, names.getValue(it.userId), it.role)) },
            stamp,
        )
    }

    /**
     * Replaces the whole set with [requested] (shape checked by [validateParticipantSet]: one Driver, at most one Approver) as a DIFF: a
     * user missing from it is removed, a new one added, one with another role changed (the row keeps who added it and when); a user
     * whose role is unchanged is untouched. A user NEW to the set (or taking a new role) must be ACTIVE (400 otherwise): an existing
     * participant whose account was deleted since may stay. Removed and changed rows are deleted before the changed and added ones
     * are inserted, so the partial unique indexes (one Driver, one Approver) never see a handover half-done. One event per added,
     * removed and changed participant. Returns the diff (empty = a no-op: nothing written, nothing recorded).
     */
    suspend fun replace(adrId: UInt, byUserId: UInt, requested: List<AdrParticipantRequest>, stamp: Long): ParticipantDiff {
        val stored = storedOf(adrId)
        val wanted = requested.associate { it.userId to it.role }
        val addedIds = wanted.keys.filter { it !in stored }
        val removedIds = stored.keys.filter { it !in wanted }
        val changedIds = wanted.keys.filter { id -> stored[id]?.let { it.participant.role != wanted.getValue(id) } == true }
        val names = requireActiveUsers(addedIds + changedIds)
        val diff = ParticipantDiff(
            added = addedIds.sorted().map { AdrParticipant(it, names.getValue(it), wanted.getValue(it)) },
            removed = removedIds.sorted().map { stored.getValue(it).participant },
            changed = changedIds.sorted().map {
                ParticipantRoleChange(it, stored.getValue(it).participant.name, stored.getValue(it).participant.role, wanted.getValue(it))
            },
        )
        if (diff.isEmpty) return diff
        val dropped = removedIds + changedIds
        if (dropped.isNotEmpty()) {
            AdrParticipants.deleteWhere { (AdrParticipants.adrId eq adrId) and (AdrParticipants.userId inList dropped) }
        }
        changedIds.forEach { id ->
            val row = stored.getValue(id)
            insertRow(adrId, id, wanted.getValue(id), row.addedAt, row.addedBy)
        }
        diff.added.forEach { insertRow(adrId, it.userId, it.role, stamp, byUserId) }
        // One batch: the event rows in this order, and the notices of the whole diff in one statement.
        events.recordAll(
            adrId, byUserId,
            diff.removed.map { adrParticipantRemovedEvent(it) } +
                diff.changed.map { adrParticipantChangedEvent(it.userId, it.name, it.from, it.to) } +
                diff.added.map { adrParticipantAddedEvent(it) },
            stamp,
        )
        return diff
    }

    private suspend fun insertRow(adrId: UInt, userId: UInt, role: AdrRole, at: Long, by: UInt) {
        AdrParticipants.insert {
            it[AdrParticipants.adrId] = adrId
            it[AdrParticipants.userId] = userId
            it[AdrParticipants.role] = role.name
            it[addedAt] = at
            it[addedBy] = by
        }
    }
}

/** Driver, Approver, Contributors, Informed; the same role by name. */
fun List<AdrParticipant>.sortedForDisplay(): List<AdrParticipant> =
    sortedWith(compareBy({ ROLE_ORDER.getValue(it.role) }, { it.name.lowercase() }, { it.userId }))
