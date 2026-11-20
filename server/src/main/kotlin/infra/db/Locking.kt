package ch.nokillswit.infra.db

import io.r2dbc.spi.IsolationLevel
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * Runs [block] inside a READ COMMITTED transaction after executing every one of [lockStatements], in the order given - the
 * cooperating-writer table-lock protocol (Toadie's `infra/db/Locking.kt`, ported with the tag registry; `persistence.md` "Tag
 * categories (V13)"). READ COMMITTED is explicit so a writer that waited on a lock sees the prior writer's committed rows before it
 * decides.
 *
 * **Order matters.** A caller passing more than one lock statement MUST list them in the fixed global order its own deadlock-free
 * protocol requires; this helper only executes them in the order it receives them.
 */
suspend fun <T> lockingTransaction(
    database: R2dbcDatabase,
    vararg lockStatements: String,
    block: suspend R2dbcTransaction.() -> T,
): T = suspendTransaction(database, transactionIsolation = IsolationLevel.READ_COMMITTED) {
    lockStatements.forEach { exec(it) }
    block()
}
