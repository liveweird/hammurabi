package ch.nokillswit

import ch.nokillswit.auth.AccountGuess
import ch.nokillswit.auth.LoginThrottle
import ch.nokillswit.authz.TooManyRequestsException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The branches of the shared lockout helper that a route can only reach through a race (a lock that trips between the
 * preflight and the password work), pinned against a real [LoginThrottle] on an injected clock. The route-level paths are
 * `LoginLockoutTest`, `PasswordChangeTest` and `MfaLockoutTest`.
 */
class AccountGuessTest {

    private var now = 1_000_000L
    private fun throttle(threshold: Int = 2, maxTracked: Int = 10) =
        LoginThrottle(threshold, lockoutMillis = 60_000, maxTracked = maxTracked, clock = { now })

    private fun guess(
        throttle: LoginThrottle,
        email: String = "a@example.test",
        knownAccount: Boolean = false,
        onLocked: () -> Unit = {},
    ) = AccountGuess(
        throttle,
        email,
        listOf("userId" to 1L),
        lockedEvent = "test.rejected_locked",
        knownAccount = knownAccount,
        onLocked = onLocked,
    )

    private fun lock(throttle: LoginThrottle, email: String = "a@example.test") {
        repeat(2) { throttle.recordFailure(email) }
    }

    @Test
    fun `a locked account is refused at the preflight, a fresh one is allowed`() {
        val t = throttle()
        guess(t).requireNotLocked()
        lock(t)
        assertFailsWith<TooManyRequestsException> { guess(t).requireNotLocked() }
    }

    @Test
    fun `an unknown identity at a full store is refused, a known account is counted and can lock`() {
        val t = throttle(maxTracked = 1)
        t.recordFailure("filler@example.test")
        assertFailsWith<TooManyRequestsException> { guess(t).requireNotLocked() }
        assertFailsWith<TooManyRequestsException> { guess(t).recordFailure() }

        // A known account passes the preflight and its failures count past the cap: the second one locks.
        val known = guess(t, knownAccount = true)
        known.requireNotLocked()
        known.recordFailure()
        known.recordFailure()
        assertFailsWith<TooManyRequestsException> { guess(t, knownAccount = true).requireNotLocked() }
    }

    @Test
    fun `a failure against an already-active lock is a 429 and runs onLocked`() {
        val t = throttle()
        lock(t)
        var dropped = false
        assertFailsWith<TooManyRequestsException> { guess(t, onLocked = { dropped = true }).recordFailure() }
        assertTrue(dropped)
    }

    @Test
    fun `the failure that trips the lock runs onLocked and is not itself refused`() {
        val t = throttle()
        var dropped = 0
        val g = guess(t, onLocked = { dropped++ })
        g.recordFailure()
        assertEquals(0, dropped)
        g.recordFailure()
        assertEquals(1, dropped)
        assertFailsWith<TooManyRequestsException> { g.requireNotLocked() }
    }

    @Test
    fun `a success clears the counter, but a lock that tripped since the preflight still refuses and runs onLocked`() {
        val t = throttle()
        val g = guess(t)
        g.recordFailure()
        g.recordSuccess()
        g.recordFailure()
        assertFalse(runCatching { g.requireNotLocked() }.isFailure, "one failure after the clear is under the threshold")

        lock(t)
        var dropped = false
        assertFailsWith<TooManyRequestsException> { guess(t, onLocked = { dropped = true }).recordSuccess() }
        assertTrue(dropped)
    }
}
