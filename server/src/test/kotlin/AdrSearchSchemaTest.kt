package ch.nokillswit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SEARCH_CONFIG = "public.hammurabi_search"

/**
 * The shape V15 promises (`persistence.md` "Search and filters (V15)"): the vector is a STORED generated column (on PostgreSQL 18 a bare
 * `GENERATED ALWAYS AS` is VIRTUAL and cannot be indexed), the GIN index and the filter indexes exist, and a word is found through the
 * unaccent configuration whichever way it is spelled. Needs a database but no application.
 */
class AdrSearchSchemaTest {
    private fun <T> query(sql: String, read: (java.sql.ResultSet) -> T): T =
        PostgresTestSupport.connect().use { connection -> connection.createStatement().use { st -> st.executeQuery(sql).use(read) } }

    @Test
    fun `the search column is stored and generated and the indexes are in place`() {
        val generated = query("SELECT attgenerated FROM pg_attribute WHERE attrelid = 'adrs'::regclass AND attname = 'search'") {
            it.next()
            it.getString(1)
        }
        assertEquals("s", generated, "STORED, not virtual")
        val indexes = query("SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'adrs'") {
            buildMap { while (it.next()) put(it.getString(1), it.getString(2)) }
        }
        assertTrue("USING gin (search)" in indexes.getValue("idx_adrs_search"), indexes["idx_adrs_search"])
        for (name in listOf("idx_adrs_created_at", "idx_adrs_updated_at", "idx_adrs_expires_on")) assertTrue(name in indexes, name)
    }

    @Test
    fun `the configuration folds accents and case and the weights are A, B and C`() {
        fun matches(document: String, query: String) = query(
            "SELECT to_tsvector('$SEARCH_CONFIG'::regconfig, '$document') @@ to_tsquery('$SEARCH_CONFIG'::regconfig, '$query')",
        ) {
            it.next()
            it.getBoolean(1)
        }
        assertTrue(matches("Żółw płynie", "zolw"))
        assertTrue(matches("zolw plynie", "ŻÓŁW"))
        val ranks = query(
            """
            SELECT ts_rank_cd(setweight(to_tsvector('public.hammurabi_search'::regconfig, 'word'), 'A'), q),
                   ts_rank_cd(setweight(to_tsvector('public.hammurabi_search'::regconfig, 'word'), 'B'), q),
                   ts_rank_cd(setweight(to_tsvector('public.hammurabi_search'::regconfig, 'word'), 'C'), q)
            FROM to_tsquery('public.hammurabi_search'::regconfig, 'word') q
            """.trimIndent(),
        ) {
            it.next()
            Triple(it.getDouble(1), it.getDouble(2), it.getDouble(3))
        }
        assertTrue(ranks.first > ranks.second && ranks.second > ranks.third, "A > B > C: $ranks")
    }
}
