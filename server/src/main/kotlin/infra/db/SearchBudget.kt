package ch.nokillswit.infra.db

import ch.nokillswit.plugins.isQueryCanceled
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * One DEADLINE for the whole database work of a search request (Adder's `SearchBudget`, ported). A transaction's `queryTimeout` bounds
 * each STATEMENT, and a search runs several (the count, the page, the page's participants, counts and tags, the snippets), so a
 * per-statement budget alone would let a request run for the sum of them. The budget is armed before each STEP of the search: the
 * `queryTimeout` becomes the whole seconds LEFT (rounded up: the driver takes whole seconds), and once the deadline has passed the next
 * step is never started. A step may issue a few quick statements under one arm (the page's three side reads), each capped at the time
 * left when the step began. A request therefore ends within the configured budget plus at most one second of rounding, plus the overrun of
 * the last step's few statements: a bound by steps, not an exact one. [clock] is nanoseconds (`System.nanoTime`; a test passes its own).
 */
class SearchBudget(totalSeconds: Int, private val clock: () -> Long = System::nanoTime) {
    private val endNanos = clock() + totalSeconds * NANOS_PER_SECOND

    /** The whole seconds left (at least 1); throws [BudgetSpent] when the deadline has passed. */
    fun remainingSeconds(): Int {
        val left = endNanos - clock()
        if (left <= 0) throw BudgetSpent()
        return ((left + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND).toInt()
    }

    /** Arms the statements that follow with the time left. */
    fun arm(transaction: R2dbcTransaction) {
        transaction.queryTimeout = remainingSeconds()
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}

/**
 * The ONE budgeted read transaction: [block] runs in a single transaction under a fresh [SearchBudget] of [timeoutSeconds]. A statement
 * the budget cancelled (SQLSTATE `57014`) or a deadline spent between two statements calls [onTimeout], which audits and throws the
 * caller's typed `429`; any other failure propagates untouched. The cancelled statement's cause is deliberately dropped (a database
 * failure's message can echo a bound value, and a search term is user content).
 */
suspend fun <T> budgetedTransaction(
    database: R2dbcDatabase,
    timeoutSeconds: Int,
    clock: () -> Long = System::nanoTime,
    onTimeout: () -> Nothing,
    block: suspend R2dbcTransaction.(SearchBudget) -> T,
): T {
    val budget = SearchBudget(timeoutSeconds, clock)
    try {
        return suspendTransaction(database) { block(budget) }
    } catch (_: BudgetSpent) {
        onTimeout()
    } catch (failure: Exception) {
        if (!failure.isQueryCanceled()) throw failure
        onTimeout()
    }
}

/** The deadline of a [SearchBudget] passed between two statements: answered like a cancelled statement (the audited `429`). */
class BudgetSpent : RuntimeException("The search budget is spent")
