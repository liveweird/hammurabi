package ch.nokillswit.notifications

import ch.nokillswit.adrs.AdrEventDescriptor
import ch.nokillswit.adrs.AdrEventType
import ch.nokillswit.adrs.AdrRole

/** Who a notice reaches, resolved INSIDE the write's transaction ([NotificationService.audienceOf]). */
internal sealed interface Audience {
    /** One named person (the participant a role was given to or taken from). */
    data class Person(val userId: UInt) : Audience

    /** The holders of [roles] on [adrId] as the participants stand when the event is recorded. */
    data class Roles(val adrId: UInt, val roles: Set<AdrRole>) : Audience

    /** The author of a discussion entry. */
    data class EntryAuthor(val entryId: UInt) : Audience
}

/**
 * One notice to make: the [type], the ADR it is about (where it links), the optional second ADR and short [detail], and its [audience].
 * The acting user is taken out of the audience by the caller (an actor never notifies themself).
 */
internal data class NoticePlan(
    val type: NotificationType,
    val adrId: UInt,
    val audience: Audience,
    val otherAdrId: UInt? = null,
    val detail: String? = null,
)

private val EVERY_ROLE: Set<AdrRole> = AdrRole.entries.toSet()
/** The Driver and the Approver: who the discussion, successor and reminder notices reach. */
internal val LEADS: Set<AdrRole> = setOf(AdrRole.DRIVER, AdrRole.APPROVER)
private val LEADS_AND_CONTRIBUTORS: Set<AdrRole> = LEADS + AdrRole.CONTRIBUTOR

/**
 * The notice a history event of [adrId] causes, or null when none follows (`adr-model.md` "Notifications": every notice derives from an
 * event of its own transaction; the reminder alone has none). Pure: the event's params in, a plan out, so a malformed param is a null,
 * never an exception inside someone else's write.
 */
internal fun noticeFor(adrId: UInt, event: AdrEventDescriptor): NoticePlan? {
    val params = event.params
    fun id(key: String): UInt? = params[key]?.toUIntOrNull()
    fun person(type: NotificationType, role: String?): NoticePlan? =
        id("userId")?.let { NoticePlan(type, adrId, Audience.Person(it), detail = role) }
    return when (event.type) {
        AdrEventType.PARTICIPANT_ADDED -> person(NotificationType.ROLE_ASSIGNED, params["role"])
        AdrEventType.PARTICIPANT_CHANGED -> person(NotificationType.ROLE_ASSIGNED, params["to"])
        AdrEventType.PARTICIPANT_REMOVED -> person(NotificationType.ROLE_REMOVED, params["role"])
        AdrEventType.TRANSITIONED ->
            NoticePlan(NotificationType.STATUS_CHANGED, adrId, Audience.Roles(adrId, EVERY_ROLE), detail = params["to"])
        AdrEventType.EXPIRED ->
            NoticePlan(NotificationType.ADR_EXPIRED, adrId, Audience.Roles(adrId, EVERY_ROLE), detail = params["expiresOn"])
        AdrEventType.SUPERSEDED -> id("successorId")?.let {
            NoticePlan(NotificationType.ADR_SUPERSEDED, adrId, Audience.Roles(adrId, EVERY_ROLE), otherAdrId = it)
        }
        // Written on the SUCCESSOR (`adrId`); the notice is the PREDECESSOR's, whose Driver and Approver learn of it. The detach's second
        // event, on the predecessor, carries `successorId` instead and is the same fact: it makes no second notice.
        AdrEventType.SUCCESSOR_LINKED -> successorNotice(NotificationType.SUCCESSOR_LINKED, adrId, id("predecessorId"))
        AdrEventType.SUCCESSOR_UNLINKED -> successorNotice(NotificationType.SUCCESSOR_UNLINKED, adrId, id("predecessorId"))
        AdrEventType.COMMENTED, AdrEventType.QUESTION_ASKED, AdrEventType.ISSUE_RAISED, AdrEventType.ESCALATED -> {
            val kind = params["kind"]
            val reach = if (kind == "COMMENT" || kind == "QUESTION") LEADS_AND_CONTRIBUTORS else LEADS
            NoticePlan(NotificationType.DISCUSSION_POSTED, adrId, Audience.Roles(adrId, reach), detail = kind)
        }
        AdrEventType.QUESTION_ANSWERED, AdrEventType.ISSUE_RESOLVED -> id("entryId")?.let {
            NoticePlan(NotificationType.DISCUSSION_SETTLED, adrId, Audience.EntryAuthor(it), detail = params["kind"])
        }
        else -> null
    }
}

private fun successorNotice(type: NotificationType, successorId: UInt, predecessorId: UInt?): NoticePlan? =
    predecessorId?.let { NoticePlan(type, it, Audience.Roles(it, LEADS), otherAdrId = successorId) }
