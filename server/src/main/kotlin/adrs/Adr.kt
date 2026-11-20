package ch.nokillswit.adrs

import ch.nokillswit.infra.db.FieldChange
import ch.nokillswit.infra.db.nameOnlyChange
import ch.nokillswit.infra.db.scalarChange
import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.infra.validation.sanitizeMultiLine
import ch.nokillswit.infra.validation.sanitizeSingleLine
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The Architecture Decision Record (V10, `.claude/docs/adr-model.md`): a name, the problem, the context, the decision, the alternatives
 * considered and the accepted consequences (Markdown, each at most [MAX_ADR_TEXT_LENGTH] characters), a lifecycle [AdrStatus], an
 * optional expiry date, its DACI participants and a revision. The wire `key` is `ADR-0042`; URLs use the row `id`.
 */
const val MAX_ADR_NAME_LENGTH = 120
const val MAX_ADR_TEXT_LENGTH = 20_000

/** The most participants one ADR holds (a DACI list is a handful of people; the bound keeps the PUT and the diff small). */
const val MAX_ADR_PARTICIPANTS = 100

/** The most tags one ADR carries (a handful of categories' worth; the bound keeps the PUT, the diff and the event small). */
const val MAX_ADR_TAGS = 20

/** The persisted lifecycle (`adrs.status`, frozen: a name is never renamed or removed; the Kotlin enum is the whitelist, no CHECK). */
@Serializable
enum class AdrStatus { PROBLEM_RAISED, SOLUTION_DRAFT, IN_REVIEW, ACTIVE, CANCELLED, WITHDRAWN, EXPIRED, SUPERSEDED }

/** The DACI roles (`adr_participants.role`, frozen like [AdrStatus]): one Driver, at most one Approver, any number of the others. */
@Serializable
enum class AdrRole { DRIVER, APPROVER, CONTRIBUTOR, INFORMED }

/** `ADR-0042`: the human-readable id, the number zero-padded to four digits (a larger number simply grows). */
fun adrKey(number: Int): String = "ADR-" + number.toString().padStart(KEY_DIGITS, '0')

private const val KEY_DIGITS = 4

@Serializable
data class AdrParticipantRequest(val userId: UInt, val role: AdrRole)

/** Only `problem` is required; a missing `name` defaults to the problem's first line. The caller becomes the Driver. */
@Serializable
data class AdrCreateRequest(
    val name: String? = null,
    val problem: String,
    val context: String = "",
    val decision: String = "",
    val alternatives: String = "",
    val consequences: String = "",
    val expiresOn: String? = null,
    /** Extra participants; never a Driver (the caller is) and never the caller. */
    val participants: List<AdrParticipantRequest> = emptyList(),
)

/** PUT is a full replace of the document: an omitted Markdown field is empty, an omitted `expiresOn` clears the date. */
@Serializable
data class AdrUpdateRequest(
    val name: String,
    val problem: String,
    val context: String = "",
    val decision: String = "",
    val alternatives: String = "",
    val consequences: String = "",
    val expiresOn: String? = null,
)

@Serializable
data class AdrTransitionRequest(val to: AdrStatus)

/** The whole participant set (a wholesale replace): exactly one Driver, at most one Approver. */
@Serializable
data class AdrParticipantsRequest(val participants: List<AdrParticipantRequest>)

/** A participant as the API shows it: the user, the display name as it is now and the role. */
@Serializable
data class AdrParticipant(val userId: UInt, val name: String, val role: AdrRole)

/** A tag as an ADR shows it: its normalised name, and `retired` once the registry dropped it (it stays until a tag write removes it). */
@Serializable
data class AdrTag(val name: String, val retired: Boolean)

/** The whole tag set (a wholesale replace applied as a diff): registry names, each once, at most [MAX_ADR_TAGS]. */
@Serializable
data class AdrTagsRequest(val tags: List<String>)

/** The successor link as a request: the ADR THIS one replaces; an omitted or null `supersedesId` removes the link (a full replace). */
@Serializable
data class AdrSupersedesRequest(val supersedesId: UInt? = null)

/** The other end of a successor link: enough to show and open it (`ADR-0007 Use PostgreSQL`, its status). */
@Serializable
data class AdrRef(val id: UInt, val key: String, val name: String, val status: AdrStatus)

@Serializable
data class AdrResponse(
    val id: UInt,
    val number: Int,
    val key: String,
    val name: String,
    val problem: String,
    val context: String,
    val decision: String,
    val alternatives: String,
    val consequences: String,
    val status: AdrStatus,
    /** `YYYY-MM-DD`, the last day the decision is in force; null = no expiry. */
    val expiresOn: String?,
    /** The optimistic-concurrency token: send it back as `X-Expected-Revision` on a document PUT or a transition. */
    val revision: Long,
    val createdAt: Long,
    val createdBy: UInt,
    val updatedAt: Long,
    val statusChangedAt: Long,
    val participants: List<AdrParticipant>,
    /** The ADR's tags by name, alphabetical; a `retired` one is no longer in the registry. */
    val tags: List<AdrTag>,
    /** The ADR this one replaces (set while it is pre-active; takes effect when this one becomes `ACTIVE`), or null. */
    val supersedes: AdrRef?,
    /** The ADR replacing this one: its pending successor, or - once this one is `SUPERSEDED` - the one that replaced it; or null. */
    val supersededBy: AdrRef?,
    /** The moves THIS caller may request now (the lifecycle map, minus the system's, filtered by the DACI verdict): the buttons. */
    val allowedTransitions: List<AdrStatus>,
    /** This caller may PUT the document now (content in a pre-active status, or the expiry date in any non-terminal one). */
    val canEdit: Boolean,
    /** This caller may replace the participants now (the Driver, the Approver or an ADMIN, on a non-terminal ADR). */
    val canManageParticipants: Boolean,
    /** This caller may replace the tags now (the Driver, the Approver or an ADMIN, on a non-terminal ADR; editable once `ACTIVE`). */
    val canEditTags: Boolean,
    /** This caller may link or unlink the predecessor now (the Driver, the Approver or an ADMIN, while the ADR is pre-active). */
    val canEditSuccessor: Boolean,
    /** This caller may detach the pending successor now (Driver, Approver or ADMIN of THIS ADR, which has a successor still pre-active). */
    val canDetachSuccessor: Boolean,
    /** The discussion takes a comment or a question now (a pre-active status): the composer. Anyone authenticated may post. */
    val canComment: Boolean,
    /** The discussion takes an issue or an escalation now (`ACTIVE`): the composer. Anyone authenticated may post. */
    val canRaiseIssue: Boolean,
    /** The OPEN issues and escalations (deleted entries excluded). */
    val openIssueCount: Int,
    /** The OPEN questions (deleted entries excluded). */
    val unansweredQuestionCount: Int,
)

/** One row of the list: the headline fields plus the Driver and the Approver (the two a list is read by). */
@Serializable
data class AdrListItem(
    val id: UInt,
    val number: Int,
    val key: String,
    val name: String,
    val status: AdrStatus,
    val expiresOn: String?,
    val revision: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val statusChangedAt: Long,
    val driver: AdrParticipant?,
    val approver: AdrParticipant?,
    /** The ADR's tags by name, alphabetical. */
    val tags: List<AdrTag>,
    /** The OPEN issues and escalations (deleted entries excluded). */
    val openIssueCount: Int,
)

typealias AdrPageResponse = PageResponse<AdrListItem>

/** The stored record, every column. */
data class AdrRecord(
    val id: UInt,
    val number: Int,
    val name: String,
    val problem: String,
    val context: String,
    val decision: String,
    val alternatives: String,
    val consequences: String,
    val status: AdrStatus,
    val expiresOn: String?,
    val revision: Long,
    val createdBy: UInt,
    val createdAt: Long,
    val updatedAt: Long,
    val statusChangedAt: Long,
    /** The ADR this one replaces (`supersedes_id`), whatever its state: the row id, resolved to a live [AdrRef] by the reader. */
    val supersedesId: UInt? = null,
)

/** A record with its participants (driver first, then approver, contributors, informed), its tags and its successor link's two ends. */
data class AdrView(
    val record: AdrRecord,
    val participants: List<AdrParticipant>,
    val tags: List<AdrTag>,
    val supersedes: AdrRef?,
    val supersededBy: AdrRef?,
)

/**
 * The response as [standing]'s caller sees it; its verdicts (`allowedTransitions`, `canEdit`, ...) come from `AdrAccess.kt`, the counts
 * from the discussion.
 */
fun AdrView.toResponse(standing: AdrStanding, counts: DiscussionCounts): AdrResponse = AdrResponse(
    id = record.id,
    number = record.number,
    key = adrKey(record.number),
    name = record.name,
    problem = record.problem,
    context = record.context,
    decision = record.decision,
    alternatives = record.alternatives,
    consequences = record.consequences,
    status = record.status,
    expiresOn = record.expiresOn,
    revision = record.revision,
    createdAt = record.createdAt,
    createdBy = record.createdBy,
    updatedAt = record.updatedAt,
    statusChangedAt = record.statusChangedAt,
    participants = participants,
    tags = tags,
    supersedes = supersedes,
    supersededBy = supersededBy,
    allowedTransitions = allowedTransitions(standing, record.status),
    canEdit = canEdit(standing, record.status),
    canManageParticipants = canManageParticipants(standing, record.status),
    canEditTags = canEditTags(standing, record.status),
    canEditSuccessor = canEditSuccessor(standing, record.status),
    canDetachSuccessor = canDetachSuccessor(standing, record.status, supersededBy),
    canComment = DiscussionKind.COMMENT.acceptedIn(record.status),
    canRaiseIssue = DiscussionKind.ISSUE.acceptedIn(record.status),
    openIssueCount = counts.openIssues,
    unansweredQuestionCount = counts.unansweredQuestions,
)

/** The list filters (all optional, whitelisted by the route). */
data class AdrListFilter(
    val statuses: List<AdrStatus> = emptyList(),
    val q: String? = null,
    val participantUserId: UInt? = null,
    val participantRole: AdrRole? = null,
    /** Any-of: the ADRs carrying at least one of these tags (normalised names; a retired tag still matches the ADRs that kept it). */
    val tags: List<String> = emptyList(),
)

data class AdrListResult(val items: List<AdrListItem>, val total: Long)

/** The content fields of [request] that differ from [before], in record order: the name with its sides, the Markdown ones by name only. */
internal fun contentChanges(before: AdrRecord, request: AdrUpdateRequest): List<FieldChange> = listOfNotNull(
    scalarChange("name", before.name, request.name),
    nameOnlyChange("problem", before.problem, request.problem),
    nameOnlyChange("context", before.context, request.context),
    nameOnlyChange("decision", before.decision, request.decision),
    nameOnlyChange("alternatives", before.alternatives, request.alternatives),
    nameOnlyChange("consequences", before.consequences, request.consequences),
)

private val WHITESPACE_RUN = Regex("\\s+")

/** The default name of an ADR created without one: the problem's first non-blank line, cut to [MAX_ADR_NAME_LENGTH] (never mid-pair). */
fun defaultAdrName(problem: String): String {
    // Tabs and whitespace runs collapse to single spaces, then the single-line sanitiser: a defaulted name is a valid name (a PUT
    // echoing it must pass), never one holding a control character.
    val line = problem.lineSequence().map { it.trim().replace(WHITESPACE_RUN, " ") }.firstOrNull { it.isNotEmpty() } ?: return ""
    if (line.length <= MAX_ADR_NAME_LENGTH) return sanitizeSingleLine(line, "Name")
    val cut = if (line[MAX_ADR_NAME_LENGTH - 1].isHighSurrogate()) MAX_ADR_NAME_LENGTH - 1 else MAX_ADR_NAME_LENGTH
    return sanitizeSingleLine(line.substring(0, cut).trimEnd(), "Name")
}

private fun blankToNull(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

/** Trims and normalises the create body (the sanitiser convention: control characters are a 400). A blank name means "default it". */
fun sanitizedAdrCreate(request: AdrCreateRequest): AdrCreateRequest = AdrCreateRequest(
    name = blankToNull(request.name)?.let { sanitizeSingleLine(it, "Name") },
    problem = sanitizeMultiLine(request.problem, "Problem"),
    context = sanitizeMultiLine(request.context, "Context"),
    decision = sanitizeMultiLine(request.decision, "Decision"),
    alternatives = sanitizeMultiLine(request.alternatives, "Alternatives"),
    consequences = sanitizeMultiLine(request.consequences, "Consequences"),
    expiresOn = blankToNull(request.expiresOn),
    participants = request.participants,
)

fun sanitizedAdrUpdate(request: AdrUpdateRequest): AdrUpdateRequest = AdrUpdateRequest(
    name = sanitizeSingleLine(request.name, "Name"),
    problem = sanitizeMultiLine(request.problem, "Problem"),
    context = sanitizeMultiLine(request.context, "Context"),
    decision = sanitizeMultiLine(request.decision, "Decision"),
    alternatives = sanitizeMultiLine(request.alternatives, "Alternatives"),
    consequences = sanitizeMultiLine(request.consequences, "Consequences"),
    expiresOn = blankToNull(request.expiresOn),
)

/** The create rules - enforced by the route AND re-checked by the service. */
fun validateAdrCreate(request: AdrCreateRequest) {
    request.name?.let(::requireName)
    requireTexts(request.problem, request.context, request.decision, request.alternatives, request.consequences)
    request.expiresOn?.let(::parseExpiresOn)
    val initial = request.participants
    if (initial.size > MAX_ADR_PARTICIPANTS - 1) throw BadRequestException("An ADR holds at most $MAX_ADR_PARTICIPANTS participants")
    if (initial.any { it.role == AdrRole.DRIVER }) {
        throw BadRequestException("The creator is the Driver; hand the role over with the participants PUT")
    }
    requireDistinctUsers(initial)
    requireAtMostOneApprover(initial)
}

fun validateAdrUpdate(request: AdrUpdateRequest) {
    requireName(request.name)
    requireTexts(request.problem, request.context, request.decision, request.alternatives, request.consequences)
    request.expiresOn?.let(::parseExpiresOn)
}

/** The participants PUT rules: a whole set with exactly one Driver (never left empty), at most one Approver, each user once. */
fun validateParticipantSet(participants: List<AdrParticipantRequest>) {
    if (participants.size > MAX_ADR_PARTICIPANTS) throw BadRequestException("An ADR holds at most $MAX_ADR_PARTICIPANTS participants")
    if (participants.count { it.role == AdrRole.DRIVER } != 1) throw BadRequestException("An ADR needs exactly one Driver")
    requireDistinctUsers(participants)
    requireAtMostOneApprover(participants)
}

private fun requireName(name: String) {
    if (name.isBlank() || name.length > MAX_ADR_NAME_LENGTH) throw BadRequestException("Name must be 1-$MAX_ADR_NAME_LENGTH characters")
}

private fun requireTexts(problem: String, context: String, decision: String, alternatives: String, consequences: String) {
    if (problem.isBlank()) throw BadRequestException("Problem is required")
    listOf(
        "Problem" to problem,
        "Context" to context,
        "Decision" to decision,
        "Alternatives" to alternatives,
        "Consequences" to consequences,
    )
        .forEach { (field, text) ->
            if (text.length > MAX_ADR_TEXT_LENGTH) throw BadRequestException("$field must be at most $MAX_ADR_TEXT_LENGTH characters")
        }
}

private fun requireDistinctUsers(participants: List<AdrParticipantRequest>) {
    if (participants.map { it.userId }.toSet().size != participants.size) throw BadRequestException("A user holds one role per ADR")
}

private fun requireAtMostOneApprover(participants: List<AdrParticipantRequest>) {
    if (participants.count { it.role == AdrRole.APPROVER } > 1) throw BadRequestException("An ADR has at most one Approver")
}

private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")

/** `YYYY-MM-DD` as a real calendar date, else a 400 (the stored text is exactly this shape, so it sorts chronologically). */
fun parseExpiresOn(value: String): LocalDate {
    val parsed = if (ISO_DATE.matches(value)) {
        try {
            LocalDate.parse(value)
        } catch (_: DateTimeParseException) {
            null
        }
    } else {
        null
    }
    return parsed ?: throw BadRequestException("expiresOn must be a date as YYYY-MM-DD")
}
