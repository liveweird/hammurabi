package ch.nokillswit

import ch.nokillswit.auth.LoginRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proxy trust (plugins/Http.kt — `http.behindProxy` + `http.proxyHops`): the per-IP rate-limit
 * buckets key on the client address the TRUSTED proxy reported, read from the END of
 * X-Forwarded-For — never the first value, which is whatever the client sent when a proxy
 * appends instead of replacing (Lettuce's v3.6.2 hardening, ported via Toadie). Every attempt
 * uses a distinct freshly seeded account (wrong password) so the per-account lockout (5 per
 * email) never trips, and the login bucket is pinned to 10 like RateLimitResponseTest; every
 * testApplication boots a fresh app, so buckets never leak. The accounts are SEEDED (bcrypt cost
 * 4), never unknown emails: an unknown email pays the login route's constant-time cost-12 bcrypt
 * verify (`TIMING_EQUALIZER_HASH`, ~225 ms locally, ~2x on a CI runner), i.e. ~2.5 s per 11-attempt
 * test that has nothing to do with proxy trust (`.claude/docs/build-times.md`).
 */
class ForwardedHeadersTest {

    /** The accounts [login] seeds — soft-deleted after each test (shared suite state is never left behind). */
    private val seeded = mutableListOf<UInt>()

    @AfterTest
    fun removeSeededAccounts() = runBlocking {
        seeded.forEach { TestUsers.softDelete(it) }
        seeded.clear()
    }

    private suspend fun HttpClient.login(forwardedFor: String?): HttpStatusCode {
        val email = uniqueEmail("xff")
        seeded += TestUsers.seed(email, "the-right-password", role = UserRole.USER)
        return post("/api/v1/login") {
            contentType(ContentType.Application.Json)
            if (forwardedFor != null) header(HttpHeaders.XForwardedFor, forwardedFor)
            setBody(LoginRequest(email, "wrong"))
        }.status
    }

    @Test
    fun `behind a proxy the login bucket keys on the LAST X-Forwarded-For value`() = testApplication {
        configureApp("http.behindProxy" to "true", "security.rateLimit.loginPerMinute" to "10")
        startApplication()
        val client = jsonClient()
        // The first value rotates (a spoofing client); the last is what the proxy appended.
        repeat(10) { i ->
            assertEquals(HttpStatusCode.Unauthorized, client.login("10.9.$i.1, 203.0.113.7"))
        }
        assertEquals(HttpStatusCode.TooManyRequests, client.login("10.9.99.1, 203.0.113.7"))
        // A different proxy-reported address is a different client.
        assertEquals(HttpStatusCode.Unauthorized, client.login("10.9.99.1, 203.0.113.8"))
    }

    @Test
    fun `junk X-Forwarded-For text mints no bucket - it counts against the connection that sent it`() = testApplication {
        configureApp("http.behindProxy" to "true", "security.rateLimit.loginPerMinute" to "10")
        startApplication()
        val client = jsonClient()
        // Hostnames, junk with colons, an overlong value, an out-of-range quad: none is an address literal, so none is a key of its own.
        val junk = listOf("a", "client-1.attacker.example", "x:y", "a:b:c", "999.1.1.1", "1.2.3", "0x7f.1", "evil:80", "zz") +
            "q".repeat(300)
        junk.forEach { assertEquals(HttpStatusCode.Unauthorized, client.login(it), it.take(20)) }
        assertEquals(HttpStatusCode.TooManyRequests, client.login("fresh-junk-after-the-tenth"), "the eleventh value: the same client")
        assertEquals(HttpStatusCode.TooManyRequests, client.login(null), "no header at all is the same connection")
        // A real address the proxy reports is still its own client.
        assertEquals(HttpStatusCode.Unauthorized, client.login("203.0.113.44"))
    }

    @Test
    fun `a forwarded IPv4 address with a port is that address, not junk (Azure Application Gateway, IIS ARR)`() = testApplication {
        configureApp("http.behindProxy" to "true", "security.rateLimit.loginPerMinute" to "10")
        startApplication()
        val client = jsonClient()
        // Each client is its own bucket, whatever source port the proxy saw; the source port rotates inside one client.
        repeat(10) { i -> assertEquals(HttpStatusCode.Unauthorized, client.login("203.0.113.7:${51234 + i}")) }
        assertEquals(HttpStatusCode.TooManyRequests, client.login("203.0.113.7:60000"))
        assertEquals(HttpStatusCode.TooManyRequests, client.login("203.0.113.7"), "the same client without its port")
        assertEquals(HttpStatusCode.Unauthorized, client.login("203.0.113.8:51234"), "another address is another client")
    }

    @Test
    fun `addresses of one IPv6 64 share a bucket, and distinct IPv4 addresses keep their own`() = testApplication {
        configureApp("http.behindProxy" to "true", "security.rateLimit.loginPerMinute" to "10")
        startApplication()
        val client = jsonClient()
        repeat(10) { i -> assertEquals(HttpStatusCode.Unauthorized, client.login("2001:db8:1:2::${i + 1}")) }
        assertEquals(HttpStatusCode.TooManyRequests, client.login("2001:db8:1:2:ffff:ffff:ffff:ffff"), "same /64, another spelling")
        assertEquals(HttpStatusCode.TooManyRequests, client.login("[2001:DB8:1:2::99]"), "brackets and case")
        assertEquals(HttpStatusCode.Unauthorized, client.login("2001:db8:1:3::1"), "the next /64 is another client")
        // The mapped spelling of an IPv4 address is that address.
        repeat(10) { assertEquals(HttpStatusCode.Unauthorized, client.login("::ffff:198.51.100.7")) }
        assertEquals(HttpStatusCode.TooManyRequests, client.login("198.51.100.7"))
        assertEquals(HttpStatusCode.Unauthorized, client.login("198.51.100.8"), "distinct IPv4 addresses are distinct clients")
        assertEquals(HttpStatusCode.Unauthorized, client.login("198.51.100.9"))
    }

    @Test
    fun `proxyHops 2 trusts the value before the last one`() = testApplication {
        configureApp(
            "http.behindProxy" to "true",
            "http.proxyHops" to "2",
            "security.rateLimit.loginPerMinute" to "10",
        )
        startApplication()
        val client = jsonClient()
        // A client-supplied spoof, then what the first trusted proxy appended, then the second's.
        repeat(10) { i ->
            assertEquals(HttpStatusCode.Unauthorized, client.login("10.9.$i.1, 203.0.113.7, 198.51.100.1"))
        }
        assertEquals(HttpStatusCode.TooManyRequests, client.login("10.9.99.1, 203.0.113.7, 198.51.100.1"))
        assertEquals(HttpStatusCode.Unauthorized, client.login("10.9.99.1, 203.0.113.9, 198.51.100.1"))
    }

    @Test
    fun `without behindProxy X-Forwarded-For is ignored`() = testApplication {
        configureApp("security.rateLimit.loginPerMinute" to "10")
        startApplication()
        val client = jsonClient()
        // Every attempt claims a different address; all of them share the direct client's bucket.
        repeat(10) { i ->
            assertEquals(HttpStatusCode.Unauthorized, client.login("10.9.$i.1"))
        }
        assertEquals(HttpStatusCode.TooManyRequests, client.login("10.9.99.1"))
    }

    @Test
    fun `a non-numeric proxyHops fails startup naming the key`() = testApplication {
        configureApp("http.behindProxy" to "true", "http.proxyHops" to "abc")
        assertStartupFails("Config \"http.proxyHops\" must be an integer") { startApplication() }
    }

    @Test
    fun `proxyHops below 1 fails startup`() = testApplication {
        configureApp("http.behindProxy" to "true", "http.proxyHops" to "0")
        assertStartupFails("http.proxyHops") { startApplication() }
    }
}
