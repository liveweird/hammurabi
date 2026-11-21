package ch.nokillswit.notifications

import ch.nokillswit.adrs.AdrService
import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.audit.audit
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.time.MILLIS_PER_DAY
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.LocalDate

private val log = LoggerFactory.getLogger("ch.nokillswit.notifications.ExpiryReminders")

/** The most ADRs one candidate page holds; the pass pages until none is left, each ADR in its own transaction. */
private const val CANDIDATE_PAGE = 100

/** Published once the sweep has built the pass: `NotificationSweepTest`'s handle to run it by hand. A TEST seam; nothing else reads it. */
internal val ExpiryRemindersKey = AttributeKey<ExpiryReminders>("ExpiryReminders")

/**
 * Stamps the marker and answers whether THIS call made the ADR's reminder due: the first reminder for an ADR inserts, a reminder for a
 * date the marker already holds changes nothing (no row returned), and a CHANGED date overwrites it and re-arms the reminder - but at
 * most once per ADR per UTC day: the marker holds only the last date, so without the day gate an Approver toggling the date A, B, A
 * would re-arm a reminder at every hourly pass. A date changed again on the day of the last reminder is reminded the next day.
 */
private const val MARK_REMINDED = """
INSERT INTO adr_expiry_reminders (adr_id, expires_on, sent_at) VALUES (CAST(? AS int4), CAST(? AS varchar), CAST(? AS int8))
ON CONFLICT (adr_id) DO UPDATE SET expires_on = EXCLUDED.expires_on, sent_at = EXCLUDED.sent_at
WHERE adr_expiry_reminders.expires_on <> EXCLUDED.expires_on AND adr_expiry_reminders.sent_at < CAST(? AS int8)
RETURNING adr_id"""

/**
 * The expiry reminder (`adr-model.md` "Notifications"; Covenant's `ReleaseLineReminders` shape): an `ACTIVE` ADR whose expiry date falls
 * within the next [reminderDays] days (today up to the last day in force, UTC) notifies its Driver and Approver ONCE per expiry date
 * (a re-armed one at most once a UTC day, [MARK_REMINDED]). The
 * dedupe is the marker `adr_expiry_reminders` (V18, one row per ADR holding the date it was reminded for), stamped in the same
 * transaction as the notices, so a changed date re-arms the reminder and a crash between the two repeats nothing. [reminderDays] 0
 * disables the pass. Idempotent and safe on several replicas: the candidate scan is only a filter, each ADR is re-read under its row lock
 * and the marker's upsert is what decides. "Today" is the injected [clock], so tests run it deterministically.
 */
internal class ExpiryReminders(
    private val database: R2dbcDatabase,
    private val notices: NotificationService,
    private val reminderDays: Int,
    private val clock: Clock = Clock.systemUTC(),
) {
    private object Reminders : Table("adr_expiry_reminders") {
        val adrId = reference("adr_id", AdrService.Adrs)
        val expiresOn = varchar("expires_on", length = 10)
    }

    private val adrs = AdrService.Adrs

    /** Reminds every candidate; answers how many ADRs THIS call reminded. One ADR's failure is logged and does not stop the rest. */
    suspend fun run(): Int {
        if (reminderDays <= 0) return 0
        val today = LocalDate.now(clock)
        val first = today.toString()
        val last = today.plusDays(reminderDays.toLong()).toString()
        var after = 0u
        var reminded = 0
        while (true) {
            val ids = candidateIds(after, first, last)
            if (ids.isEmpty()) return reminded
            reminded += ids.count { remindQuietly(it, first, last) }
            after = ids.last()
        }
    }

    private suspend fun candidateIds(after: UInt, first: String, last: String): List<UInt> = suspendTransaction(database) {
        adrs.select(adrs.id).where {
            adrs.active() and (adrs.status eq AdrStatus.ACTIVE.name) and adrs.expiresOn.isNotNull() and
                (adrs.expiresOn greaterEq first) and (adrs.expiresOn lessEq last) and (adrs.id greater after) and
                notExists(Reminders.select(Reminders.adrId)
                    .where { (Reminders.adrId eq adrs.id) and (Reminders.expiresOn eq adrs.expiresOn) })
        }.orderBy(adrs.id).limit(CANDIDATE_PAGE).map { it[adrs.id].value }.toList()
    }

    private suspend fun remindQuietly(id: UInt, first: String, last: String): Boolean = try {
        remind(id, first, last)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        // Not the message: a driver message can carry bound values (observability.md "The 500 line").
        log.warn("Could not remind about the expiry of ADR {}: {}", id, failure.javaClass.name)
        false
    }

    private suspend fun remind(id: UInt, first: String, last: String): Boolean {
        val done = suspendTransaction(database) {
            // The notices are charged to the ADR's Approver (no actor): their budget row is the first lock, as in a user write.
            notices.reserve(null, id)
            val row = adrs.selectAll().where { (adrs.id eq id) and adrs.active() }
                .forUpdate(ForUpdateOption.PostgreSQL.ForNoKeyUpdate).toList().singleOrNull() ?: return@suspendTransaction null
            val expiresOn = row[adrs.expiresOn]
            if (row[adrs.status] != AdrStatus.ACTIVE.name || expiresOn == null || expiresOn < first || expiresOn > last) {
                return@suspendTransaction null
            }
            val stamp = nowMillis()
            val marked = TransactionManager.current().exec(
                MARK_REMINDED,
                listOf(
                    IntegerColumnType() to id.toInt(), TextColumnType() to expiresOn, LongColumnType() to stamp,
                    LongColumnType() to stamp - stamp % MILLIS_PER_DAY,
                ),
                explicitStatementType = StatementType.SELECT,
            ) { it.get(0, Int::class.javaObjectType) }?.toList().orEmpty().isNotEmpty()
            if (!marked) return@suspendTransaction null
            val plan = NoticePlan(NotificationType.EXPIRY_REMINDER, id, Audience.Roles(id, LEADS), detail = expiresOn)
            Triple(row[adrs.number], expiresOn, notices.make(plan, null, stamp).size)
        } ?: return false
        audit("adr.expiry_reminded", "adrId" to id.toLong(), "number" to done.first, "expiresOn" to done.second, "recipients" to done.third)
        return true
    }
}
