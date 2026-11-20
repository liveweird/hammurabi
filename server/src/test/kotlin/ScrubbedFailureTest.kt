package ch.nokillswit

import ch.nokillswit.plugins.ScrubbedFailure
import ch.nokillswit.plugins.scrubbedForLog
import ch.nokillswit.plugins.scrubbedSummary
import io.r2dbc.spi.R2dbcNonTransientResourceException
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The scrubbed rendering of a failure chain the 500 catch-all logs (`plugins/ScrubbedFailure.kt`), over hand-built chains. */
class ScrubbedFailureTest {
    private fun Throwable.chain(): List<Throwable> = generateSequence(this) { it.cause }.toList()

    @Test
    fun `a chain holding a database failure keeps class names, SQLSTATE and frames and drops every message`() {
        val driver = R2dbcNonTransientResourceException("rejected body SECRET-1", "P0001")
        val wrapper = IllegalStateException("wrapped SECRET-2", driver)
        val top = RuntimeException("top SECRET-3", wrapper)

        val scrubbed = top.scrubbedForLog()

        val links = scrubbed.chain()
        assertTrue(links.all { it is ScrubbedFailure }, "every link is a scrubbed one")
        assertEquals(
            listOf(
                "java.lang.RuntimeException",
                "java.lang.IllegalStateException",
                "io.r2dbc.spi.R2dbcNonTransientResourceException [SQLSTATE P0001]",
            ),
            links.map { it.message },
        )
        assertTrue(links.none { "SECRET" in (it.message ?: "") || "SECRET" in it.toString() }, "no original message survives")
        assertEquals(top.stackTrace.toList(), links.first().stackTrace.toList(), "the frames are kept")
        assertEquals(driver.stackTrace.toList(), links.last().stackTrace.toList())
    }

    @Test
    fun `the one-line summary joins the scrubbed links and leaves a plain failure as its toString`() {
        val wrapped = RuntimeException("w SECRET-11", R2dbcNonTransientResourceException("m SECRET-12", "57014"))
        assertEquals(
            "java.lang.RuntimeException <- io.r2dbc.spi.R2dbcNonTransientResourceException [SQLSTATE 57014]",
            wrapped.scrubbedSummary(),
        )
        assertEquals("java.lang.IllegalStateException: plain", IllegalStateException("plain").scrubbedSummary())
    }

    @Test
    fun `a JDBC failure is a database failure too and carries its SQLSTATE`() {
        val scrubbed = RuntimeException("x", SQLException("value SECRET-4 is bad", "22P02")).scrubbedForLog()
        assertEquals("java.sql.SQLException [SQLSTATE 22P02]", scrubbed.chain().last().message)
    }

    @Test
    fun `a chain without a database failure is logged as it is`() {
        val failure = RuntimeException("plain", IllegalArgumentException("inner"))
        assertSame(failure, failure.scrubbedForLog())
    }

    @Test
    fun `a cause cycle still ends and a driver failure without a SQLSTATE names the class only`() {
        val a = RuntimeException("a SECRET-5")
        val b = R2dbcNonTransientResourceException("b SECRET-6", null, 0, a)
        a.initCause(b)

        val scrubbed = a.scrubbedForLog()

        assertEquals(
            listOf("java.lang.RuntimeException", "io.r2dbc.spi.R2dbcNonTransientResourceException"),
            scrubbed.chain().map { it.message },
        )
        assertFalse(scrubbed.chain().any { "SECRET" in it.toString() })
        assertNull(scrubbed.chain().last().cause)
    }

    @Test
    fun `a chain longer than the walk's bound is scrubbed whole, never logged raw`() {
        var failure: Throwable = RuntimeException("deepest SECRET-7")
        repeat(40) { failure = RuntimeException("link $it SECRET-8", failure) }

        val scrubbed = failure.scrubbedForLog()

        assertTrue(scrubbed.chain().all { it is ScrubbedFailure })
        assertTrue(scrubbed.chain().size in 1..32, "the walk is bounded")
        assertFalse(scrubbed.chain().any { "SECRET" in (it.message ?: "") })
    }

    @Test
    fun `a database failure that exists only as a suppressed exception is scrubbed too`() {
        val failure = RuntimeException("outer SECRET-9")
        failure.addSuppressed(R2dbcNonTransientResourceException("rejected body SECRET-10", "P0001"))

        val scrubbed = failure.scrubbedForLog()

        assertEquals(listOf("java.lang.RuntimeException"), scrubbed.chain().map { it.message })
        assertEquals(0, scrubbed.suppressed.size, "no suppressed exception survives")
        assertFalse(scrubbed.chain().any { "SECRET" in it.toString() })
    }

    @Test
    fun `a plain cause cycle with no database failure and nothing suppressed is logged as it is`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertSame(a, a.scrubbedForLog())
    }
}
