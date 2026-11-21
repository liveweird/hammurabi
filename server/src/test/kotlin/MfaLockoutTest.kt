package ch.nokillswit

import ch.nokillswit.auth.LoginRequest
import ch.nokillswit.auth.MfaChallengeResponse
import ch.nokillswit.auth.MfaVerifyRequest
import ch.nokillswit.auth.PasswordResetConfirmRequest
import ch.nokillswit.auth.PasswordResetRequest
import ch.nokillswit.users.UserFeaturesUpdateRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The email-MFA code is a guess target like a password: the wrong codes of ALL of an account's challenges count into the per-account
 * lockout (the `/login` counter), a fresh login never resets that budget, an account holds a few live challenges at most, and every
 * `login.mfa_failure` for a known challenge names the account (C-SEC-3). The flow itself is `MfaLoginTest`; the pure store is
 * `MfaChallengesTest`.
 */
class MfaLockoutTest {

    private suspend fun HttpClient.login(email: String, password: String): HttpResponse =
        postJson("/api/v1/login", LoginRequest(email, password))

    private suspend fun HttpClient.verify(challengeId: String, code: String): HttpResponse =
        postJson("/api/v1/login/mfa", MfaVerifyRequest(challengeId, code))

    private suspend fun ApplicationTestBuilder.seedMfaUser(email: String): UInt {
        val adminEmail = uniqueEmail("mfa-lock-admin")
        TestUsers.seed(email = adminEmail, password = "pw-123456789")
        val admin = authedClient(adminEmail, "pw-123456789")
        val userId = TestUsers.seed(email = email, password = "pw-123456789", role = UserRole.USER)
        // An empty disabled set removes the default MFA row, i.e. enables MFA.
        val enabled = admin.putJson("/api/v1/users/$userId/features", UserFeaturesUpdateRequest(emptyList()))
        assertEquals(HttpStatusCode.NoContent, enabled.status)
        return userId
    }

    /** The sign-in codes emailed to [email] so far, oldest first, once at least [count] have been delivered (log transport). */
    private suspend fun LogCapture.codes(email: String, count: Int): List<String> {
        repeat(100) {
            val codes = events.filter { "To: $email" in it.formattedMessage }
                .mapNotNull { Regex("""(?m)^\d{6}$""").find(it.formattedMessage)?.value }
            if (codes.size >= count) return codes
            delay(50)
        }
        error("expected $count sign-in code emails for $email")
    }

    private fun wrongOf(code: String) = if (code == "000000") "000001" else "000000"

    @Test
    fun `a confirmed password reset lifts an escalated lock and its level, an unconfirmed request does not`() = testApplication {
        configureApp("security.lockout.threshold" to "3")
        startApplication()
        val email = uniqueEmail("mfa-reset")
        val userId = seedMfaUser(email)
        val mail = LogCapture("ch.nokillswit.mail")
        val audit = LogCapture("ch.nokillswit.audit")
        try {
            val client = jsonClient()
            suspend fun lockByWrongCodes(mails: Int, password: String) {
                val challenge = client.login(email, password).body<MfaChallengeResponse>()
                val code = mail.codes(email, mails).last()
                repeat(3) { assertEquals(HttpStatusCode.Unauthorized, client.verify(challenge.challengeId, wrongOf(code)).status) }
            }
            fun lockouts() = audit.events.filter { it.message == "login.lockout" && it.hasKeyValue("userId", userId.toLong()) }
            lockByWrongCodes(1, "pw-123456789")
            assertEquals(HttpStatusCode.TooManyRequests, client.login(email, "pw-123456789").status)

            // An unconfirmed request (the 202 path, a link merely sent) clears nothing.
            assertEquals(HttpStatusCode.Accepted, client.postJson("/api/v1/password-reset", PasswordResetRequest(email)).status)
            assertNotNull(audit.awaitEvent { it.message == "password_reset.link_sent" && it.hasKeyValue("email", email) })
            assertEquals(HttpStatusCode.TooManyRequests, client.login(email, "pw-123456789").status)

            // The confirmed reset (the link proves the mailbox) lifts the lock, and says so.
            val token = assertNotNull(newPasswordResetService().issueToken(userId, 0, email))
            assertEquals(
                HttpStatusCode.NoContent,
                client.postJson("/api/v1/password-reset/confirm", PasswordResetConfirmRequest(token, "chosen-password-1")).status,
            )
            assertNotNull(
                audit.awaitEvent { it.message == "password_reset.completed" && it.hasKeyValue("lockoutCleared", true) },
            )
            assertTrue(client.login(email, "chosen-password-1").body<MfaChallengeResponse>().mfaRequired, "no 429: the lock is gone")

            // ...and the escalation level too: the next code-tripped lock is level 1 (900 s) again, not level 2.
            lockByWrongCodes(3, "chosen-password-1")
            assertNotNull(audit.awaitEvent { e -> lockouts().size == 2 && e.message == "login.lockout" })
            assertEquals(listOf(1, 1), lockouts().map { e -> e.keyValuePairs.first { it.key == "escalationLevel" }.value })
            assertEquals(listOf(900L, 900L), lockouts().map { e -> e.keyValuePairs.first { it.key == "lockSeconds" }.value })
        } finally {
            mail.detach()
            audit.detach()
        }
    }

    @Test
    fun `wrong codes on a challenge minted before a password reset neither count nor lock the owner again`() = testApplication {
        configureApp("security.lockout.threshold" to "2")
        startApplication()
        val email = uniqueEmail("mfa-stale")
        val userId = seedMfaUser(email)
        val mail = LogCapture("ch.nokillswit.mail")
        val audit = LogCapture("ch.nokillswit.audit")
        try {
            val client = jsonClient()
            val old = client.login(email, "pw-123456789").body<MfaChallengeResponse>()
            val oldCode = mail.codes(email, 1).single()
            val token = assertNotNull(newPasswordResetService().issueToken(userId, 0, email))
            assertEquals(
                HttpStatusCode.NoContent,
                client.postJson("/api/v1/password-reset/confirm", PasswordResetConfirmRequest(token, "chosen-password-1")).status,
            )
            // The old challenge is dead: wrong codes on it (more than the threshold) answer the uniform 401 and lock nobody.
            repeat(3) { assertEquals(HttpStatusCode.Unauthorized, client.verify(old.challengeId, wrongOf(oldCode)).status) }
            assertNotNull(
                audit.awaitEvent { it.message == "login.mfa_failure" && it.hasKeyValue("reason", "credential_changed") },
            )
            assertEquals(
                0,
                audit.events.count { it.message == "login.lockout" && it.hasKeyValue("userId", userId.toLong()) },
                "nothing counted",
            )
            assertTrue(client.login(email, "chosen-password-1").body<MfaChallengeResponse>().mfaRequired, "no 429")
        } finally {
            mail.detach()
            audit.detach()
        }
    }

    @Test
    fun `wrong codes across fresh challenges add up and lock the account`() = testApplication {
        configureApp("security.lockout.threshold" to "3")
        startApplication()
        val email = uniqueEmail("mfa-lock")
        val userId = seedMfaUser(email)
        val mail = LogCapture("ch.nokillswit.mail")
        val audit = LogCapture("ch.nokillswit.audit")
        try {
            val client = jsonClient()
            val first = client.login(email, "pw-123456789").body<MfaChallengeResponse>()
            val firstCode = mail.codes(email, 1).single()
            assertEquals(HttpStatusCode.Unauthorized, client.verify(first.challengeId, wrongOf(firstCode)).status)
            assertEquals(HttpStatusCode.Unauthorized, client.verify(first.challengeId, wrongOf(firstCode)).status)

            // A fresh login (the password is right) must NOT reset the guess budget...
            val second = client.login(email, "pw-123456789").body<MfaChallengeResponse>()
            val secondCode = mail.codes(email, 2).last()
            // ...so the third wrong code, on the NEW challenge, trips the lock (the locking failure itself is the uniform 401).
            assertEquals(HttpStatusCode.Unauthorized, client.verify(second.challengeId, wrongOf(secondCode)).status)
            // The first MFA-tripped lock is the flat window (900 s) at escalation level 1.
            assertNotNull(
                audit.awaitEvent {
                    it.message == "login.lockout" && it.hasKeyValue("userId", userId.toLong()) &&
                        it.hasKeyValue("lockSeconds", 900L) && it.hasKeyValue("escalationLevel", 1)
                },
            )

            // Locked: the right password answers 429, and no pending challenge can finish a sign-in, its right code included.
            assertEquals(HttpStatusCode.TooManyRequests, client.login(email, "pw-123456789").status)
            assertEquals(HttpStatusCode.Unauthorized, client.verify(second.challengeId, secondCode).status)
            assertNotNull(
                audit.awaitEvent { it.message == "login.mfa_failure" && it.hasKeyValue("reason", "unknown_challenge") },
                "the locked account's challenges were dropped",
            )
        } finally {
            mail.detach()
            audit.detach()
        }
    }

    @Test
    fun `a correct code clears the budget - the next login starts from zero`() = testApplication {
        configureApp("security.lockout.threshold" to "3")
        startApplication()
        val email = uniqueEmail("mfa-clear")
        seedMfaUser(email)
        val mail = LogCapture("ch.nokillswit.mail")
        try {
            val client = jsonClient()
            val first = client.login(email, "pw-123456789").body<MfaChallengeResponse>()
            val firstCode = mail.codes(email, 1).single()
            repeat(2) { assertEquals(HttpStatusCode.Unauthorized, client.verify(first.challengeId, wrongOf(firstCode)).status) }
            assertEquals(HttpStatusCode.OK, client.verify(first.challengeId, firstCode).status)

            val second = client.login(email, "pw-123456789").body<MfaChallengeResponse>()
            val secondCode = mail.codes(email, 2).last()
            // Two wrong codes again stay under the threshold of 3 only because the accepted code cleared the two before.
            repeat(2) { assertEquals(HttpStatusCode.Unauthorized, client.verify(second.challengeId, wrongOf(secondCode)).status) }
            assertEquals(HttpStatusCode.OK, client.verify(second.challengeId, secondCode).status)
        } finally {
            mail.detach()
        }
    }

    @Test
    fun `a lock tripped by wrong passwords after the challenge refuses its right code with 429`() = testApplication {
        configureApp("security.lockout.threshold" to "2")
        startApplication()
        val email = uniqueEmail("mfa-lock-pw")
        seedMfaUser(email)
        val mail = LogCapture("ch.nokillswit.mail")
        try {
            val client = jsonClient()
            val challenge = client.login(email, "pw-123456789").body<MfaChallengeResponse>()
            val code = mail.codes(email, 1).single()
            repeat(2) { assertEquals(HttpStatusCode.Unauthorized, client.login(email, "wrong-pw").status) }
            assertEquals(HttpStatusCode.TooManyRequests, client.verify(challenge.challengeId, code).status)
        } finally {
            mail.detach()
        }
    }

    @Test
    fun `an account holds at most three live challenges - the fourth login replaces the oldest`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("mfa-cap-user")
        seedMfaUser(email)
        val mail = LogCapture("ch.nokillswit.mail")
        try {
            val client = jsonClient()
            val challenges = mutableListOf<MfaChallengeResponse>()
            val codes = mutableListOf<String>()
            for (i in 1..4) {
                challenges += client.login(email, "pw-123456789").body<MfaChallengeResponse>()
                // The delivery is fire-and-forget: wait for THIS login's email before the next, so the codes stay in login order.
                codes += mail.codes(email, i).last()
            }

            // The first (oldest) challenge was replaced: its own right code no longer works.
            assertEquals(HttpStatusCode.Unauthorized, client.verify(challenges[0].challengeId, codes[0]).status)
            // The three newest stay independently verifiable.
            assertEquals(HttpStatusCode.OK, client.verify(challenges[3].challengeId, codes[3]).status)
            assertEquals(HttpStatusCode.OK, client.verify(challenges[1].challengeId, codes[1]).status)
            assertEquals(HttpStatusCode.OK, client.verify(challenges[2].challengeId, codes[2]).status)
        } finally {
            mail.detach()
        }
    }

    @Test
    fun `login mfa_failure names the account whenever the challenge was found, and nobody for an unknown id`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("mfa-audit-user")
        val userId = seedMfaUser(email)
        val mail = LogCapture("ch.nokillswit.mail")
        val audit = LogCapture("ch.nokillswit.audit")
        try {
            val client = jsonClient()
            val challenge = client.login(email, "pw-123456789").body<MfaChallengeResponse>()
            val code = mail.codes(email, 1).single()
            assertEquals(HttpStatusCode.Unauthorized, client.verify(challenge.challengeId, wrongOf(code)).status)
            assertNotNull(
                audit.awaitEvent {
                    it.message == "login.mfa_failure" && it.hasKeyValue("reason", "wrong_code") && it.hasKeyValue("userId", userId.toLong())
                },
                "a wrong code names the account",
            )

            assertEquals(HttpStatusCode.Unauthorized, client.verify("0".repeat(32), "123456").status)
            val unknown = assertNotNull(
                audit.awaitEvent { it.message == "login.mfa_failure" && it.hasKeyValue("reason", "unknown_challenge") },
            )
            assertTrue(unknown.keyValuePairs.orEmpty().none { it.key == "userId" }, "an unknown id names nobody: no userId field at all")
            assertTrue(unknown.keyValuePairs.orEmpty().none { it.key == "email" }, "the guesser's input is never echoed")
        } finally {
            mail.detach()
            audit.detach()
        }
    }

    @Test
    fun `a wrong code at an already-locked account answers the lockout 429 and drops its challenges`() = testApplication {
        configureApp("security.lockout.threshold" to "2")
        startApplication()
        val email = uniqueEmail("mfa-already-locked")
        val userId = seedMfaUser(email)
        val mail = LogCapture("ch.nokillswit.mail")
        val audit = LogCapture("ch.nokillswit.audit")
        try {
            val client = jsonClient()
            val challenge = client.login(email, "pw-123456789").body<MfaChallengeResponse>()
            val code = mail.codes(email, 1).single()
            // Two wrong passwords lock the account while the challenge is still live (the lock did not come from the MFA step).
            repeat(2) { assertEquals(HttpStatusCode.Unauthorized, client.login(email, "wrong-pw").status) }

            // A guess now meets an ACTIVE lock: the lockout's 429 (not the uniform 401), audited, and the pending challenge is dropped.
            assertEquals(HttpStatusCode.TooManyRequests, client.verify(challenge.challengeId, wrongOf(code)).status)
            assertNotNull(
                audit.awaitEvent {
                    it.message == "login.rejected_locked" && it.hasKeyValue("userId", userId.toLong()) && it.hasKeyValue("source", "mfa")
                },
            )
            assertEquals(HttpStatusCode.Unauthorized, client.verify(challenge.challengeId, code).status)
        } finally {
            mail.detach()
            audit.detach()
        }
    }

    @Test
    fun `wrong codes still count and lock with the lockout store full, and the right code is then refused`() = testApplication {
        configureApp("security.lockout.maxTracked" to "1", "security.lockout.threshold" to "2")
        startApplication()
        val email = uniqueEmail("mfa-capacity-code")
        val userId = seedMfaUser(email)
        val mail = LogCapture("ch.nokillswit.mail")
        val audit = LogCapture("ch.nokillswit.audit")
        try {
            val client = jsonClient()
            val challenge = client.login(email, "pw-123456789").body<MfaChallengeResponse>()
            val code = mail.codes(email, 1).single()
            // Another identity's failure takes the only slot AFTER this account's password step (which tracks nothing): the store is
            // full, which is exactly what a password holder can arrange with junk logins before guessing codes.
            assertEquals(HttpStatusCode.Unauthorized, client.login(uniqueEmail("mfa-capacity-filler"), "wrong-pw").status)

            // The account exists (the challenge proves it), so its wrong codes are counted past the cap: the second one locks.
            assertEquals(HttpStatusCode.Unauthorized, client.verify(challenge.challengeId, wrongOf(code)).status)
            assertEquals(HttpStatusCode.Unauthorized, client.verify(challenge.challengeId, wrongOf(code)).status)
            assertNotNull(audit.awaitEvent { it.message == "login.lockout" && it.hasKeyValue("userId", userId.toLong()) })
            // Locked: the right code no longer finishes a sign-in (the lockout's 429, or the uniform 401 once the challenge is dropped).
            assertEquals(HttpStatusCode.Unauthorized, client.verify(challenge.challengeId, code).status)
            assertEquals(HttpStatusCode.TooManyRequests, client.login(email, "pw-123456789").status)
            assertTrue(
                audit.events.none { it.message == "login.capacity_rejected" && it.hasKeyValue("userId", userId.toLong()) },
                "a known account is never turned away as a capacity case",
            )
        } finally {
            mail.detach()
            audit.detach()
        }
    }

    @Test
    fun `the right code still signs in with the lockout store full - an untracked account is not turned away`() = testApplication {
        configureApp("security.lockout.maxTracked" to "1")
        startApplication()
        val email = uniqueEmail("mfa-capacity-ok")
        seedMfaUser(email)
        val mail = LogCapture("ch.nokillswit.mail")
        try {
            val client = jsonClient()
            val challenge = client.login(email, "pw-123456789").body<MfaChallengeResponse>()
            val code = mail.codes(email, 1).single()
            // The only slot goes to another identity AFTER this account's password step: the account is untracked at a full store.
            assertEquals(HttpStatusCode.Unauthorized, client.login(uniqueEmail("mfa-capacity-filler-ok"), "wrong-pw").status)
            assertEquals(HttpStatusCode.OK, client.verify(challenge.challengeId, code).status)
        } finally {
            mail.detach()
        }
    }
}
