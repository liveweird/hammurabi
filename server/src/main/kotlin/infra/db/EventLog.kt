package ch.nokillswit.infra.db

import ch.nokillswit.infra.json.decodeParams
import ch.nokillswit.infra.json.encodeParams
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.infra.paging.applyPaging
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * The acting-user table an event log joins for names: the table itself and the two columns the history shows (the name recorded
 * for the actor and whether the account has since been soft-deleted). `infra/` may not name a feature package, so the FEATURE
 * supplies these (`adrs/` passes `UserService.Users`) instead of this file importing `users/`: Toadie's verbatim port imported
 * `UserService` and so broke `PackageBoundaryTest`.
 */
class EventLogActor(val table: IdTable<UInt>, val name: Column<String>, val deleted: Column<Boolean>)

/**
 * The shared shape of the per-record audit-event tables (`adr_events`, V11) - ported from Adder's `EventLog` (Toadie's, itself
 * Lettuce's, where seven `*_events` tables ride it): an FK to the owning record, the acting user, a creation timestamp, and a
 * STRUCTURED event (kind + JSON params) the SPA localizes. Feature packages declare
 * `object XEvents : EventLogTable("x_events", "x_id", XTable, actor)` and keep their typed record/list wrappers; the mechanics live
 * once here.
 *
 * Departures from Adder's copy: the acting user is NULLABLE - a null actor is the SYSTEM (the expiry sweep, the successor
 * activation of 0.4.0), so the actor join is a LEFT join and [EventLogRow.userId] / [EventLogRow.userName] may be null; the bulk
 * `createAllInTransaction` is left behind (no ADR write fans one event out to many records).
 */
abstract class EventLogTable(
    name: String,
    ownerColumn: String,
    ownerTable: IdTable<UInt>,
    val actor: EventLogActor,
) : UIntIdTable(name) {
    val ownerId: Column<EntityID<UInt>> = reference(ownerColumn, ownerTable)

    /** The acting user; null = the system. */
    val userId = reference("user_id", actor.table).nullable()
    val createdAt = long("created_at")

    // Structured event so the SPA can localize it: the kind plus a JSON params map.
    val eventType = varchar("event_type", EVENT_TYPE_LENGTH)
    val params = text("params")
}

private const val EVENT_TYPE_LENGTH = 40

/** The sortable whitelist every events endpoint declares (an event has nothing else to sort on). */
val EVENT_LOG_SORT_FIELDS: Set<String> = setOf("id", "createdAt")

/**
 * Newest first, with the id tiebreaker DESCENDING too: one mutation mints its events inside a single millisecond, so only the id
 * ordering makes them read as a true reversal of mint order.
 */
val EVENT_LOG_DEFAULT_SORT: List<SortField> =
    listOf(SortField("createdAt", descending = true), SortField("id", descending = true))

/** One raw event row with the acting user resolved - feature wrappers map it to their typed response. */
data class EventLogRow(
    val id: UInt,
    val ownerId: UInt,
    /** The acting user, null for a system event. */
    val userId: UInt?,
    val userName: String?,
    /** The acting account has since been soft-deleted (its name is still the one recorded). */
    val userDeleted: Boolean,
    val createdAt: Long,
    val type: String,
    val params: Map<String, String>,
)

/** One page of a record's history plus the pre-pagination total (the list-endpoint envelope). */
data class EventLogPage(val items: List<EventLogRow>, val total: Long)

class EventLog(private val database: R2dbcDatabase, private val table: EventLogTable) {
    private val actor = table.actor

    // The sanctioned cross-feature table read (persistence.md): the acting user's display fields must come from the same
    // transaction as the event rows, so the actor table is joined directly instead of calling a service (a second transaction).
    // LEFT: a system event has no actor row.
    private fun joined() = table.join(actor.table, JoinType.LEFT, onColumn = table.userId, otherColumn = actor.table.id)

    private val sortableColumns: Map<String, Column<*>> = mapOf("id" to table.id, "createdAt" to table.createdAt)

    /**
     * Inserts an event using the caller's current transaction. The timestamp is [at] (the write's own stamp, so an event and the change
     * it describes share one instant), never a client's. [actingUserId] null records a system event. Rows are immutable: there is no
     * update or delete, and none is wanted - an event outlives its record's changes.
     */
    internal suspend fun createInTransaction(
        ownerId: UInt,
        actingUserId: UInt?,
        type: String,
        eventParams: Map<String, String>,
        at: Long = nowMillis(),
    ): UInt =
        table.insert {
            it[table.ownerId] = ownerId
            it[table.userId] = actingUserId
            it[table.createdAt] = at
            it[table.eventType] = type
            it[table.params] = encodeParams(eventParams)
        }[table.id].value

    /** The record's history page with acting user names; count + rows on one predicate, one transaction. */
    suspend fun listFor(ownerId: UInt, paging: PageRequest): EventLogPage = suspendTransaction(database) {
        val total = joined().selectAll().where { table.ownerId eq ownerId }.count()
        val rows = joined().selectAll()
            .where { table.ownerId eq ownerId }
            .applyPaging(paging, sortableColumns)
            .map { row ->
                EventLogRow(
                    id = row[table.id].value,
                    ownerId = ownerId,
                    userId = row[table.userId]?.value,
                    userName = row.getOrNull(actor.name),
                    userDeleted = row.getOrNull(actor.deleted) ?: false,
                    createdAt = row[table.createdAt],
                    type = row[table.eventType],
                    params = decodeParams(row[table.params]),
                )
            }
            .toList()
        EventLogPage(items = rows, total = total)
    }
}
