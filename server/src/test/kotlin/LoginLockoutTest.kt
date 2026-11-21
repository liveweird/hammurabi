package ch.nokillswit

import ch.nokillswit.users.UserRole
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * The per-account lockout (auth/LoginThrottle.kt, built by auth/AccountLockout.kt): after `threshold`
 * consecutive failures for one email, further attempts — even with the correct password —
 * answer 429 for the lockout window. Independent of the per-IP rate limit.
 */
class LoginLockoutTest {

    @Test
    fun `a full lockout store rejects unseen identities while retaining existing state`() = testApplication {
        configureApp(
            "security.lockout.threshold" to "2",
            "security.lockout.maxTracked" to "1",
        )
        startApplication()
        val client = jsonClient()
        val tracked = uniqueEmail("capacity-tracked")
        val rejected = " ${"x".repeat(10_000)}@invalid " // over the 254-character audit cutoff, under the 16 KiB auth body limit
        val auditEvents = LogCapture("ch.nokillswit.audit")
        try {
            assertEquals(HttpStatusCode.Unauthorized, client.login(tracked, "wrong").status)
            assertEquals(HttpStatusCode.TooManyRequests, client.login(rejected, "wrong").status)
            val capacityEvent = assertNotNull(
                auditEvents.awaitEvent { it.message == "login.capacity_rejected" },
                "capacity rejection should be audited",
            )
            val auditFields = capacityEvent.keyValuePairs.orEmpty()
            val digest = auditFields.single { it.key == "emailDigest" }.value as String
            assertEquals(64, digest.length)
            assertFalse(auditFields.any { it.key == "email" })
            // The fresh tracked counter survived: its next failure still trips its lock.
            assertEquals(HttpStatusCode.Unauthorized, client.login(tracked, "wrong").status)
            assertNotNull(
                auditEvents.awaitEvent {
                    it.message == "login.lockout" && it.hasKeyValue("emailDigest", testAuditDigest(tracked))
                },
                "an identity with no account is named by its digest, never in the clear",
            )
            assertEquals(HttpStatusCode.TooManyRequests, client.login(tracked, "wrong").status)
        } finally {
            auditEvents.detach()
        }
    }

    @Test
    fun `threshold consecutive failures lock the account - even the right password answers 429`() = testApplication {
        configureApp("security.lockout.threshold" to "3")
        startApplication()
        val email = uniqueEmail("locked")
        TestUsers.seed(email = email, password = "right-pw")
        val client = jsonClient()

        repeat(3) {
            val attempt = client.login(email, "wrong-pw")
            assertEquals(HttpStatusCode.Unauthorized, attempt.status)
        }

        val lockedOut = client.login(email, "right-pw")
        assertEquals(HttpStatusCode.TooManyRequests, lockedOut.status)
        // The lockout's account-specific detail must survive StatusPages' generic 429 handler.
        assertContains(lockedOut.bodyAsText(), "failed login attempts")
    }

    @Test
    fun `a successful login resets the failure counter`() = testApplication {
        configureApp("security.lockout.threshold" to "3")
        startApplication()
        val email = uniqueEmail("reset")
        TestUsers.seed(email = email, password = "right-pw")
        val client = jsonClient()

        repeat(2) {
            client.login(email, "wrong-pw")
        }
        val success = client.login(email, "right-pw")
        assertEquals(HttpStatusCode.OK, success.status)

        // The counter restarted: two more failures stay under the threshold of 3.
        repeat(2) {
            val attempt = client.login(email, "wrong-pw")
            assertEquals(HttpStatusCode.Unauthorized, attempt.status)
        }
    }

    @Test
    fun `the lockout bucket is shared across email case variants`() = testApplication {
        configureApp("security.lockout.threshold" to "3")
        startApplication()
        val email = uniqueEmail("variant")
        TestUsers.seed(email = email, password = "right-pw")
        val client = jsonClient()

        for (variant in listOf(email, email.uppercase(), " $email ")) {
            client.login(variant, "wrong-pw")
        }
        val lockedOut = client.login(email, "right-pw")
        assertEquals(HttpStatusCode.TooManyRequests, lockedOut.status)
    }

    @Test
    fun `at a full store a real account's login gets the same capacity 429 as an unknown email`() = testApplication {
        configureApp("security.lockout.maxTracked" to "1")
        startApplication()
        val client = jsonClient()
        val real = uniqueEmail("capacity-real")
        TestUsers.seed(email = real, password = "pw-123456789", role = UserRole.USER)
        // Another identity's failure takes the only slot.
        assertEquals(HttpStatusCode.Unauthorized, client.login(uniqueEmail("capacity-filler"), "wrong").status)

        val unknown = client.login(uniqueEmail("capacity-ghost"), "wrong")
        val known = client.login(real, "wrong")
        val knownRight = client.login(real, "pw-123456789")
        assertEquals(HttpStatusCode.TooManyRequests, unknown.status)
        assertEquals(unknown.status, known.status)
        assertEquals(unknown.status, knownRight.status, "not even the right password is compared at a full store")
        // The same problem body, byte for byte (status, title, detail, instance): nothing tells the three apart.
        val expected = unknown.bodyAsText()
        assertContains(expected, "tracked")
        assertEquals(expected, known.bodyAsText())
        assertEquals(expected, knownRight.bodyAsText())
    }
}
