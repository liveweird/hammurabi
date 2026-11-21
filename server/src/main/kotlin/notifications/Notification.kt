package ch.nokillswit.notifications

import ch.nokillswit.infra.paging.PageResponse
import kotlinx.serialization.Serializable

/**
 * The kinds of notice, PERSISTED by name and frozen (`notifications.notification_type` has no CHECK: this enum is the whitelist, the V5
 * rule). A stored name this build does not know is invisible to every read, never fatal. A new kind is a new constant; none is renamed.
 * `adr-model.md` "Notifications" says who each one reaches and what `detail` holds.
 */
@Serializable
enum class NotificationType {
    /** The recipient was given a DACI role (a new participant, or a changed role); `detail` is the role. */
    ROLE_ASSIGNED,

    /** The recipient was removed from the ADR; `detail` is the role they held. */
    ROLE_REMOVED,

    /** An ADR the recipient takes part in (Informed too) moved to another status; `detail` is the new status. */
    STATUS_CHANGED,

    /**
     * A comment, question, issue or escalation on an ADR the recipient drives or approves (Contributors too for comments and
     * questions); `detail` is the kind.
     */
    DISCUSSION_POSTED,

    /** The recipient's question was answered, or their issue or escalation resolved; `detail` is the kind. */
    DISCUSSION_SETTLED,

    /** An ADR the recipient takes part in expired; `detail` is the expiry date. */
    ADR_EXPIRED,

    /** An ADR the recipient takes part in was superseded; `otherAdr` is the successor. */
    ADR_SUPERSEDED,

    /** A pending successor was linked to the recipient's ADR (the predecessor); `otherAdr` is the successor. */
    SUCCESSOR_LINKED,

    /** A pending successor was detached from (or dropped its link to) the recipient's ADR; `otherAdr` is the successor. */
    SUCCESSOR_UNLINKED,

    /** The recipient's ADR expires soon (`adrs.expiry.reminderDays`); `detail` is the expiry date. A system notice. */
    EXPIRY_REMINDER,
}

/**
 * One notice of the caller, for the bell. The row holds ids only: the actor's name and the ADR's key and name are read when the page is
 * read, so a rename shows at once and nothing stored can outlive a delete (`adr-model.md` "Notifications"). A null [actorId] is the
 * system. [detail] is the one short enum-valued string the kind needs (a role, a status, an entry kind or a date), [otherAdrId] the second
 * ADR of a successor notice.
 */
@Serializable
data class NotificationResponse(
    val id: UInt,
    val type: NotificationType,
    val seen: Boolean,
    val createdAt: Long,
    val actorId: UInt?,
    val actorName: String?,
    val adrId: UInt,
    val adrKey: String,
    val adrName: String,
    val otherAdrId: UInt?,
    val otherAdrKey: String?,
    val detail: String?,
)

typealias NotificationPageResponse = PageResponse<NotificationResponse>

/** One page of the caller's notices plus the (capped) pre-pagination total (the list-endpoint envelope). */
data class NotificationListResult(val items: List<NotificationResponse>, val total: Long)
