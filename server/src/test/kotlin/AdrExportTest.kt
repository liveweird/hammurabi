package ch.nokillswit

import ch.nokillswit.adrs.AdrCreateRequest
import ch.nokillswit.adrs.AdrFilterDocument
import ch.nokillswit.adrs.AdrListServiceKey
import ch.nokillswit.adrs.AdrResponse
import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.adrs.normalized
import ch.nokillswit.adrs.toListFilter
import ch.nokillswit.plugins.ProblemDetail
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Markdown export (0.8.0, `adr-model.md` "Export"): the one-ADR download and the bulk ZIP over the list's filters. ADRs are shared
 * suite state, so every test mints a unique name marker and filters on it (`q`).
 */
class AdrExportTest {
    private fun marker() = "Exp" + UUID.randomUUID().toString().replace("-", "").take(10)

    private fun encoded(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

    private fun today() = LocalDate.now(ZoneOffset.UTC).toString()

    private suspend fun HttpClient.raise(name: String, problem: String = "How do we decide?"): AdrResponse {
        val response = postJson("/api/v1/adrs", AdrCreateRequest(name = name, problem = problem, decision = "We decided $name"))
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    private suspend fun HttpResponse.zip(): Map<String, String> = ZipInputStream(ByteArrayInputStream(body<ByteArray>())).use { zip ->
        val entries = linkedMapOf<String, String>()
        generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes().decodeToString() }
        entries
    }

    @Test
    fun `any signed-in user downloads an ADR as Markdown with an ASCII file name`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val name = "Żółw ${marker()}"
        val adr = cast.driver.client.raise(name, problem = "Pierwszy wiersz\r\n\r\nDrugi")
        val response = cast.stranger.client.get("/api/v1/adrs/${adr.id}/export")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("text/markdown; charset=utf-8", response.headers[HttpHeaders.ContentType]?.lowercase())
        val disposition = response.headers[HttpHeaders.ContentDisposition].orEmpty()
        val fileName = Regex("""^attachment; filename="(ADR-\d{4}-zolw-[a-z0-9-]+\.md)"$""").matchEntire(disposition)?.groupValues?.get(1)
        assertNotNull(fileName, disposition)
        assertTrue(fileName.startsWith("ADR-${adr.number.toString().padStart(4, '0')}-zolw-exp"), fileName)
        val text = response.bodyAsText()
        assertTrue(text.startsWith("# ${adr.number}. $name\n\nDate: ${today()}\n\n## Status\n\nProblem raised\n"), text.take(200))
        assertTrue("### Problem\n\nPierwszy wiersz\n\nDrugi\n" in text)
        assertTrue("## Decision\n\nWe decided $name\n" in text)
        assertTrue("## Consequences\n\n_Not recorded yet._\n" in text)
        assertTrue("- **Driver:** Drv\n" in text, text)
        assertFalse("Alternatives considered" in text)
        assertFalse('\r' in text)
        assertTrue(text.endsWith("<!-- Exported from Hammurabi on ${today()} -->\n"))
    }

    @Test
    fun `a name with no Latin letter or digit exports as the bare key`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val name = "決定" + String(CharArray(8) { (0x4E00 + kotlin.random.Random.nextInt(0x3000)).toChar() })
        val adr = cast.driver.client.raise(name)
        val response = cast.stranger.client.get("/api/v1/adrs/${adr.id}/export")
        assertEquals("""attachment; filename="${adr.key}.md"""", response.headers[HttpHeaders.ContentDisposition])
        assertTrue(response.bodyAsText().startsWith("# ${adr.number}. $name\n"))
    }

    @Test
    fun `the Markdown carries the tags, the roles, the expiry and both ends of a successor link`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val tag = uniqueTag()
        val category = categoryName("Exp")
        cast.admin.client.createCategory(name = category, tags = listOf(tag))
        val predecessor = cast.raise()
        cast.activate(predecessor)
        val successor = cast.raise()
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setTags(successor.id, listOf(tag)).status)
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(successor.id, predecessor.id).status)
        val pending = cast.stranger.client.get("/api/v1/adrs/${successor.id}/export").bodyAsText()
        assertTrue("- $category: $tag\n" in pending, pending)
        assertTrue("- **Approver:** Apr\n- **Contributors:** Con\n- **Informed:** Inf\n" in pending)
        assertTrue("Supersedes [${predecessor.key}: ${predecessor.name}](${predecessor.key}-" in pending)
        val planned = cast.stranger.client.get("/api/v1/adrs/${predecessor.id}/export").bodyAsText()
        assertTrue("\nSuccessor proposed: [${successor.key}: ${successor.name}](${successor.key}-" in planned, planned)
        assertFalse("superseded by" in planned.lowercase(), "an unaccepted successor is only a proposal")
        cast.approver.client.acceptSuccessorOk(predecessor.id, successor.id)
        val accepted = cast.stranger.client.get("/api/v1/adrs/${predecessor.id}/export").bodyAsText()
        assertTrue("\nTo be superseded by [${successor.key}: ${successor.name}](${successor.key}-" in accepted, accepted)
        cast.activate(successor)
        val replaced = cast.stranger.client.get("/api/v1/adrs/${predecessor.id}/export").bodyAsText()
        assertTrue("## Status\n\nSuperseded\n\nSuperseded by [${successor.key}: ${successor.name}]" in replaced, replaced)
    }

    @Test
    fun `an ended predecessor names no pending successor in a file or the ZIP, and the successor still names it`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val tag = marker()
        val predecessor = cast.driver.client.raise("$tag old")
        val successor = cast.driver.client.raise("$tag new")
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(successor.id, predecessor.id).status)
        val files = cast.stranger.client.get("/api/v1/adrs/export?q=${encoded(tag)}").zip()
        fun file(adr: AdrResponse) = files.keys.first { it.startsWith(adr.key) }
        assertTrue("\nSuccessor proposed: [${successor.key}" in files.getValue(file(predecessor)), "pending: named")
        cast.driver.client.moveAdrNow(predecessor.id, AdrStatus.CANCELLED)
        val ended = cast.stranger.client.get("/api/v1/adrs/${predecessor.id}/export").bodyAsText()
        assertFalse("Successor proposed" in ended || "superseded by" in ended.lowercase(), ended)
        val bulk = cast.stranger.client.get("/api/v1/adrs/export?q=${encoded(tag)}").zip()
        val bulkOld = bulk.getValue(bulk.keys.first { it.startsWith(predecessor.key) })
        assertFalse("Successor proposed" in bulkOld || "superseded by" in bulkOld.lowercase(), bulkOld)
        val next = cast.stranger.client.get("/api/v1/adrs/${successor.id}/export").bodyAsText()
        assertTrue("Supersedes [${predecessor.key}: ${predecessor.name}]" in next, "the successor keeps naming it: $next")
    }

    @Test
    fun `the date is the first activation, else the creation day`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        forceAdrTimes(adr.id, createdAt = LocalDate.of(2020, 1, 2).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        assertTrue("\nDate: 2020-01-02\n" in cast.stranger.client.get("/api/v1/adrs/${adr.id}/export").bodyAsText())
        cast.activate(adr)
        assertTrue("\nDate: ${today()}\n" in cast.stranger.client.get("/api/v1/adrs/${adr.id}/export").bodyAsText())
    }

    @Test
    fun `an expiry date is stated in the status`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.driver.client.createAdr(expiresOn = LocalDate.now(ZoneOffset.UTC).plusDays(30).toString())
        val text = cast.stranger.client.get("/api/v1/adrs/${adr.id}/export").bodyAsText()
        assertTrue("\nExpires on ${adr.expiresOn} (the last day the decision is in force)\n" in text)
    }

    @Test
    fun `an unknown or soft-deleted ADR is 404`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        assertEquals(HttpStatusCode.NotFound, cast.stranger.client.get("/api/v1/adrs/4000000/export").status)
        assertEquals(HttpStatusCode.NoContent, cast.admin.client.delete("/api/v1/adrs/${adr.id}").status)
        assertEquals(HttpStatusCode.NotFound, cast.stranger.client.get("/api/v1/adrs/${adr.id}/export").status)
    }

    @Test
    fun `the ZIP holds a file per matching ADR and an index in number order`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val tag = marker()
        val first = cast.driver.client.raise("$tag Zażółć gęślą")
        val second = cast.driver.client.raise("$tag second")
        val cancelled = cast.driver.client.raise("$tag third")
        cast.driver.client.moveAdrNow(cancelled.id, AdrStatus.CANCELLED)
        cast.driver.client.raise("Other ${marker()}")
        val response = cast.stranger.client.get("/api/v1/adrs/export?q=${encoded(tag)}")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("application/zip", response.headers[HttpHeaders.ContentType])
        assertEquals("""attachment; filename="adr-export-${today()}.zip"""", response.headers[HttpHeaders.ContentDisposition])
        val files = response.zip()
        fun file(adr: AdrResponse) = Regex("${adr.key}-[a-z0-9-]+\\.md").matchEntire(files.keys.first { it.startsWith(adr.key) })!!.value
        assertEquals(setOf(file(first), file(second), file(cancelled), "index.md"), files.keys)
        assertTrue(files.getValue(file(first)).startsWith("# ${first.number}. ${first.name}\n"))
        assertTrue("## Status\n\nCancelled\n" in files.getValue(file(cancelled)))
        val bullets = files.getValue("index.md").lines().filter { it.startsWith("- ") }
        assertEquals(
            listOf(
                "- [${first.key}: ${first.name}](${file(first)}) (Problem raised)",
                "- [${second.key}: ${second.name}](${file(second)}) (Problem raised)",
                "- [${cancelled.key}: ${cancelled.name}](${file(cancelled)}) (Cancelled)",
            ),
            bullets,
        )
    }

    @Test
    fun `the ZIP takes the list's filters - status, tag, text, and refuses what the list refuses`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val tag = marker()
        val word = "xyl" + UUID.randomUUID().toString().replace("-", "").take(12)
        val kept = cast.driver.client.raise("$tag kept", problem = "About $word")
        val dropped = cast.driver.client.raise("$tag dropped")
        cast.driver.client.moveAdrNow(dropped.id, AdrStatus.CANCELLED)
        val byStatus = cast.stranger.client.get("/api/v1/adrs/export?q=${encoded(tag)}&status=PROBLEM_RAISED").zip()
        assertEquals(listOf(kept.key), byStatus.keys.filter { it != "index.md" }.map { it.take(kept.key.length) })
        val byText = cast.stranger.client.get("/api/v1/adrs/export?text=${encoded(word)}").zip()
        assertEquals(1, byText.size - 1, "the full-text search is allowed: ${byText.keys}")
        assertTrue(byText.keys.any { it.startsWith(kept.key) })
        val none = cast.stranger.client.get("/api/v1/adrs/export?q=${encoded(marker())}")
        assertEquals(HttpStatusCode.OK, none.status)
        assertEquals(listOf("index.md"), none.zip().keys.toList())
        for (bad in listOf("participantRole=DRIVER", "status=NOPE", "createdFrom=2026-02-30", "text=!!", "q=${"x".repeat(101)}")) {
            val refused = cast.stranger.client.get("/api/v1/adrs/export?$bad")
            assertEquals(HttpStatusCode.BadRequest, refused.status, bad)
            assertEquals(400, refused.body<ProblemDetail>().status)
        }
    }

    @Test
    fun `the archive is not compressed a second time, the Markdown may be`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        val zip = cast.stranger.client.get("/api/v1/adrs/export?q=${encoded(adr.name)}") { header(HttpHeaders.AcceptEncoding, "gzip") }
        assertEquals(null, zip.headers[HttpHeaders.ContentEncoding], "a ZIP is already deflated")
        assertEquals(1, zip.zip().keys.count { it.startsWith(adr.key) })
    }

    @Test
    fun `a match over the cap is refused with its total and no rows, before anything is streamed`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val tag = marker()
        repeat(3) { cast.driver.client.raise("$tag n$it") }
        val service = application.attributes[AdrListServiceKey]
        val filter = AdrFilterDocument(q = tag).normalized().toListFilter()
        val over = service.exportPlan(filter, cap = 2, callerUserId = cast.driver.id)
        assertEquals(3L, over.total)
        assertEquals(emptyList(), over.rows)
        val within = service.exportPlan(filter, cap = 3, callerUserId = cast.driver.id)
        assertEquals(3, within.rows.size)
        assertEquals(within.rows.map { it.number }.sorted(), within.rows.map { it.number }, "number order")
    }

    @Test
    fun `both exports audit ids and counts only, never a name or a filter`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val tag = marker()
        val one = cast.driver.client.raise("$tag Secret title")
        val two = cast.driver.client.raise("$tag Another")
        withAuditCapture { capture ->
            cast.stranger.client.get("/api/v1/adrs/${one.id}/export")
            val single = capture.awaitEvent { it.message == "adr.exported" && it.hasKeyValue("adrId", one.id.toLong()) }
            assertNotNull(single, "a single export audits")
            assertTrue(single.hasKeyValue("byUserId", cast.stranger.id.toLong()))
            assertTrue(single.hasKeyValue("number", one.number))
            cast.stranger.client.get("/api/v1/adrs/export?q=${encoded(tag)}").zip()
            val bulk = capture.awaitEvent { it.message == "adr.exported_bulk" && it.hasKeyValue("adrIds", "${one.id},${two.id}") }
            assertNotNull(bulk, "a bulk export audits")
            assertTrue(bulk.hasKeyValue("count", 2))
            assertTrue(bulk.hasKeyValue("byUserId", cast.stranger.id.toLong()))
            delay(200)
            assertFalse(capture.events.any { e -> e.keyValuePairs.orEmpty().any { tag in it.value.toString() } }, "no name, no filter")
            cast.stranger.client.get("/api/v1/adrs/4000000/export")
            cast.stranger.client.get("/api/v1/adrs/export?participantRole=DRIVER")
            delay(200)
            assertEquals(1, capture.events.count { it.message == "adr.exported" }, "a 404 exports nothing")
            assertEquals(1, capture.events.count { it.message == "adr.exported_bulk" }, "a refusal exports nothing")
        }
    }

    @Test
    fun `the per-user export bucket allows 20 a minute and then answers 429, keyed by the user`() = testApplication {
        usePostgresTestcontainer()
        val limited = adrActor("exp-bucket")
        val other = adrActor("exp-bucket-other")
        val adr = limited.client.raise(marker())
        withAuditCapture { capture ->
            repeat(20) { n ->
                assertEquals(HttpStatusCode.OK, limited.client.get("/api/v1/adrs/${adr.id}/export").status, "request ${n + 1}")
            }
            val rejected = limited.client.get("/api/v1/adrs/export?q=${encoded(adr.name)}")
            assertEquals(HttpStatusCode.TooManyRequests, rejected.status, "one bucket for both exports")
            assertEquals(429, rejected.body<ProblemDetail>().status)
            assertEquals(HttpStatusCode.OK, other.client.get("/api/v1/adrs/${adr.id}/export").status)
            val event = capture.awaitEvent { it.message == "rate_limit.exceeded" && it.hasKeyValue("bucket", "export") }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("byUserId", limited.id.toLong()))
        }
    }
}
