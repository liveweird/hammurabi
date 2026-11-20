package ch.nokillswit

import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.users.UserRole
import ch.nokillswit.users.UserService
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * `containsNormalized` (`infra/db/Sql.kt`) against the real database: the needle is folded (`public.unaccent`, then `LOWER`) and ONLY THEN
 * escaped, in SQL. PostgreSQL's unaccent maps the fullwidth `％ ＿ ＼` to the ASCII `% _ \`, so an escape done in Kotlin before the fold
 * would be undone: a typed `％` would be a wildcard. Users are SHARED suite state: every row carries a UNIQUE marker and each match is
 * scoped to it.
 */
class ContainsPatternTest {
    private val marker = UUID.randomUUID().toString().substring(0, 8)
    private val minted = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking { minted.forEach { TestUsers.softDelete(it) } }

    private suspend fun seed(name: String) {
        minted += TestUsers.seed(email = uniqueEmail("cp"), password = "pw", role = UserRole.USER, name = "$marker $name")
    }

    /** The names (without the marker) of this test's rows that `name containsNormalized needle` keeps. */
    private suspend fun matching(needle: String): List<String> = suspendTransaction(sharedDatabaseForTests()) {
        val users = UserService.Users
        users.select(users.name)
            .where { users.name.containsNormalized(marker) and users.name.containsNormalized(needle) }
            .map { it[users.name].removePrefix("$marker ") }
            .toList()
            .sorted()
    }

    private suspend fun seedAll() {
        listOf("100% sure", "100％ wide", "snake_case", "snake＿case", "back\\slash", "back＼slash", "plain", "ŻÓŁW")
            .forEach { seed(it) }
    }

    @Test
    fun `a typed percent, underscore or backslash is literal`() = runBlocking {
        seedAll()
        // `0%` finds the literal percent signs, not every row the way an unescaped wildcard would.
        assertEquals(listOf("100% sure", "100％ wide"), matching("0%"))
        assertEquals(listOf("snake_case", "snake＿case"), matching("e_c"))
        assertEquals(emptyList(), matching("sn_ke"), "an underscore is no one-character wildcard")
        assertEquals(listOf("back\\slash", "back＼slash"), matching("k\\s"))
    }

    @Test
    fun `the fullwidth percent, underscore and backslash are literal too - the fold cannot turn them into wildcards`() = runBlocking {
        seedAll()
        assertEquals(listOf("100% sure", "100％ wide"), matching("0％"))
        assertEquals(emptyList(), matching("pl％n"), "a fullwidth percent is no wildcard (it would match `plain`)")
        assertEquals(emptyList(), matching("sn＿ke"), "a fullwidth underscore is no one-character wildcard")
        assertEquals(listOf("snake_case", "snake＿case"), matching("e＿c"))
        assertEquals(listOf("back\\slash", "back＼slash"), matching("k＼s"))
        // A trailing fullwidth backslash folds to a trailing escape character: it must still be a plain character, never a LIKE error.
        assertEquals(emptyList(), matching("plain＼"))
    }

    @Test
    fun `case and accents still fold, and an uppercase diacritic needle still matches`() = runBlocking {
        seedAll()
        assertEquals(listOf("plain"), matching("PLAIN"))
        assertEquals(listOf("ŻÓŁW"), matching("zolw"))
        assertEquals(listOf("ŻÓŁW"), matching("ŻÓŁ"))
    }
}
