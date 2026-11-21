package ch.nokillswit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** API-LIST-001: multi-statement list reads keep their count, rows and side reads in one stable database snapshot. */
class ReadSnapshotTest {
    private val repoRoot = File(System.getProperty("repo.root") ?: "..")

    private fun source(path: String): String = File(repoRoot, path).readText()

    @Test
    fun `ADR plain and searched lists use a repeatable read-only snapshot`() {
        val plain = source("server/src/main/kotlin/adrs/AdrListService.kt")
        val searched = source("server/src/main/kotlin/infra/db/SearchBudget.kt")
        assertSnapshot(plain)
        assertSnapshot(searched)
    }

    @Test
    fun `notification list uses a repeatable read-only snapshot`() {
        assertSnapshot(source("server/src/main/kotlin/notifications/NotificationService.kt"))
    }

    private fun assertSnapshot(source: String) {
        assertTrue("transactionIsolation = IsolationLevel.REPEATABLE_READ" in source)
        assertTrue("readOnly = true" in source)
    }
}
