package ch.nokillswit.auth

import ch.nokillswit.infra.ExpiringIndex
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.maskedSecret
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * In-memory store of pending email-MFA challenges (Lettuce's, ported): a login with correct
 * credentials by an MFA-enabled user mints a challenge — an opaque id handed to the client
 * plus a 6-digit code emailed to the account — and the pair must come back to
 * POST /api/v1/login/mfa within [ttlMillis] and [maxAttempts] guesses. A challenge is
 * single-use: consumed on success, dropped on expiry or when the attempt cap is exceeded.
 *
 * In-memory and per-instance by design (the LoginThrottle posture): the deployment runs a
 * single replica, and a restart only invalidates pending challenges — the user simply signs
 * in again. An account holds at most [maxPerUser] live challenges: issuing one more REPLACES
 * the oldest (refusing would let anyone holding the password lock the owner out of signing
 * in, and every extra live code is another independent guess target). The per-challenge
 * attempt cap bounds one code; the ROUTE counts every wrong guess into the per-account
 * lockout (`LoginThrottle`, `auth/AccountLockout.kt`), which bounds the guesses across
 * challenges, so a fresh login never resets the budget.
 */
class MfaChallenges(
    private val ttlMillis: Long,
    private val maxAttempts: Int,
    private val maxTracked: Int = 10_000,
    private val maxPerUser: Int = DEFAULT_MAX_PER_USER,
    private val clock: () -> Long = ::nowMillis,
) {
    init {
        require(maxTracked > 0) { "maxTracked must be positive" }
        require(maxPerUser > 0) { "maxPerUser must be positive" }
    }

    class CapacityExceededException : RuntimeException()

    data class IssuedChallenge(val challengeId: String, val code: String, val expiresAt: Long) {
        override fun toString(): String =
            "IssuedChallenge(challengeId=${maskedSecret(challengeId)}, code=${maskedSecret(code)}, expiresAt=$expiresAt)"
    }

    /** The account a challenge was minted for; [email] is the lockout identity the password step verified. */
    data class Subject(val userId: UInt, val email: String)

    sealed interface Outcome {
        data class Success(val subject: Subject, val credentialRevision: Long) : Outcome

        /**
         * [reason] feeds the audit trail only — the HTTP answer stays a uniform 401. [subject] is set whenever the challenge was
         * found (so the audit line can name the account); [wrongCode] marks a guess that must count into the account lockout, and
         * [credentialRevision] is the revision the challenge was minted at (the route counts the guess only while it is still current).
         */
        data class Failure(
            val reason: String,
            val subject: Subject? = null,
            val wrongCode: Boolean = false,
            val credentialRevision: Long? = null,
        ) : Outcome
    }

    private data class Challenge(
        val subject: Subject,
        val credentialRevision: Long,
        val code: String,
        val expiresAt: Long,
        val attempts: Int,
    ) {
        override fun toString(): String =
            "Challenge(userId=${subject.userId}, credentialRevision=$credentialRevision, code=${maskedSecret(code)}, " +
                "expiresAt=$expiresAt, attempts=$attempts)"
    }

    private val challenges = mutableMapOf<String, Challenge>()
    private val expiries = ExpiringIndex<String>()

    /** Each account's live challenge ids, oldest first (every challenge has the same TTL, so issue order is expiry order). */
    private val byUser = mutableMapOf<UInt, ArrayDeque<String>>()
    private val lock = Any()

    /**
     * Mints a challenge for [userId] ([email] is the lockout identity). At [maxPerUser] live challenges the oldest is replaced; the
     * capacity check counts that replacement, so an account at its cap is never refused for capacity it is about to free.
     */
    fun issue(userId: UInt, email: String, credentialRevision: Long): IssuedChallenge = synchronized(lock) {
        val now = clock()
        pruneExpired(now)
        val oldest = byUser[userId]?.takeIf { it.size >= maxPerUser }?.first()
        if (oldest == null && challenges.size >= maxTracked) throw CapacityExceededException()
        if (oldest != null) removeChallenge(oldest)
        val id = generateChallengeId()
        val code = generateMfaCode()
        val expiresAt = now + ttlMillis
        challenges[id] = Challenge(Subject(userId, email), credentialRevision, code, expiresAt, attempts = 0)
        expiries.add(expiresAt, id)
        byUser.getOrPut(userId) { ArrayDeque() }.addLast(id)
        IssuedChallenge(id, code, expiresAt)
    }

    fun verify(challengeId: String, code: String): Outcome = synchronized(lock) {
        val challenge = challenges[challengeId]
            ?: return@synchronized Outcome.Failure("unknown_challenge")
        if (challenge.expiresAt <= clock()) {
            removeChallenge(challengeId)
            return@synchronized Outcome.Failure("expired", challenge.subject)
        }
        if (MessageDigest.isEqual(challenge.code.toByteArray(), code.toByteArray())) {
            removeChallenge(challengeId)
            return@synchronized Outcome.Success(challenge.subject, challenge.credentialRevision)
        }
        val attempts = challenge.attempts + 1
        if (attempts >= maxAttempts) {
            removeChallenge(challengeId)
            Outcome.Failure("too_many_attempts", challenge.subject, wrongCode = true, credentialRevision = challenge.credentialRevision)
        } else {
            challenges[challengeId] = challenge.copy(attempts = attempts)
            Outcome.Failure("wrong_code", challenge.subject, wrongCode = true, credentialRevision = challenge.credentialRevision)
        }
    }

    /** Drops every pending challenge of [userId] (their account just locked: no pending code may finish a sign-in). */
    fun discardUser(userId: UInt) = synchronized(lock) {
        byUser[userId]?.toList()?.forEach { removeChallenge(it) }
    }

    /** Drops an issued challenge when its delivery fails, immediately releasing its slot. */
    fun discard(challengeId: String) = synchronized(lock) {
        removeChallenge(challengeId)
    }

    private fun pruneExpired(now: Long) {
        expiries.pruneExpired(now) { id, expiresAt ->
            // Not removeChallenge(): the index entry is already polled; only the maps are left to clear.
            if (challenges[id]?.expiresAt == expiresAt) dropFromMaps(id)
        }
    }

    private fun removeChallenge(id: String): Boolean {
        val removed = dropFromMaps(id) ?: return false
        expiries.remove(removed.expiresAt, id)
        return true
    }

    private fun dropFromMaps(id: String): Challenge? {
        val removed = challenges.remove(id) ?: return null
        byUser[removed.subject.userId]?.let { ids ->
            ids.remove(id)
            if (ids.isEmpty()) byUser.remove(removed.subject.userId)
        }
        return removed
    }

    companion object {
        /** Live challenges one account may hold; one more replaces the oldest. A retry or a second tab needs two, three is slack. */
        const val DEFAULT_MAX_PER_USER = 3

        private val secureRandom = SecureRandom()

        /** 128 bits of opaque, unguessable challenge identity. */
        private fun generateChallengeId(): String {
            val bytes = ByteArray(16)
            secureRandom.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
