package ch.nokillswit

import ch.nokillswit.infra.mail.Mailer
import ch.nokillswit.notifications.DIGEST_BATCH
import ch.nokillswit.notifications.DigestBounds
import ch.nokillswit.notifications.NotificationDigest
import ch.nokillswit.notifications.NotificationPageResponse
import ch.nokillswit.notifications.NotificationResponse
import ch.nokillswit.notifications.NotificationService
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/** The caller's notifications page (asserting 200). */
suspend fun HttpClient.notices(query: String = ""): NotificationPageResponse {
    val response = get("/api/v1/notifications$query")
    assertEquals(HttpStatusCode.OK, response.status, "list notifications: ${response.status}")
    return response.body()
}

/** The caller's notices of [type] on [adrId], newest first. */
suspend fun HttpClient.noticesOf(type: NotificationType, adrId: UInt? = null): List<NotificationResponse> =
    notices("?pageSize=100").items.filter { it.type == type && (adrId == null || it.adrId == adrId) }

suspend fun HttpClient.markSeen(id: UInt): HttpResponse = post("/api/v1/notifications/$id/seen")

suspend fun HttpClient.markAllSeen(): HttpResponse = post("/api/v1/notifications/seen-all")

/** The stored notice rows of [recipientId], oldest first, with the columns the HTTP list hides (`emailed_at`). */
data class RawNotice(val id: UInt, val type: String, val actorId: UInt?, val adrId: UInt, val otherAdrId: UInt?, val detail: String?,
    val seenAt: Long?, val emailedAt: Long?, val createdAt: Long)

suspend fun rawNotices(recipientId: UInt): List<RawNotice> = suspendTransaction(sharedDatabaseForTests()) {
    TransactionManager.current().exec(
        "SELECT id, notification_type, actor_id, adr_id, other_adr_id, detail, seen_at, emailed_at, created_at FROM notifications " +
            "WHERE recipient_id = CAST(? AS int8) ORDER BY id",
        listOf(LongColumnType() to recipientId.toLong()),
        explicitStatementType = org.jetbrains.exposed.v1.core.statements.StatementType.SELECT,
    ) { row ->
        RawNotice(
            id = row.get(0, Int::class.javaObjectType)!!.toUInt(),
            type = row.get(1, String::class.java)!!,
            actorId = row.get(2, Long::class.javaObjectType)?.toUInt(),
            adrId = row.get(3, Int::class.javaObjectType)!!.toUInt(),
            otherAdrId = row.get(4, Int::class.javaObjectType)?.toUInt(),
            detail = row.get(5, String::class.java),
            seenAt = row.get(6, Long::class.javaObjectType),
            emailedAt = row.get(7, Long::class.javaObjectType),
            createdAt = row.get(8, Long::class.javaObjectType)!!,
        )
    }?.toList().orEmpty().filterNotNull()
}

/** Inserts [count] unseen notices for [recipientId] about [adrId], [ageMillis] old each (one statement), and returns nothing. */
suspend fun insertNotices(recipientId: UInt, adrId: UInt, count: Int, type: NotificationType = NotificationType.STATUS_CHANGED,
    ageMillis: Long = 0) {
    suspendTransaction(sharedDatabaseForTests()) {
        TransactionManager.current().exec(
            "INSERT INTO notifications (recipient_id, notification_type, adr_id, detail, created_at) " +
                "SELECT CAST(? AS int8), CAST(? AS varchar), CAST(? AS int4), 'ACTIVE', CAST(? AS int8) - g " +
                "FROM generate_series(1, CAST(? AS int4)) g",
            listOf(
                LongColumnType() to recipientId.toLong(),
                TextColumnType() to type.name,
                IntegerColumnType() to adrId.toInt(),
                LongColumnType() to (System.currentTimeMillis() - ageMillis),
                IntegerColumnType() to count,
            ),
        )
    }
}

/**
 * Settles every notice that is still waiting for the digest (`emailed_at = created_at`, V17's "settled, never mailed"): the digest tests
 * start from an empty queue, because the claim takes the OLDEST waiting people first and the shared database holds every earlier test's.
 */
suspend fun settleNoticeBacklog() {
    suspendTransaction(sharedDatabaseForTests()) {
        TransactionManager.current().exec("UPDATE notifications SET emailed_at = created_at WHERE emailed_at IS NULL")
    }
}

/** A service over the shared database, for the tests that build the history service or the sweep by hand. */
internal fun newNotificationService(): NotificationService = NotificationService(sharedDatabaseForTests())

/** The head of an insert into the preferences table; the caller appends the VALUES tuples. */
const val PREFS_INSERT = "INSERT INTO user_notification_preferences (user_id, notification_type, channel) VALUES "

/** Runs one raw statement in its own transaction on the shared database (a fixture row the API cannot make). */
suspend fun execRaw(sql: String) {
    suspendTransaction(sharedDatabaseForTests()) { TransactionManager.current().exec(sql) }
}

/**
 * A [Mailer] that records what it sends. [failing] addresses are refused with an error whose message carries a secret (the digest must
 * never let it reach the trail); [failWith] refuses everything with that exception. [attempts] counts every call, sent or refused.
 */
class RecordingMailer(private val failing: Set<String> = emptySet(), private val failWith: Exception? = null) : Mailer {
    class Mail(val to: String, val subject: String, val body: String)

    val sent = CopyOnWriteArrayList<Mail>()
    private val tried = AtomicInteger()
    val attempts: Int get() = tried.get()

    override suspend fun send(to: String, subject: String, body: String) {
        tried.incrementAndGet()
        failWith?.let { throw it }
        if (to in failing) error("smtp refused $to: a secret body")
        sent += Mail(to, subject, body)
    }

    fun to(email: String): List<Mail> = sent.filter { it.to == email }
}

/** A seeded person the digest tests mail (never the actor of anything). */
class MailRecipient(val id: UInt, val email: String)

suspend fun mailRecipient(prefix: String, language: String = "en"): MailRecipient {
    val email = uniqueEmail(prefix)
    val id = TestUsers.seed(email = email, password = "pw", name = prefix.replaceFirstChar { it.uppercase() }, role = UserRole.USER,
        language = language)
    return MailRecipient(id, email)
}

/** The public origin the digest tests build links from. */
const val DIGEST_TEST_ORIGIN = "https://h.example"

/** A digest over the shared database with the test knobs (no delay and no spacing unless given). */
internal fun digestOf(
    mailer: Mailer?,
    delay: Long = 0,
    interval: Long = 0,
    maxPerRun: Int = 100,
    batchSize: Int = DIGEST_BATCH,
    appUrl: String? = DIGEST_TEST_ORIGIN,
    bounds: DigestBounds = DigestBounds(),
    clock: () -> Long = System::currentTimeMillis,
    afterClaim: suspend () -> Unit = {},
) = NotificationDigest(
    database = sharedDatabaseForTests(),
    mailer = mailer,
    appUrl = appUrl,
    delayMillis = delay,
    minIntervalMillis = interval,
    clock = clock,
    batchSize = batchSize,
    maxPerRun = maxPerRun,
    bounds = bounds,
    afterClaim = afterClaim,
)
