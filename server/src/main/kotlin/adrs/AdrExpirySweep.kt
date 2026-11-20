package ch.nokillswit.adrs

import ch.nokillswit.audit.audit
import ch.nokillswit.infra.backgroundScope
import ch.nokillswit.infra.config.requireConfigLong
import ch.nokillswit.infra.db.R2dbcDatabaseKey
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.nowMillis
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.config.ApplicationConfig
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.LocalDate

private val log = LoggerFactory.getLogger("ch.nokillswit.adrs.AdrExpirySweep")

/** The most ADRs one candidate page holds; the sweep pages until none is left, each ADR in its own transaction. */
private const val CANDIDATE_PAGE = 100

/**
 * The periodic expiry (`.claude/docs/adr-model.md` "Expiry"; Covenant's `ReleaseLineReminders` loop shape): every `ACTIVE` ADR whose
 * `expires_on` is BEFORE today (UTC; the date is the last day the decision is in force) moves to `EXPIRED` in its own transaction with a
 * system-actor `EXPIRED` event. "Today" is the injected [clock] (a test pins it). Idempotent and safe on several replicas: the candidate
 * scan is only a filter, each ADR is re-read under its row lock (`FOR NO KEY UPDATE`, skipping none: a second replica waits, then finds
 * the status moved and does nothing), so one ADR expires exactly once and records one event.
 */
class AdrExpirySweep(
    private val database: R2dbcDatabase,
    private val events: AdrEventService,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val adrs = AdrService.Adrs

    /** Expires every candidate; returns how many THIS call moved. A failure of one ADR is logged and does not stop the rest. */
    suspend fun sweep(): Int {
        val today = LocalDate.now(clock).toString()
        var after = 0u
        var expired = 0
        while (true) {
            val ids = candidateIds(after, today)
            if (ids.isEmpty()) return expired
            expired += ids.count { expireQuietly(it, today) }
            after = ids.last()
        }
    }

    /** One ADR in its own transaction: true when it moved, false when it did not (or failed: logged, never fatal to the pass). */
    private suspend fun expireQuietly(id: UInt, today: String): Boolean = try {
        expire(id, today)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        // Not the message: a driver message can carry bound values (observability.md "The 500 line").
        log.warn("Could not expire ADR {}: {}", id, failure.javaClass.name)
        false
    }

    /** The scan: ISO dates sort chronologically as text, `idx_adrs_expiry` (V10) serves it. Every fact is re-checked under the lock. */
    private suspend fun candidateIds(after: UInt, today: String): List<UInt> = suspendTransaction(database) {
        adrs.select(adrs.id).where {
            adrs.active() and (adrs.status eq AdrStatus.ACTIVE.name) and adrs.expiresOn.isNotNull() and
                (adrs.expiresOn less today) and (adrs.id greater after)
        }.orderBy(adrs.id).limit(CANDIDATE_PAGE).map { it[adrs.id].value }.toList()
    }

    private suspend fun expire(id: UInt, today: String): Boolean {
        val expired = suspendTransaction(database) {
            val row = adrs.selectAll().where { (adrs.id eq id) and adrs.active() }
                .forUpdate(ForUpdateOption.PostgreSQL.ForNoKeyUpdate).toList().singleOrNull()
                ?: return@suspendTransaction null
            val expiresOn = row[adrs.expiresOn]
            if (row[adrs.status] != AdrStatus.ACTIVE.name || expiresOn == null || expiresOn >= today) return@suspendTransaction null
            val stamp = nowMillis()
            adrs.update({ adrs.id eq id }) {
                it[status] = AdrStatus.EXPIRED.name
                it[statusChangedAt] = stamp
                it[revision] = row[adrs.revision] + 1
                it[updatedAt] = stamp
            }
            events.record(id, null, adrExpiredEvent(expiresOn), stamp)
            row[adrs.number] to expiresOn
        } ?: return false
        audit("adr.expired", "adrId" to id.toLong(), "number" to expired.first, "expiresOn" to expired.second)
        return true
    }
}

/** The sweep's settings (`adrs.expiry.*`), validated on EVERY boot, enabled or not (a malformed value refuses startup naming its key). */
data class AdrExpiryConfig(val enabled: Boolean, val intervalSeconds: Long)

fun readAdrExpiryConfig(config: ApplicationConfig): AdrExpiryConfig {
    val enabledKey = "adrs.expiry.enabled"
    val rawEnabled = config.property(enabledKey).getString()
    val enabled = rawEnabled.toBooleanStrictOrNull() ?: error("Config \"$enabledKey\" must be true or false (was \"$rawEnabled\")")
    return AdrExpiryConfig(enabled, requireConfigLong(config, "adrs.expiry.intervalSeconds", min = 1, max = 86_400))
}

/** Published once the sweep loop runs: `AdrExpirySweepTest`'s "loop started" seam, and the handle a test sweeps by hand. */
val AdrExpirySweepKey = AttributeKey<AdrExpirySweep>("AdrExpirySweep")

/**
 * The sweep's module: `adrs.expiry.enabled` (env `ADRS_EXPIRY_ENABLED`, default true; the suite sets it false) and
 * `adrs.expiry.intervalSeconds` (env `ADRS_EXPIRY_INTERVAL_SECONDS`, default 3600, 1..86400). The loop starts on [ApplicationStarted] on
 * the ONE background scope (`infra/BackgroundScope.kt`: cancelled and joined on `ApplicationStopping`, before the pool is disposed),
 * sweeps at once and then every interval; a failed sweep is logged and retried at the next one.
 */
fun Application.configureAdrExpirySweep() {
    val config = readAdrExpiryConfig(environment.config)
    if (!config.enabled) return
    monitor.subscribe(ApplicationStarted) { app ->
        val sweep = AdrExpirySweep(app.attributes[R2dbcDatabaseKey], app.attributes[AdrEventServiceKey])
        app.attributes.put(AdrExpirySweepKey, sweep)
        app.backgroundScope("hammurabi-adr-expiry").launch {
            while (isActive) {
                try {
                    sweep.sweep()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    log.warn("The ADR expiry sweep failed: {}", failure.javaClass.name)
                }
                delay(config.intervalSeconds * 1000)
            }
        }
    }
}
