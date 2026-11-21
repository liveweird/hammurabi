package ch.nokillswit.adrs

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.TooManyRequestsException
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.infra.ProblemTypes
import ch.nokillswit.infra.config.requireConfigInt
import ch.nokillswit.plugins.RateLimits
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.resources.get
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean

/** Seconds the `Retry-After` header of an [exportBusy] answer names: a slot frees when a download ends, so a few seconds. */
const val EXPORT_BUSY_RETRY_AFTER_SECONDS = 5

private val MARKDOWN = ContentType.parse("text/markdown").withCharset(Charsets.UTF_8)

private fun today(): LocalDate = LocalDate.now(ZoneOffset.UTC)

/** `attachment; filename="..."`: every name this builds is `[A-Za-z0-9.-]` only (a slug), so it needs no quoting rules or `filename*`. */
private fun attachment(fileName: String) = "attachment; filename=\"$fileName\""

/**
 * The process already runs its maximum of concurrent bulk exports (`adrs.export.maxConcurrent`): a `429` with `Retry-After`, typed apart
 * from the rate-limit bucket's by its URI (the remedy is "retry shortly", not "slow down"). Sent at once, never after a wait: a slow
 * reader of a download holds a blocking IO thread, and this cap bounds how many.
 */
fun exportBusy(): TooManyRequestsException = TooManyRequestsException(
    "Too many exports are running - retry in a moment",
    problemType = ProblemTypes.EXPORT_BUSY,
    retryAfterSeconds = EXPORT_BUSY_RETRY_AFTER_SECONDS,
)

/**
 * One slot of the bulk-export cap. Once the response body is handed to the engine the STREAM owns it (the handler returns while the body
 * is still being written, so the handler must not give it back): the writer releases it when it ends or fails. The handler gives it back
 * only for a call that never reached its writer (a refusal, or a response that could not start). Released at most once either way.
 */
private class ExportPermit(private val permits: Semaphore) {
    private val released = AtomicBoolean(false)
    private val streaming = AtomicBoolean(false)

    fun streamStarted() = streaming.set(true)

    fun release() {
        if (released.compareAndSet(false, true)) permits.release()
    }

    /** The handler failed: free the slot unless the stream already took it over (then the stream frees it). */
    fun releaseUnlessStreaming() {
        if (!streaming.get()) release()
    }
}

/**
 * The ADR export surface (0.8.0, `adr-model.md` "Export"): `GET /api/v1/adrs/{id}/export` (one Markdown file) and
 * `GET /api/v1/adrs/export` (a ZIP of the ADRs matching the list's filters, plus `index.md`). Both are reads open to every authenticated
 * user, like the ADR itself (a missing or soft-deleted ADR is a plain 404, nothing to disclose), behind the per-USER `export` bucket,
 * and each audits what left the system (`adr.exported`, `adr.exported_bulk`: ids and counts, never a name, a filter or a text). `HEAD` is
 * refused (`405`): it would run the plan and the audit for a body nobody receives. Settings: `adrs.export.maxAdrs` (the cap of one bulk
 * export) and `adrs.export.maxConcurrent` (how many bulk exports one process streams at once), both boot-validated. The export service is
 * resolved per call, so a test can swap it for one that fails a batch.
 */
fun Application.configureAdrExportRoutes() {
    val listService = attributes[AdrListServiceKey]
    val maxAdrs = requireConfigInt(environment.config, "adrs.export.maxAdrs", min = 1, max = MAX_EXPORT_ADRS_CEILING)
    val permits = Semaphore(requireConfigInt(environment.config, "adrs.export.maxConcurrent", min = 1, max = MAX_EXPORT_CONCURRENT_CEILING))
    routing {
        authenticate {
            rateLimit(RateLimitName(RateLimits.EXPORT)) {
                exportOne()
                exportMany(listService, maxAdrs, permits)
            }
        }
    }
}

private const val MAX_EXPORT_ADRS_CEILING = 5_000
private const val MAX_EXPORT_CONCURRENT_CEILING = 16

/** Answers `405` to a `HEAD` (the auto-HEAD would otherwise run the handler for no body); true when it did. */
private suspend fun RoutingContext.refusedHead(): Boolean {
    if (call.request.local.method != HttpMethod.Head) return false
    call.response.header(HttpHeaders.Allow, "GET")
    call.respond(HttpStatusCode.MethodNotAllowed)
    return true
}

private fun Route.exportOne() {
    get<AdrsRoute.Id.Export> { route ->
        if (refusedHead()) return@get
        val caller = call.caller()
        val document = call.application.attributes[AdrExportServiceKey].document(route.parent.id).orNotFound("ADR")
        audit("adr.exported", "byUserId" to caller.userId.toLong(), "adrId" to route.parent.id.toLong(), "number" to document.number)
        call.response.header(HttpHeaders.ContentDisposition, attachment(adrExportFileName(document.number, document.name)))
        call.respondText(renderAdrMarkdown(document, today()), MARKDOWN)
    }
}

/**
 * The bulk export. A slot of the concurrency cap is taken first (`429` `export-busy` at once when none is free). Everything else that can
 * be refused (the filters, the search deadline, more than [maxAdrs] matches) is judged by the PLAN, before the first byte, so a refusal is
 * an ordinary problem response. The archive is then streamed [EXPORT_BATCH_SIZE] ADRs at a time; a failure after the first byte ABORTS
 * the connection (see [writeArchive]): the client sees a failed download, never a complete-looking archive that is missing files.
 */
private fun Route.exportMany(listService: AdrListService, maxAdrs: Int, permits: Semaphore) {
    get<AdrsRoute.Export> {
        if (refusedHead()) return@get
        val caller = call.caller()
        if (!permits.tryAcquire()) {
            audit("export.busy", "byUserId" to caller.userId.toLong())
            throw exportBusy()
        }
        val permit = ExportPermit(permits)
        try {
            val filter = adrFilterDocument(call.request.queryParameters).normalized().toListFilter()
            val plan = listService.exportPlan(filter, maxAdrs, caller.userId)
            if (plan.total > maxAdrs) {
                throw BadRequestException("The filters match ${plan.total} ADRs; an export holds at most $maxAdrs - narrow the filters")
            }
            audit(
                "adr.exported_bulk",
                "byUserId" to caller.userId.toLong(),
                "count" to plan.rows.size,
                "adrIds" to plan.rows.joinToString(",") { it.id.toString() },
            )
            val exports = call.application.attributes[AdrExportServiceKey]
            val exportedOn = today()
            call.response.header(HttpHeaders.ContentDisposition, attachment("adr-export-$exportedOn.zip"))
            call.respondBytesWriter(ContentType.Application.Zip) {
                permit.streamStarted()
                try {
                    withContext(Dispatchers.IO) { writeArchive(plan.rows, exports, exportedOn) }
                } finally {
                    permit.release()
                }
            }
        } catch (failure: Throwable) {
            permit.releaseUnlessStreaming()
            throw failure
        }
    }
}
