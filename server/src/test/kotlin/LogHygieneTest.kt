package ch.nokillswit

import ch.nokillswit.teams.TeamCreateRequest
import ch.nokillswit.users.UserRole
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory

/**
 * Application-log hygiene of a failed database statement: a driver or Exposed message can carry bound values (Postgres echoes a value
 * into a `RAISE` or an input-syntax failure), and a team name or any later free-text field is such a value. So the 500 catch-all logs
 * a database failure as class names, the SQLSTATE and frames only (`plugins/ScrubbedFailure.kt`), and NO log line of ANY logger - the
 * root logger collects all of them - may carry the value. The capture renders every part of an event (message, arguments, key/values,
 * MDC, the throwable with its cause chain). It watches the levels the application is configured to log at (INFO): the DEBUG statement
 * logs of the database driver are off by design and would bind every parameter.
 */
class LogHygieneTest {
    /** Everything an event can carry, as one string. */
    private fun ILoggingEvent.rendered(): String = buildString {
        append(loggerName).append(' ').append(formattedMessage).append(' ').append(message)
        argumentArray?.forEach { append(' ').append(it) }
        keyValuePairs?.forEach { append(' ').append(it.key).append('=').append(it.value) }
        mdcPropertyMap?.forEach { (key, value) -> append(' ').append(key).append('=').append(value) }
        markerList?.forEach { append(' ').append(it.name) }
        throwableProxy?.let { append(' ').append(ThrowableProxyUtil.asString(it)) }
    }

    /** The 500 lines of the capture: what the catch-all logged for a failed request. */
    private fun LogCapture.failureLines(): List<ILoggingEvent> =
        events.filter { it.message.startsWith("Unhandled exception while processing") }

    @Test
    fun `the 500 line names the driver class and 57014`() = testApplication {
        val marker = "HYGIENE-${UUID.randomUUID()}"
        val capture = LogCapture(Logger.ROOT_LOGGER_NAME)
        try {
            usePostgresTestcontainer()
            val admin = seededClient("hyg-cancel", UserRole.ADMIN)

            // A SHAPE pin, not a leak proof (the default message does not expand arguments, the echo test below proves the leak): the
            // create passes every check, its INSERT waits behind a table lock, and cancelling that backend is a database failure (57014)
            // whose 500 line must still name the driver's class and the SQLSTATE after the scrubbing.
            val status = PostgresTestSupport.connect().use { holder ->
                holder.autoCommit = false
                holder.createStatement().use { it.execute("LOCK TABLE teams IN ACCESS EXCLUSIVE MODE") }
                try {
                    coroutineScope {
                        val creating = async { admin.postJson("/api/v1/teams", TeamCreateRequest(name = "Hyg $marker")) }
                        awaitLockWait("INSERT INTO teams")
                        cancelBackends("INSERT INTO teams")
                        creating.await().status
                    }
                } finally {
                    holder.rollback()
                }
            }
            assertEquals(HttpStatusCode.InternalServerError, status)

            val failure = capture.failureLines().map { it.rendered() }
            assertTrue(failure.isNotEmpty(), "the 500 was logged")
            assertTrue(failure.all { "57014" in it }, "the line still names the SQLSTATE")
            assertTrue(failure.all { "PostgresqlNonTransientResourceException" in it }, "the line still names the driver's exception class")
            val leaked = capture.events.filter { marker in it.rendered() }
            assertEquals(emptyList(), leaked.map { it.rendered().take(300) }, "a failed statement logged its bound values")
        } finally {
            capture.detach()
        }
    }

    /**
     * Postgres itself can echo a value into an error message (an input-syntax failure, a `RAISE`, a malformed tsquery), and the driver
     * carries that message in its exception: the catch-all must not render it. A trigger that rejects ONLY this test's marker stands in
     * for such a failure, so the message is raised from the database with the bound name in its text.
     */
    @Test
    fun `a database error whose own message echoes the name answers 500 and leaves the name in no log line`() = testApplication {
        val marker = "HYGIENE-${UUID.randomUUID()}"
        val capture = LogCapture(Logger.ROOT_LOGGER_NAME)
        try {
            usePostgresTestcontainer()
            val admin = seededClient("hyg-echo", UserRole.ADMIN)
            PostgresTestSupport.connect().use { conn ->
                conn.createStatement().use { st ->
                    st.execute(
                        "CREATE OR REPLACE FUNCTION hyg_reject() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN " +
                            "IF position('$marker' in NEW.name) > 0 THEN RAISE EXCEPTION 'rejected name %', NEW.name; " +
                            "END IF; RETURN NEW; END \$\$",
                    )
                    st.execute("CREATE TRIGGER hyg_reject_trg BEFORE INSERT ON teams FOR EACH ROW EXECUTE FUNCTION hyg_reject()")
                }
            }
            try {
                val created = admin.postJson("/api/v1/teams", TeamCreateRequest(name = "Hyg $marker"))
                assertEquals(HttpStatusCode.InternalServerError, created.status)
            } finally {
                PostgresTestSupport.connect().use { conn ->
                    conn.createStatement().use { st ->
                        st.execute("DROP TRIGGER IF EXISTS hyg_reject_trg ON teams")
                        st.execute("DROP FUNCTION IF EXISTS hyg_reject()")
                    }
                }
            }

            val failure = capture.failureLines().map { it.rendered() }
            assertTrue(failure.isNotEmpty(), "the 500 was logged")
            assertTrue(failure.all { "P0001" in it }, "the line still names the SQLSTATE")
            val leaked = capture.events.filter { marker in it.rendered() }
            assertEquals(emptyList(), leaked.map { it.rendered().take(300) }, "a database error message reached a log line")
        } finally {
            capture.detach()
        }
    }

    /**
     * Five loggers print a failure's message, a bound value or a request URI below INFO (Exposed's retry DEBUG line, StatusPages'
     * TRACE line, the R2DBC driver's bound parameters, Ktor Authentication's and RateLimit's traces with `?q=`); `logback.xml` pins all
     * five so a root-level change cannot switch them on.
     */
    @Test
    fun `the loggers that print a failure's message below INFO are pinned to INFO`() {
        listOf(
            "Exposed",
            "io.ktor.server.plugins.statuspages",
            "io.r2dbc.postgresql",
            "io.ktor.server.auth.Authentication",
            "io.ktor.server.plugins.ratelimit.RateLimit",
        ).forEach { name ->
            assertEquals(Level.INFO, (LoggerFactory.getLogger(name) as Logger).level, name)
        }
    }

    /** `pg_cancel_backend` for every session currently waiting on a lock whose query contains [queryFragment]. */
    private fun cancelBackends(queryFragment: String) {
        PostgresTestSupport.connect().use { probe ->
            probe.prepareStatement(
                "SELECT pg_cancel_backend(pid) FROM pg_stat_activity " +
                    "WHERE wait_event_type = 'Lock' AND pid <> pg_backend_pid() AND query ILIKE ?",
            ).use { st ->
                st.setString(1, "%$queryFragment%")
                st.executeQuery().close()
            }
        }
    }
}
