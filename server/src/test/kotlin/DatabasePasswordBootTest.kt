package ch.nokillswit

import ch.qos.logback.classic.Level
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Postgres-password fail-closed check (infra/db/DatabasePassword.kt, run at the head of the Flyway module): the shipped
 * default and the k8s placeholder are tolerated in development and refuse production; a private password passes.
 */
class DatabasePasswordBootTest {

    private fun ApplicationTestBuilder.productionApp(vararg overrides: Pair<String, String>) {
        configureApp(
            "jwt.secret" to strongJwtSecret(), "security.encryption.key" to strongEncryptionKey(),
            "mail.transport" to "disabled", *overrides,
        )
        serverConfig { developmentMode = false }
    }

    private fun Throwable?.causeMessages(): String =
        generateSequence(this) { it.cause }.mapNotNull { it.message }.joinToString(" | ")

    @Test
    fun `production mode refuses the shipped default password`() = testApplication {
        productionApp("postgres.password" to "hammurabi")
        assertStartupFails("POSTGRES_PASSWORD") { startApplication() }
    }

    @Test
    fun `production mode refuses the template placeholder in any spelling, and a blank one`() {
        for (burned in listOf("CHANGE-ME", "changeme", " Hammurabi ", "")) {
            testApplication {
                productionApp("postgres.password" to burned)
                assertStartupFails("POSTGRES_PASSWORD") { startApplication() }
            }
        }
    }

    @Test
    fun `the refusal and the logs never print the password`() = testApplication {
        val burned = " cHaNgE-mE " // a distinctive spelling: any echo of it, in any case, is a leak
        productionApp("postgres.password" to burned)
        val capture = LogCapture(org.slf4j.Logger.ROOT_LOGGER_NAME)
        try {
            val messages = runCatching { startApplication() }.exceptionOrNull().causeMessages()
            assertTrue("POSTGRES_PASSWORD" in messages, messages)
            assertFalse("change-me" in messages.lowercase(), messages)
            assertFalse(capture.events.any { "change-me" in it.formattedMessage.lowercase() }, "a log line echoed the password")
        } finally {
            capture.detach()
        }
    }

    @Test
    fun `development mode tolerates the shipped default with a warning`() = testApplication {
        // The container's real password is private, so this boot cannot connect with "hammurabi": the point is that the password
        // check itself only warns, which the later connection failure (not naming POSTGRES_PASSWORD) shows.
        configureApp("postgres.password" to "hammurabi", "postgres.connectRetries" to "0")
        val capture = LogCapture(org.slf4j.Logger.ROOT_LOGGER_NAME)
        try {
            val messages = runCatching { startApplication() }.exceptionOrNull().causeMessages()
            assertFalse("POSTGRES_PASSWORD" in messages, messages)
            val warning = capture.events.firstOrNull { "POSTGRES_PASSWORD" in it.formattedMessage }
            assertNotNull(warning, "the development warning must be logged")
            assertEquals(Level.WARN, warning.level)
            assertTrue("permitted in development only" in warning.formattedMessage, warning.formattedMessage)
        } finally {
            capture.detach()
        }
    }

    @Test
    fun `production mode accepts a private password`() = testApplication {
        productionApp("bootstrap.adminInitialPassword" to "rotated-${java.util.UUID.randomUUID()}")
        withSeedRestored { startApplication() }
    }
}
