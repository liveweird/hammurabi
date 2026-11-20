package ch.nokillswit

import ch.nokillswit.adrs.AdrParticipantRequest
import ch.nokillswit.adrs.AdrRole
import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.adrs.DiscussionKind
import io.ktor.client.request.delete
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Row locks vs. FK locks (persistence.md "Row locks vs. FK locks"), for the first FK-referenced ADR row: every ADR write locks the row
 * `FOR NO KEY UPDATE`, which does not conflict with the `FOR KEY SHARE` an FK check (a participant or an event insert, a long future
 * transaction) holds on it - but still serializes with another writer of the row. A raw JDBC transaction stands in for the FK check
 * and is rolled back when closed.
 */
class AdrRowLockTest {

    private class OpenTransaction : AutoCloseable {
        private val connection: Connection = PostgresTestSupport.connect().also { it.autoCommit = false }

        fun execute(sql: String) = connection.createStatement().use { it.execute(sql) }

        override fun close() {
            runCatching { connection.rollback() }
            connection.close()
        }
    }

    private suspend fun <T> promptly(block: suspend () -> T): T = withContext(Dispatchers.Default) { withTimeout(15_000) { block() } }

    @Test
    fun `ADR writes do not wait behind a key-share on the row`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        OpenTransaction().use { fk ->
            fk.execute("SELECT id FROM adrs WHERE id = ${adr.id} FOR KEY SHARE")
            val edited = adr.asUpdate().copy(context = "edited under key-share")
            val edit = promptly { cast.driver.client.saveAdr(adr.id, adr.revision, edited) }
            assertEquals(HttpStatusCode.NoContent, edit.status, "the document PUT")
            val move = promptly { cast.driver.client.moveAdr(adr.id, adr.revision + 1, AdrStatus.SOLUTION_DRAFT) }
            assertEquals(HttpStatusCode.OK, move.status, "a transition")
            val set = listOf(
                AdrParticipantRequest(cast.driver.id, AdrRole.DRIVER),
                AdrParticipantRequest(cast.approver.id, AdrRole.APPROVER),
            )
            val people = promptly { cast.driver.client.setParticipants(adr.id, set) }
            assertEquals(HttpStatusCode.NoContent, people.status, "the participants")
            val deleted = promptly { cast.admin.client.delete("/api/v1/adrs/${adr.id}") }
            assertEquals(HttpStatusCode.NoContent, deleted.status, "the soft delete")
        }
    }

    @Test
    fun `a second writer of the row still waits for the first`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        OpenTransaction().use { writer ->
            writer.execute("SELECT id FROM adrs WHERE id = ${adr.id} FOR NO KEY UPDATE")
            coroutineScope {
                val pending = async(Dispatchers.Default) {
                    cast.driver.client.saveAdr(adr.id, adr.revision, adr.asUpdate().copy(context = "waits for the lock"))
                }
                awaitLockWait("adrs")
                delay(300)
                assertFalse(pending.isCompleted, "the write waits while another transaction holds the row")
                writer.close()
                assertEquals(HttpStatusCode.NoContent, promptly { pending.await() }.status)
            }
        }
    }

    @Test
    fun `a discussion write takes the same lock - free beside a key-share, queued behind another writer`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        OpenTransaction().use { fk ->
            fk.execute("SELECT id FROM adrs WHERE id = ${adr.id} FOR KEY SHARE")
            val posted = promptly { cast.stranger.client.postEntry(adr.id, DiscussionKind.COMMENT) }
            assertEquals(HttpStatusCode.Created, posted.status, "a post beside a key-share")
        }
        OpenTransaction().use { writer ->
            writer.execute("SELECT id FROM adrs WHERE id = ${adr.id} FOR NO KEY UPDATE")
            coroutineScope {
                val pending = async(Dispatchers.Default) { cast.stranger.client.postEntry(adr.id, DiscussionKind.COMMENT) }
                awaitLockWait("adrs")
                delay(300)
                assertFalse(pending.isCompleted, "the post waits while a lifecycle move may be in flight")
                writer.close()
                assertEquals(HttpStatusCode.Created, promptly { pending.await() }.status)
            }
        }
    }
}
