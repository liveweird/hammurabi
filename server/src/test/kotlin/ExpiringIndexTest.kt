package ch.nokillswit

import ch.nokillswit.infra.ExpiringIndex
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure unit tests of the TTL expiry index shared by the lockout, reset-cooldown and MFA-challenge stores. */
class ExpiringIndexTest {

    private fun ExpiringIndex<String>.swept(now: Long): List<Pair<String, Long>> =
        buildList { pruneExpired(now) { key, expiresAt -> add(key to expiresAt) } }

    @Test
    fun `an entry expires AT its instant, not after it`() {
        val index = ExpiringIndex<String>()
        index.add(100, "a")
        assertEquals(emptyList(), index.swept(99))
        assertEquals(listOf("a" to 100L), index.swept(100))
    }

    @Test
    fun `a sweep drops the expired instants in order and stops at the first live one`() {
        val index = ExpiringIndex<String>()
        index.add(300, "late")
        index.add(100, "first")
        index.add(200, "second")
        assertEquals(listOf("first" to 100L, "second" to 200L), index.swept(250))
        assertEquals(1, index.instants())
        assertEquals(listOf("late" to 300L), index.swept(300))
        assertEquals(0, index.instants())
    }

    @Test
    fun `keys sharing an instant are all swept, in insertion order, and a key added twice is held once`() {
        val index = ExpiringIndex<String>()
        index.add(100, "a")
        index.add(100, "b")
        index.add(100, "a")
        assertEquals(listOf("a" to 100L, "b" to 100L), index.swept(100))
    }

    @Test
    fun `remove forgets one key and drops an instant it empties`() {
        val index = ExpiringIndex<String>()
        index.add(100, "a")
        index.add(100, "b")
        index.add(200, "c")
        index.remove(100, "a")
        assertEquals(2, index.instants())
        index.remove(100, "b")
        assertEquals(1, index.instants())
        assertEquals(listOf("c" to 200L), index.swept(1_000))
    }

    @Test
    fun `removing a key that is not indexed there is a no-op`() {
        val index = ExpiringIndex<String>()
        index.add(100, "a")
        index.remove(100, "missing")
        index.remove(999, "a")
        assertEquals(1, index.instants())
        assertEquals(listOf("a" to 100L), index.swept(100))
    }

    @Test
    fun `a swept instant is gone from the index before the callback runs`() {
        val index = ExpiringIndex<String>()
        index.add(100, "a")
        var instantsDuringDrop = -1
        index.pruneExpired(100) { _, _ -> instantsDuringDrop = index.instants() }
        assertEquals(0, instantsDuringDrop)
    }

    @Test
    fun `a key indexed at two instants is reported per instant - the index never merges slots, the owner checks the current one`() {
        val index = ExpiringIndex<String>()
        index.add(100, "a")
        index.add(300, "a")
        assertEquals(listOf("a" to 100L), index.swept(200))
        assertEquals(1, index.instants())
        assertEquals(listOf("a" to 300L), index.swept(300))
    }
}
