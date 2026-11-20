package ch.nokillswit

import ch.nokillswit.adrs.SuccessorGraph
import ch.nokillswit.adrs.SupersedesLink
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The pure cycle walk (Astrolabe's `cycleClosedBy`, reduced to a walk UP one chain of predecessors). */
class SuccessorGraphTest {
    private fun link(predecessor: UInt, successor: UInt) = SupersedesLink(predecessor, successor)

    /** The stored chains as the walk reads them: `successor -> predecessor`; counts every lookup so the walk's cost is pinned. */
    private class Chains(vararg pairs: Pair<UInt, UInt>) {
        private val predecessors = pairs.toMap()
        var lookups = 0

        val lookup: suspend (UInt) -> UInt? = {
            lookups++
            predecessors[it]
        }
    }

    private fun cycle(chains: Chains, link: SupersedesLink) = runBlocking { SuccessorGraph.cycleClosedBy(link, chains.lookup) }

    @Test
    fun `an empty graph closes no cycle and a self-link is a loop of one`() {
        assertNull(cycle(Chains(), link(1u, 2u)))
        assertEquals(listOf(6u, 6u), cycle(Chains(), link(6u, 6u)))
    }

    @Test
    fun `a chain extended at either end is fine and a link back along it closes the loop`() {
        // 2 replaces 1, 3 replaces 2, 4 replaces 3.
        val chains = Chains(2u to 1u, 3u to 2u, 4u to 3u)
        assertNull(cycle(chains, link(4u, 5u)), "extending the chain is fine")
        assertNull(cycle(chains, link(9u, 1u)), "a new head in front of the oldest is fine")
        assertEquals(listOf(4u, 1u, 2u, 3u, 4u), cycle(chains, link(4u, 1u)), "the new link's predecessor first and last")
        assertEquals(listOf(2u, 1u, 2u), cycle(Chains(2u to 1u), link(2u, 1u)), "a two-ADR loop")
    }

    @Test
    fun `the walk climbs only the requested predecessor's own chain and costs one lookup per step`() {
        // A long chain 1..50 plus an unrelated pair: only the 50 steps up from the predecessor are read.
        val chain = (2u..50u).associateWith { it - 1u }
        val chains = Chains(*chain.toList().toTypedArray(), 91u to 90u)
        assertNull(cycle(chains, link(50u, 99u)))
        assertEquals(50, chains.lookups, "the whole table is never loaded")
        chains.lookups = 0
        assertNull(cycle(chains, link(91u, 99u)))
        assertEquals(2, chains.lookups, "a short chain is a short walk")
    }

    @Test
    fun `a loop above the walk that never reaches the successor ends it instead of spinning`() {
        val chains = Chains(2u to 3u, 3u to 2u)
        assertNull(cycle(chains, link(2u, 9u)))
    }
}
