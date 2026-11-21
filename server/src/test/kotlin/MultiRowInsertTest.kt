package ch.nokillswit

import ch.nokillswit.infra.db.StatementProbe
import ch.nokillswit.infra.db.insertRowsIgnoringConflicts
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `infra/db/MultiRowInsert.kt`'s `insertRowsIgnoringConflicts` writes exactly what Exposed's `batchInsert` writes, only as multi-row
 * `VALUES`
 * statements. Every case inserts the SAME rows through both into two owners of the scratch table
 * (`MultiRowScratch`) and compares what the database holds, so a difference in value conversion, null handling, the client-side
 * defaults or the chunking shows up as a row difference, not a guess. The donor project's version ran against its bulk tables, which
 * Hammurabi does not carry; the writers are generic and ported as they were.
 */
class MultiRowInsertTest {
    private data class Item(val id: Long, val name: String, val note: String?)

    private val t = MultiRowScratch

    private fun Item.asRow(owner: Int) = listOf(owner, id, name, note)

    private suspend fun read(owner: Int): List<List<Any?>> = suspendTransaction(sharedDatabaseForTests()) {
        t.selectAll().where { t.owner eq owner }.orderBy(t.id to SortOrder.ASC).toList().map { row ->
            listOf(row[t.owner], row[t.id], row[t.name], row[t.note], row[t.flag])
        }
    }

    private suspend fun cleanUp(vararg owners: Int) = t.clear(*owners)

    @Test
    fun `rows - including a second chunk and NULL notes - equal what batchInsert writes`() = runBlocking {
        t.ensureCreated()
        // 5 columns x 7,000 rows = 35,000 bind parameters: above the 32,000 cap, so two statements.
        val rows = (1..7_000).map { Item(it.toLong(), "name-$it", if (it % 5 == 0) null else "note $it") }
        try {
            suspendTransaction(sharedDatabaseForTests()) {
                rows.chunked(500).forEach { chunk ->
                    t.batchInsert(chunk) {
                        this[t.owner] = OWNER_BATCH
                        this[t.id] = it.id
                        this[t.name] = it.name
                        this[t.note] = it.note
                    }
                }
                t.insertRowsIgnoringConflicts(rows) {
                    this[t.owner] = OWNER_ROWS
                    this[t.id] = it.id
                    this[t.name] = it.name
                    this[t.note] = it.note
                }
            }
            val expected = read(OWNER_BATCH)
            val actual = read(OWNER_ROWS)
            assertEquals(rows.map { it.asRow(OWNER_BATCH) + false }, expected, "the reference write must round-trip the input")
            assertEquals(expected.map { it.drop(1) }, actual.map { it.drop(1) }, "the writer must store the same rows as batchInsert")
            assertTrue(actual.any { it[3] == null } && actual.any { it[3] != null }, "both NULL and non-NULL notes were exercised")
        } finally {
            cleanUp(OWNER_BATCH, OWNER_ROWS)
        }
    }

    @Test
    fun `a client-side default column no row sets is filled like batchInsert and nullable columns stay NULL`() = runBlocking {
        t.ensureCreated()
        try {
            suspendTransaction(sharedDatabaseForTests()) {
                t.batchInsert(listOf(1L, 2L, 3L)) { id ->
                    this[t.owner] = OWNER_BATCH
                    this[t.id] = id
                    this[t.name] = "n-$id"
                }
                t.insertRowsIgnoringConflicts(listOf(1L, 2L, 3L)) { id ->
                    this[t.owner] = OWNER_ROWS
                    this[t.id] = id
                    this[t.name] = "n-$id"
                }
            }
            val expected = read(OWNER_BATCH)
            assertEquals(3, expected.size)
            assertEquals(false, expected.first()[4], "the client-side default applies when no row sets flag")
            assertEquals(null, expected.first()[3], "an unset nullable column stays NULL")
            assertEquals(expected.map { it.drop(1) }, read(OWNER_ROWS).map { it.drop(1) })
        } finally {
            cleanUp(OWNER_BATCH, OWNER_ROWS)
        }
    }

    @Test
    fun `insertRowsIgnoringConflicts drops a present row and a repeated key, first write wins, across chunks`() = runBlocking {
        t.ensureCreated()
        try {
            suspendTransaction(sharedDatabaseForTests()) {
                t.insertRowsIgnoringConflicts(listOf(1L)) {
                    this[t.owner] = OWNER_ROWS
                    this[t.id] = it
                    this[t.name] = "present"
                }
                // 5 columns -> 6,400 rows per statement: 7,002 rows are two statements; id 1 is already stored, ids 2 and 6,999 repeat.
                val rows = (1L..7_000L).map { it to "first" } + listOf(2L to "second", 6_999L to "second")
                t.insertRowsIgnoringConflicts(rows) {
                    this[t.owner] = OWNER_ROWS
                    this[t.id] = it.first
                    this[t.name] = it.second
                }
                t.insertRowsIgnoringConflicts(emptyList<Pair<Long, String>>()) { this[t.owner] = OWNER_ROWS }
            }
            val stored = read(OWNER_ROWS).associateBy { it[1] as Long }
            assertEquals(7_000, stored.size)
            assertEquals("present", stored.getValue(1L)[2], "an already-present row is left untouched")
            assertEquals("first", stored.getValue(2L)[2], "the first of a repeated key wins")
            assertEquals("first", stored.getValue(6_999L)[2], "also across the chunk boundary")
        } finally {
            cleanUp(OWNER_ROWS)
        }
    }

    @Test
    fun `no rows is a no-op and rows that set different columns are refused`() = runBlocking {
        t.ensureCreated()
        try {
            suspendTransaction(sharedDatabaseForTests()) {
                t.insertRowsIgnoringConflicts(emptyList<Item>()) { this[t.owner] = OWNER_ROWS }
                assertEquals(0L, t.selectAll().where { t.owner eq OWNER_ROWS }.count())
            }
            assertFailsWith<IllegalArgumentException> {
                suspendTransaction(sharedDatabaseForTests()) {
                    t.insertRowsIgnoringConflicts(listOf(1L, 2L)) {
                        this[t.owner] = OWNER_ROWS
                        this[t.id] = it
                        this[t.name] = "x"
                        if (it == 1L) this[t.note] = "only the first row sets it"
                    }
                }
            }
            assertFailsWith<IllegalArgumentException> {
                suspendTransaction(sharedDatabaseForTests()) { t.insertRowsIgnoringConflicts(listOf(1L)) { } }
            }
            Unit // the last expression of a runBlocking test body must be Unit (JUnit4 wants a void method)
        } finally {
            cleanUp(OWNER_ROWS)
        }
    }

    /** A table whose id comes from a named sequence (`autoIncrement("…")`) — never inserted into. */
    private object SequencedRows : Table("multi_row_scratch") {
        val id = long("id").autoIncrement("multi_row_scratch_id_seq")
    }

    private object OtherTable : Table("multi_row_other") {
        val id = long("id")
    }

    @Test
    fun `a column of another table and a sequence-backed autoIncrement table are refused`() = runBlocking {
        val foreign = assertFailsWith<IllegalArgumentException> {
            suspendTransaction(sharedDatabaseForTests()) {
                t.insertRowsIgnoringConflicts(listOf(1L)) { this[OtherTable.id] = it }
            }
        }
        assertTrue(foreign.message!!.contains("id") && foreign.message!!.contains("multi_row_other"), foreign.message)
        val sequenced = assertFailsWith<IllegalArgumentException> {
            suspendTransaction(sharedDatabaseForTests()) {
                SequencedRows.insertRowsIgnoringConflicts(listOf(1L)) { this[SequencedRows.id] = it }
            }
        }
        assertTrue(sequenced.message!!.contains("sequence-backed"), sequenced.message)
    }

    @Test
    fun `rows are built only while their statement is assembled - the rows in flight are one statement's`() = runBlocking {
        t.ensureCreated()
        val rows = (1..15_000).map { Item(it.toLong(), "n$it", null) }
        val built = java.util.concurrent.atomic.AtomicInteger()
        val builtAtStatement = mutableListOf<Int>()
        val sizes = mutableListOf<Int>()
        try {
            val probe = StatementProbe { _, size ->
                sizes += size
                builtAtStatement += built.get()
            }
            withContext(probe) {
                suspendTransaction(sharedDatabaseForTests()) {
                    t.insertRowsIgnoringConflicts(rows) {
                        built.incrementAndGet()
                        this[t.owner] = OWNER_ROWS
                        this[t.id] = it.id
                        this[t.name] = it.name
                        this[t.note] = it.note
                    }
                    assertEquals(15_000L, t.selectAll().where { t.owner eq OWNER_ROWS }.count())
                }
            }
        } finally {
            cleanUp(OWNER_ROWS)
        }
        // 5 columns: 6,400 rows a statement, so 6,400 + 6,400 + the binary decomposition of the 2,200 left.
        assertEquals(chunkSizes(15_000, MultiRowScratch.ROWS_PER_STATEMENT), sizes)
        assertEquals(15_000, sizes.sum())
        // Rows built when each statement is about to run: exactly the rows of the statements so far (every body ran once, the first row
        // included) - never the whole call's rows ahead of the first statement.
        var written = 0
        sizes.forEachIndexed { index, size ->
            written += size
            assertEquals(written, builtAtStatement[index], "rows built when statement ${index + 1} ($size rows) runs")
        }
        assertEquals(15_000, built.get(), "every row is built exactly once")
        assertTrue(builtAtStatement.first() <= MultiRowScratch.ROWS_PER_STATEMENT, "only the first statement's rows exist before it runs")
    }

    @Test
    fun `chunk sizes are full chunks then a binary decomposition - a handful of distinct statement texts`() {
        assertEquals(emptyList(), chunkSizes(0, 100))
        assertEquals(listOf(8, 4, 1), chunkSizes(13, 100))
        assertEquals(listOf(100, 100, 32, 8, 2, 1), chunkSizes(243, 100))
        assertEquals(listOf(100), chunkSizes(100, 100))
        assertEquals(listOf(1), chunkSizes(1, 5_333))
        val max = 5_333
        val seen = mutableSetOf<Int>()
        for (total in 0..12_000) {
            val sizes = chunkSizes(total, max)
            assertEquals(total, sizes.sum(), "every row is written exactly once (total $total)")
            assertTrue(sizes.all { it == max || Integer.bitCount(it) == 1 }, "only full or power-of-two chunks (total $total)")
            seen += sizes
        }
        // the max itself plus the 13 powers of two below it: the whole vocabulary of a 5,333-row-wide table
        assertTrue(seen.size <= 14, "distinct chunk sizes: ${seen.size}")
    }

    private companion object {
        const val OWNER_BATCH = 901
        const val OWNER_ROWS = 902
    }
}
