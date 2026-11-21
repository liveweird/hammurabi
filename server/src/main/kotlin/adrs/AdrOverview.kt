package ch.nokillswit.adrs

import kotlinx.serialization.Serializable

/**
 * The decision log's caps (0.9.0, `adr-model.md` "Overview", `list-endpoints.md`): the overview is the one purpose-built read outside the
 * paged envelope, so every section is bounded by one of these and carries the count it left out (`more`). Constants, not configuration:
 * they are the page's layout, not a deployment knob (only the expiry horizon is: `adrs.overview.expiringSoonDays`).
 */
const val OVERVIEW_PER_TAG = 20

/** The most ADRs "recently decided" lists. */
const val OVERVIEW_RECENT = 10

/** The most ADRs "expiring soon" lists. */
const val OVERVIEW_EXPIRING = 20

/** The most in-review ADRs "awaiting decision" lists. */
const val OVERVIEW_AWAITING = 20

/** The most tag groups the "in force" section shows (category, then tag name); the tags beyond are counted in `hiddenTags`. */
const val OVERVIEW_TAG_GROUPS = 100

/** The `adrs.overview.expiringSoonDays` bounds: a horizon of a day to a year. */
const val OVERVIEW_EXPIRING_DAYS_MAX = 365

/** An ADR as the log lists it: enough to name and open it, plus the two instants the sections order by. */
@Serializable
data class AdrLogItem(
    val id: UInt,
    val number: Int,
    val key: String,
    val name: String,
    /** `YYYY-MM-DD`, the last day the decision is in force; null = none. */
    val expiresOn: String?,
    /** When the ADR entered its current status: the activation of an ACTIVE one, the submission of one in review. */
    val statusChangedAt: Long,
)

/** An in-review ADR: the log item plus who has to decide and whether that is THIS caller. */
@Serializable
data class AdrAwaitingItem(
    val id: UInt,
    val number: Int,
    val key: String,
    val name: String,
    val expiresOn: String?,
    val statusChangedAt: Long,
    /** The Approver seat, or null when it is empty (an ADR cannot be in review without one, but the account may have left). */
    val approver: AdrParticipant?,
    /** The caller is the Approver: this one is waiting for them. */
    val mine: Boolean,
)

/** One capped list: the [items] shown, the [total] they were cut from and the [more] left out (`total - items.size`, never negative). */
@Serializable
data class AdrLogSection(val total: Long, val items: List<AdrLogItem>, val more: Long)

/** The expiry section: the [days] horizon the server used, counted from today (UTC). */
@Serializable
data class AdrExpiringSection(val days: Int, val total: Long, val items: List<AdrLogItem>, val more: Long)

/** The review queue: [mineTotal] counts ALL the caller's, including any beyond the cap, so a link to the filtered list can be offered. */
@Serializable
data class AdrAwaitingSection(val total: Long, val items: List<AdrAwaitingItem>, val more: Long, val mineTotal: Long)

/** The ACTIVE ADRs carrying one live tag (an ADR is listed under each of its live tags). */
@Serializable
data class AdrTagGroup(val name: String, val total: Long, val items: List<AdrLogItem>, val more: Long)

@Serializable
data class AdrCategoryGroup(val id: UInt, val name: String, val tags: List<AdrTagGroup>)

/** What is in force: the count, the ACTIVE ADRs by category and tag, the tags left out, and those with no live tag. */
@Serializable
data class AdrInForce(
    val total: Long,
    val categories: List<AdrCategoryGroup>,
    /** Tags with at least one ACTIVE ADR that the [OVERVIEW_TAG_GROUPS] cap left out. */
    val hiddenTags: Int,
    val untagged: AdrLogSection,
)

@Serializable
data class AdrStatusCount(val status: AdrStatus, val count: Long)

@Serializable
data class AdrOverviewResponse(
    val generatedAt: Long,
    /** Every status, in lifecycle order, zero included. */
    val counts: List<AdrStatusCount>,
    val inForce: AdrInForce,
    val recentlyDecided: AdrLogSection,
    val expiringSoon: AdrExpiringSection,
    val awaitingDecision: AdrAwaitingSection,
)
