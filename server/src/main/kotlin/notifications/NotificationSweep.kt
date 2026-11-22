package ch.nokillswit.notifications

import ch.nokillswit.infra.backgroundScope
import ch.nokillswit.infra.catchingFailures
import ch.nokillswit.infra.config.requireConfigInt
import ch.nokillswit.infra.config.requireConfigLong
import ch.nokillswit.infra.db.R2dbcDatabaseKey
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.mail.mailAppUrl
import ch.nokillswit.infra.mail.mailer
import ch.nokillswit.infra.time.MILLIS_PER_DAY
import ch.nokillswit.plugins.scrubbedSummary
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ch.nokillswit.notifications.NotificationSweep")

private const val HOUSEKEEPING_EVERY_MILLIS = 3_600_000L

/** The ceiling of both digest timings: one day (the digest mails nothing older than that anyway). */
private const val MAX_EMAIL_SECONDS = 86_400L

/** The ceiling of `notifications.email.maxPerRun`. */
private const val MAX_EMAIL_PER_RUN = 1000

/** The ceiling of `adrs.expiry.reminderDays`: a year ahead (`0` = no reminders). */
internal const val MAX_REMINDER_DAYS = 365

/**
 * Published once the sweep has started and has run its first pass: how many notices that pass purged, or null when it failed. A TEST seam
 * (`NotificationSweepTest` awaits it); nothing in the application reads it. Absent while `notifications.sweepEnabled` is false.
 */
internal val NotificationSweepKey = AttributeKey<Deferred<Int?>>("NotificationSweep")

/**
 * The notifications' periodic work (`adr-model.md` "Notifications"): every tick runs the email digest; then, at most once an hour
 * ([HOUSEKEEPING_EVERY_MILLIS], whatever the tick interval) and on the first tick, the expiry reminders ([remind]) and the retention of
 * notices (a notice older than [retentionMillis] is deleted), in the sweep's OWN scope and transactions, never inside an ADR write. Each
 * step's failure is logged (class names and the SQLSTATE only, [scrubbedSummary]: a database message can echo a bound value) and never
 * stops the next step; the rows wait for the next pass. Cancellation is never swallowed.
 */
internal class NotificationSweep(
    private val retentionMillis: Long,
    private val clock: () -> Long = ::nowMillis,
    /** Deletes the notices created before the cutoff (epoch millis) and answers how many went: [NotificationService.purgeOlderThan]. */
    private val purge: suspend (cutoffMillis: Long) -> Int,
    /** The email digest's run ([NotificationDigest.run]), every tick; a no-op where nothing mails (a test, mail disabled). */
    private val digest: suspend () -> Unit = {},
    /** The expiry reminder pass ([ExpiryReminders.run]); a no-op by default. */
    private val remind: suspend () -> Unit = {},
) {
    /** When the last housekeeping ran, or null before the first; touched by the sweep's own coroutine alone. */
    private var lastHousekeepingAt: Long? = null

    /**
     * One tick. Answers how many notices the retention deleted, or null when no housekeeping was due or the purge failed. `internal`
     * for the
     * tests.
     */
    suspend fun tick(): Int? {
        catchingFailures(block = { digest() }, onFailure = { e -> log.warn("notification email digest failed: {}", e.scrubbedSummary()) })
        val now = clock()
        val last = lastHousekeepingAt
        if (last != null && now - last < HOUSEKEEPING_EVERY_MILLIS) return null
        lastHousekeepingAt = now
        catchingFailures(block = { remind() }, onFailure = { e -> log.warn("expiry reminders failed: {}", e.scrubbedSummary()) })
        return catchingFailures(
            block = {
                purge(now - retentionMillis).also {
                    if (it > 0) log.info("notification retention: {} notice(s) deleted", it)
                }
            },
            onFailure = { e ->
                log.warn("notification retention failed, the rows stay until the next pass: {}", e.scrubbedSummary())
                null
            },
        )
    }
}

/**
 * The sweep's module: `notifications.*` and `adrs.expiry.reminderDays` are validated on every boot, enabled or not; the sweep itself starts
 * on [ApplicationStarted] in its own scope when `notifications.sweepEnabled` is true (the test harness turns it off, so no booted app
 * purges the shared database, mails a notice or reminds about an ADR) and is cancelled and joined on `ApplicationStopping`, before the pool
 * is disposed. Each tick also runs the email digest ([NotificationDigest]); with `mail.transport=disabled` there is no mailer, the digest
 * does nothing and logs it once, and startup is never refused for it (the bell works without a mailer). `mail.appUrl` (the origin the
 * password reset uses) builds the digest's links.
 */
fun Application.configureNotificationSweep() {
    val config = environment.config
    val retentionDays = requireConfigInt(config, "notifications.retentionDays", min = 1, max = 3650)
    val intervalSeconds = requireConfigInt(config, "notifications.sweepIntervalSeconds", min = 10, max = 3600)
    val delaySeconds = requireConfigLong(config, "notifications.email.delaySeconds", min = 0, max = MAX_EMAIL_SECONDS)
    val minIntervalSeconds = requireConfigLong(config, "notifications.email.minIntervalSeconds", min = 0, max = MAX_EMAIL_SECONDS)
    // The most mails one run sends: the digest shares the Mailer with MFA codes and password resets, and this budget is what protects them.
    val maxPerRun = requireConfigInt(config, "notifications.email.maxPerRun", min = 1, max = MAX_EMAIL_PER_RUN)
    val reminderDays = requireConfigInt(config, "adrs.expiry.reminderDays", min = 0, max = MAX_REMINDER_DAYS)
    // A notice is mailed only inside [delay, a day): a delay or a spacing that, with one tick, reaches a day makes every notice either
    // too young or too old, and the digest would silently mail nothing.
    check(maxOf(delaySeconds, minIntervalSeconds) + intervalSeconds < MAX_EMAIL_SECONDS) {
        "Config \"notifications.email.delaySeconds\" and \"notifications.email.minIntervalSeconds\" (the larger one) plus " +
            "\"notifications.sweepIntervalSeconds\" must be under 86400 seconds: nothing older than a day is mailed"
    }
    val raw = config.property("notifications.sweepEnabled").getString()
    val enabled = raw.toBooleanStrictOrNull() ?: error("Config \"notifications.sweepEnabled\" must be true or false (was \"$raw\")")
    if (!enabled) return
    monitor.subscribe(ApplicationStarted) { app ->
        val service = app.attributes[NotificationServiceKey]
        val database = app.attributes[R2dbcDatabaseKey]
        val digest = NotificationDigest(
            database = database,
            mailer = app.mailer(),
            appUrl = app.mailAppUrl(),
            delayMillis = delaySeconds * 1000,
            minIntervalMillis = minIntervalSeconds * 1000,
            batchSize = digestBatchSize(maxPerRun),
            maxPerRun = maxPerRun,
        )
        val reminders = ExpiryReminders(database, service, reminderDays)
        app.attributes.put(ExpiryRemindersKey, reminders)
        val sweep = NotificationSweep(
            retentionDays * MILLIS_PER_DAY,
            purge = { service.purgeOlderThan(it) },
            digest = { digest.run() },
            remind = { reminders.run() },
        )
        val scope = app.backgroundScope("hammurabi-notification-sweep")
        val first = CompletableDeferred<Int?>()
        app.attributes.put(NotificationSweepKey, first)
        scope.launch {
            first.complete(sweep.tick())
            while (isActive) {
                delay(intervalSeconds * 1000L)
                sweep.tick()
            }
        }
    }
}
