package ch.nokillswit.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * "Progressive for MFA only" (the 2026-11-20 checkup decision): a lock tripped by a wrong emailed code doubles per consecutive such
 * lock, a lock tripped by a wrong password stays flat, on an injected clock. The base cases are `LoginThrottleTest`.
 */
class LoginThrottleProgressiveTest {

    private val base = 15 * MINUTE
    private var now = 1_000_000L
    private val email = "mfa@test"

    private fun throttle(
        threshold: Int = 2,
        maxTracked: Int = 100,
        cap: Long = 8 * HOUR,
        decay: Long = DAY,
    ) = LoginThrottle(
        threshold = threshold,
        lockoutMillis = base,
        maxTracked = maxTracked,
        mfaMaxLockoutMillis = cap,
        mfaDecayMillis = decay,
        clock = { now },
    )

    /** Two wrong guesses: returns the outcome of the one that trips the lock. */
    private fun LoginThrottle.trip(who: String = email, mfa: Boolean): LoginThrottle.FailureOutcome {
        recordFailureOutcome(who, knownAccount = mfa, mfaCode = mfa)
        return recordFailureOutcome(who, knownAccount = mfa, mfaCode = mfa)
    }

    @Test
    fun `consecutive MFA-tripped locks double from the base up to the cap`() {
        val t = throttle(cap = 2 * HOUR)
        val expected = listOf(15 * MINUTE, 30 * MINUTE, HOUR, 2 * HOUR, 2 * HOUR, 2 * HOUR)
        expected.forEachIndexed { i, millis ->
            val outcome = t.trip(mfa = true)
            assertEquals(LoginThrottle.FailureResult.LOCKED_NOW, outcome.result)
            assertEquals(millis, outcome.lockMillis, "lock ${i + 1}")
            assertEquals(i + 1, outcome.level)
            // The lock really lasts that long: still locked a millisecond before it ends, free the instant it does.
            now += millis - 1
            assertEquals(LoginThrottle.Preflight.LOCKED, t.preflight(email, knownAccount = true))
            now += 1
            assertEquals(LoginThrottle.Preflight.ALLOWED, t.preflight(email, knownAccount = true))
        }
    }

    @Test
    fun `a cap below the base never shortens a lock and a huge level count cannot overflow`() {
        val t = throttle(cap = 1_000)
        assertEquals(base, t.trip(mfa = true).lockMillis)
        now += base
        assertEquals(base, t.trip(mfa = true).lockMillis)

        val month = 30 * DAY
        val long = LoginThrottle(2, 1_000, mfaMaxLockoutMillis = month, mfaDecayMillis = month, clock = { now })
        repeat(60) {
            val outcome = long.trip(mfa = true)
            assertEquals(minOf(1_000L shl it.coerceAtMost(40), month), outcome.lockMillis, "lock ${it + 1}")
            now += outcome.lockMillis
        }
        assertEquals(month, long.trip(mfa = true).lockMillis)
    }

    @Test
    fun `a password-tripped lock stays flat and neither escalates nor resets the MFA level`() {
        val t = throttle()
        assertEquals(base, t.trip(mfa = true).lockMillis)
        now += base
        assertEquals(2 * base, t.trip(mfa = true).lockMillis)
        now += 2 * base

        val byPassword = t.trip(mfa = false)
        assertEquals(LoginThrottle.FailureResult.LOCKED_NOW, byPassword.result)
        assertEquals(base, byPassword.lockMillis)
        assertEquals(0, byPassword.level)
        now += base

        // No reset: the next code-tripped lock continues the sequence (level 3), no escalation was gained by the password lock either.
        val next = t.trip(mfa = true)
        assertEquals(3, next.level)
        assertEquals(4 * base, next.lockMillis)
    }

    @Test
    fun `a password-tripped lock on an account with no MFA history is never escalated`() {
        val t = throttle()
        repeat(5) {
            val outcome = t.trip(mfa = false)
            assertEquals(base, outcome.lockMillis)
            assertEquals(0, outcome.level)
            now += base
        }
        assertEquals(0, t.escalationCount())
    }

    @Test
    fun `a success resets the level, a refused success during a lock does not`() {
        val t = throttle()
        t.trip(mfa = true)
        now += base
        t.trip(mfa = true)
        assertEquals(1, t.escalationCount())

        // Still locked (2 * base): the success is refused and the level survives.
        assertEquals(LoginThrottle.SuccessResult.LOCKED, t.recordSuccess(email))
        assertEquals(1, t.escalationCount())

        now += 2 * base
        assertEquals(LoginThrottle.SuccessResult.CLEARED, t.recordSuccess(email))
        assertEquals(0, t.escalationCount())
        assertEquals(0, t.escalationInstants())
        val again = t.trip(mfa = true)
        assertEquals(1, again.level)
        assertEquals(base, again.lockMillis)
    }

    @Test
    fun `the level decays after a quiet period measured from the end of the last lock`() {
        val decay = 2 * HOUR
        val stillHeld = throttle(decay = decay)
        stillHeld.trip(mfa = true)
        now += base + decay - 1
        assertEquals(2, stillHeld.trip(mfa = true).level)

        now = 1_000_000L
        val decayed = throttle(decay = decay)
        decayed.trip(mfa = true)
        now += base + decay
        val fresh = decayed.trip(mfa = true)
        assertEquals(1, fresh.level)
        assertEquals(base, fresh.lockMillis)
    }

    @Test
    fun `any wrong code in the window escalates the lock, whichever failure trips it`() {
        val t = throttle(threshold = 5)
        // Four wrong codes, then the attacker trips the lock with a wrong PASSWORD at /login (not a known-account call).
        repeat(4) { t.recordFailureOutcome(email, knownAccount = true, mfaCode = true) }
        val first = t.recordFailureOutcome(email)
        assertEquals(LoginThrottle.FailureResult.LOCKED_NOW, first.result)
        assertEquals(1, first.level)
        assertEquals(base, first.lockMillis)
        now += base

        // The next round escalates the same way: level 2, double length.
        repeat(4) { t.recordFailureOutcome(email, knownAccount = true, mfaCode = true) }
        val second = t.recordFailureOutcome(email)
        assertEquals(2, second.level)
        assertEquals(2 * base, second.lockMillis)
        now += 2 * base

        // One code failure first and the password failures after it count the same.
        t.recordFailureOutcome(email, knownAccount = true, mfaCode = true)
        repeat(3) { t.recordFailureOutcome(email) }
        assertEquals(3, t.recordFailureOutcome(email).level)
    }

    @Test
    fun `a pure-password window stays flat and a code failure does not leak into the next window`() {
        val t = throttle(threshold = 5)
        repeat(4) { t.recordFailureOutcome(email) }
        val flat = t.recordFailureOutcome(email)
        assertEquals(0, flat.level)
        assertEquals(base, flat.lockMillis)
        assertEquals(0, t.escalationCount())
        now += base

        // A code failure whose window then lapses (the counter expires after the base window), or is cleared by a success, is gone.
        t.recordFailureOutcome(email, knownAccount = true, mfaCode = true)
        now += base
        repeat(4) { t.recordFailureOutcome(email) }
        assertEquals(0, t.recordFailureOutcome(email).level)
        now += base

        t.recordFailureOutcome(email, knownAccount = true, mfaCode = true)
        assertEquals(LoginThrottle.SuccessResult.CLEARED, t.recordSuccess(email))
        repeat(4) { t.recordFailureOutcome(email) }
        assertEquals(0, t.recordFailureOutcome(email).level)
        assertEquals(0, t.escalationCount())
    }

    private fun LoginThrottle.code() = recordFailureOutcome(email, knownAccount = true, mfaCode = true)

    @Test
    fun `pacing under the threshold cannot dodge the lock - the code counter trips an escalated one`() {
        val t = throttle(threshold = 5, decay = DAY)
        // The paced attack: four wrong codes, wait out the shared (flat) window, four more...
        repeat(4) { assertEquals(LoginThrottle.FailureResult.RECORDED, t.code().result) }
        now += base
        assertEquals(LoginThrottle.Preflight.ALLOWED, t.preflight(email, knownAccount = true), "the shared window lapsed")
        // ...and the 5th code inside the decay period trips an ESCALATED lock, though the shared counter holds only that one.
        val fifth = t.code()
        assertEquals(LoginThrottle.FailureResult.LOCKED_NOW, fifth.result)
        assertEquals(1, fifth.level)
        assertEquals(base, fifth.lockMillis)
        assertEquals(0, t.codeGuessCount(email), "the counter starts over with the lock")
        now += base

        // Every further paced round escalates again: four codes, a lapse, the fifth.
        repeat(4) { assertEquals(LoginThrottle.FailureResult.RECORDED, t.code().result) }
        now += base
        val again = t.code()
        assertEquals(2, again.level)
        assertEquals(2 * base, again.lockMillis)
    }

    @Test
    fun `the code counter's window is fixed at its first failure and a lapsed period starts over`() {
        val t = throttle(threshold = 5, decay = DAY)
        t.code()
        now += DAY - 1
        repeat(3) { t.code() }
        assertEquals(4, t.codeGuessCount(email), "still the same period")
        now += 1
        // The period ended (a fixed window from the FIRST failure, not sliding): the next code is the first of a new one.
        assertEquals(LoginThrottle.FailureResult.RECORDED, t.code().result)
        assertEquals(1, t.codeGuessCount(email))
    }

    @Test
    fun `password-only pacing stays flat and builds no code counter`() {
        val t = throttle(threshold = 5)
        repeat(10) {
            repeat(4) { assertEquals(LoginThrottle.FailureResult.RECORDED, t.recordFailureOutcome(email).result) }
            now += base
        }
        assertEquals(0, t.codeGuessAccounts())
        repeat(4) { t.recordFailureOutcome(email) }
        val flat = t.recordFailureOutcome(email)
        assertEquals(0, flat.level)
        assertEquals(base, flat.lockMillis)
    }

    @Test
    fun `a success and clear drop the code counter`() {
        val t = throttle(threshold = 5)
        repeat(4) { t.code() }
        assertEquals(LoginThrottle.SuccessResult.CLEARED, t.recordSuccess(email))
        assertEquals(0, t.codeGuessCount(email))
        repeat(4) { assertEquals(LoginThrottle.FailureResult.RECORDED, t.code().result) }

        now += base
        assertEquals(true, t.clear(email), "a counter alone counts as held")
        assertEquals(0, t.codeGuessAccounts())
        assertEquals(0, t.codeGuessInstants())
        repeat(4) { assertEquals(LoginThrottle.FailureResult.RECORDED, t.code().result) }
    }

    @Test
    fun `the code counter takes no capacity slot and empties with its expiry`() {
        val t = throttle(maxTracked = 1, threshold = 5, decay = HOUR)
        assertEquals(LoginThrottle.FailureResult.RECORDED, t.recordFailure("filler@test"))
        repeat(3) { t.code() }
        assertEquals(1, t.codeGuessAccounts())
        assertEquals(LoginThrottle.Preflight.CAPACITY_EXCEEDED, t.preflight("stranger@test"), "an unknown identity is still refused")

        now += HOUR
        assertEquals(LoginThrottle.Preflight.ALLOWED, t.preflight("stranger@test"))
        assertEquals(0, t.codeGuessAccounts())
        assertEquals(0, t.codeGuessInstants())
    }

    @Test
    fun `clear lifts the lock, the counter and the level of one identity only`() {
        val t = throttle()
        assertEquals(false, t.clear(email), "nothing held")
        t.trip(mfa = true)
        now += base
        t.trip(mfa = true)
        t.trip(who = "other@test", mfa = true)
        assertEquals(LoginThrottle.Preflight.LOCKED, t.preflight(email, knownAccount = true))

        assertEquals(true, t.clear(email))
        assertEquals(LoginThrottle.Preflight.ALLOWED, t.preflight(email, knownAccount = true))
        assertEquals(1, t.escalationCount(), "the other identity keeps its level")
        assertEquals(LoginThrottle.Preflight.LOCKED, t.preflight("other@test", knownAccount = true))
        assertEquals(false, t.clear(email))
        val after = t.trip(mfa = true)
        assertEquals(1, after.level)
        assertEquals(base, after.lockMillis)
    }

    /** Three consecutive escalated locks (levels 1, 2, 3); leaves [now] at the instant the last one ends. */
    private fun LoginThrottle.climbToThree() {
        repeat(3) {
            val lockMillis = trip(mfa = true).lockMillis
            now += lockMillis
        }
    }

    @Test
    fun `the level falls ONE step per quiet period, not to zero`() {
        val decay = DAY
        // After k full quiet periods the next escalated lock is level 3 - k + 1 (and level 1 once it has run out).
        for ((periods, expectedLevel) in listOf(0 to 4, 1 to 3, 2 to 2, 3 to 1, 4 to 1)) {
            now = 1_000_000L
            val t = throttle(decay = decay, cap = 8 * HOUR)
            t.climbToThree()
            now += periods * decay
            assertEquals(expectedLevel, t.trip(mfa = true).level, "after $periods quiet periods")
        }
        // A partial period loses nothing.
        now = 1_000_000L
        val t = throttle(decay = decay, cap = 8 * HOUR)
        t.climbToThree()
        now += decay - 1
        assertEquals(4, t.trip(mfa = true).level)
    }

    @Test
    fun `waiting out a period and climbing again keeps the guesser at the top of the ladder`() {
        val t = throttle(decay = DAY, cap = 2 * HOUR)
        // Climb to the cap (15 min, 30, 60, 120 = level 4), then keep going: decay-then-re-climb cycles never go back down.
        repeat(6) { now += t.trip(mfa = true).lockMillis }
        repeat(10) {
            now += DAY // one full quiet period after the last lock ended: one step down...
            val outcome = t.trip(mfa = true) // ...and the next escalated lock one step up again
            assertEquals(2 * HOUR, outcome.lockMillis, "cycle $it stays at the capped length")
            assertEquals(6, outcome.level)
            now += outcome.lockMillis
        }
    }

    @Test
    fun `the escalation entry and its index slot empty once the level has stepped down to zero`() {
        val t = throttle(decay = HOUR)
        t.climbToThree()
        assertEquals(1, t.escalationCount())
        now += 3 * HOUR - 1
        assertEquals(LoginThrottle.Preflight.ALLOWED, t.preflight(email, knownAccount = true))
        assertEquals(1, t.escalationCount(), "level 1 left, still held")
        now += 1
        assertEquals(LoginThrottle.Preflight.ALLOWED, t.preflight(email, knownAccount = true))
        assertEquals(0, t.escalationCount())
        assertEquals(0, t.escalationInstants())
    }

    @Test
    fun `a failed ALREADY_LOCKED race neither escalates nor extends the lock`() {
        val t = throttle()
        val first = t.trip(mfa = true)
        val raced = t.recordFailureOutcome(email, knownAccount = true, mfaCode = true)
        assertEquals(LoginThrottle.FailureOutcome(LoginThrottle.FailureResult.ALREADY_LOCKED), raced)
        now += first.lockMillis
        assertEquals(2, t.trip(mfa = true).level)
    }

    @Test
    fun `the escalation map is bounded by its own expiry and does not take a capacity slot`() {
        val t = throttle(maxTracked = 1, decay = HOUR)
        // One slot in the main store, taken by an unknown identity's fresh counter.
        assertEquals(LoginThrottle.FailureResult.RECORDED, t.recordFailure("filler@test"))
        assertEquals(LoginThrottle.Preflight.CAPACITY_EXCEEDED, t.preflight("stranger@test"))

        // A known account over the cap still locks and escalates, as before the change.
        assertEquals(base, t.trip(who = "known@test", mfa = true).lockMillis)
        assertEquals(1, t.escalationCount())
        assertEquals(LoginThrottle.Preflight.CAPACITY_EXCEEDED, t.preflight("stranger@test"))

        // Everything expires: the lock entry at its end, the level an hour later; the index empties with the map.
        now += base + HOUR
        assertEquals(LoginThrottle.Preflight.ALLOWED, t.preflight("stranger@test"))
        assertEquals(0, t.escalationCount())
        assertEquals(0, t.escalationInstants())
    }

    @Test
    fun `an MFA code failure for an unknown account is a programming error`() {
        val t = throttle()
        assertFailsWith<IllegalArgumentException> { t.recordFailureOutcome("ghost@test", knownAccount = false, mfaCode = true) }
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
