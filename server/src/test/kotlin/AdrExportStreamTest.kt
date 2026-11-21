package ch.nokillswit

import ch.nokillswit.adrs.AdrEventService
import ch.nokillswit.adrs.AdrExportDocument
import ch.nokillswit.adrs.AdrExportService
import ch.nokillswit.adrs.AdrExportServiceKey
import ch.nokillswit.adrs.AdrParticipantService
import ch.nokillswit.adrs.AdrResponse
import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.adrs.AdrSuccessorService
import ch.nokillswit.adrs.AdrTagService
import ch.nokillswit.auth.LoginResponse
import ch.nokillswit.infra.ProblemTypes
import ch.nokillswit.infra.db.R2dbcDatabaseKey
import ch.nokillswit.notifications.NotificationService
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The bulk export's limits and its failure mode (`AdrExportRoutes.kt`): the configurable cap over HTTP, the process-wide concurrency cap
 * (`export-busy`), an archive that FAILS MID-STREAM (it must abort, never finish a short archive), and `HEAD`. The failures are staged by
 * swapping the route's export service (resolved per call) for one that gates or fails a batch.
 */
class AdrExportStreamTest {
    private fun marker() = "Str" + UUID.randomUUID().toString().replace("-", "").take(10)

    private fun encoded(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

    /** An export service over the app's own database whose batches [onBatch] may hold up or fail. */
    private fun ApplicationTestBuilder.staged(onBatch: suspend (call: Int) -> Unit): AdrExportService {
        val database = application.attributes[R2dbcDatabaseKey]
        val events = AdrEventService(database, NotificationService(database))
        val calls = AtomicInteger()
        return object : AdrExportService(
            database, AdrParticipantService(events), AdrTagService(events), AdrSuccessorService(events), events,
        ) {
            override suspend fun documents(ids: Collection<UInt>): List<AdrExportDocument> {
                onBatch(calls.incrementAndGet())
                return super.documents(ids)
            }
        }
    }

    private suspend fun AdrActor.raise(name: String): AdrResponse = client.createAdr(name = name)

    private fun hasCentralDirectory(bytes: ByteArray): Boolean {
        val signature = byteArrayOf(0x50, 0x4b, 0x05, 0x06)
        return (0..bytes.size - signature.size).any { at -> signature.indices.all { bytes[at + it] == signature[it] } }
    }

    @Test
    fun `the cap is configurable - over it is 400 with the problem body and no audit, exactly at it is 200`() = testApplication {
        configureApp("adrs.export.maxAdrs" to "2")
        startApplication()
        val cast = adrCast()
        val tag = marker()
        val kept = List(3) { cast.driver.raise("$tag n$it") }
        withAuditCapture { capture ->
            val over = cast.stranger.client.get("/api/v1/adrs/export?q=${encoded(tag)}")
            assertEquals(HttpStatusCode.BadRequest, over.status)
            val problem = over.body<ProblemDetail>()
            assertEquals(400, problem.status)
            assertTrue("3 ADRs" in problem.detail.orEmpty() && "at most 2" in problem.detail.orEmpty(), problem.detail)
            delay(300)
            assertEquals(0, capture.events.count { it.message == "adr.exported_bulk" }, "a refusal exports nothing")
            cast.driver.client.moveAdrNow(kept.last().id, AdrStatus.CANCELLED)
            val exact = cast.stranger.client.get("/api/v1/adrs/export?q=${encoded(tag)}&status=PROBLEM_RAISED")
            assertEquals(HttpStatusCode.OK, exact.status, "exactly the cap is allowed")
            assertNotNull(capture.awaitEvent { it.message == "adr.exported_bulk" && it.hasKeyValue("count", 2) })
        }
    }

    @Test
    fun `an archive that fails after its first batch is aborted, never finished as a short archive`() = testApplication {
        configureApp("adrs.export.maxConcurrent" to "1")
        startApplication()
        val cast = adrCast()
        val tag = marker()
        repeat(26) { cast.driver.raise("$tag n$it") }
        application.attributes.put(AdrExportServiceKey, staged { call -> if (call == 2) error("the second batch fails") })
        val url = "/api/v1/adrs/export?q=${encoded(tag)}"
        val outcome = runCatching { cast.stranger.client.get(url).body<ByteArray>() }
        outcome.onSuccess { bytes ->
            assertFalse(hasCentralDirectory(bytes), "a delivered partial body must carry no central directory (${bytes.size} bytes)")
        }
        // The slot came back: with the cap at ONE, a healthy export now runs.
        application.attributes.put(AdrExportServiceKey, staged { })
        val healthy = cast.stranger.client.get(url)
        assertEquals(HttpStatusCode.OK, healthy.status)
        assertTrue(hasCentralDirectory(healthy.body<ByteArray>()), "a complete archive ends with its central directory")
    }

    @Test
    fun `a bulk export past the process cap is refused at once with the typed 429 export-busy, and the slot returns when one ends`() =
        testApplication {
            configureApp("adrs.export.maxConcurrent" to "1")
            startApplication()
            val cast = adrCast()
            val tag = marker()
            cast.driver.raise("$tag one")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            application.attributes.put(AdrExportServiceKey, staged { entered.complete(Unit); release.await() })
            val url = "/api/v1/adrs/export?q=${encoded(tag)}"
            withAuditCapture { capture ->
                val refused: HttpResponse = coroutineScope {
                    val running = async { cast.stranger.client.get(url) }
                    withTimeout(20_000) { entered.await() }
                    val refused = cast.approver.client.get(url)
                    release.complete(Unit)
                    assertEquals(HttpStatusCode.OK, running.await().status, "the export holding the slot completes")
                    refused
                }
                assertEquals(HttpStatusCode.TooManyRequests, refused.status)
                assertEquals("5", refused.headers[HttpHeaders.RetryAfter])
                assertEquals(ProblemTypes.EXPORT_BUSY, refused.body<ProblemDetail>().type)
                assertNotNull(capture.awaitEvent { it.message == "export.busy" && it.hasKeyValue("byUserId", cast.approver.id.toLong()) })
                assertEquals(HttpStatusCode.OK, cast.approver.client.get(url).status, "the slot is free again")
            }
        }

    @Test
    fun `HEAD on both exports is 405 and neither audits nor reads anything`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("exp-head")
        TestUsers.seed(email = email, password = "pw", name = "Head", role = UserRole.USER)
        val token = jsonClient().login(email, "pw").body<LoginResponse>().token
        val adr = adrActor("exp-head-owner").client.createAdr()
        withAuditCapture { capture ->
            for (path in listOf("/api/v1/adrs/${adr.id}/export", "/api/v1/adrs/export?q=${encoded(adr.name)}")) {
                val response = client.head(path) { header(HttpHeaders.Authorization, "Bearer $token") }
                assertEquals(HttpStatusCode.MethodNotAllowed, response.status, path)
                assertEquals("GET", response.headers[HttpHeaders.Allow])
            }
            val anonymous = client.head("/api/v1/adrs/${adr.id}/export")
            assertEquals(HttpStatusCode.Unauthorized, anonymous.status, "HEAD is still behind authentication")
            delay(300)
            assertEquals(0, capture.events.count { it.message?.startsWith("adr.exported") == true })
        }
    }
}
