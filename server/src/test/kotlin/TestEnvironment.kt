package ch.nokillswit

import ch.nokillswit.auth.LoginRequest
import ch.nokillswit.auth.LoginResponse
import ch.nokillswit.infra.crypto.hashPassword
import ch.nokillswit.infra.db.SEED_ADMIN_EMAIL
import ch.nokillswit.infra.db.SEED_PASSWORD_HASH
import ch.nokillswit.infra.db.connectPooledDatabase
import ch.nokillswit.users.User
import ch.nokillswit.users.UserRole
import ch.nokillswit.users.UserService
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.config.mergeWith
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.TestApplicationBuilder
import io.r2dbc.spi.ConnectionFactoryOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import java.time.Duration

/**
 * Points the app at the shared Testcontainers Postgres (with CSRF off) WITHOUT starting it —
 * callers that assert startup behavior (fail-closed checks) add their own overrides and call
 * `startApplication()` themselves. Later duplicate keys win in [MapApplicationConfig], so
 * [overrides] may replace the defaults listed first.
 *
 * [overrides] replace the defaults; the telemetry sampler is off by default (see the key below).
 */
fun TestApplicationBuilder.configureApp(vararg overrides: Pair<String, String>) {
    environment {
        config = ApplicationConfig("application.yaml").mergeWith(
            MapApplicationConfig(
                "postgres.jdbcUrl" to PostgresTestSupport.jdbcUrl,
                "postgres.r2dbcUrl" to PostgresTestSupport.r2dbcUrl,
                "postgres.user" to PostgresTestSupport.user,
                "postgres.password" to PostgresTestSupport.password,
                "security.csrf.enabled" to "false",
                // The self-service reset builds its emailed link from this origin. https, not http: the origin is validated at
                // boot in every mode, and the production-mode boot tests reuse this config (http is a development-only scheme).
                "mail.appUrl" to "https://localhost:8090",
                // The telemetry sampler is off for the suite (it would write a process row and samples into the shared database from
                // every booted app); its tests build the sampler directly or opt in with "telemetry.enabled" to "true".
                "telemetry.enabled" to "false",
                // The ADR expiry sweep is off for the suite (a loop would expire ADRs other tests forced into the past and race their
                // assertions); its tests build the sweep directly with a pinned clock or opt in with "adrs.expiry.enabled" to "true".
                "adrs.expiry.enabled" to "false",
                // The notification sweep (digest, expiry reminders, retention) is off for the suite for the same reason; its tests
                // build the digest, the reminders and the sweep directly or opt in with "notifications.sweepEnabled" to "true".
                "notifications.sweepEnabled" to "false",
                *overrides,
            )
        )
    }
}

suspend fun ApplicationTestBuilder.usePostgresTestcontainer() {
    configureApp()
    startApplication()
}

/**
 * Shared config for every test HTTP client: JSON (+ problem+json) negotiation and the
 * [OpenApiConformance] plugin, which validates each /api/ interaction against the OpenAPI spec.
 */
fun HttpClientConfig<*>.testClientDefaults() {
    install(ContentNegotiation) { json(); json(contentType = ContentType.parse("application/problem+json")) }
    install(OpenApiConformance)
}

fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient { testClientDefaults() }

/** A unique throwaway email so tests never collide on the partial-unique active-email index. */
fun uniqueEmail(prefix: String) = "$prefix-${java.util.UUID.randomUUID()}@test"

/** A private 64-hex data-encryption key — every production-mode boot needs one (the dev default is burned). */
fun strongEncryptionKey(): String =
    java.util.UUID.randomUUID().toString().replace("-", "") + java.util.UUID.randomUUID().toString().replace("-", "")

/** Test-only private JWT key in the production-required 64-hex format. */
fun strongJwtSecret(): String = strongEncryptionKey()

/** POSTs [body] as JSON — the contentType+setBody ceremony, owned once. */
suspend inline fun <reified T> HttpClient.postJson(path: String, body: T): HttpResponse =
    post(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

/** PUTs [body] as JSON. */
suspend inline fun <reified T> HttpClient.putJson(path: String, body: T): HttpResponse =
    put(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

/** The raw login POST — for tests asserting login behavior itself ([authedClient] wraps it). */
suspend fun HttpClient.login(email: String, password: String): HttpResponse =
    postJson("/api/v1/login", LoginRequest(email, password))

/** Logs in as [email] and returns a client that sends the bearer token on every request. */
suspend fun ApplicationTestBuilder.authedClient(email: String, password: String): HttpClient {
    val token = jsonClient().login(email, password).body<LoginResponse>().token
    return createClient {
        testClientDefaults()
        install(DefaultRequest) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
    }
}

/** Seeds a unique throwaway user and logs them in — the standard per-test caller fixture. */
suspend fun ApplicationTestBuilder.seededClient(prefix: String, role: UserRole = UserRole.USER): HttpClient {
    val email = uniqueEmail(prefix)
    TestUsers.seed(email = email, password = "pw", role = role)
    return authedClient(email, "pw")
}

/**
 * Captures a logger's events (the audit trail on `ch.nokillswit.audit`, or another logger — e.g.
 * the ROOT logger during application startup). Use in a try/finally with [detach]; [awaitEvent]
 * polls for asynchronously produced events.
 */
class LogCapture(loggerName: String) {
    private val logger = org.slf4j.LoggerFactory.getLogger(loggerName) as ch.qos.logback.classic.Logger
    // A copy-on-write list rather than Logback's ListAppender (a plain ArrayList): a capture on a
    // busy logger — the ROOT logger during application start, say — is appended to from other
    // threads while a test iterates `events`, which raised ConcurrentModificationException
    // (ported from Toadie).
    private val captured = java.util.concurrent.CopyOnWriteArrayList<ch.qos.logback.classic.spi.ILoggingEvent>()
    private val appender = object : ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent>() {
        override fun append(eventObject: ch.qos.logback.classic.spi.ILoggingEvent) {
            captured.add(eventObject)
        }
    }

    init {
        appender.start()
        logger.addAppender(appender)
    }

    val events: List<ch.qos.logback.classic.spi.ILoggingEvent> get() = captured

    fun detach() = logger.detachAppender(appender)

    suspend fun awaitEvent(
        predicate: (ch.qos.logback.classic.spi.ILoggingEvent) -> Boolean,
    ): ch.qos.logback.classic.spi.ILoggingEvent? {
        repeat(100) {
            events.firstOrNull(predicate)?.let { return it }
            kotlinx.coroutines.delay(50)
        }
        return null
    }
}

/**
 * audit() fields travel as SLF4J key/values, not in the message text — and TYPED: ids arrive
 * as Longs, flags as Booleans, so compare with the same type the emitter used.
 */
fun ch.qos.logback.classic.spi.ILoggingEvent.hasKeyValue(key: String, value: Any?) =
    keyValuePairs?.any { it.key == key && it.value == value } == true

/** The audit-trail capture scaffold: attaches to the audit logger and always detaches. */
suspend fun <T> withAuditCapture(block: suspend (LogCapture) -> T): T {
    val capture = LogCapture("ch.nokillswit.audit")
    return try {
        block(capture)
    } finally {
        capture.detach()
    }
}

/** Bootstrap/prod-mode scaffold: whatever [block] does to the seed admin is restored after. */
suspend fun withSeedRestored(block: suspend () -> Unit) {
    try {
        block()
    } finally {
        TestSeedState.restoreSeedAccounts()
    }
}

/** Asserts the app refuses to start and that the failure cause chain mentions [messagePart]. */
suspend fun assertStartupFails(messagePart: String, start: suspend () -> Unit) {
    val failure = runCatching { start() }.exceptionOrNull()
    kotlin.test.assertNotNull(failure, "startup must fail closed")
    val messages = generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
    kotlin.test.assertTrue(messagePart in messages, "unexpected startup failure: $messages")
}

/** A fresh blocklist service (own cache) over the shared container DB, with an injected clock. */
internal fun newTokenBlocklistService(clock: () -> Long): ch.nokillswit.auth.TokenBlocklistService =
    ch.nokillswit.auth.TokenBlocklistService(sharedTestDatabase, clock)

internal fun newPasswordResetService(ttlMillis: Long = 900_000, clock: () -> Long = System::currentTimeMillis) =
    ch.nokillswit.auth.PasswordResetService(sharedTestDatabase, ttlMillis, clock)

/** The emailed token of a freshly minted grant, or null when [ch.nokillswit.auth.PasswordResetService.issue] refused (stale or capped). */
internal suspend fun ch.nokillswit.auth.PasswordResetService.issueToken(userId: UInt, credentialRevision: Long, email: String): String? =
    (issue(userId, credentialRevision, email) as? ch.nokillswit.auth.PasswordResetService.IssueOutcome.Issued)?.token

/** The digests of [userId]'s stored reset grants (the table never holds a raw token). */
internal suspend fun resetTokenHashes(userId: UInt): List<String> = suspendTransaction(sharedTestDatabase) {
    with(ch.nokillswit.auth.PasswordResetService.Grants) {
        selectAll().where { this@with.userId eq userId }.toList().map { it[tokenHash] }
    }
}

/** The shared test database — for fixtures that construct a service by hand (the encryption tests). */
fun sharedDatabaseForTests(): R2dbcDatabase = sharedTestDatabase

/**
 * POOLED like production (`connectPooledDatabase`): an unpooled
 * connect opened a fresh PostgreSQL backend per `suspendTransaction`. Small — the suite's test-side
 * calls are sequential, and a test holding a transaction open while another test-side call runs
 * needs a second connection; 8 leaves headroom and the acquire timeout turns a real deadlock into
 * a failure in seconds. Never disposed: the JVM exit (and the container's shutdown hook) ends it.
 */
private val sharedTestDatabase: R2dbcDatabase by lazy {
    val options = ConnectionFactoryOptions.parse(PostgresTestSupport.r2dbcUrl)
        .mutate()
        .option(ConnectionFactoryOptions.USER, PostgresTestSupport.user)
        .option(ConnectionFactoryOptions.PASSWORD, PostgresTestSupport.password)
        .build()
    connectPooledDatabase(
        options,
        maxSize = 10, // headroom for tests that claim several connections at once
        initialSize = 1,
        maxAcquireTime = Duration.ofSeconds(30),
        maxIdleTime = Duration.ofMinutes(10),
    ).first
}

object TestUsers {
    val service: UserService by lazy { UserService(sharedTestDatabase) }

    suspend fun seed(
        email: String,
        password: String,
        name: String = "Test",
        role: UserRole = UserRole.ADMIN,
        language: String = "en",
    ): UInt = service.create(
        User(
            name = name,
            email = email,
            passwordHash = hashPassword(password, cost = 4),
            role = role,
            language = language,
        )
    )

    /** Direct soft-delete for fixtures needing to bypass the endpoint's guards. */
    suspend fun softDelete(id: UInt) {
        suspendTransaction(sharedTestDatabase) {
            UserService.Users.update({ UserService.Users.id eq id }) {
                it[UserService.Users.markedAsDeleted] = true
            }
        }
    }

    /** Sets `password_changed_at` directly — the refresh test moves it past a token's `iat` instead of sleeping. */
    suspend fun stampPasswordChangedAt(id: UInt, epochMillis: Long) {
        suspendTransaction(sharedTestDatabase) {
            UserService.Users.update({ UserService.Users.id eq id }) {
                it[UserService.Users.passwordChangedAt] = epochMillis
            }
        }
    }

    /**
     * Runs [block] while the users in [soloAdminIds] are the ONLY active admins — every other
     * active ADMIN row (the seed admin and other tests' fixtures included) is temporarily
     * soft-deleted and restored in a finally. Backs the last-admin-protection tests, which
     * need `countActiveAdmins()` to be exact in the shared container.
     */
    suspend fun withSoloAdmins(soloAdminIds: Set<UInt>, block: suspend () -> Unit) {
        val parked: List<UInt> = suspendTransaction(sharedTestDatabase) {
            val others = UserService.Users.selectAll()
                .where {
                    (UserService.Users.role eq UserRole.ADMIN.name) and
                        (UserService.Users.markedAsDeleted eq false)
                }
                .map { it[UserService.Users.id].value }
                .toList()
                .filter { it !in soloAdminIds }
            UserService.Users.update({ UserService.Users.id inList others }) {
                it[UserService.Users.markedAsDeleted] = true
            }
            others
        }
        try {
            block()
        } finally {
            suspendTransaction(sharedTestDatabase) {
                UserService.Users.update({ UserService.Users.id inList parked }) {
                    it[UserService.Users.markedAsDeleted] = false
                }
            }
        }
    }
}

object TestSeedState {
    suspend fun restoreSeedAccounts() {
        suspendTransaction(sharedTestDatabase) {
            UserService.Users.update({ UserService.Users.email eq SEED_ADMIN_EMAIL }) {
                it[UserService.Users.passwordHash] = SEED_PASSWORD_HASH
                it[UserService.Users.markedAsDeleted] = false
                it[UserService.Users.passwordChangedAt] = 0
                it[UserService.Users.credentialRevision] = 0
                // A test flipping the seed admin's language must not leak into other tests.
                it[UserService.Users.language] = "en"
            }
        }
    }
}

/** Direct team fixtures — raw reads past the routes (the soft-delete assertions) and quick seeding. */
object TestTeams {
    val service: ch.nokillswit.teams.TeamService by lazy { ch.nokillswit.teams.TeamService(sharedTestDatabase) }

    suspend fun seed(name: String): UInt = service.create(ch.nokillswit.teams.TeamCreateRequest(name = name))

    data class RawTeam(val id: UInt, val name: String, val markedAsDeleted: Boolean)

    suspend fun rawRows(): List<RawTeam> = suspendTransaction(sharedTestDatabase) {
        val t = ch.nokillswit.teams.TeamService.Teams
        t.selectAll().map { RawTeam(it[t.id].value, it[t.name], it[t.markedAsDeleted]) }.toList()
    }
}

/**
 * Polls `pg_stat_activity` until some OTHER session is waiting on a lock inside a statement whose text contains [queryFragment]
 * (case-insensitive): the "the request under test reached its lock wait" barrier of a deterministic concurrency test. The test
 * holds the conflicting lock on a raw JDBC connection, starts the request asynchronously, calls this, then releases the lock.
 */
suspend fun awaitLockWait(queryFragment: String) = withTimeout(30_000) {
    PostgresTestSupport.connect().use { probe ->
        while (true) {
            val waiting = probe.prepareStatement(
                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND pid <> pg_backend_pid() AND query ILIKE ?",
            ).use { st ->
                st.setString(1, "%$queryFragment%")
                st.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
            if (waiting > 0) break
            delay(50)
        }
    }
}
