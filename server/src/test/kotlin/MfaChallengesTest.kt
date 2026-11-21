package ch.nokillswit

import ch.nokillswit.auth.MfaChallenges
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Unit tests for the in-memory email-MFA challenge store (deterministic via an injected clock). */
class MfaChallengesTest {

    private var now = 1_000_000L
    private fun store(ttlMillis: Long = 300_000, maxAttempts: Int = 5, maxTracked: Int = 10_000, maxPerUser: Int = 3) =
        MfaChallenges(ttlMillis, maxAttempts, clock = { now }, maxTracked = maxTracked, maxPerUser = maxPerUser)

    private companion object {
        const val EMAIL = "someone@example.test"
    }

    @Test
    fun `a correct code succeeds exactly once - the challenge is single-use`() {
        val s = store()
        val issued = s.issue(42u, EMAIL, credentialRevision = 7)
        assertEquals(6, issued.code.length)
        assertTrue(issued.code.all { it.isDigit() })
        assertEquals(now + 300_000, issued.expiresAt)

        val outcome = s.verify(issued.challengeId, issued.code)
        assertIs<MfaChallenges.Outcome.Success>(outcome)
        assertEquals(42u, outcome.subject.userId)
        assertEquals(EMAIL, outcome.subject.email)
        assertEquals(7L, outcome.credentialRevision)

        // Replay of the consumed challenge is indistinguishable from an unknown one.
        val replay = s.verify(issued.challengeId, issued.code)
        assertIs<MfaChallenges.Outcome.Failure>(replay)
        assertEquals("unknown_challenge", replay.reason)
    }

    @Test
    fun `an expired challenge fails and is dropped`() {
        val s = store(ttlMillis = 60_000)
        val issued = s.issue(7u, EMAIL, credentialRevision = 0)
        now += 60_000
        val outcome = s.verify(issued.challengeId, issued.code)
        assertIs<MfaChallenges.Outcome.Failure>(outcome)
        assertEquals("expired", outcome.reason)
        // The drop is permanent — a later attempt sees unknown, not expired.
        assertEquals(
            "unknown_challenge",
            (s.verify(issued.challengeId, issued.code) as MfaChallenges.Outcome.Failure).reason,
        )
    }

    @Test
    fun `wrong codes count toward the attempt cap and exhausting it kills the challenge`() {
        val s = store(maxAttempts = 3)
        val issued = s.issue(7u, EMAIL, credentialRevision = 0)
        assertEquals("wrong_code", (s.verify(issued.challengeId, "x") as MfaChallenges.Outcome.Failure).reason)
        assertEquals("wrong_code", (s.verify(issued.challengeId, "x") as MfaChallenges.Outcome.Failure).reason)
        assertEquals(
            "too_many_attempts",
            (s.verify(issued.challengeId, "x") as MfaChallenges.Outcome.Failure).reason,
        )
        // Even the correct code no longer works — the challenge is gone.
        assertEquals(
            "unknown_challenge",
            (s.verify(issued.challengeId, issued.code) as MfaChallenges.Outcome.Failure).reason,
        )
    }

    @Test
    fun `an unknown challenge id fails uniformly`() {
        val s = store()
        val outcome = s.verify("no-such-challenge", "123456")
        assertIs<MfaChallenges.Outcome.Failure>(outcome)
        assertEquals("unknown_challenge", outcome.reason)
    }

    @Test
    fun `challenge ids are unique and opaque`() {
        val s = store()
        val a = s.issue(1u, EMAIL, credentialRevision = 0)
        val b = s.issue(1u, EMAIL, credentialRevision = 0)
        assertNotEquals(a.challengeId, b.challengeId)
        assertEquals(32, a.challengeId.length)
        // Both stay independently verifiable (repeated logins may coexist within the TTL).
        assertIs<MfaChallenges.Outcome.Success>(s.verify(b.challengeId, b.code))
        assertIs<MfaChallenges.Outcome.Success>(s.verify(a.challengeId, a.code))
    }

    @Test
    fun `the store prunes expired entries once oversized instead of growing without bound`() {
        val s = store(ttlMillis = 1_000, maxTracked = 2)
        val stale = (1..2).map { s.issue(it.toUInt(), EMAIL, credentialRevision = 0) }
        assertFailsWith<MfaChallenges.CapacityExceededException> { s.issue(3u, EMAIL, credentialRevision = 0) }
        now += 2_000
        val fresh = s.issue(99u, EMAIL, credentialRevision = 0)
        assertEquals(
            "unknown_challenge",
            (s.verify(stale.first().challengeId, stale.first().code) as MfaChallenges.Outcome.Failure).reason,
        )
        assertIs<MfaChallenges.Outcome.Success>(s.verify(fresh.challengeId, fresh.code))
    }

    @Test
    fun `capacity keeps existing challenges verifiable`() {
        val s = store(maxTracked = 1)
        val issued = s.issue(1u, EMAIL, credentialRevision = 9)
        assertFailsWith<MfaChallenges.CapacityExceededException> { s.issue(2u, EMAIL, credentialRevision = 0) }
        val outcome = assertIs<MfaChallenges.Outcome.Success>(s.verify(issued.challengeId, issued.code))
        assertEquals(9L, outcome.credentialRevision)
        assertTrue(s.issue(2u, EMAIL, credentialRevision = 0).challengeId.isNotBlank())
    }

    @Test
    fun `discarding an undelivered challenge immediately releases its slot`() {
        val s = store(maxTracked = 1)
        val undelivered = s.issue(1u, EMAIL, credentialRevision = 0)
        s.discard(undelivered.challengeId)
        val replacement = s.issue(2u, EMAIL, credentialRevision = 0)
        assertEquals(
            "unknown_challenge",
            (s.verify(undelivered.challengeId, undelivered.code) as MfaChallenges.Outcome.Failure).reason,
        )
        assertIs<MfaChallenges.Outcome.Success>(s.verify(replacement.challengeId, replacement.code))
    }

    @Test
    fun `concurrent issuance cannot exceed capacity`() {
        val s = store(maxTracked = 1)
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { userId ->
                executor.submit<Boolean> {
                    ready.countDown()
                    go.await()
                    runCatching { s.issue(userId.toUInt(), EMAIL, credentialRevision = 0) }.isSuccess
                }
            }
            ready.await()
            go.countDown()
            assertEquals(listOf(false, true), results.map { it.get() }.sorted())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `an account holds at most maxPerUser live challenges - a new one replaces the oldest`() {
        val s = store(maxPerUser = 2)
        val first = s.issue(1u, EMAIL, credentialRevision = 0)
        val second = s.issue(1u, EMAIL, credentialRevision = 0)
        val third = s.issue(1u, EMAIL, credentialRevision = 0)
        // The oldest is gone (not merely failing its attempts): its correct code is an unknown challenge now.
        assertEquals(
            "unknown_challenge",
            (s.verify(first.challengeId, first.code) as MfaChallenges.Outcome.Failure).reason,
        )
        assertIs<MfaChallenges.Outcome.Success>(s.verify(second.challengeId, second.code))
        assertIs<MfaChallenges.Outcome.Success>(s.verify(third.challengeId, third.code))
    }

    @Test
    fun `the per-user cap is per account and replacing never needs spare capacity`() {
        val s = store(maxPerUser = 1, maxTracked = 2)
        val a1 = s.issue(1u, EMAIL, credentialRevision = 0)
        val b1 = s.issue(2u, "b@example.test", credentialRevision = 0)
        // The store is full, but account 1's new challenge replaces its own: no capacity is consumed.
        val a2 = s.issue(1u, EMAIL, credentialRevision = 0)
        assertEquals(
            "unknown_challenge",
            (s.verify(a1.challengeId, a1.code) as MfaChallenges.Outcome.Failure).reason,
        )
        assertIs<MfaChallenges.Outcome.Success>(s.verify(b1.challengeId, b1.code))
        assertIs<MfaChallenges.Outcome.Success>(s.verify(a2.challengeId, a2.code))
    }

    @Test
    fun `a wrong code names the account and marks itself a guess, an unknown id names nobody`() {
        val s = store(maxAttempts = 2)
        val issued = s.issue(7u, EMAIL, credentialRevision = 0)
        val wrong = assertIs<MfaChallenges.Outcome.Failure>(s.verify(issued.challengeId, "x"))
        assertEquals(MfaChallenges.Subject(7u, EMAIL), wrong.subject)
        assertTrue(wrong.wrongCode)
        val exhausted = assertIs<MfaChallenges.Outcome.Failure>(s.verify(issued.challengeId, "x"))
        assertEquals("too_many_attempts", exhausted.reason)
        assertTrue(exhausted.wrongCode)
        val unknown = assertIs<MfaChallenges.Outcome.Failure>(s.verify(issued.challengeId, "x"))
        assertEquals(null, unknown.subject)
        assertEquals(false, unknown.wrongCode)
    }

    @Test
    fun `an expired challenge names its account but is not a guess`() {
        val s = store(ttlMillis = 1_000)
        val issued = s.issue(7u, EMAIL, credentialRevision = 0)
        now += 1_000
        val outcome = assertIs<MfaChallenges.Outcome.Failure>(s.verify(issued.challengeId, issued.code))
        assertEquals("expired", outcome.reason)
        assertEquals(7u, outcome.subject?.userId)
        assertEquals(false, outcome.wrongCode)
    }

    @Test
    fun `discardUser drops every pending challenge of that account only`() {
        val s = store()
        val mine = (1..2).map { s.issue(1u, EMAIL, credentialRevision = 0) }
        val other = s.issue(2u, "b@example.test", credentialRevision = 0)
        s.discardUser(1u)
        mine.forEach {
            assertEquals("unknown_challenge", (s.verify(it.challengeId, it.code) as MfaChallenges.Outcome.Failure).reason)
        }
        assertIs<MfaChallenges.Outcome.Success>(s.verify(other.challengeId, other.code))
    }

    @Test
    fun `expiry releases the per-user slots too`() {
        val s = store(ttlMillis = 1_000, maxPerUser = 1, maxTracked = 1)
        s.issue(1u, EMAIL, credentialRevision = 0)
        now += 1_000
        // The expired challenge is pruned from every index, so account 2 gets the only slot and account 1 can come back later.
        val other = s.issue(2u, "b@example.test", credentialRevision = 0)
        assertIs<MfaChallenges.Outcome.Success>(s.verify(other.challengeId, other.code))
        assertTrue(s.issue(1u, EMAIL, credentialRevision = 0).challengeId.isNotBlank())
    }
}
