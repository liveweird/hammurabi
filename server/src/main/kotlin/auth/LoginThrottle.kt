package ch.nokillswit.auth

import ch.nokillswit.infra.ExpiringIndex
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.json.sha256Hex

/**
 * Per-account login throttle: after [threshold] consecutive failures for the same submitted
 * email, further attempts are rejected for [lockoutMillis] — regardless of whether the account
 * exists (so it leaks nothing), and independent of the per-IP rate limit (which an attacker can
 * sidestep by rotating hosts).
 *
 * In-memory and per-instance by design: the deployment runs a single replica, and losing the
 * counters on restart only resets the throttle, never grants access. A successful login clears
 * the account's counter.
 *
 * **Progressive for MFA only.** A lock whose failure window held ANY wrong emailed code (`mfaCode = true`, whichever failure then
 * tripped it: the attacker picks that one) means the guesser already holds the password (a code failure needs a live challenge), so
 * code guessing gets steadily dearer: the n-th consecutive such lock lasts `lockoutMillis * 2^(n-1)`, capped at `mfaMaxLockoutMillis`
 * (never below `lockoutMillis`). A window of wrong passwords ONLY (login, password change) is ALWAYS the flat `lockoutMillis` (a
 * username alone must never buy a longer lock-out of the real owner) and neither escalates nor resets the MFA level.
 * The level lives in its own map and expiry index: it outlives the lock entry (which is pruned the instant the lock ends). It
 * decays ONE STEP per full [mfaDecayMillis] without an escalated lock, counted from the end of the last one (computed lazily from
 * that instant; the expiry slot is the instant the level reaches 0, where the entry is dropped), and a success or [clear] (a
 * confirmed password reset) drops it at once. Decaying straight to 0 would let a guesser wait one period and climb the cheap bottom
 * of the ladder again (about 22 guesses a day); one step per period keeps a guesser who reached the top at the top.
 *
 * **Pacing.** The shared counter's window is only the flat lockout window, so a guesser who stays one short of the threshold and
 * waits it out would never trip a lock and never escalate. A SEPARATE per-account counter of wrong CODES only (`codeGuesses`) closes
 * that: it lives [mfaDecayMillis] from the FIRST code failure of its period (a fixed window, not sliding: a sliding one would let
 * an owner's two typos a day apart keep it alive for ever), and when it reaches the threshold it trips an escalated lock whatever
 * the shared counter says (and starts over). Pacing therefore buys at most `threshold - 1` code guesses per decay period before an
 * escalating lock. A success and [clear] drop it.
 * **The system bound, with both:** once the ladder tops out a guesser gets about 5 guesses per 24 h lock, or `threshold - 1` per
 * quiet period when pacing, so roughly 4.5 a day for the best mix (`security.md`). The shared counter is NOT lengthened (that
 * would let a username-only attacker escalate the owner with wrong passwords after one typo).
 *
 * Both extra maps are written only by a code failure, which is a known account's (the MFA step's subject), so their key space is
 * bounded by the user table like the above-cap entries, each entry expires on its own `ExpiringIndex`, and neither counts against
 * [maxTracked].
 */
class LoginThrottle(
    private val threshold: Int,
    private val lockoutMillis: Long,
    private val maxTracked: Int = 10_000,
    mfaMaxLockoutMillis: Long = DEFAULT_MFA_MAX_LOCKOUT_MILLIS,
    private val mfaDecayMillis: Long = DEFAULT_MFA_DECAY_MILLIS,
    private val clock: () -> Long = ::nowMillis,
) {
    init {
        require(maxTracked > 0) { "maxTracked must be positive" }
        require(mfaDecayMillis > 0) { "mfaDecayMillis must be positive" }
    }

    /** An escalated lock is never shorter than the flat one, whatever the configured cap. */
    private val mfaCapMillis = maxOf(mfaMaxLockoutMillis, lockoutMillis)

    enum class Preflight {
        ALLOWED,
        LOCKED,
        CAPACITY_EXCEEDED,
    }

    enum class FailureResult {
        RECORDED,
        LOCKED_NOW,
        ALREADY_LOCKED,
        CAPACITY_EXCEEDED,
    }

    enum class SuccessResult {
        CLEARED,
        LOCKED,
    }

    /**
     * What a failure did: the [result] and, for [FailureResult.LOCKED_NOW], the lock's length and its MFA escalation [level]
     * (0 = a flat lock tripped by a password; n >= 1 = the n-th consecutive lock tripped by a wrong code). Both are 0 otherwise.
     */
    data class FailureOutcome(val result: FailureResult, val lockMillis: Long = 0, val level: Int = 0)

    /** [codeFailed]: some failure of the current counter window was a wrong emailed code (it makes the tripping lock escalate). */
    private data class State(val failures: Int, val lockedUntil: Long, val expiresAt: Long, val codeFailed: Boolean = false)

    /**
     * The level at its last escalated lock, which ended at [anchor]; one step is lost per full decay period after it, so the level
     * reaches 0 (and the entry [expiresAt]) at `anchor + level * decay`.
     */
    private data class Escalation(val level: Int, val anchor: Long, val expiresAt: Long)

    /** The wrong codes of one account in the current decay period; [expiresAt] is fixed by the first of them. */
    private data class CodeGuesses(val count: Int, val expiresAt: Long)

    private val states = mutableMapOf<String, State>()
    private val expiries = ExpiringIndex<String>()
    private val escalations = mutableMapOf<String, Escalation>()
    private val escalationExpiries = ExpiringIndex<String>()
    private val codeGuesses = mutableMapOf<String, CodeGuesses>()
    private val codeGuessExpiries = ExpiringIndex<String>()
    private val lock = Any()

    private fun key(email: String) = loginIdentityKey(email)

    /**
     * Checks an identity before the account lookup and bcrypt work. Existing identities may
     * continue at capacity; a new identity is rejected until stale state can be reclaimed, unless
     * the caller already KNOWS the account exists ([knownAccount]: the MFA challenge's subject, the
     * signed-in password-change caller). A known account is always admitted: its key space is the
     * user table, so the memory it can take is bounded by the number of accounts, whatever the cap.
     */
    fun preflight(email: String, knownAccount: Boolean = false): Preflight {
        val k = key(email)
        return synchronized(lock) {
            val now = clock()
            pruneExpired(now)
            val state = states[k]
            when {
                state?.lockedUntil?.let { it > now } == true -> Preflight.LOCKED
                state != null || knownAccount || states.size < maxTracked -> Preflight.ALLOWED
                else -> Preflight.CAPACITY_EXCEEDED
            }
        }
    }

    /**
     * Records a failure, atomically rechecking capacity after the password work. A [knownAccount] failure is
     * ALWAYS counted, even at capacity (a guess at a code or password that was already compared must not go
     * uncounted because someone filled the store with junk identities); only an identity nobody knows is refused.
     */
    fun recordFailure(email: String, knownAccount: Boolean = false, mfaCode: Boolean = false): FailureResult =
        recordFailureOutcome(email, knownAccount, mfaCode).result

    /**
     * [recordFailure] with the lock's length and level (the audit line's fields). [mfaCode] marks the failure as a wrong emailed
     * code; it implies a [knownAccount] (the code's challenge was minted for a real account), which keeps the escalation map bounded.
     */
    fun recordFailureOutcome(email: String, knownAccount: Boolean = false, mfaCode: Boolean = false): FailureOutcome {
        require(knownAccount || !mfaCode) { "an MFA code failure belongs to a known account" }
        val k = key(email)
        return synchronized(lock) {
            val now = clock()
            pruneExpired(now)
            val current = states[k]
            if (current == null && !knownAccount && states.size >= maxTracked) {
                return@synchronized FailureOutcome(FailureResult.CAPACITY_EXCEEDED)
            }
            // A request that passed preflight just before another request tripped the lock must
            // never replace that active lock with a fresh counter.
            if (current != null && current.lockedUntil > now) {
                return@synchronized FailureOutcome(FailureResult.ALREADY_LOCKED)
            }
            val failures = (current?.failures ?: 0) + 1
            val codeFailed = mfaCode || current?.codeFailed == true
            val codeCount = if (mfaCode) bumpCodeGuesses(k, now) else 0
            // The code counter trips an escalated lock by itself (pacing); otherwise the shared counter does, escalated if its
            // window held a code failure.
            if (failures >= threshold || codeCount >= threshold) {
                trip(k, now, escalate = codeFailed)
            } else {
                putState(k, State(failures, 0, now + lockoutMillis, codeFailed))
                FailureOutcome(FailureResult.RECORDED)
            }
        }
    }

    /** Sets the lock: [escalate] doubles it per consecutive escalated lock (and restarts the code counter), else the flat window. */
    private fun trip(key: String, now: Long, escalate: Boolean): FailureOutcome {
        val level = if (escalate) minOf(currentLevel(key, now) + 1, MAX_ESCALATION_LEVEL) else 0
        val lockMillis = if (escalate) escalatedLockMillis(level) else lockoutMillis
        putState(key, State(0, now + lockMillis, now + lockMillis))
        if (escalate) {
            val anchor = now + lockMillis
            putEscalation(key, Escalation(level, anchor, anchor + level * mfaDecayMillis))
            removeCodeGuesses(key)
        }
        return FailureOutcome(FailureResult.LOCKED_NOW, lockMillis, level)
    }

    /** The stored level less one step per full decay period since its last lock ended (0 when none, or when it has run out). */
    private fun currentLevel(key: String, now: Long): Int {
        val held = escalations[key] ?: return 0
        val steps = if (now > held.anchor) (now - held.anchor) / mfaDecayMillis else 0L
        return maxOf(0L, held.level - steps).toInt()
    }

    /** Counts one wrong code; the period (and its expiry) starts with the first. Returns the count including this one. */
    private fun bumpCodeGuesses(key: String, now: Long): Int {
        val current = codeGuesses[key]
        val next = CodeGuesses((current?.count ?: 0) + 1, current?.expiresAt ?: (now + mfaDecayMillis))
        removeCodeGuesses(key)
        codeGuesses[key] = next
        codeGuessExpiries.add(next.expiresAt, key)
        return next.count
    }

    private fun removeCodeGuesses(key: String) {
        val removed = codeGuesses.remove(key) ?: return
        codeGuessExpiries.remove(removed.expiresAt, key)
    }

    /** `lockoutMillis * 2^(level-1)`, capped; doubles step by step so nothing can overflow (every value stays <= the cap). */
    private fun escalatedLockMillis(level: Int): Long {
        var millis = lockoutMillis
        repeat(level - 1) { millis = minOf(millis * 2, mfaCapMillis) }
        return millis
    }

    fun recordSuccess(email: String): SuccessResult {
        val key = key(email)
        return synchronized(lock) {
            val now = clock()
            pruneExpired(now)
            val current = states[key]
            if (current != null && current.lockedUntil > now) {
                SuccessResult.LOCKED
            } else {
                removeState(key)
                removeEscalation(key)
                removeCodeGuesses(key)
                SuccessResult.CLEARED
            }
        }
    }

    /**
     * Forgets everything held for [email]: the failure counter, an active lock and the MFA escalation level, whether or not a lock
     * is running (a confirmed password reset: the link reached the mailbox that gets the codes, which a code guesser lacks).
     * True when something was held.
     */
    fun clear(email: String): Boolean {
        val k = key(email)
        return synchronized(lock) {
            pruneExpired(clock())
            val held = k in states || k in escalations || k in codeGuesses
            removeState(k)
            removeEscalation(k)
            removeCodeGuesses(k)
            held
        }
    }

    private fun putState(key: String, state: State) {
        removeState(key)
        states[key] = state
        expiries.add(state.expiresAt, key)
    }

    private fun removeState(key: String) {
        val removed = states.remove(key) ?: return
        expiries.remove(removed.expiresAt, key)
    }

    private fun putEscalation(key: String, escalation: Escalation) {
        removeEscalation(key)
        escalations[key] = escalation
        escalationExpiries.add(escalation.expiresAt, key)
    }

    private fun removeEscalation(key: String) {
        val removed = escalations.remove(key) ?: return
        escalationExpiries.remove(removed.expiresAt, key)
    }

    private fun pruneExpired(now: Long) {
        expiries.pruneExpired(now) { key, expiresAt ->
            if (states[key]?.expiresAt == expiresAt) states.remove(key)
        }
        escalationExpiries.pruneExpired(now) { key, expiresAt ->
            if (escalations[key]?.expiresAt == expiresAt) escalations.remove(key)
        }
        codeGuessExpiries.pruneExpired(now) { key, expiresAt ->
            if (codeGuesses[key]?.expiresAt == expiresAt) codeGuesses.remove(key)
        }
    }

    /** A test seam: how many escalation levels are held (a store that empties with its entries leaks nothing). */
    internal fun escalationCount(): Int = synchronized(lock) { escalations.size }

    /** A test seam: how many distinct instants the escalation expiry index holds. */
    internal fun escalationInstants(): Int = synchronized(lock) { escalationExpiries.instants() }

    /** A test seam: the wrong codes counted for [email] in its current decay period (0 when none). */
    internal fun codeGuessCount(email: String): Int = synchronized(lock) { codeGuesses[key(email)]?.count ?: 0 }

    /** A test seam: how many accounts hold a code counter, and how many distinct instants its expiry index holds. */
    internal fun codeGuessAccounts(): Int = synchronized(lock) { codeGuesses.size }

    internal fun codeGuessInstants(): Int = synchronized(lock) { codeGuessExpiries.instants() }

    companion object {
        /** The default ceiling of an MFA-escalated lock: 24 hours. */
        const val DEFAULT_MFA_MAX_LOCKOUT_MILLIS = 24L * 3600 * 1000

        /** The default quiet period after the last MFA-tripped lock ends before its level is forgotten: 24 hours. */
        const val DEFAULT_MFA_DECAY_MILLIS = 24L * 3600 * 1000

        /** Clamps the level counter (23 doublings of one second already exceed the 30-day configuration ceiling). */
        private const val MAX_ESCALATION_LEVEL = 32
    }
}

/** Fixed-size, non-reversible key so an arbitrarily long submitted identity is never retained. */
internal fun loginIdentityKey(email: String): String = sha256Hex(email.trim().lowercase())
