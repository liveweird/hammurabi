package ch.nokillswit.notifications

import ch.nokillswit.audit.audit
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.mail.Mailer
import ch.nokillswit.infra.time.MILLIS_PER_DAY
import ch.nokillswit.plugins.scrubbedSummary
import ch.nokillswit.adrs.AdrService
import ch.nokillswit.adrs.adrKey
import ch.nokillswit.users.UserService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.update
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ch.nokillswit.notifications.NotificationDigest")

/** PEOPLE one run claims at most (the rest wait): oldest waiting first per recipient, so one flooded backlog crowds out nobody. */
internal const val DIGEST_BATCH = 500

/** Notices of ONE person a mail reads as entries (it lists [DIGEST_MAX_ENTRIES]); the re-check's survivors beyond are only counted. */
internal const val DIGEST_MAX_NOTICES_PER_RECIPIENT = 50

/**
 * Rows ONE scan of the due window reads at most: the claim's `eligible` set and the settle's scan are each `ORDER BY created_at LIMIT`
 * this (oldest first, off `idx_notifications_email_due`). It bounds the work a tick does however many notices are due. **The trade-off is
 * fairness**: a person whose notices all lie beyond the oldest [DIGEST_SCAN_CAP] due ones waits for a later tick (the head of the window
 * leaves the index as it is mailed or settled).
 */
internal const val DIGEST_SCAN_CAP = 5_000

/** Ids one re-check statement carries at most (a claim is bounded by [DIGEST_SCAN_CAP]; more are chunked). */
private const val RECHECK_CHUNK = 1_000

/** The claim's and the settle's statement budget, whole seconds (the transaction's `queryTimeout`, the only way a timeout is set). */
internal const val DIGEST_STATEMENT_TIMEOUT_SECONDS = 10

/** People a claim takes beyond the run's send budget (at least this, or a fifth of it): the margin for those skipped at send time. */
internal const val DIGEST_MIN_SLACK = 10

/**
 * People one claim takes: the send budget plus a margin (people skipped at send time do not use a send), never more than [batchSize].
 * A claim of 5x what a run may send would stamp the rest only to release it again.
 */
internal fun peopleToClaim(batchSize: Int, maxPerRun: Int): Int = minOf(batchSize, maxPerRun + maxOf(DIGEST_MIN_SLACK, maxPerRun / 5))

/** Mails one run sends at most when `notifications.email.maxPerRun` is not given (`security.md`: the digest shares the `Mailer`). */
internal const val DIGEST_MAX_PER_RUN = 100

/**
 * People remembered as "mailed within the interval". A run that finds the memory this full claims nothing and says so (fail closed: a
 * process that cannot remember whom it mailed must not mail again).
 */
internal const val DIGEST_MAX_REMEMBERED = 50_000

/**
 * Failures in a row (a failed re-check or send) that end a run: with SMTP or the database down every further attempt is a certain failure
 * and would hold the sweep's tick (and the purge behind it) for as long as the timeouts add up. The untried claims are released.
 */
internal const val DIGEST_MAX_CONSECUTIVE_FAILURES = 3

/** The bounds of the claim's statements: rows one scan reads ([DIGEST_SCAN_CAP]) and the statement budget in seconds. */
internal data class DigestBounds(
    val scanCap: Int = DIGEST_SCAN_CAP,
    val statementTimeoutSeconds: Int = DIGEST_STATEMENT_TIMEOUT_SECONDS,
)

/** What one run did, in counts (a test seam and the log line). */
internal data class DigestRun(val claimed: Int, val sent: Int, val failed: Int, val released: Int = 0)

/**
 * The email digest of unseen notices (`adr-model.md` "Notifications", `security.md` "Outbound email"). A run (the sweep's tick):
 *
 * 1. **Settles and claims**, one short transaction under a [DIGEST_STATEMENT_TIMEOUT_SECONDS] statement budget ([SETTLE], then
 *    [CLAIM]; both read at most `scanCap` rows): notices of a switched-off kind or a gone account are settled; then the notices still
 *    unseen after [delayMillis] of at most [peopleToClaim] PEOPLE (oldest waiting first; ALL of a person's due notices within the scan,
 *    the first [DIGEST_MAX_NOTICES_PER_RECIPIENT] listed and the rest counted, so a flooded person gets one mail, not one a tick) are
 *    marked BEFORE any send, so a notice is mailed at most once and a failed send is not retried (it is audited). A statement past its
 *    budget is logged scrubbed and the run ends with nothing claimed (the transaction rolled back).
 * 2. **Sends**, outside every transaction, one email per recipient, at most [maxPerRun] mails a run (the budget the MFA and password-reset
 *    mails are protected by: they share the `Mailer`; the claims beyond it are released). Right before each send ALL of the recipient's
 *    claimed notices are RE-CHECKED in one short read transaction: the account is active, the notice still exists unseen the
 *    ADR still exists and the kind is not switched off. The survivors are listed (the first [DIGEST_MAX_NOTICES_PER_RECIPIENT]) and
 *    counted (the rest). With none the recipient is skipped silently, stays claimed (never mailed later) and claims no slot at the next
 *    tick. No pooled connection is held across the send.
 * 3. **Audits** `notification.email_sent` (userId, count) or `notification.email_failed` (userId, errorType: the class name only), for a
 *    failed re-check too: the recipient was attempted and is not retried.
 * 4. **Releases** the claims of recipients that were never ATTEMPTED when the run ends early (cancelled, or
 *    [DIGEST_MAX_CONSECUTIVE_FAILURES] failures in a row): `emailed_at` goes back to null in a `NonCancellable` finally, so a stop or an
 *    outage does not silently settle notices nobody tried to mail. That is not a retry: nothing was sent. What can still be lost is a
 *    recipient attempted when the process dies or is cancelled MID-SEND (the send may or may not have left): at most once is the rule.
 *    **A hard kill (SIGKILL, power loss) runs no `finally`, so it loses EVERY claimed recipient not yet released** - the attempted one
 *    and all the unattempted ones stay stamped and are never mailed (at most once, not at least once); only a cancellation or an early
 *    end releases.
 *
 * "Mailed within the interval" ([minIntervalMillis]) lives in memory, per process, like the login throttle: a restart forgets it (at worst
 * one early email), and a person inside it is left unclaimed. A null [mailer] (mail disabled) does nothing and says so once. The text is
 * [notificationEmail]'s: who, what, the ADR's key and a link, never an ADR name or text.
 */
internal class NotificationDigest(
    private val database: R2dbcDatabase,
    private val mailer: Mailer?,
    private val appUrl: String?,
    private val delayMillis: Long,
    private val minIntervalMillis: Long,
    private val clock: () -> Long = ::nowMillis,
    /** The most people per claim ([DIGEST_BATCH]); the claim takes [peopleToClaim] of them. */
    private val batchSize: Int = DIGEST_BATCH,
    /** Mails per run ([DIGEST_MAX_PER_RUN]; `notifications.email.maxPerRun`). */
    private val maxPerRun: Int = DIGEST_MAX_PER_RUN,
    /** The claim's bounds ([DigestBounds]); a TEST seam. */
    private val bounds: DigestBounds = DigestBounds(),
    /** A TEST seam: runs after a non-empty claim and before the first send (the gap the send-time re-check exists for). */
    private val afterClaim: suspend () -> Unit = {},
) {
    private val lock = Mutex()
    private val mailedAt = HashMap<UInt, Long>()
    private var disabledLogged = false

    /** One run. Answers its counts; an empty run is `DigestRun(0, 0, 0)`. */
    suspend fun run(): DigestRun {
        val mailer = this.mailer
        if (mailer == null) {
            if (!disabledLogged) {
                disabledLogged = true
                log.info("notification email digest is off: this deployment has no outbound email (mail.transport=disabled)")
            }
            return DigestRun(0, 0, 0)
        }
        return lock.withLock { runLocked(mailer) }
    }

    private suspend fun runLocked(mailer: Mailer): DigestRun {
        val now = clock()
        mailedAt.values.removeAll { now - it >= minIntervalMillis }
        if (mailedAt.size >= DIGEST_MAX_REMEMBERED) {
            log.warn("notification email digest paused: {} recipients are inside the mail interval", mailedAt.size)
            return DigestRun(0, 0, 0)
        }
        val claimed = try {
            claim(now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A statement past its budget (SQLSTATE 57014) or a failing database: the transaction rolled back, so nothing is claimed.
            log.warn("notification email digest could not claim: {}", e.scrubbedSummary())
            return DigestRun(0, 0, 0)
        }
        if (claimed.isEmpty()) return DigestRun(0, 0, 0)
        // The recipients not yet attempted, in claim order. One leaves the queue as its send starts (before that, a stop releases it).
        val queue = ArrayDeque(claimed.groupBy { it.recipientId }.entries.toList())
        var sent = 0
        var failed = 0
        var inARow = 0
        var released = 0
        try {
            afterClaim()
            while (queue.isNotEmpty() && inARow < DIGEST_MAX_CONSECUTIVE_FAILURES && sent + failed < maxPerRun) {
                val (recipientId, notices) = queue.first()
                val leave = { if (queue.firstOrNull()?.key == recipientId) queue.removeFirst() else null }
                val outcome = deliver(mailer, recipientId, notices, now) { leave() }
                when (outcome) {
                    Delivery.SENT -> { sent++; inARow = 0 }
                    Delivery.FAILED -> { failed++; inARow++ }
                    Delivery.SKIPPED -> Unit
                }
            }
        } finally {
            if (queue.isNotEmpty()) released = release(queue.flatMap { it.value }.map { it.id }, now)
            log.info(
                "notification email digest: {} notice(s) claimed, {} email(s) sent, {} failed, {} released",
                claimed.size, sent, failed, released,
            )
        }
        return DigestRun(claimed.size, sent, failed, released)
    }

    private enum class Delivery { SENT, FAILED, SKIPPED }

    /**
     * Re-checks, then sends, one recipient. [attempted] runs right before the send (the recipient stops being releasable). A failure of
     * either step is audited with the class name only and is not retried; cancellation passes through.
     */
    private suspend fun deliver(
        mailer: Mailer,
        recipientId: UInt,
        notices: List<ClaimedNotice>,
        now: Long,
        attempted: () -> Unit,
    ): Delivery {
        try {
            val mail = resolve(recipientId, notices)
            attempted()
            mailedAt[recipientId] = now // a skipped person too: nothing to mail now, no slot in the next claim for one interval
            if (mail == null) return Delivery.SKIPPED
            mailer.send(mail.email, mail.subject, mail.body)
            audit("notification.email_sent", "userId" to recipientId.toLong(), "count" to mail.count)
            return Delivery.SENT
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            attempted() // a failed re-check or send is audited and not retried: the recipient leaves the queue
            // The class name only: a mail provider's exception may embed the message it was sending.
            audit("notification.email_failed", "userId" to recipientId.toLong(), "errorType" to e.javaClass.simpleName)
            log.warn("notification email failed ({})", e.javaClass.simpleName)
            return Delivery.FAILED
        }
    }

    /**
     * Gives back the claims of [ids] (stamped [stamp] by this run) in a `NonCancellable` context, so a cancelled run still releases.
     * Answers how many went back; a failure here is logged (scrubbed) and the rows stay settled (the cost is an unmailed digest).
     */
    private suspend fun release(ids: List<UInt>, stamp: Long): Int = withContext(NonCancellable) {
        try {
            suspendTransaction(database) {
                val n = NotificationService.Notifications
                queryTimeout = DIGEST_STATEMENT_TIMEOUT_SECONDS
                n.update({ (n.id inList ids) and (n.emailedAt eq stamp) }) { it[emailedAt] = null }
            }
        } catch (e: Exception) {
            log.warn("notification email digest could not release {} claim(s): {}", ids.size, e.scrubbedSummary())
            0
        }
    }

    private suspend fun claim(now: Long): List<ClaimedNotice> = suspendTransaction(database) {
        queryTimeout = bounds.statementTimeoutSeconds
        val cutoff = LongColumnType() to now - delayMillis
        val floor = LongColumnType() to now - MILLIS_PER_DAY
        val settled = TransactionManager.current().exec(
            SETTLE,
            listOf(cutoff, floor, IntegerColumnType() to bounds.scanCap, LongColumnType() to now),
            explicitStatementType = StatementType.SELECT,
        ) { row -> row.get(0, Int::class.javaObjectType) }?.toList().orEmpty().size
        if (settled > 0) log.info("notification email digest: {} notice(s) of a switched-off kind or a gone account settled", settled)
        val arguments = listOf(
            cutoff,
            floor,
            ArrayColumnType<Long, List<Long>>(LongColumnType()) to mailedAt.keys.map { it.toLong() },
            IntegerColumnType() to bounds.scanCap,
            IntegerColumnType() to peopleToClaim(batchSize, maxPerRun),
            LongColumnType() to now,
        )
        TransactionManager.current().exec(CLAIM, arguments, explicitStatementType = StatementType.SELECT) { row ->
            val type = NotificationType.entries.firstOrNull { it.name == row.get(4, String::class.java) }
            type?.let {
                ClaimedNotice(
                    id = row.get(0, Int::class.javaObjectType)!!.toUInt(),
                    recipientId = row.get(1, Long::class.javaObjectType)!!.toUInt(),
                    adrId = row.get(2, Int::class.javaObjectType)!!.toUInt(),
                    otherAdrId = row.get(3, Int::class.javaObjectType)?.toUInt(),
                    type = it,
                    actorId = row.get(5, Long::class.javaObjectType)?.toUInt(),
                    detail = row.get(6, String::class.java),
                    rank = row.get(7, Long::class.javaObjectType)!!,
                )
            }
        }?.toList().orEmpty().filterNotNull().sortedBy { it.id }
    }

    /**
     * The send-time re-check and the current names, in ONE read transaction; null when nothing is left to mail. The re-check (unseen, the
     * ADR still there, the kind on) runs on ALL of the person's [claimed] notices: the first [DIGEST_MAX_NOTICES_PER_RECIPIENT]
     * survivors by rank become entries, the surviving rest is counted (dead notices neither hide live ones nor inflate it). The names and
     * keys are read now (a rename shows), for the entries only.
     */
    private suspend fun resolve(recipientId: UInt, claimed: List<ClaimedNotice>): Mail? = suspendTransaction(database) {
        val users = UserService.Users
        val user = users.select(users.name, users.email, users.language)
            .where { (users.id eq recipientId) and users.active() }.toList().singleOrNull()
            ?: return@suspendTransaction null
        val n = NotificationService.Notifications
        val pending = claimed.map { it.id }.chunked(RECHECK_CHUNK).flatMap { chunk ->
            n.select(n.id).where { (n.id inList chunk) and n.seenAt.isNull() }.map { it[n.id].value }.toList()
        }.toSet()
        val off = emailDisabledKinds(recipientId)
        val kept = claimed.filter { it.id in pending && it.type !in off }
        val keys = adrKeys(kept.flatMap { listOfNotNull(it.adrId, it.otherAdrId) }.toSet())
        // A notice whose second ADR is gone too (a soft-deleted successor) is dropped: its sentence would name nothing ("superseded by ").
        val survivors = kept.filter { it.adrId in keys && (it.otherAdrId == null || it.otherAdrId in keys) }.sortedBy { it.rank }
        if (survivors.isEmpty()) return@suspendTransaction null
        val listed = survivors.take(DIGEST_MAX_NOTICES_PER_RECIPIENT)
        val actors = actorNames(listed.mapNotNull { it.actorId }.toSet())
        val entries = listed.map {
            DigestEntry(it.type, it.actorId?.let(actors::get), it.adrId, keys.getValue(it.adrId), it.otherAdrId?.let(keys::get), it.detail)
        }
        val email = notificationEmail(user[users.name], entries, appUrl, user[users.language], survivors.size - listed.size)
        Mail(user[users.email], email.subject, email.body, survivors.size)
    }

    /** `ADR-0042` by id for the ADRs that still exist (a soft-deleted one is absent: its notices are not mailed). */
    private suspend fun adrKeys(ids: Set<UInt>): Map<UInt, String> = ids.chunked(RECHECK_CHUNK).flatMap { chunk ->
        val adrs = AdrService.Adrs
        adrs.select(adrs.id, adrs.number).where { (adrs.id inList chunk) and adrs.active() }
            .map { it[adrs.id].value to adrKey(it[adrs.number]) }.toList()
    }.toMap()

    private suspend fun actorNames(ids: Set<UInt>): Map<UInt, String> {
        if (ids.isEmpty()) return emptyMap()
        val users = UserService.Users
        return users.select(users.id, users.name).where { users.id inList ids }.map { it[users.id].value to it[users.name] }.toList()
            .toMap()
    }

    /** What one person is mailed, resolved at send time. */
    private class Mail(val email: String, val subject: String, val body: String, val count: Int)
}
