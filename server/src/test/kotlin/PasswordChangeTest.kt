package ch.nokillswit

import ch.nokillswit.auth.LoginRequest
import ch.nokillswit.users.PasswordUpdateRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PasswordChangeTest {

    @Test
    fun `self change with the correct current password works and the new password logs in`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("self")
        val userId = TestUsers.seed(email = email, password = "old-password", role = UserRole.USER)
        val client = authedClient(email, "old-password")

        val response = client.putJson(
            "/api/v1/users/$userId/password",
            PasswordUpdateRequest(password = "brand-new-password", currentPassword = "old-password"),
        )
        assertEquals(HttpStatusCode.NoContent, response.status)

        val login = jsonClient().postJson("/api/v1/login", LoginRequest(email, "brand-new-password"))
        assertEquals(HttpStatusCode.OK, login.status)
    }

    @Test
    fun `self change with a wrong or missing current password is 403 and mutates nothing`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("wrongcur")
        val userId = TestUsers.seed(email = email, password = "old-password", role = UserRole.USER)
        val client = authedClient(email, "old-password")

        val wrong = client.putJson(
            "/api/v1/users/$userId/password",
            PasswordUpdateRequest(password = "brand-new-password", currentPassword = "not-it"),
        )
        assertEquals(HttpStatusCode.Forbidden, wrong.status)

        val missing = client.putJson("/api/v1/users/$userId/password", PasswordUpdateRequest(password = "brand-new-password"))
        assertEquals(HttpStatusCode.Forbidden, missing.status)

        val oldStillWorks = jsonClient().postJson("/api/v1/login", LoginRequest(email, "old-password"))
        assertEquals(HttpStatusCode.OK, oldStillWorks.status)
    }

    @Test
    fun `an admin resets another user's password without the current one`() = testApplication {
        usePostgresTestcontainer()
        val adminEmail = uniqueEmail("resetter")
        val targetEmail = uniqueEmail("target")
        TestUsers.seed(email = adminEmail, password = "admin-pw", role = UserRole.ADMIN)
        val targetId = TestUsers.seed(email = targetEmail, password = "old-password", role = UserRole.USER)
        val client = authedClient(adminEmail, "admin-pw")

        val response = client.putJson("/api/v1/users/$targetId/password", PasswordUpdateRequest(password = "admin-chosen-pw"))
        assertEquals(HttpStatusCode.NoContent, response.status)

        val login = jsonClient().postJson("/api/v1/login", LoginRequest(targetEmail, "admin-chosen-pw"))
        assertEquals(HttpStatusCode.OK, login.status)
    }

    @Test
    fun `a regular user may not change somebody else's password`() = testApplication {
        usePostgresTestcontainer()
        val callerEmail = uniqueEmail("caller")
        val otherEmail = uniqueEmail("other")
        TestUsers.seed(email = callerEmail, password = "pw", role = UserRole.USER)
        val otherId = TestUsers.seed(email = otherEmail, password = "pw", role = UserRole.USER)
        val client = authedClient(callerEmail, "pw")

        val response = client.putJson("/api/v1/users/$otherId/password", PasswordUpdateRequest(password = "hijacked-pw!"))
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `a too-short new password is 400`() = testApplication {
        usePostgresTestcontainer()
        val adminEmail = uniqueEmail("short")
        val targetId = TestUsers.seed(email = uniqueEmail("shorttarget"), password = "pw", role = UserRole.USER)
        TestUsers.seed(email = adminEmail, password = "admin-pw", role = UserRole.ADMIN)
        val client = authedClient(adminEmail, "admin-pw")

        val response = client.putJson("/api/v1/users/$targetId/password", PasswordUpdateRequest(password = "tiny"))
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `resetting an unknown user is 404`() = testApplication {
        usePostgresTestcontainer()
        val adminEmail = uniqueEmail("notfound")
        TestUsers.seed(email = adminEmail, password = "admin-pw", role = UserRole.ADMIN)
        val client = authedClient(adminEmail, "admin-pw")

        val response = client.putJson("/api/v1/users/999999999/password", PasswordUpdateRequest(password = "whatever-works"))
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `the self change rides a per-user bucket of 5 a minute - a burst is 429, another user is unaffected`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("burst")
        val userId = TestUsers.seed(email = email, password = "old-password", role = UserRole.USER)
        val otherEmail = uniqueEmail("burst-other")
        val otherId = TestUsers.seed(email = otherEmail, password = "old-password", role = UserRole.USER)
        val client = authedClient(email, "old-password")
        val other = authedClient(otherEmail, "old-password")
        val capture = LogCapture("ch.nokillswit.audit")
        try {
            // A MISSING current password is a 403 that is no guess (nothing counts toward the lockout), so only the bucket can answer 429.
            repeat(5) { n ->
                val response = client.putJson("/api/v1/users/$userId/password", PasswordUpdateRequest(password = "brand-new-password"))
                assertEquals(HttpStatusCode.Forbidden, response.status, "change ${n + 1}")
            }
            val rejected = client.putJson("/api/v1/users/$userId/password", PasswordUpdateRequest(password = "brand-new-password"))
            assertEquals(HttpStatusCode.TooManyRequests, rejected.status)
            val event = capture.awaitEvent { it.message == "rate_limit.exceeded" && it.hasKeyValue("bucket", "password-change") }
            assertNotNull(event)
            assertEquals(
                HttpStatusCode.Forbidden,
                other.putJson("/api/v1/users/$otherId/password", PasswordUpdateRequest(password = "brand-new-password")).status,
                "keyed by the user, not the address",
            )
        } finally {
            capture.detach()
        }
    }

    @Test
    fun `wrong current passwords count into the login lockout - the account locks, login and the change answer 429`() = testApplication {
        configureApp("security.lockout.threshold" to "3")
        startApplication()
        val email = uniqueEmail("lockme")
        val userId = TestUsers.seed(email = email, password = "old-password", role = UserRole.USER)
        val client = authedClient(email, "old-password")
        val capture = LogCapture("ch.nokillswit.audit")
        try {
            repeat(3) { n ->
                val wrong = client.putJson(
                    "/api/v1/users/$userId/password",
                    PasswordUpdateRequest(password = "brand-new-password", currentPassword = "not-it-$n"),
                )
                assertEquals(HttpStatusCode.Forbidden, wrong.status, "guess ${n + 1}")
            }
            // A password-tripped lock is the flat window and never escalates.
            assertNotNull(
                capture.awaitEvent {
                    it.message == "login.lockout" && it.hasKeyValue("userId", userId.toLong()) &&
                        it.hasKeyValue("lockSeconds", 900L) && it.hasKeyValue("escalationLevel", 0)
                },
            )

            // Locked: even the CORRECT current password is refused, with the lockout's specific 429, before any bcrypt.
            val locked = client.putJson(
                "/api/v1/users/$userId/password",
                PasswordUpdateRequest(password = "brand-new-password", currentPassword = "old-password"),
            )
            assertEquals(HttpStatusCode.TooManyRequests, locked.status)
            assertContains(locked.bodyAsText(), "failed login attempts")
            assertNotNull(
                capture.awaitEvent {
                    it.message == "password.change_rejected_locked" && it.hasKeyValue("targetUserId", userId.toLong()) &&
                        it.hasKeyValue("byUserId", userId.toLong())
                },
            )
            // The same lock refuses a sign-in with the right password, and nothing was changed.
            assertEquals(HttpStatusCode.TooManyRequests, jsonClient().login(email, "old-password").status)
        } finally {
            capture.detach()
        }
    }

    @Test
    fun `failed logins and wrong current passwords share ONE counter`() = testApplication {
        configureApp("security.lockout.threshold" to "3")
        startApplication()
        val email = uniqueEmail("shared")
        val userId = TestUsers.seed(email = email, password = "old-password", role = UserRole.USER)
        val client = authedClient(email, "old-password")

        repeat(2) { assertEquals(HttpStatusCode.Unauthorized, jsonClient().login(email, "wrong-pw").status) }
        val wrong = client.putJson(
            "/api/v1/users/$userId/password",
            PasswordUpdateRequest(password = "brand-new-password", currentPassword = "not-it"),
        )
        assertEquals(HttpStatusCode.Forbidden, wrong.status, "the locking failure itself answers like any wrong password")
        assertEquals(HttpStatusCode.TooManyRequests, jsonClient().login(email, "old-password").status)
    }

    @Test
    fun `a correct change clears the failure counter`() = testApplication {
        configureApp("security.lockout.threshold" to "3")
        startApplication()
        val email = uniqueEmail("clears")
        val userId = TestUsers.seed(email = email, password = "old-password", role = UserRole.USER)
        val client = authedClient(email, "old-password")

        repeat(2) {
            val wrong = client.putJson(
                "/api/v1/users/$userId/password",
                PasswordUpdateRequest(password = "brand-new-password", currentPassword = "not-it"),
            )
            assertEquals(HttpStatusCode.Forbidden, wrong.status)
        }
        val ok = client.putJson(
            "/api/v1/users/$userId/password",
            PasswordUpdateRequest(password = "brand-new-password", currentPassword = "old-password"),
        )
        assertEquals(HttpStatusCode.NoContent, ok.status)
        // The two earlier failures no longer count: two more stay under the threshold of 3.
        repeat(2) { assertEquals(HttpStatusCode.Unauthorized, jsonClient().login(email, "wrong-pw").status) }
        assertEquals(HttpStatusCode.OK, jsonClient().login(email, "brand-new-password").status)
    }

    @Test
    fun `an admin resetting another user's password neither counts into nor clears nor is refused by the target's counter`() =
        testApplication {
            configureApp("security.lockout.threshold" to "3")
            startApplication()
            val adminEmail = uniqueEmail("resetter-lock")
            TestUsers.seed(email = adminEmail, password = "admin-pw", role = UserRole.ADMIN)
            val admin = authedClient(adminEmail, "admin-pw")

            // Counts nothing: the target sits at 2 of 3 failures, a reset must not be the third.
            val partialEmail = uniqueEmail("target-partial")
            val partialId = TestUsers.seed(email = partialEmail, password = "old-password", role = UserRole.USER)
            repeat(2) { assertEquals(HttpStatusCode.Unauthorized, jsonClient().login(partialEmail, "wrong-pw").status) }
            val reset = admin.putJson("/api/v1/users/$partialId/password", PasswordUpdateRequest(password = "admin-chosen-pw"))
            assertEquals(HttpStatusCode.NoContent, reset.status)
            assertEquals(HttpStatusCode.OK, jsonClient().login(partialEmail, "admin-chosen-pw").status, "the reset did not lock the target")

            // Is not refused by a lock, and does not clear it: the target stays locked after the reset.
            val lockedEmail = uniqueEmail("target-locked")
            val lockedId = TestUsers.seed(email = lockedEmail, password = "old-password", role = UserRole.USER)
            repeat(3) { jsonClient().login(lockedEmail, "wrong-pw") }
            assertEquals(HttpStatusCode.TooManyRequests, jsonClient().login(lockedEmail, "old-password").status)
            val resetLocked = admin.putJson("/api/v1/users/$lockedId/password", PasswordUpdateRequest(password = "admin-chosen-pw"))
            assertEquals(HttpStatusCode.NoContent, resetLocked.status)
            assertEquals(HttpStatusCode.TooManyRequests, jsonClient().login(lockedEmail, "admin-chosen-pw").status, "still locked")
        }

    @Test
    fun `a full lockout store does not stop a wrong current password counting - the account locks, wrong stays a 403`() =
        testApplication {
            configureApp("security.lockout.maxTracked" to "1", "security.lockout.threshold" to "2")
            startApplication()
            val email = uniqueEmail("capacity-change")
            val userId = TestUsers.seed(email = email, password = "old-password", role = UserRole.USER)
            val client = authedClient(email, "old-password")
            val capture = LogCapture("ch.nokillswit.audit")
            try {
                // Another identity's failed login takes the only slot.
                assertEquals(HttpStatusCode.Unauthorized, jsonClient().login(uniqueEmail("capacity-filler"), "wrong-pw").status)

                fun body(current: String) = PasswordUpdateRequest(password = "brand-new-password", currentPassword = current)
                // The caller is a known account: not the capacity 429, a plain 403 each time, and the failures COUNT past the cap.
                assertEquals(HttpStatusCode.Forbidden, client.putJson("/api/v1/users/$userId/password", body("not-it")).status)
                assertEquals(HttpStatusCode.Forbidden, client.putJson("/api/v1/users/$userId/password", body("not-it-either")).status)
                assertNotNull(capture.awaitEvent { it.message == "login.lockout" && it.hasKeyValue("userId", userId.toLong()) })
                // Locked: even the right current password is refused with the lockout's 429, before any bcrypt.
                assertEquals(HttpStatusCode.TooManyRequests, client.putJson("/api/v1/users/$userId/password", body("old-password")).status)
                assertTrue(
                    capture.events.none { it.message == "login.capacity_rejected" && it.hasKeyValue("userId", userId.toLong()) },
                    "a known account is never audited as a capacity rejection",
                )
            } finally {
                capture.detach()
            }
        }

    @Test
    fun `a full lockout store does not stop the right current password - an untracked account still changes it`() = testApplication {
        configureApp("security.lockout.maxTracked" to "1")
        startApplication()
        val email = uniqueEmail("capacity-change-ok")
        val userId = TestUsers.seed(email = email, password = "old-password", role = UserRole.USER)
        val client = authedClient(email, "old-password")
        // Another identity's failed login takes the only slot; this account is untracked.
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().login(uniqueEmail("capacity-filler-ok"), "wrong-pw").status)
        val ok = client.putJson(
            "/api/v1/users/$userId/password",
            PasswordUpdateRequest(password = "brand-new-password", currentPassword = "old-password"),
        )
        assertEquals(HttpStatusCode.NoContent, ok.status)
    }
}
