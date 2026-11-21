package ch.nokillswit.infra

import java.util.TreeMap

/**
 * The expiry order of an in-memory TTL store (the login lockout, the password-reset cooldown and the MFA challenges keep one each):
 * which keys expire at which instant, so a sweep drops the expired ones in order of expiry and stops at the first that is still live,
 * never scanning the store. It holds KEYS only; the owner keeps the entries and says, per swept key, whether the entry still expires
 * at that instant (an entry re-keyed to a later expiry is not dropped by its old slot).
 *
 * NOT thread-safe, by design: the owner guards the index with the same lock as its entry map, so the two never disagree. The owner
 * also bounds the store (`maxTracked`), which bounds this index with it.
 */
class ExpiringIndex<K> {
    private val byExpiry = TreeMap<Long, MutableSet<K>>()

    /** Records that [key] expires at [expiresAt] (epoch millis). A key added twice at one instant is held once. */
    fun add(expiresAt: Long, key: K) {
        byExpiry.getOrPut(expiresAt) { mutableSetOf() }.add(key)
    }

    /** Forgets [key] at [expiresAt]; a no-op when it is not indexed there. An emptied instant is dropped (no leaked slots). */
    fun remove(expiresAt: Long, key: K) {
        byExpiry[expiresAt]?.let { keys ->
            keys.remove(key)
            if (keys.isEmpty()) byExpiry.remove(expiresAt)
        }
    }

    /**
     * Polls every instant at or before [now] (an entry expires AT its instant: `expiresAt <= now`), earliest first, and calls [drop]
     * once per key indexed there with that instant. The instant is already gone from the index when [drop] runs, so [drop] clears the
     * owner's own maps only and must not call [remove] for the swept key.
     */
    fun pruneExpired(now: Long, drop: (key: K, expiresAt: Long) -> Unit) {
        while (byExpiry.firstEntry()?.key?.let { it <= now } == true) {
            val (expiresAt, keys) = byExpiry.pollFirstEntry()
            keys.forEach { drop(it, expiresAt) }
        }
    }

    /** The number of distinct expiry instants held (a test seam: an index that empties with its store leaks nothing). */
    fun instants(): Int = byExpiry.size
}
