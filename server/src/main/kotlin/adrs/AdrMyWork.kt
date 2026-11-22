package ch.nokillswit.adrs

import kotlinx.serialization.Serializable

/** The fixed number of rows each part of the personal work view may return. */
const val MY_WORK_SECTION_CAP = 12

/** Why an ADR appears in the action or attention queue. */
@Serializable
enum class AdrMyWorkReason { REVIEW, EXPIRING, STALLED_DRAFT, STALLED_REVIEW, RETURNED, ESCALATION }

/** One of the caller's ADRs, carrying their role and the latest meaningful event time used to order the personal list. */
@Serializable
data class AdrMyWorkAdr(
    val id: UInt,
    val key: String,
    val name: String,
    val role: AdrRole,
    val status: AdrStatus,
    val lastActivityAt: Long,
    val expiresOn: String?,
)

@Serializable
data class AdrMyWorkAttention(
    val adr: AdrMyWorkAdr,
    val reason: AdrMyWorkReason,
    /** The relevant event time: submission, return, escalation or last meaningful drafting progress. */
    val since: Long,
    /** The active Approver, when there is one; useful when another role is watching a decision. */
    val approverName: String?,
)

@Serializable
data class AdrMyWorkActivity(val adr: AdrMyWorkAdr, val event: AdrEventResponse)

@Serializable
data class AdrMyWorkAttentionSection(val items: List<AdrMyWorkAttention>, val hasMore: Boolean)

@Serializable
data class AdrMyWorkActivitySection(val items: List<AdrMyWorkActivity>, val hasMore: Boolean)

@Serializable
data class AdrMyWorkAdrSection(val items: List<AdrMyWorkAdr>, val hasMore: Boolean)

@Serializable
data class AdrMyWorkResponse(
    val generatedAt: Long,
    val actions: AdrMyWorkAttentionSection,
    val watching: AdrMyWorkAttentionSection,
    val activity: AdrMyWorkActivitySection,
    val adrs: AdrMyWorkAdrSection,
)
