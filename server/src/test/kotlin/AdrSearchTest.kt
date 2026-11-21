package ch.nokillswit

import ch.nokillswit.adrs.AdrCreateRequest
import ch.nokillswit.adrs.AdrPageResponse
import ch.nokillswit.adrs.AdrResponse
import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.adrs.SEARCH_BUSY_RETRY_AFTER_SECONDS
import ch.nokillswit.adrs.SEARCH_TIMEOUT_RETRY_AFTER_SECONDS
import ch.nokillswit.infra.ProblemTypes
import ch.nokillswit.plugins.ProblemDetail
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The full-text search of `GET /api/v1/adrs?text=` (V15, `adr-model.md` "Search"): the fields it reads and their weights, the folding
 * (case, accents), prefixes, every-word-matches, the typed snippets (never markup), the validation, the deadline and the cap, and that a
 * term reaches no log line. ADRs are shared suite state: each test mints a UNIQUE word and searches for that.
 */
class AdrSearchTest {
    private fun word(prefix: String) = prefix + UUID.randomUUID().toString().replace("-", "").take(12)

    private fun encoded(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

    private suspend fun HttpClient.raise(
        name: String = adrName(),
        problem: String = "How do we decide?",
        context: String = "",
        decision: String = "",
        alternatives: String = "",
        consequences: String = "",
    ): AdrResponse {
        val response = postJson("/api/v1/adrs", AdrCreateRequest(name, problem, context, decision, alternatives, consequences))
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    private suspend fun HttpClient.search(text: String, extra: String = ""): AdrPageResponse {
        val response = get("/api/v1/adrs?text=${encoded(text)}$extra")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText().take(200))
        return response.body()
    }

    @Test
    fun `a word in any content field finds the ADR, and an unrelated word does not`() = testApplication {
        usePostgresTestcontainer()
        val reader = adrActor("srch-fields").client
        val driver = adrActor("srch-fields-d").client
        val inName = driver.raise(name = "Name ${word("nm")}")
        val nameWord = inName.name.substringAfter(' ')
        val inProblem = word("pr").let { it to driver.raise(problem = "The $it hurts") }
        val inContext = word("cx").let { it to driver.raise(context = "Because $it") }
        val inDecision = word("dc").let { it to driver.raise(decision = "We pick $it") }
        val inAlternatives = word("al").let { it to driver.raise(alternatives = "Or $it instead") }
        val inConsequences = word("cq").let { it to driver.raise(consequences = "Then $it follows") }

        assertEquals(listOf(inName.id), reader.search(nameWord).items.map { it.id })
        for ((term, adr) in listOf(inProblem, inContext, inDecision, inAlternatives, inConsequences)) {
            assertEquals(listOf(adr.id), reader.search(term).items.map { it.id }, "the word $term")
        }
        assertEquals(0, reader.search(word("nothing")).total)
    }

    @Test
    fun `the search folds case and accents both ways and a word of three letters or more is a prefix`() = testApplication {
        usePostgresTestcontainer()
        val driver = adrActor("srch-fold").client
        val tag = UUID.randomUUID().toString().replace("-", "").take(10)
        val accented = driver.raise(name = "Żółw$tag", decision = "A slow Żółw$tag wins")
        val plain = driver.raise(name = "Gesla$tag", decision = "The plain Gesla$tag decision")

        assertEquals(listOf(accented.id), driver.search("zolw$tag").items.map { it.id }, "zolw finds Żółw")
        assertEquals(listOf(accented.id), driver.search("ŻÓŁW$tag").items.map { it.id }, "case and accents both fold")
        assertEquals(listOf(plain.id), driver.search("GĘŚLA$tag").items.map { it.id }, "accents in the query fold too")
        // The prefix: the first twelve characters of the unique word are enough.
        assertEquals(listOf(accented.id), driver.search("zolw${tag.take(6)}").items.map { it.id })
    }

    @Test
    fun `every word must match, in any order`() = testApplication {
        usePostgresTestcontainer()
        val driver = adrActor("srch-all").client
        val first = word("aaa")
        val second = word("bbb")
        driver.raise(decision = "only $first here")
        val both = driver.raise(decision = "the $second and then $first")
        assertEquals(listOf(both.id), driver.search("$first $second").items.map { it.id })
        assertEquals(listOf(both.id), driver.search("$second   $first").items.map { it.id })
    }

    @Test
    fun `the weights rank a name hit over a decision hit over a consequences hit, and a sort overrides the rank`() = testApplication {
        usePostgresTestcontainer()
        val driver = adrActor("srch-rank").client
        val term = word("rank")
        val inConsequences = driver.raise(consequences = "side effect $term")
        val inName = driver.raise(name = "About $term")
        val inDecision = driver.raise(decision = "decided $term")

        val byRelevance = driver.search(term).items.map { it.id }
        assertEquals(listOf(inName.id, inDecision.id, inConsequences.id), byRelevance, "default order is the relevance")
        assertEquals(byRelevance, driver.search(term, "&sort=-relevance").items.map { it.id })
        assertEquals(byRelevance.reversed(), driver.search(term, "&sort=relevance").items.map { it.id })
        val newest = listOf(inDecision, inName, inConsequences).sortedByDescending { it.number }.map { it.id }
        assertEquals(newest, driver.search(term, "&sort=-number").items.map { it.id }, "an explicit sort wins")
    }

    @Test
    fun `a row carries its match as typed segments, and a plain list carries none`() = testApplication {
        usePostgresTestcontainer()
        val driver = adrActor("srch-snip").client
        val term = word("Snip")
        val adr = driver.raise(name = "Title $term", decision = "We pick the $term option for good reasons")

        val row = driver.search(term.lowercase()).items.single()
        val match = assertNotNull(row.match)
        assertEquals(listOf("Title ", term), match.name.map { it.text })
        assertEquals(listOf(false, true), match.name.map { it.hit })
        assertTrue(match.snippet.any { it.hit && it.text == term }, "the hit keeps the stored spelling: ${match.snippet}")
        assertTrue(match.snippet.size > 1, "the hit has plain text around it: ${match.snippet}")

        assertNull(driver.get("/api/v1/adrs?q=${encoded(adr.name)}").body<AdrPageResponse>().items.single().match)
    }

    @Test
    fun `a name-only hit has an empty snippet, and markup in a body is only ever text`() = testApplication {
        usePostgresTestcontainer()
        val driver = adrActor("srch-html").client
        val onlyName = word("OnlyName")
        val named = driver.raise(name = "Title $onlyName", decision = "nothing relevant here at all")
        assertEquals(emptyList(), driver.search(onlyName).items.single { it.id == named.id }.match!!.snippet)

        val term = word("markup")
        driver.raise(decision = "pre <img src=x onerror=alert(1)> $term <script>alert(2)</script> post")
        val segments = driver.search(term).items.single().match!!.snippet
        val text = segments.joinToString("") { it.text }
        assertTrue("alert(1)" in text, "dangerous-looking text is carried as plain text: $text")
        assertTrue(segments.none { segment -> segment.text.any { it == '\u0002' || it == '\u0003' } }, "the markers never survive")
        assertTrue(segments.single { it.hit }.text == term)
    }

    @Test
    fun `the search combines with the other filters`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val term = word("combo")
        val raised = cast.driver.client.raise(name = "Combo A ${word("n")}", decision = "uses $term")
        val draft = cast.driver.client.raise(name = "Combo B ${word("n")}", decision = "uses $term")
        cast.driver.client.moveAdrNow(draft.id, AdrStatus.SOLUTION_DRAFT)
        val other = cast.stranger.client.raise(name = "Combo C ${word("n")}", decision = "uses $term")

        val reader = cast.informed.client
        assertEquals(setOf(raised.id, draft.id, other.id), reader.search(term).items.map { it.id }.toSet())
        assertEquals(listOf(draft.id), reader.search(term, "&status=SOLUTION_DRAFT").items.map { it.id })
        val drivenBy = "&participantId=${cast.driver.id}&participantRole=DRIVER"
        assertEquals(setOf(raised.id, draft.id), reader.search(term, drivenBy).items.map { it.id }.toSet())
        assertEquals(listOf(raised.id), reader.search(term, "&q=${encoded("Combo A")}").items.map { it.id })
        assertEquals(0, reader.search(term, "&hasOpenIssues=true").total)
        assertEquals(1, reader.search(term, "&pageSize=1").items.size)
        assertEquals(3, reader.search(term, "&pageSize=1").total, "the total counts the matches, not the page")
    }

    @Test
    fun `text is validated and no problem ever names the term`() = testApplication {
        usePostgresTestcontainer()
        val client = adrActor("srch-val").client
        suspend fun status(query: String) = client.get("/api/v1/adrs?$query")

        assertEquals(HttpStatusCode.BadRequest, status("text=a").status, "one character")
        assertEquals(HttpStatusCode.BadRequest, status("text=${encoded("!!! ???")}").status, "no letter or digit")
        assertEquals(HttpStatusCode.BadRequest, status("text=${encoded("x".repeat(101))}").status, "over 100")
        assertEquals(HttpStatusCode.BadRequest, status("text=${encoded("\uD83D\uDE00\uD83D\uDE00")}").status, "emoji only")
        assertEquals(HttpStatusCode.BadRequest, status("text=${encoded("a\u0001bc")}").status, "a control character")
        assertEquals(HttpStatusCode.BadRequest, status("text=ab&text=cd").status, "a repeated key")
        val secret = word("secretterm")
        val rejected = status("text=${encoded("$secret\u0001")}")
        assertEquals(HttpStatusCode.BadRequest, rejected.status)
        assertTrue(secret !in rejected.bodyAsText(), "the problem never echoes the term")

        assertEquals(HttpStatusCode.BadRequest, status("text=${encoded("\\")}").status, "no letter or digit")
        assertEquals(HttpStatusCode.OK, status("text=%20%20").status, "a blank text is no search")
        assertNull(status("text=%20").body<AdrPageResponse>().items.firstOrNull()?.match)
        assertEquals(HttpStatusCode.BadRequest, status("sort=relevance").status, "relevance needs text")
        assertEquals(HttpStatusCode.BadRequest, status("sort=-relevance&text=%20").status)
        assertEquals(HttpStatusCode.BadRequest, status("text=ab&pageSize=21").status, "a search page is at most 20")
        assertEquals(HttpStatusCode.OK, status("text=ab&pageSize=20").status)
        assertEquals(HttpStatusCode.OK, status("pageSize=21").status, "a plain list keeps its 100")
    }

    @Test
    fun `query syntax is only ever punctuation`() = testApplication {
        usePostgresTestcontainer()
        val client = adrActor("srch-syntax").client
        val term = word("syntax")
        val adr = client.raise(decision = "about $term and more")
        for (hostile in listOf("'; DROP TABLE adrs; --", "a:* & !b | (c)", "\"quoted\"", "<script>alert(1)</script>", "x ' y")) {
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/adrs?text=${encoded(hostile)}").status, hostile)
        }
        val punctuated = client.search("($term) & $term").items.map { it.id }
        assertEquals(listOf(adr.id), punctuated, "operators are punctuation, the words still match")
        assertEquals(listOf(adr.id), client.search("\"$term\"").items.map { it.id })
    }

    @Test
    fun `the term reaches no log line`() = testApplication {
        usePostgresTestcontainer()
        val client = adrActor("srch-logs").client
        val term = word("logterm")
        client.raise(decision = "mentions $term")
        val capture = LogCapture(Logger.ROOT_LOGGER_NAME)
        try {
            client.search(term)
            client.get("/api/v1/adrs?text=${encoded("$term\u0001")}")
            val leaked = capture.events.filter { term in it.rendered() }
            assertEquals(emptyList(), leaked.map { it.rendered().take(300) }, "the term reached a log line")
        } finally {
            capture.detach()
        }
    }

    @Test
    fun `a search past its deadline is the audited 429 search-timeout and the pool is clean after it`() = testApplication {
        configureApp("search.timeoutSeconds" to "1")
        startApplication()
        val client = adrActor("srch-slow").client
        val term = word("slowterm")
        client.raise(decision = "mentions $term")
        val capture = LogCapture(Logger.ROOT_LOGGER_NAME)
        try {
            // The count waits behind a table lock held by a raw transaction: past the 1 s budget the database cancels it (57014).
            val started = System.nanoTime()
            val response = PostgresTestSupport.connect().use { holder ->
                holder.autoCommit = false
                holder.createStatement().use { it.execute("LOCK TABLE adrs IN ACCESS EXCLUSIVE MODE") }
                try {
                    client.get("/api/v1/adrs?text=${encoded(term)}")
                } finally {
                    holder.rollback()
                }
            }
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertEquals(SEARCH_TIMEOUT_RETRY_AFTER_SECONDS.toString(), response.headers["Retry-After"])
            val problem = response.body<ProblemDetail>()
            assertEquals(ProblemTypes.SEARCH_TIMEOUT, problem.type)
            assertTrue(term !in problem.detail.orEmpty(), "the problem never names the term")
            assertTrue(elapsedMillis in 900..10_000, "it answered at the budget ($elapsedMillis ms)")
            assertNotNull(capture.awaitEvent { it.message == "search.timeout" }).let { event ->
                assertTrue(event.keyValuePairs.orEmpty().any { it.key == "byUserId" })
            }
            val leaked = capture.events.filter { term in it.rendered() }
            assertEquals(emptyList(), leaked.map { it.rendered().take(300) }, "the term reached a log line")
            val errors = capture.events.filter { it.level.isGreaterOrEqual(ch.qos.logback.classic.Level.ERROR) }
            assertTrue(errors.isEmpty(), "a cancelled search is not a failure")
            assertEquals(1, client.search(term).total, "the lock is gone and the same search works")
        } finally {
            capture.detach()
        }
    }

    @Test
    fun `a search past the process cap is refused at once with the typed 429 search-busy`() = testApplication {
        configureApp("search.maxConcurrent" to "1", "search.timeoutSeconds" to "20")
        startApplication()
        val first = adrActor("srch-busy-a").client
        val second = adrActor("srch-busy-b").client
        val term = word("busyterm")
        first.raise(decision = "mentions $term")
        withAuditCapture { capture ->
            val refused: HttpResponse = PostgresTestSupport.connect().use { holder ->
                holder.autoCommit = false
                holder.createStatement().use { it.execute("LOCK TABLE adrs IN ACCESS EXCLUSIVE MODE") }
                try {
                    coroutineScope {
                        val running = async { first.get("/api/v1/adrs?text=${encoded(term)}") }
                        awaitLockWait("adrs.search")
                        val started = System.nanoTime()
                        val refused = second.get("/api/v1/adrs?text=${encoded(term)}")
                        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
                        assertTrue(elapsedMillis < 3_000, "refused at once, not after a wait ($elapsedMillis ms)")
                        holder.rollback()
                        assertEquals(HttpStatusCode.OK, running.await().status, "the search holding the permit completes")
                        refused
                    }
                } finally {
                    holder.rollback()
                }
            }
            assertEquals(HttpStatusCode.TooManyRequests, refused.status)
            assertEquals(SEARCH_BUSY_RETRY_AFTER_SECONDS.toString(), refused.headers["Retry-After"])
            val problem = refused.body<ProblemDetail>()
            assertEquals(ProblemTypes.SEARCH_BUSY, problem.type)
            assertTrue(term !in problem.detail.orEmpty())
            assertNotNull(capture.awaitEvent { it.message == "search.busy" })
            // The permit is back, and a plain list never needed one.
            assertEquals(1, second.search(term).total)
            assertEquals(HttpStatusCode.OK, second.get("/api/v1/adrs").status)
        }
    }

    /** A plain Exposed `insert` answers `RETURNING *`: with the stored `search` vector that would ride back on every create. */
    @Test
    fun `an ADR INSERT returns its id only, never the search vector`() = testApplication {
        usePostgresTestcontainer()
        val client = adrActor("srch-ret").client
        val driver = org.slf4j.LoggerFactory.getLogger("io.r2dbc.postgresql.QUERY") as Logger
        val saved = driver.level
        val capture = LogCapture(driver.name)
        try {
            driver.level = ch.qos.logback.classic.Level.DEBUG
            client.raise()
            val inserts = capture.events.map { it.formattedMessage }.filter { "INSERT INTO adrs " in it }
            assertEquals(1, inserts.size, "captured: $inserts")
            assertTrue(inserts.single().endsWith("RETURNING adrs.id"), inserts.single())
        } finally {
            driver.level = saved
            capture.detach()
        }
    }

    /** Everything an event can carry, as one string. */
    private fun ILoggingEvent.rendered(): String = buildString {
        append(loggerName).append(' ').append(formattedMessage).append(' ').append(message)
        argumentArray?.forEach { append(' ').append(it) }
        keyValuePairs?.forEach { append(' ').append(it.key).append('=').append(it.value) }
        mdcPropertyMap?.forEach { (key, value) -> append(' ').append(key).append('=').append(value) }
        throwableProxy?.let { append(' ').append(ThrowableProxyUtil.asString(it)) }
    }
}
