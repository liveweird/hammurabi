package ch.nokillswit

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V19 on a populated database (`persistence.md` "Successor acceptance (V19)"): a scratch database is built at V18 with raw rows - links in
 * effect, links still pending, a soft-deleted successor, an unlinked one - then migrated on. A link already in effect (the successor holds
 * its `SUCCESSOR_ACTIVATED` event) is backfilled as accepted, a pending one stays unaccepted, and nothing else about any row moves. The
 * scratch database is the fork's own and is dropped after, so the shared suite database is never touched.
 */
class SuccessorAcceptanceMigrationTest {
    private fun flywayFor(database: String, target: String? = null): Flyway = Flyway.configure()
        .dataSource(PostgresTestSupport.jdbcUrlFor(database), PostgresTestSupport.user, PostgresTestSupport.password)
        .locations("classpath:db/migration")
        .apply { if (target != null) target(MigrationVersion.fromVersion(target)) }
        .load()

    private fun execute(sql: String, database: String? = null) {
        val connection = if (database == null) PostgresTestSupport.connect() else PostgresTestSupport.connect(database)
        connection.use { c -> c.createStatement().use { it.execute(sql) } }
    }

    private fun <T> query(database: String, sql: String, row: (ResultSet) -> T): List<T> =
        PostgresTestSupport.connect(database).use { c ->
            c.createStatement().use { st -> st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(row(rs)) } } }
        }

    /** The id of the ADR with [number], as a scalar sub-select. */
    private fun idOf(number: Int) = "(SELECT id FROM adrs WHERE \"number\" = $number)"

    /** Every column of the release the migration must leave alone, per ADR in id order. */
    private fun release(database: String) = query(
        database,
        "SELECT \"number\", name, status, revision, supersedes_id, marked_as_deleted FROM adrs ORDER BY id",
    ) { listOf(it.getInt(1), it.getString(2), it.getString(3), it.getLong(4), it.getObject(5), it.getBoolean(6)) }

    private fun refuses(database: String, sql: String, why: String) =
        PostgresTestSupport.connect(database).use { connection ->
            connection.createStatement().use { statement -> assertFailsWith<SQLException>(why) { statement.executeUpdate(sql) } }
        }

    /** One ADR row of the V18 schema; [supersedes] is the predecessor's NUMBER (rows are keyed by it here). */
    private fun adr(number: Int, status: String, supersedes: Int? = null, deleted: Boolean = false) =
        "INSERT INTO adrs (\"number\", \"name\", problem, status, created_by, created_at, updated_at, status_changed_at, " +
            "marked_as_deleted, supersedes_id) VALUES ($number, 'Migration $number', 'p', '$status', (SELECT min(id) FROM users), " +
            "1, 1, 1, $deleted, " + (supersedes?.let(::idOf) ?: "NULL") + ");"

    private fun event(number: Int, type: String) =
        "INSERT INTO adr_events (adr_id, user_id, created_at, event_type, params) " +
            "VALUES (${idOf(number)}, NULL, 1, '$type', '{}');"

    @Test
    fun `links in effect are backfilled as accepted, links still pending stay unaccepted and nothing else moves`() {
        val database = "accept_" + UUID.randomUUID().toString().replace("-", "").take(12)
        execute("CREATE DATABASE $database")
        try {
            assertEquals(18, flywayFor(database, target = "18").migrate().migrationsExecuted, "a fresh database to the V18 schema")
            val script = listOf(
                // 1: superseded by 2 (in effect); 3: withdrawn after it activated over 4, which it superseded (in effect).
                adr(1, "SUPERSEDED"), adr(2, "ACTIVE", supersedes = 1), event(2, "SUCCESSOR_ACTIVATED"),
                adr(4, "SUPERSEDED"), adr(3, "WITHDRAWN", supersedes = 4), event(3, "SUCCESSOR_ACTIVATED"),
                // 5 is in force and 6 waits in review to replace it; 8 is in force and the draft 7 waits to replace it.
                adr(5, "ACTIVE"), adr(6, "IN_REVIEW", supersedes = 5), adr(8, "ACTIVE"), adr(7, "PROBLEM_RAISED", supersedes = 8),
                // 9 was soft-deleted while pending; 10 was soft-deleted after it took effect; 11 was cancelled; 12 was unlinked.
                adr(13, "ACTIVE"), adr(9, "IN_REVIEW", supersedes = 13, deleted = true),
                adr(14, "SUPERSEDED"), adr(10, "ACTIVE", supersedes = 14, deleted = true), event(10, "SUCCESSOR_ACTIVATED"),
                adr(15, "ACTIVE"), adr(11, "CANCELLED", supersedes = 15), event(11, "SUCCESSOR_UNLINKED"),
                adr(12, "SOLUTION_DRAFT"), event(12, "SUCCESSOR_UNLINKED"),
                // 16 carries the activation event but no link any more (nothing to accept).
                adr(16, "ACTIVE"), event(16, "SUCCESSOR_ACTIVATED"),
            ).joinToString("\n")
            execute(script, database)
            val before = release(database)

            // Stopped at V19: later migrations (V20 rewrites lens documents) have their own populated-upgrade test.
            val migrated = flywayFor(database, target = "19").migrate()
            assertTrue(migrated.success)
            assertEquals(1, migrated.migrationsExecuted, "exactly V19 ran on the populated database")
            flywayFor(database, target = "19").validate()

            val accepted = query(
                database,
                "SELECT a.\"number\", p.\"number\" FROM adrs a LEFT JOIN adrs p ON p.id = a.supersedes_accepted_id ORDER BY a.\"number\"",
            ) { it.getInt(1) to it.getObject(2)?.let { _ -> it.getInt(2) } }.toMap()
            // In effect: backfilled with the predecessor the link names, the soft-deleted one included.
            assertEquals(1, accepted[2])
            assertEquals(4, accepted[3])
            assertEquals(14, accepted[10])
            // Pending, cancelled, unlinked, soft-deleted while pending and an event with no link: unaccepted.
            for (number in listOf(6, 7, 9, 11, 12, 16)) assertNull(accepted[number], "ADR $number is not accepted")
            // Rows with no link at all have nothing to accept.
            for (number in listOf(1, 4, 5, 8, 13, 14, 15)) assertNull(accepted[number], "ADR $number has no link")

            assertEquals(before, release(database), "the migration changed no column of the release")

            // The CHECK: an acceptance can only name the link's own predecessor.
            val another = "UPDATE adrs SET supersedes_accepted_id = ${idOf(5)} WHERE \"number\" = 2"
            refuses(database, another, "an acceptance naming another predecessor")
            refuses(database, "UPDATE adrs SET supersedes_accepted_id = ${idOf(5)} WHERE \"number\" = 5", "an acceptance with no link")
        } finally {
            // A failing drop must not mask the assertion error that is already propagating.
            runCatching { execute("DROP DATABASE IF EXISTS $database WITH (FORCE)") }
        }
    }
}
