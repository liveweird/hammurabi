package ch.nokillswit

import ch.nokillswit.adrs.AdrFilterDocument
import ch.nokillswit.adrs.AdrRole
import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.lenses.LensRequest
import ch.nokillswit.lenses.LensResponse
import ch.nokillswit.lenses.LensService
import ch.nokillswit.lenses.LensVisibility
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import java.sql.ResultSet
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V20 on a populated database (`persistence.md` "Lens filter names (V20)"): a scratch database is built at V19 with raw lens rows - all
 * four old filter keys, one, none, a soft-deleted lens - then migrated on. The four keys are renamed with their JSON values untouched,
 * every other key stays, a document without an old key is left byte-identical (odd spacing and all), and the rewritten documents decode
 * as the list's current filter document (and through the real lenient reader, `GET /lenses/{id}`); a document holding BOTH spellings
 * keeps the new key's value. The scratch database is the fork's own and is dropped after.
 */
class LensFilterNamesMigrationTest {
    private val oldKeys = listOf("participant", "role", "supersedes", "supersededBy")
    private val json = Json { ignoreUnknownKeys = false }

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

    private fun lens(name: String, filters: String, deleted: Boolean = false) =
        "INSERT INTO lenses (\"name\", visibility, filters, created_by, created_at, updated_at, marked_as_deleted) " +
            "VALUES ('$name', 'PRIVATE', '$filters', (SELECT min(id) FROM users), 1, 1, $deleted);"

    private fun stored(database: String): Map<String, String> =
        query(database, "SELECT \"name\", filters FROM lenses") { it.getString(1) to it.getString(2) }.toMap()

    @Test
    fun `the four old keys are renamed in every lens, other keys and untouched documents stay as they were`() = testApplication {
        usePostgresTestcontainer()
        val database = "lenses_" + UUID.randomUUID().toString().replace("-", "").take(12)
        execute("CREATE DATABASE $database")
        try {
            assertEquals(19, flywayFor(database, target = "19").migrate().migrationsExecuted, "a fresh database to the V19 schema")
            val untouched = """{ "status" : ["ACTIVE"],  "q":"budget", "hasOpenIssues": true }"""
            execute(
                listOf(
                    lens(
                        "all four",
                        """{"status":["ACTIVE","IN_REVIEW"],"participant":7,"role":"DRIVER","supersedes":false,""" +
                            """"supersededBy":true,"tag":["data"],"q":"x"}""",
                    ),
                    lens("one key", """{"supersedes":true}"""),
                    lens("role only", """{"role":"APPROVER","participant":9}"""),
                    lens("none", untouched),
                    lens("empty", "{}"),
                    lens("deleted", """{"participant":3,"supersededBy":false}""", deleted = true),
                    lens("already new", """{"participantId":4,"hasSuccessor":true}"""),
                    // A lens saved by 0.9.x after a rollback and migrated again: both spellings in one document, the new key wins.
                    lens("both spellings", """{"supersededBy":false,"hasSuccessor":true,"participant":1,"participantId":2}"""),
                ).joinToString("\n"),
                database,
            )

            val migrated = flywayFor(database, target = "20").migrate()
            assertTrue(migrated.success)
            assertEquals(1, migrated.migrationsExecuted, "exactly V20 ran on the populated database")
            flywayFor(database).validate()

            val after = stored(database)
            fun doc(name: String): JsonObject = json.parseToJsonElement(after.getValue(name)).jsonObject
            assertEquals(
                json.parseToJsonElement(
                    """{"status":["ACTIVE","IN_REVIEW"],"participantId":7,"participantRole":"DRIVER","hasPredecessor":false,""" +
                        """"hasSuccessor":true,"tag":["data"],"q":"x"}""",
                ),
                doc("all four"),
                "four keys renamed; the number stays a number, the booleans booleans, the arrays arrays",
            )
            assertEquals(
                AdrFilterDocument(
                    status = listOf(AdrStatus.ACTIVE, AdrStatus.IN_REVIEW),
                    q = "x",
                    participantId = 7u,
                    participantRole = AdrRole.DRIVER,
                    tag = listOf("data"),
                    hasPredecessor = false,
                    hasSuccessor = true,
                ),
                json.decodeFromJsonElement(AdrFilterDocument.serializer(), doc("all four")),
                "the rewritten document is the list's current filter document",
            )
            assertEquals(json.parseToJsonElement("""{"hasPredecessor":true}"""), doc("one key"))
            assertEquals(json.parseToJsonElement("""{"participantRole":"APPROVER","participantId":9}"""), doc("role only"))
            assertEquals(json.parseToJsonElement("""{"participantId":3,"hasSuccessor":false}"""), doc("deleted"), "a soft-deleted lens too")
            assertEquals(untouched, after.getValue("none"), "a document with no old key is byte-identical")
            assertEquals(
                json.parseToJsonElement("""{"hasSuccessor":true,"participantId":2}"""),
                doc("both spellings"),
                "a document holding both spellings keeps the new key's value, whatever the key order",
            )
            assertEquals("{}", after.getValue("empty"))
            assertEquals("""{"participantId":4,"hasSuccessor":true}""", after.getValue("already new"))
            // The real reader: the migrated text planted into a lens of the shared database comes back through GET /lenses/{id}.
            val owner = adrActor("lens-v20")
            val seed = LensRequest("V20 ${UUID.randomUUID().toString().take(8)}", LensVisibility.PRIVATE, AdrFilterDocument(q = "x"))
            val planted = owner.client.postJson("/api/v1/lenses", seed).body<LensResponse>()
            suspendTransaction(sharedDatabaseForTests()) {
                LensService.Lenses.update({ LensService.Lenses.id eq planted.id }) { it[filters] = after.getValue("all four") }
            }
            assertEquals(
                AdrFilterDocument(
                    status = listOf(AdrStatus.ACTIVE, AdrStatus.IN_REVIEW),
                    q = "x",
                    participantId = 7u,
                    participantRole = AdrRole.DRIVER,
                    tag = listOf("data"),
                    hasPredecessor = false,
                    hasSuccessor = true,
                ),
                owner.client.get("/api/v1/lenses/${planted.id}").body<LensResponse>().filters,
                "the lenient reader serves the migrated document with every filter in force",
            )
            assertTrue(
                after.values.none { text -> oldKeys.any { "\"$it\"" in text } },
                "no old key is left anywhere",
            )
        } finally {
            // A failing drop must not mask the assertion error that is already propagating.
            runCatching { execute("DROP DATABASE IF EXISTS $database WITH (FORCE)") }
        }
    }
}
