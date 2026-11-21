package ch.nokillswit.notifications

import ch.nokillswit.audit.audit
import ch.nokillswit.infra.time.MILLIS_PER_DAY
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.Key
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.slf4j.LoggerFactory

/** The default of `notifications.email.maxNoticesPerActorPerDay`. */
internal const val DEFAULT_EMAIL_BUDGET_PER_ACTOR = 200

/** The ceiling of `notifications.email.maxNoticesPerActorPerDay`. */
internal const val MAX_EMAIL_BUDGET_PER_ACTOR = 10_000

/** Makes sure the actor's row of the day exists (constant text, two casts); a no-op once it does. */
private const val ENSURE_ROW = """
INSERT INTO notification_email_budget (actor_id, day) VALUES (CAST(? AS int8), CAST(? AS int4))
ON CONFLICT (actor_id, day) DO NOTHING"""

/**
 * Reads and LOCKS the actor's row of the day: the notices already admitted to the email digest and whether the budget was already
 * exceeded today. The lock lasts to the end of the caller's transaction, so two writes of one actor serialize here. Plain `FOR UPDATE`
 * (not `FOR NO KEY UPDATE`, `persistence.md` "Row locks vs. FK locks") is right: nothing references this row, so no foreign-key check
 * can wait behind the lock, and the row's own key is what a concurrent `ENSURE_ROW` insert conflicts on.
 */
private const val LOCK_ROW = """
SELECT admitted, exceeded_at IS NOT NULL FROM notification_email_budget
WHERE actor_id = CAST(? AS int8) AND day = CAST(? AS int4) FOR UPDATE"""

private const val SAVE_ROW = """
UPDATE notification_email_budget SET admitted = CAST(? AS int4), exceeded_at = COALESCE(exceeded_at, CAST(? AS int8))
WHERE actor_id = CAST(? AS int8) AND day = CAST(? AS int4)"""

/** What [NotificationEmailBudget.reserve] decided for a transaction: the charged account (null = exempt) and the UTC day, both final. */
private class Reservation(val account: UInt?, val day: Int)

/** The transaction-local slot of the reservation (Exposed's per-transaction user data: gone when the transaction ends). */
private val RESERVATION = Key<Reservation>()

private val log = LoggerFactory.getLogger("ch.nokillswit.notifications.NotificationEmailBudget")

/**
 * The per-actor mail budget (V21, `adr-model.md` "Notifications", `security.md` "Notices"): the most notices one actor's writes can make
 * eligible for the email digest in a UTC day, [perDay] of them. The bell is not touched: the notice is always made, and over the budget
 * only its email is withheld (the caller stamps `emailed_at`, "settled, never mailed"). Adder's budget counts distinct recipients; an ADR's
 * audience is up to 99 people and a mail names the ADR by key only, so the unit here is the notice (the user's decision D1).
 *
 * The first [perDay] notices of the day are admitted, in the order the caller hands them over, and every later one is withheld for good
 * (it is not delayed and not summarised). Every notice counts, whoever its recipient is: whether the recipient switched the kind's email
 * off is read at SEND time, so it cannot be known here. An ADMIN actor is NOT exempt. A notice a write makes with no actor (the expiry
 * sweep's, the reminder pass's) is CHARGED to the ADR's current Approver by the caller ([NotificationService]; the person who decides when
 * a decision in force ends), so two people cannot arm the system into an unbudgeted flood; an ADR with no active Approver is the one
 * exemption. The day is the epoch day of [clock] (UTC), so the budget resets at 00:00 UTC.
 *
 * The charged account's row is locked `FOR UPDATE` until the transaction ends, so the budget holds when one account is charged by
 * several writes at once. It is the FIRST lock of every write that can make notices ([reserve], taken before the ADR rows and before the
 * numbering and link advisory locks) and the reservation is TRANSACTION-SCOPED: it fixes which account and which UTC day the write's
 * notices are charged to, so a later change of the ADR's Approver or the passing of midnight cannot ask for another row late. The order
 * is the same everywhere, so the budget row sits in no cycle, and a write queued behind the account's other in-flight writes never
 * holds a global lock while it waits. The wait is deliberately NOT bounded by a `lock_timeout`: its worst case is
 * that account's in-flight notice-making writes, each a few milliseconds, and a timeout would turn a harmless queue into a failed write.
 * Not tied to the notices: removing a participant deletes nothing here, so adding and removing people cannot reset the budget, and a
 * rolled-back write rolls its spending back with it.
 */
internal class NotificationEmailBudget(
    private val perDay: Int,
    private val clock: () -> Long,
) {
    init {
        require(perDay in 1..MAX_EMAIL_BUDGET_PER_ACTOR) { "the mail budget must be 1..$MAX_EMAIL_BUDGET_PER_ACTOR (was $perDay)" }
    }

    private fun dayOf(millis: Long): Int = (millis / MILLIS_PER_DAY).toInt()

    /**
     * Makes sure the [actorId]'s row of [day] exists and LOCKS it until the caller's transaction ends; answers the notices already
     * admitted that day and whether it was already marked exceeded. Idempotent inside one transaction (the second call finds its own lock).
     */
    private suspend fun lock(actorId: UInt, day: Int): Pair<Int, Boolean> {
        val transaction = TransactionManager.current()
        val actor = LongColumnType() to actorId.toLong()
        val dayArg = IntegerColumnType() to day
        transaction.exec(ENSURE_ROW, listOf(actor, dayArg))
        return transaction.exec(
            LOCK_ROW,
            listOf(actor, dayArg),
            explicitStatementType = StatementType.SELECT,
        ) { row -> row.get(0, Int::class.javaObjectType)!! to (row.get(1, Boolean::class.javaObjectType) == true) }
            ?.toList().orEmpty().filterNotNull().single()
    }

    /**
     * Reserves the budget for the whole transaction BEFORE the write takes any other lock (the ADR rows, the numbering and link advisory
     * locks): locks [account]'s row of today and records, as transaction-local state, exactly WHICH account and WHICH UTC day the write's
     * notices will be charged to. A null [account] reserves "exempt" (a system notice about an ADR without an active Approver). The
     * decision is final for the transaction: [withheld] charges that account for that day even if the Approver changed, or midnight
     * passed, in between, so the budget row is the first lock of every notice-making transaction and never a late one, the same order
     * everywhere: no cycle exists, and a write queued behind the account's other writes waits for this row alone, never while holding a
     * global lock. A second call in the same transaction changes nothing (the first reservation wins).
     */
    suspend fun reserve(account: UInt?) {
        val transaction = TransactionManager.current()
        if (transaction.getUserData(RESERVATION) != null) return
        val day = dayOf(clock())
        if (account != null) lock(account, day)
        transaction.putUserData(RESERVATION, Reservation(account, day))
    }

    /**
     * Of [made], the ids of the notices just made on [adrId] (ascending, the order they were made), answers those over the budget and so
     * withheld from the digest. The account and the day are the RESERVED ones ([reserve]); only a transaction that reserved nothing (a
     * write path that forgot to; a test driving the service directly) falls back to [fallbackAccount] and today, and logs a WARN for it
     * (its lock is late and could join a cycle). Audits `notification.email_budget_exceeded` for EVERY write that withholds something (the
     * bucket and the ids-and-counts-only fields keep it small; a rolled-back write repeats it, which is harmless), so a silencing run
     * leaves a line per write, not one per day.
     */
    suspend fun withheld(adrId: UInt, made: List<Int>, fallbackAccount: suspend () -> UInt?): Set<Int> {
        if (made.isEmpty()) return emptySet()
        val transaction = TransactionManager.current()
        val reservation = transaction.getUserData(RESERVATION)
        val now = clock()
        val account = if (reservation != null) reservation.account else fallbackAccount()
        if (account == null) return emptySet()
        val day = reservation?.day ?: dayOf(now)
        if (reservation == null) {
            log.warn("Notices of ADR {} charged to account {} with no reservation: a write path forgot to reserve first", adrId, account)
        }
        val (admitted, alreadyExceeded) = lock(account, day)
        val room = (perDay - admitted).coerceAtLeast(0)
        val over = made.drop(room).toSet()
        val newlyAdmitted = made.size - over.size
        if (newlyAdmitted > 0 || (over.isNotEmpty() && !alreadyExceeded)) {
            transaction.exec(
                SAVE_ROW,
                listOf(
                    IntegerColumnType() to admitted + newlyAdmitted,
                    LongColumnType() to if (over.isEmpty()) null else now,
                    LongColumnType() to account.toLong(),
                    IntegerColumnType() to day,
                ),
            )
        }
        if (over.isNotEmpty()) {
            audit(
                "notification.email_budget_exceeded",
                "byUserId" to account.toLong(),
                "adrId" to adrId.toLong(),
                "budget" to perDay,
                "withheld" to over.size,
            )
        }
        return over
    }

    /** The retention of the budget rows: deletes the days before the one [cutoffMillis] falls on. Answers how many rows went. */
    suspend fun purgeBefore(cutoffMillis: Long): Int {
        val cutoffDay = (cutoffMillis / MILLIS_PER_DAY).toInt()
        return BudgetDays.deleteWhere { BudgetDays.day less cutoffDay }
    }

    /** Only the day column, for the retention delete (its row count is the answer); every other statement is the constants above. */
    private object BudgetDays : Table("notification_email_budget") {
        val day = integer("day")
    }
}
