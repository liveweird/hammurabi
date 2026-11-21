package ch.nokillswit.notifications

import ch.nokillswit.adrs.AdrDiscussionService
import ch.nokillswit.adrs.AdrEventDescriptor
import ch.nokillswit.adrs.AdrNotices
import ch.nokillswit.adrs.AdrParticipantService
import ch.nokillswit.adrs.AdrService
import ch.nokillswit.adrs.adrKey
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.users.UserService
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

internal val NotificationServiceKey = AttributeKey<NotificationService>("NotificationService")

private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to NotificationService.Notifications.id,
    "createdAt" to NotificationService.Notifications.createdAt,
)

/**
 * The most notices a count reads: a list's `total` is capped here, so a recipient's count never grows with their history
 * (`security.md` "Notices").
 */
const val MAX_COUNTED_NOTICES = 1000

/** Rows one "mark everything seen" chunk updates, each chunk its own short transaction. */
internal const val SEEN_BATCH = 1000

/** Rows one retention batch deletes, each batch its own short transaction. */
internal const val PURGE_BATCH = 1000

/**
 * ONE statement for every notice of a write, constant text: the parallel arrays (recipient, kind, ADR, second ADR, detail) unnest into
 * one row each, kept only for an ACTIVE account that has NOT switched the kind's in-app channel off. Every parameter is cast; a missing
 * second ADR is carried as 0 and a missing detail as '' (no id is 0 and no detail is empty), so no array holds a null. `ORDER BY` the
 * array position gives the rows ids in plan order (the planner would otherwise pick any), and `RETURNING` answers the rows that were
 * really inserted (who was reached).
 */
private const val INSERT_NOTICES = """
INSERT INTO notifications (recipient_id, notification_type, actor_id, adr_id, other_adr_id, detail, created_at)
SELECT u.r, u.t, CAST(? AS int8), u.a, NULLIF(u.o, 0), NULLIF(u.d, ''), CAST(? AS int8)
FROM unnest(CAST(? AS int8[]), CAST(? AS varchar[]), CAST(? AS int4[]), CAST(? AS int4[]), CAST(? AS varchar[])) WITH ORDINALITY AS u(r, t, a, o, d, n)
WHERE EXISTS (SELECT 1 FROM users x WHERE x.id = u.r AND x.marked_as_deleted = false)
  AND NOT EXISTS (SELECT 1 FROM user_notification_preferences p
                  WHERE p.user_id = u.r AND p.channel = 'IN_APP' AND p.notification_type = u.t)
ORDER BY u.n
RETURNING recipient_id"""

/** The ONE sortable whitelist of the notification list: `id` (time order) and `createdAt`. */
val NOTIFICATION_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

/**
 * The notices of a person (V17 `notifications`, `adr-model.md` "Notifications") and the [AdrNotices] port of the ADR writes. A notice is a
 * POINTER, ids and one short detail: it is made INSIDE the write that caused it ([onEvent], from the history event the write just recorded,
 * in the write's own transaction), so a rolled-back write announces nothing. Rows are hard-deleted with their ADR or user (the foreign
 * keys) and by retention ([purgeOlderThan]).
 *
 * The list is the caller's own rows on ADRs that still exist, of a kind this build knows. Names are read for the page only (the actor's
 * from `users`, soft-deleted accounts included; the ADR's key and name and the second ADR's key from `adrs`), in the list's own
 * transaction. No name or text is stored and none is ever logged.
 */
internal class NotificationService(private val database: R2dbcDatabase) : AdrNotices {
    /** The notices. Ids and one short detail: there is no text column and no soft delete. */
    object Notifications : UIntIdTable("notifications") {
        val recipientId = uinteger("recipient_id")
        val type = varchar("notification_type", length = 40)

        /** Null = the system. */
        val actorId = uinteger("actor_id").nullable()
        val adrId = uinteger("adr_id")
        val otherAdrId = uinteger("other_adr_id").nullable()
        val detail = varchar("detail", length = 40).nullable()
        val createdAt = long("created_at")

        /** Null while unseen. */
        val seenAt = long("seen_at").nullable()

        /** Null until the email digest claims the notice. */
        val emailedAt = long("emailed_at").nullable()
    }

    override suspend fun onEvents(adrId: UInt, byUserId: UInt?, events: List<AdrEventDescriptor>, at: Long) {
        val plans = events.mapNotNull { noticeFor(adrId, it) }
        if (plans.isNotEmpty()) makeAll(plans, byUserId, at)
    }

    /** Makes the notices of [plan] for its audience minus [byUserId] (an actor never notifies themself); answers who was reached. */
    suspend fun make(plan: NoticePlan, byUserId: UInt?, at: Long): List<UInt> = makeAll(listOf(plan), byUserId, at)

    /**
     * Makes the notices of every plan of ONE write in a single multi-row `INSERT`, each for its audience minus [byUserId] (an actor never
     * notifies themself), in the caller's transaction. Answers the recipient of every row that was really inserted (a deleted account or
     * a switched-off kind makes none), one entry per notice.
     */
    suspend fun makeAll(plans: List<NoticePlan>, byUserId: UInt?, at: Long): List<UInt> {
        val recipients = ArrayList<Long>()
        val types = ArrayList<String>()
        val adrIds = ArrayList<Int>()
        val otherIds = ArrayList<Int>()
        val details = ArrayList<String>()
        for (plan in plans) {
            for (recipient in audienceOf(plan.audience).filter { it != byUserId }.distinct()) {
                recipients += recipient.toLong()
                types += plan.type.name
                adrIds += plan.adrId.toInt()
                otherIds += plan.otherAdrId?.toInt() ?: 0
                details += plan.detail.orEmpty()
            }
        }
        if (recipients.isEmpty()) return emptyList()
        val arguments = listOf(
            LongColumnType() to byUserId?.toLong(),
            LongColumnType() to at,
            ArrayColumnType<Long, List<Long>>(LongColumnType()) to recipients,
            ArrayColumnType<String, List<String>>(TextColumnType()) to types,
            ArrayColumnType<Int, List<Int>>(IntegerColumnType()) to adrIds,
            ArrayColumnType<Int, List<Int>>(IntegerColumnType()) to otherIds,
            ArrayColumnType<String, List<String>>(TextColumnType()) to details,
        )
        return TransactionManager.current().exec(INSERT_NOTICES, arguments, explicitStatementType = StatementType.SELECT) { row ->
            row.get(0, Long::class.javaObjectType)!!.toUInt()
        }?.toList().orEmpty().filterNotNull()
    }

    /** The people an [audience] names right now, in the caller's transaction (the participants as the write left them). */
    suspend fun audienceOf(audience: Audience): List<UInt> = when (audience) {
        is Audience.Person -> listOf(audience.userId)
        is Audience.Roles -> {
            val p = AdrParticipantService.AdrParticipants
            val adrs = AdrService.Adrs
            // The status predicate is an EXISTS on the ADR's own row, in the same statement: the status as the write left it.
            val inStatus: Op<Boolean> = audience.onlyWhile
                ?.let { status -> exists(adrs.select(adrs.id).where { (adrs.id eq audience.adrId) and (adrs.status eq status.name) }) }
                ?: Op.TRUE
            p.select(p.userId).where { (p.adrId eq audience.adrId) and (p.role inList audience.roles.map { it.name }) and inStatus }
                .map { it[p.userId].value }.toList()
        }
        is Audience.EntryAuthor -> {
            val d = AdrDiscussionService.AdrDiscussion
            d.select(d.authorId).where { d.id eq audience.entryId }.map { it[d.authorId].value }.toList()
        }
    }

    /**
     * One page of the caller's notices, newest first by default: ONE predicate for the count and the rows in ONE transaction. The
     * `total` is CAPPED at [MAX_COUNTED_NOTICES] (the count reads at most that many plus one row), and a page starting at or past the cap
     * is empty without the rows being read (no OFFSET over a flooded recipient's history). [seen] null is both. A notice of a soft-deleted
     * ADR is hidden.
     */
    suspend fun list(callerUserId: UInt, seen: Boolean?, paging: PageRequest): NotificationListResult =
        suspendTransaction(database) {
            val n = Notifications
            val adrs = AdrService.Adrs
            val source = n.join(adrs, JoinType.INNER, onColumn = n.adrId, otherColumn = adrs.id)
            var predicate: Op<Boolean> = (n.recipientId eq callerUserId) and adrs.active() and
                (n.type inList NotificationType.entries.map { it.name })
            seen?.let { predicate = predicate and (if (it) n.seenAt.isNotNull() else n.seenAt.isNull()) }
            val counted = source.select(n.id).where { predicate }.limit(MAX_COUNTED_NOTICES + 1).count()
            val total = counted.coerceAtMost(MAX_COUNTED_NOTICES.toLong())
            // A page that starts at or past the cap holds nothing the capped count admits: the OFFSET never runs.
            if ((paging.page - 1).toLong() * paging.pageSize >= MAX_COUNTED_NOTICES) {
                return@suspendTransaction NotificationListResult(emptyList(), total)
            }
            val rows = source.select(
                n.id, n.type, n.seenAt, n.createdAt, n.actorId, n.adrId, n.otherAdrId, n.detail, adrs.number, adrs.name,
            ).where { predicate }.applyPaging(paging, SORTABLE_COLUMNS).toList()
            if (rows.isEmpty()) return@suspendTransaction NotificationListResult(emptyList(), total)
            val actors = namesOf(rows.mapNotNull { it[n.actorId] }.toSet())
            val others = keysOf(rows.mapNotNull { it[n.otherAdrId] }.toSet())
            NotificationListResult(
                rows.map { row ->
                    NotificationResponse(
                        id = row[n.id].value,
                        type = NotificationType.valueOf(row[n.type]),
                        seen = row[n.seenAt] != null,
                        createdAt = row[n.createdAt],
                        actorId = row[n.actorId],
                        actorName = row[n.actorId]?.let { actors[it] },
                        adrId = row[n.adrId],
                        adrKey = adrKey(row[adrs.number]),
                        adrName = row[adrs.name],
                        otherAdrId = row[n.otherAdrId],
                        otherAdrKey = row[n.otherAdrId]?.let { others[it] },
                        detail = row[n.detail],
                    )
                },
                total,
            )
        }

    private suspend fun namesOf(ids: Set<UInt>): Map<UInt, String> {
        if (ids.isEmpty()) return emptyMap()
        val users = UserService.Users
        return users.select(users.id, users.name).where { users.id inList ids }.map { it[users.id].value to it[users.name] }.toList()
            .toMap()
    }

    private suspend fun keysOf(ids: Set<UInt>): Map<UInt, String> {
        if (ids.isEmpty()) return emptyMap()
        val adrs = AdrService.Adrs
        return adrs.select(adrs.id, adrs.number)
            .where { (adrs.id inList ids) and adrs.active() }.map { it[adrs.id].value to adrKey(it[adrs.number]) }.toList().toMap()
    }

    /**
     * Marks the caller's notice [id] seen at [at]. False when the caller has no VISIBLE such notice - it is someone else's, does not
     * exist, is of a soft-deleted ADR or of a kind this build does not know (the list hides those): all the same answer, so an id probes
     * nothing; a visible notice already seen is true and unchanged.
     */
    suspend fun markSeen(callerUserId: UInt, id: UInt, at: Long): Boolean = suspendTransaction(database) {
        val n = Notifications
        val adrs = AdrService.Adrs
        val visible = n.join(adrs, JoinType.INNER, onColumn = n.adrId, otherColumn = adrs.id)
            .select(n.id, n.seenAt)
            .where {
                (n.id eq id) and (n.recipientId eq callerUserId) and adrs.active() and
                    (n.type inList NotificationType.entries.map { it.name })
            }.toList().singleOrNull() ?: return@suspendTransaction false
        if (visible[n.seenAt] == null) n.update({ (n.id eq id) and n.seenAt.isNull() }) { it[seenAt] = at }
        true
    }

    /**
     * Marks every unseen notice of the caller seen at [at], in chunks of [SEEN_BATCH] rows each in its own short transaction (one
     * `UPDATE` over a flooded recipient's thousands of rows would hold its locks and its connection for as long); answers how many.
     * Notices made while it runs (a higher id than the newest at the start) stay unseen, as they would after one statement. Nobody
     * else's row is touched.
     */
    suspend fun markAllSeen(
        callerUserId: UInt,
        at: Long,
        chunk: Int = SEEN_BATCH,
        /** A TEST seam: runs between a chunk's select and its update (the gap a concurrent "seen" or delete falls into). */
        afterSelect: suspend (List<UInt>) -> Unit = {},
    ): Int {
        val n = Notifications
        val ceiling = suspendTransaction(database) {
            n.select(n.id).where { (n.recipientId eq callerUserId) and n.seenAt.isNull() }
                .orderBy(n.id, SortOrder.DESC).limit(1).map { it[n.id].value }.toList().singleOrNull()
        } ?: return 0
        var total = 0
        do {
            // The loop runs while the chunk SELECTED was full, not while it UPDATED a full chunk: a row seen or deleted by someone
            // else between the two statements makes the update short, and stopping there would leave the rest unseen.
            val (selected, moved) = suspendTransaction(database) {
                val ids = n.select(n.id)
                    .where { (n.recipientId eq callerUserId) and n.seenAt.isNull() and (n.id lessEq ceiling) }
                    .orderBy(n.id).limit(chunk).map { it[n.id].value }.toList()
                afterSelect(ids)
                ids.size to if (ids.isEmpty()) 0 else n.update({ (n.id inList ids) and n.seenAt.isNull() }) { it[seenAt] = at }
            }
            total += moved
        } while (selected == chunk)
        return total
    }

    /**
     * Deletes the notices created before [cutoffMillis] (retention), in batches of [batchSize] rows, each its own short transaction
     * (`ORDER BY created_at LIMIT n FOR UPDATE SKIP LOCKED`): never one unbounded `DELETE`, and a row an ADR write holds is left for the
     * next pass. Answers how many went; [onBatch] sees each batch's size (a test seam). The batch is read straight off
     * `idx_notifications_created`, so a pass costs its deletions and never a scan of the table. Outside any ADR write; the sweep runs it.
     */
    suspend fun purgeOlderThan(cutoffMillis: Long, batchSize: Int = PURGE_BATCH, onBatch: (Int) -> Unit = {}): Int {
        var total = 0
        do {
            val deleted = purgeBatch(cutoffMillis, batchSize)
            total += deleted
            if (deleted > 0) onBatch(deleted)
        } while (deleted == batchSize)
        return total
    }

    private suspend fun purgeBatch(cutoffMillis: Long, size: Int): Int = suspendTransaction(database) {
        val n = Notifications
        val ids = n.select(n.id).where { n.createdAt less cutoffMillis }
            .orderBy(n.createdAt)
            .limit(size)
            .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
            .map { it[n.id].value }.toList()
        if (ids.isEmpty()) 0 else n.deleteWhere { n.id inList ids }
    }
}
