package ch.nokillswit

import ch.nokillswit.auth.AuditDigest
import ch.nokillswit.auth.loginIdentityKey
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a login audit line calls the identity (`observability.md`): the `email` of an account that EXISTS, the fixed-size `emailDigest`
 * for an address with no account, because what is typed into the email box can be anything, a password included. The rule covers the
 * failure, the lockout and the refusal lines alike, and nothing may print the typed text in the clear.
 */
/** The `emailDigest` the test application writes for [email]: its JWT secret is the development default `secret` (`application.yaml`). */
internal fun testAuditDigest(email: String): String = AuditDigest("secret").of(email)

class AuthAuditIdentityTest {
    @Test
    fun `the existence probe sees an active account by its folded address and nothing else`() = kotlinx.coroutines.runBlocking {
        val email = uniqueEmail("probe")
        val id = TestUsers.seed(email = email, password = "pw")
        val service = TestUsers.service
        assertTrue(service.accountExists(email))
        assertTrue(service.accountExists("  ${email.uppercase()} "), "folded like the full lookup")
        assertEquals(false, service.accountExists(uniqueEmail("probe-none")))
        TestUsers.softDelete(id)
        assertEquals(false, service.accountExists(email), "a soft-deleted account is no account")
    }

    @Test
    fun `the audit digest is a keyed HMAC - stable per deployment, not the unsalted hash, different under another secret`() {
        val digest = AuditDigest("secret")
        val typed = "hunter2@hammurabi.test"
        assertEquals(64, digest.of(typed).length)
        assertEquals(digest.of(typed), digest.of("  HUNTER2@hammurabi.test "), "folded like the lockout key, so one identity is one digest")
        assertNotEquals(loginIdentityKey(typed), digest.of(typed), "an offline guess of the plain SHA-256 must not match")
        assertNotEquals(digest.of(typed), AuditDigest("another-secret").of(typed), "the key is the deployment's")
        assertNotEquals(digest.of(typed), digest.of("hunter3@hammurabi.test"))
    }

    @Test
    fun `a failed login for an address with no account is named by its digest, a wrong password for a real one by its email`() =
        testApplication {
            configureApp("security.lockout.threshold" to "2")
            startApplication()
            val client = jsonClient()
            val typedPassword = "hunter2-${UUID.randomUUID()}" // someone pasted their password into the email box
            val unknown = "$typedPassword@hammurabi.test"
            val known = uniqueEmail("audit-known")
            TestUsers.seed(email = known, password = "right-pw")
            val capture = LogCapture("ch.nokillswit.audit")
            try {
                assertEquals(HttpStatusCode.Unauthorized, client.login(unknown, "x").status)
                val unknownLine = assertNotNull(
                    capture.awaitEvent { it.message == "login.failure" && it.hasKeyValue("emailDigest", testAuditDigest(unknown)) },
                )
                assertTrue(unknownLine.hasKeyValue("reason", "unknown_email"))
                assertEquals(HttpStatusCode.Unauthorized, client.login(known, "wrong-pw").status)
                val knownLine = assertNotNull(capture.awaitEvent { it.message == "login.failure" && it.hasKeyValue("email", known) })
                assertTrue(knownLine.hasKeyValue("reason", "wrong_password"))

                // The second guess locks the identity (threshold 2), the third is refused: for a guess at no account too.
                assertEquals(HttpStatusCode.Unauthorized, client.login(unknown, "x").status)
                assertEquals(HttpStatusCode.TooManyRequests, client.login(unknown, "x").status)
                val digest = testAuditDigest(unknown)
                assertNotNull(capture.awaitEvent { it.message == "login.lockout" && it.hasKeyValue("emailDigest", digest) })
                assertNotNull(capture.awaitEvent { it.message == "login.rejected_locked" && it.hasKeyValue("emailDigest", digest) })
                // A real account that locks keeps its email on the same lines.
                assertEquals(HttpStatusCode.Unauthorized, client.login(known, "wrong-pw").status)
                assertNotNull(capture.awaitEvent { it.message == "login.lockout" && it.hasKeyValue("email", known) })
                assertEquals(HttpStatusCode.TooManyRequests, client.login(known, "right-pw").status)
                assertNotNull(capture.awaitEvent { it.message == "login.rejected_locked" && it.hasKeyValue("email", known) })

                val everything = capture.events.flatMap { e ->
                    listOf(e.formattedMessage) + e.keyValuePairs.orEmpty().map { "${it.key}=${it.value}" }
                }
                assertTrue(everything.none { typedPassword in it }, "the text typed for an unknown address reaches no audit line")
            } finally {
                capture.detach()
            }
        }
}
