package ch.nokillswit.adrs

/** A stored successor link as the walk sees it: [successor] replaces [predecessor] (`adrs.supersedes_id` of the successor row). */
data class SupersedesLink(val predecessor: UInt, val successor: UInt)

/**
 * The successor chains as a pure walk (no I/O of its own; the shape of Astrolabe's `planning/DependencyGraph.kt` `cycleClosedBy`, reduced
 * to one kind of node and to the one direction that matters). Every ADR has at most one predecessor, so the stored links are chains and a
 * NEW link `p -> s` closes a cycle exactly when `s` is an ancestor of `p`: the check walks UP from `p` along the stored predecessor
 * links, O(depth) lookups, and never loads the whole table. An index cannot see that, so the service runs it under the link advisory lock.
 */
object SuccessorGraph {
    /**
     * The cycle the NEW [link] would close, as the ADR ids along it - the link's predecessor first and repeated last - or null when it
     * closes none. [predecessorOf] answers the ADR a given ADR replaces right now (null = none, or a link that no longer counts); the
     * link itself is not part of what it answers. A loop that already exists above the walk (it cannot, by the checks) ends the walk.
     */
    suspend fun cycleClosedBy(link: SupersedesLink, predecessorOf: suspend (UInt) -> UInt?): List<UInt>? {
        val upward = arrayListOf(link.predecessor)
        val seen = hashSetOf(link.predecessor)
        var cursor = link.predecessor
        while (cursor != link.successor) {
            cursor = predecessorOf(cursor)?.takeIf { seen.add(it) } ?: return null
            upward.add(cursor)
        }
        return listOf(link.predecessor) + upward.asReversed()
    }
}
