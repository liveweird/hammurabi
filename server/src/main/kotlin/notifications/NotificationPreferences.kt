package ch.nokillswit.notifications

import ch.nokillswit.infra.db.active
import ch.nokillswit.users.UserService
import io.ktor.server.plugins.BadRequestException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * The channels a notice can reach a person through, PERSISTED by name and frozen (`user_notification_preferences.channel` has no CHECK:
 * this enum is the whitelist, the V5 rule). `IN_APP` is the bell (a switched-off kind makes no notice for the person), `EMAIL` the digest
 * (a switched-off kind is never mailed; the bell is unchanged). A stored name this build does not know is dropped by every reader.
 */
@Serializable
enum class NotificationChannel { IN_APP, EMAIL }

/** One DISABLED `(kind, channel)` pair: the stored shape, and one element of the PUT body. */
@Serializable
data class DisabledNotificationPreference(val type: NotificationType, val channel: NotificationChannel)

/** One kind in `GET /users/{id}/notification-preferences`: whether its bell notice and its digest email are on for the person. */
@Serializable
data class NotificationPreferenceKind(val type: NotificationType, val inApp: Boolean, val email: Boolean)

/** Body of `GET /users/{id}/notification-preferences`. [emailAvailable] is false with no mailer (the email switches then do nothing). */
@Serializable
data class NotificationPreferencesResponse(val emailAvailable: Boolean, val kinds: List<NotificationPreferenceKind>)

/** Body of `PUT /users/{id}/notification-preferences`: a wholesale replace of the person's disabled set. */
@Serializable
data class NotificationPreferencesUpdateRequest(val disabled: List<DisabledNotificationPreference>)

/** Every `(kind, channel)` pair there is: a longer list repeats one (and is refused as that). The spec's `maxItems` on the PUT body. */
internal val MAX_DISABLED_PAIRS = NotificationType.entries.size * NotificationChannel.entries.size

/**
 * The PUT body's rules, run by the route AND the service: no pair twice (a longer list than there are pairs is a repeat by the pigeonhole
 * rule, checked first so an oversized body is refused before it is hashed). Answers the set; a violation is a `400`. An unknown kind or
 * channel never gets here: it fails to decode.
 */
internal fun validateNotificationPreferences(requested: List<DisabledNotificationPreference>): Set<DisabledNotificationPreference> {
    if (requested.size > MAX_DISABLED_PAIRS) throw BadRequestException("A notification preference is listed more than once")
    val wanted = requested.toSet()
    if (wanted.size != requested.size) throw BadRequestException("A notification preference is listed more than once")
    return wanted
}

/** Every kind with its two switches, in enum order: a kind with no stored row is on. */
internal fun preferencesResponse(disabled: Set<DisabledNotificationPreference>, emailAvailable: Boolean): NotificationPreferencesResponse =
    NotificationPreferencesResponse(
        emailAvailable,
        NotificationType.entries.map {
            NotificationPreferenceKind(
                it,
                inApp = DisabledNotificationPreference(it, NotificationChannel.IN_APP) !in disabled,
                email = DisabledNotificationPreference(it, NotificationChannel.EMAIL) !in disabled,
            )
        },
    )

/** Audit format of a disabled set: sorted `TYPE:CHANNEL` pairs, comma-joined, `""` for none. */
internal fun Set<DisabledNotificationPreference>.joinedForAudit(): String =
    map { "${it.type.name}:${it.channel.name}" }.sorted().joinToString(",")

/**
 * Per-user notification preferences (V17; Lettuce's V84 model): the table stores the DISABLED `(kind, channel)` pairs, so an empty set
 * means everything is on and a new kind starts on for everybody. A row whose name this build does not know is dropped by every reader (the
 * open-set rule). The in-app switch is read when a notice is MADE (the insert's own predicate, `NotificationService`), the email switch by
 * the digest at send time (`NotificationDigest`), so a switch takes effect for notices not yet made or mailed.
 */
internal class NotificationPreferenceService(private val database: R2dbcDatabase) {
    object Preferences : Table("user_notification_preferences") {
        val userId = reference("user_id", UserService.Users)
        val notificationType = varchar("notification_type", length = 40)
        val channel = varchar("channel", length = 10)
        override val primaryKey = PrimaryKey(userId, notificationType, channel)
    }

    /** The person's disabled set, or null when the id is unknown or soft-deleted (the route's `404`). */
    suspend fun read(userId: UInt): Set<DisabledNotificationPreference>? = suspendTransaction(database) {
        val users = UserService.Users
        if (users.select(users.id).where { (users.id eq userId) and users.active() }.toList().isEmpty()) return@suspendTransaction null
        disabledInTransaction(userId)
    }

    /**
     * Wholesale-replaces the person's disabled set and answers the set that was in place immediately before, or null when the id is
     * unknown or soft-deleted (the route's `404`). The user row is locked `FOR NO KEY UPDATE` for the read-then-write, so two replaces
     * serialize (the second reads the first's committed set: last writer wins, and the audited `from` is true) instead of
     * interleaving into a union. A same-set PUT is a no-op replace.
     */
    suspend fun replace(userId: UInt, requested: List<DisabledNotificationPreference>): Set<DisabledNotificationPreference>? {
        val disabled = validateNotificationPreferences(requested)
        return suspendTransaction(database) {
            val users = UserService.Users
            val exists = users.select(users.id).where { (users.id eq userId) and users.active() }
                .forUpdate(ForUpdateOption.PostgreSQL.ForNoKeyUpdate).toList().isNotEmpty()
            if (!exists) return@suspendTransaction null
            val previous = disabledInTransaction(userId)
            Preferences.deleteWhere { Preferences.userId eq userId }
            disabled.forEach { pair ->
                Preferences.insert {
                    it[Preferences.userId] = userId
                    it[notificationType] = pair.type.name
                    it[channel] = pair.channel.name
                }
            }
            previous
        }
    }

    private suspend fun disabledInTransaction(userId: UInt): Set<DisabledNotificationPreference> {
        val p = Preferences
        val known = NotificationType.entries.map { it.name }
        return p.selectAll().where { (p.userId eq userId) and (p.notificationType inList known) }
            .map { it[p.notificationType] to it[p.channel] }.toList()
            .mapNotNull { (type, channel) ->
                NotificationChannel.entries.firstOrNull { it.name == channel }
                    ?.let { DisabledNotificationPreference(NotificationType.valueOf(type), it) }
            }.toSet()
    }
}

/**
 * The kinds [userId] switched off for email, read INSIDE the caller's transaction (the digest's per-recipient read, one statement, no
 * second transaction).
 */
internal suspend fun emailDisabledKinds(userId: UInt): Set<NotificationType> {
    val p = NotificationPreferenceService.Preferences
    val known = NotificationType.entries.map { it.name }
    return p.select(p.notificationType)
        .where { (p.userId eq userId) and (p.channel eq NotificationChannel.EMAIL.name) and (p.notificationType inList known) }
        .map { NotificationType.valueOf(it[p.notificationType]) }.toList().toSet()
}
