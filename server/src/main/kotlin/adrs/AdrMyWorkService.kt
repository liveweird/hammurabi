package ch.nokillswit.adrs

import ch.nokillswit.infra.json.decodeParams
import ch.nokillswit.infra.time.MILLIS_PER_DAY
import io.ktor.util.AttributeKey
import io.r2dbc.spi.IsolationLevel
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.time.Clock
import java.time.LocalDate

val AdrMyWorkServiceKey = AttributeKey<AdrMyWorkService>("AdrMyWorkService")

private const val DRAFT_STALE_DAYS = 14L
private const val REVIEW_STALE_DAYS = 7L
private const val ACTIVITY_DAYS = 7L

/**
 * The event types the activity feed and the "last activity" of an ADR read: meaningful personal activity. Participant and tag fan-out
 * cannot crowd decision work out of the short feed. Every [AdrEventType] is classified in exactly ONE of this set and
 * [MY_WORK_EXCLUDED_EVENT_TYPES] (`AdrMyWorkTest` pins it), so a new event type fails the build until someone decides.
 */
internal val MY_WORK_ACTIVITY_EVENT_TYPES: Set<AdrEventType> = setOf(
    AdrEventType.CREATED, AdrEventType.UPDATED, AdrEventType.TRANSITIONED, AdrEventType.EXPIRED, AdrEventType.SUPERSEDED,
    AdrEventType.SUCCESSOR_LINKED, AdrEventType.SUCCESSOR_UNLINKED, AdrEventType.SUCCESSOR_ACTIVATED, AdrEventType.SUCCESSOR_ACCEPTED,
    AdrEventType.PREDECESSOR_ENDED,
    AdrEventType.COMMENTED, AdrEventType.QUESTION_ASKED, AdrEventType.QUESTION_ANSWERED, AdrEventType.QUESTION_REOPENED,
    AdrEventType.ISSUE_RAISED, AdrEventType.ESCALATED, AdrEventType.ISSUE_RESOLVED, AdrEventType.ISSUE_REOPENED,
)

/** Deliberately NOT personal activity: roster and tag housekeeping, which stays in the full History (`adr-model.md` "My work"). */
internal val MY_WORK_EXCLUDED_EVENT_TYPES: Set<AdrEventType> = setOf(
    AdrEventType.PARTICIPANT_ADDED, AdrEventType.PARTICIPANT_REMOVED, AdrEventType.PARTICIPANT_CHANGED, AdrEventType.TAGS_CHANGED,
)

/** Enum names only (never user text), so the quoted list is safe to inline in the statements. */
private val ACTIVITY_EVENT_TYPES_SQL = MY_WORK_ACTIVITY_EVENT_TYPES.joinToString(", ") { "'${it.name}'" }

private val WORKSPACE_FACTS_SQL = """
    LEFT JOIN LATERAL (
        SELECT e.created_at FROM adr_events e
        WHERE e.adr_id = a.id AND e.event_type IN ($ACTIVITY_EVENT_TYPES_SQL)
        ORDER BY e.created_at DESC, e.id DESC LIMIT 1
    ) activity ON true
    LEFT JOIN LATERAL (
        SELECT e.created_at FROM adr_events e
        WHERE a.status IN ('PROBLEM_RAISED', 'SOLUTION_DRAFT') AND e.adr_id = a.id
            AND e.created_at >= a.status_changed_at AND (
                e.event_type IN ('CREATED', 'TRANSITIONED') OR
                (e.event_type = 'UPDATED' AND (e.params::jsonb ->> 'changed') ~
                    '(^|,)(name|problem|context|decision|alternatives|consequences)(,|${'$'})')
            )
        ORDER BY e.created_at DESC, e.id DESC LIMIT 1
    ) progress ON true
    LEFT JOIN LATERAL (
        SELECT e.created_at FROM adr_events e
        WHERE a.status = 'SOLUTION_DRAFT' AND e.adr_id = a.id AND e.created_at = a.status_changed_at
            AND e.event_type = 'TRANSITIONED' AND e.params::jsonb ->> 'from' = 'IN_REVIEW'
            AND e.params::jsonb ->> 'to' = 'SOLUTION_DRAFT'
            AND NOT EXISTS (
                SELECT 1 FROM adr_participants driver
                WHERE driver.adr_id = a.id AND driver.role = 'DRIVER' AND driver.user_id = e.user_id
            )
        ORDER BY e.id DESC LIMIT 1
    ) returned ON true
    LEFT JOIN LATERAL (
        SELECT d.created_at
        FROM adr_discussion d WHERE a.status = 'ACTIVE' AND d.adr_id = a.id AND d.kind = 'ESCALATION'
            AND d.status = 'OPEN' AND NOT d.marked_as_deleted
        ORDER BY d.created_at, d.id LIMIT 1
    ) escalation ON true
""".trimIndent()

private enum class WorkItemKind { ACTION, WATCH, ADR }

private data class WorkItem(val kind: WorkItemKind, val adr: AdrMyWorkAdr, val attention: AdrMyWorkAttention?)

private data class Workspace(
    val actions: AdrMyWorkAttentionSection,
    val watching: AdrMyWorkAttentionSection,
    val adrs: AdrMyWorkAdrSection,
)

/**
 * The personal ADR workspace. Two capped statements run in one repeatable-read transaction: one classifies the caller's open ADRs into
 * actions, attention and the recent list; one reads curated seven-day history. Both start from `idx_adr_participants_user`; the bounded
 * per-ADR facts use the ADR-leading history and open-discussion indexes.
 */
class AdrMyWorkService(
    private val database: R2dbcDatabase,
    private val expiryReminderDays: Int,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun myWork(callerUserId: UInt, role: AdrRole?): AdrMyWorkResponse = suspendTransaction(
        database,
        transactionIsolation = IsolationLevel.REPEATABLE_READ,
        readOnly = true,
    ) {
        val now = clock.millis()
        val workspace = workspace(callerUserId, role, now, LocalDate.now(clock))
        AdrMyWorkResponse(
            generatedAt = now,
            actions = workspace.actions,
            watching = workspace.watching,
            activity = activity(callerUserId, role, now),
            adrs = workspace.adrs,
        )
    }

    private fun roleClause(role: AdrRole?): String = if (role == null) "" else " AND p.role = ?"

    private fun membershipArguments(callerUserId: UInt, role: AdrRole?) = buildList<Pair<IColumnType<*>, Any>> {
        add(LongColumnType() to callerUserId.toLong())
        if (role != null) add(TextColumnType() to role.name)
    }

    private fun capped(arguments: List<Pair<IColumnType<*>, Any>>) =
        arguments + (IntegerColumnType() to (MY_WORK_SECTION_CAP + 1))

    private fun workspaceArguments(callerUserId: UInt, role: AdrRole?, now: Long, today: LocalDate) = capped(
        membershipArguments(callerUserId, role) + listOf(
            BooleanColumnType() to (expiryReminderDays > 0),
            TextColumnType() to today.plusDays(expiryReminderDays.toLong()).toString(),
            LongColumnType() to (now - REVIEW_STALE_DAYS * MILLIS_PER_DAY),
            LongColumnType() to (now - DRAFT_STALE_DAYS * MILLIS_PER_DAY),
            BooleanColumnType() to (expiryReminderDays > 0),
            TextColumnType() to today.plusDays(expiryReminderDays.toLong()).toString(),
            LongColumnType() to (now - REVIEW_STALE_DAYS * MILLIS_PER_DAY),
            LongColumnType() to (now - DRAFT_STALE_DAYS * MILLIS_PER_DAY),
        ),
    )

    private suspend fun R2dbcTransaction.workspace(
        callerUserId: UInt,
        role: AdrRole?,
        now: Long,
        today: LocalDate,
    ): Workspace {
        val sql = """
            WITH mine AS (
                SELECT a.id, a."number", a.name, a.status, a.expires_on, a.created_at, a.status_changed_at,
                       p.role, approver.name AS approver_name,
                       COALESCE(activity.created_at, a.created_at) AS last_activity_at,
                       COALESCE(progress.created_at, a.status_changed_at) AS last_progress_at,
                       returned.created_at IS NOT NULL AS returned, escalation.created_at AS escalation_at
                FROM adr_participants p
                JOIN adrs a ON a.id = p.adr_id AND NOT a.marked_as_deleted
                    AND a.status IN ('PROBLEM_RAISED', 'SOLUTION_DRAFT', 'IN_REVIEW', 'ACTIVE')
                LEFT JOIN adr_participants ap ON ap.adr_id = a.id AND ap.role = 'APPROVER'
                LEFT JOIN users approver ON approver.id = ap.user_id AND NOT approver.marked_as_deleted
                $WORKSPACE_FACTS_SQL
                WHERE p.user_id = ?${roleClause(role)}
            ), classified AS (
                SELECT *,
                    CASE
                        WHEN role = 'APPROVER' AND status = 'ACTIVE' AND ? AND expires_on IS NOT NULL AND expires_on <= ? THEN 'EXPIRING'
                        WHEN role IN ('DRIVER', 'APPROVER') AND status = 'ACTIVE' AND escalation_at IS NOT NULL THEN 'ESCALATION'
                        WHEN role = 'APPROVER' AND status = 'IN_REVIEW' AND status_changed_at <= ? THEN 'STALLED_REVIEW'
                        WHEN role = 'APPROVER' AND status = 'IN_REVIEW' THEN 'REVIEW'
                        WHEN role = 'DRIVER' AND status = 'SOLUTION_DRAFT' AND returned THEN 'RETURNED'
                        WHEN role='DRIVER' AND status IN ('PROBLEM_RAISED', 'SOLUTION_DRAFT') AND last_progress_at <= ? THEN 'STALLED_DRAFT'
                    END AS action_reason
                FROM mine
            ), reasoned AS (
                SELECT *,
                    CASE WHEN action_reason IS NULL THEN
                        CASE
                            WHEN status = 'ACTIVE' AND ? AND expires_on IS NOT NULL AND expires_on <= ? THEN 'EXPIRING'
                            WHEN status = 'ACTIVE' AND escalation_at IS NOT NULL THEN 'ESCALATION'
                            WHEN status = 'IN_REVIEW' AND status_changed_at <= ? THEN 'STALLED_REVIEW'
                            WHEN status = 'SOLUTION_DRAFT' AND returned THEN 'RETURNED'
                            WHEN status IN ('PROBLEM_RAISED', 'SOLUTION_DRAFT') AND last_progress_at <= ? THEN 'STALLED_DRAFT'
                        END
                    END AS watch_reason
                FROM classified
            ), items AS (
                SELECT *, 'ACTION' AS item_kind, action_reason AS reason,
                    CASE action_reason
                        WHEN 'EXPIRING' THEN status_changed_at WHEN 'ESCALATION' THEN escalation_at
                        WHEN 'REVIEW' THEN status_changed_at WHEN 'STALLED_REVIEW' THEN status_changed_at
                        WHEN 'RETURNED' THEN status_changed_at ELSE last_progress_at
                    END AS reason_since
                FROM reasoned WHERE action_reason IS NOT NULL
                UNION ALL
                SELECT *, 'WATCH' AS item_kind, watch_reason AS reason,
                    CASE watch_reason
                        WHEN 'EXPIRING' THEN status_changed_at WHEN 'ESCALATION' THEN escalation_at
                        WHEN 'STALLED_REVIEW' THEN status_changed_at WHEN 'RETURNED' THEN status_changed_at ELSE last_progress_at
                    END AS reason_since
                FROM reasoned WHERE watch_reason IS NOT NULL
                UNION ALL
                SELECT *, 'ADR' AS item_kind, NULL AS reason, last_activity_at AS reason_since FROM reasoned
            ), ranked AS (
                SELECT *, row_number() OVER (
                    PARTITION BY item_kind
                    ORDER BY CASE WHEN item_kind = 'ADR' THEN last_activity_at END DESC,
                        CASE reason WHEN 'EXPIRING' THEN 1 WHEN 'ESCALATION' THEN 2
                            WHEN 'REVIEW' THEN 3 WHEN 'STALLED_REVIEW' THEN 3 WHEN 'RETURNED' THEN 4 ELSE 5 END,
                        CASE WHEN reason = 'EXPIRING' THEN expires_on END, reason_since, id
                ) AS rn
                FROM items
            )
            SELECT * FROM ranked WHERE rn <= ?
            ORDER BY CASE item_kind WHEN 'ACTION' THEN 1 WHEN 'WATCH' THEN 2 ELSE 3 END, rn
        """.trimIndent()
        val rows = exec(sql, workspaceArguments(callerUserId, role, now, today), explicitStatementType = StatementType.SELECT) { row ->
            val adr = adr(
                id = row.get("id", Int::class.javaObjectType)!!, number = row.get("number", Int::class.javaObjectType)!!,
                name = row.get("name", String::class.java)!!, role = row.get("role", String::class.java)!!,
                status = row.get("status", String::class.java)!!, expiresOn = row.get("expires_on", String::class.java),
                lastActivityAt = row.get("last_activity_at", Long::class.javaObjectType)!!,
            )
            workItem(
                adr, row.get("item_kind", String::class.java)!!, row.get("reason", String::class.java),
                row.get("reason_since", Long::class.javaObjectType)!!,
                row.get("approver_name", String::class.java),
            )
        }?.toList().orEmpty().filterNotNull()
        return rows.toWorkspace()
    }

    private fun workItem(adr: AdrMyWorkAdr, rawKind: String, rawReason: String?, since: Long, approverName: String?): WorkItem {
        val reason = rawReason?.let(AdrMyWorkReason::valueOf)
        return WorkItem(
            kind = WorkItemKind.valueOf(rawKind),
            adr = adr,
            attention = reason?.let { AdrMyWorkAttention(adr, it, since, approverName) },
        )
    }

    private fun List<WorkItem>.toWorkspace(): Workspace {
        val actions = filter { it.kind == WorkItemKind.ACTION }.mapNotNull { it.attention }
        val watching = filter { it.kind == WorkItemKind.WATCH }.mapNotNull { it.attention }
        val adrs = filter { it.kind == WorkItemKind.ADR }.map { it.adr }
        return Workspace(
            AdrMyWorkAttentionSection(actions.take(MY_WORK_SECTION_CAP), actions.size > MY_WORK_SECTION_CAP),
            AdrMyWorkAttentionSection(watching.take(MY_WORK_SECTION_CAP), watching.size > MY_WORK_SECTION_CAP),
            AdrMyWorkAdrSection(adrs.take(MY_WORK_SECTION_CAP), adrs.size > MY_WORK_SECTION_CAP),
        )
    }

    private suspend fun R2dbcTransaction.activity(callerUserId: UInt, role: AdrRole?, now: Long): AdrMyWorkActivitySection {
        val sql = """
            SELECT a.id, a."number", a.name, a.status, a.expires_on, p.role,
                   max(e.created_at) OVER (PARTITION BY a.id) AS last_activity_at,
                   e.id AS event_id, e.user_id, actor.name AS actor_name,
                   COALESCE(actor.marked_as_deleted, false) AS actor_deleted, e.event_type, e.params, e.created_at
            FROM adr_participants p
            JOIN adrs a ON a.id = p.adr_id AND NOT a.marked_as_deleted
            JOIN LATERAL (
                SELECT recent.* FROM adr_events recent
                WHERE recent.adr_id = a.id AND recent.created_at >= ?
                    AND recent.event_type IN ($ACTIVITY_EVENT_TYPES_SQL)
                ORDER BY recent.created_at DESC, recent.id DESC
                LIMIT ?
            ) e ON true
            LEFT JOIN users actor ON actor.id = e.user_id
            WHERE p.user_id = ?${roleClause(role)}
            ORDER BY e.created_at DESC, e.id DESC
            LIMIT ?
        """.trimIndent()
        val arguments = listOf(
            LongColumnType() to (now - ACTIVITY_DAYS * MILLIS_PER_DAY),
            IntegerColumnType() to (MY_WORK_SECTION_CAP + 1),
        ) + capped(membershipArguments(callerUserId, role))
        val rows = exec(sql, arguments, explicitStatementType = StatementType.SELECT) { row ->
            AdrMyWorkActivity(
                adr = adr(
                    id = row.get("id", Int::class.javaObjectType)!!,
                    number = row.get("number", Int::class.javaObjectType)!!,
                    name = row.get("name", String::class.java)!!,
                    role = row.get("role", String::class.java)!!,
                    status = row.get("status", String::class.java)!!,
                    lastActivityAt = row.get("last_activity_at", Long::class.javaObjectType)!!,
                    expiresOn = row.get("expires_on", String::class.java),
                ),
                event = AdrEventResponse(
                    id = row.get("event_id", Int::class.javaObjectType)!!.toUInt(),
                    actorId = row.get("user_id", Long::class.javaObjectType)?.toUInt(),
                    actorName = row.get("actor_name", String::class.java),
                    actorDeleted = row.get("actor_deleted", Boolean::class.javaObjectType)!!,
                    type = AdrEventType.valueOf(row.get("event_type", String::class.java)!!),
                    params = decodeParams(row.get("params", String::class.java)!!),
                    createdAt = row.get("created_at", Long::class.javaObjectType)!!,
                ),
            )
        }?.toList().orEmpty().filterNotNull()
        return AdrMyWorkActivitySection(rows.take(MY_WORK_SECTION_CAP), rows.size > MY_WORK_SECTION_CAP)
    }

    private fun adr(
        id: Int,
        number: Int,
        name: String,
        role: String,
        status: String,
        lastActivityAt: Long,
        expiresOn: String?,
    ) = AdrMyWorkAdr(
        id = id.toUInt(),
        key = adrKey(number),
        name = name,
        role = AdrRole.valueOf(role),
        status = AdrStatus.valueOf(status),
        lastActivityAt = lastActivityAt,
        expiresOn = expiresOn,
    )
}
