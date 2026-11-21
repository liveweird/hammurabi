package ch.nokillswit.adrs

import ch.nokillswit.infra.validation.sanitizeSingleLine
import ch.nokillswit.tags.MAX_TAG_NAME_LENGTH
import ch.nokillswit.tags.normalizedTagName
import ch.nokillswit.tags.requireValidTagName
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneOffset

/** The longest `q` the list takes, in characters (the shared free-text bound, API-LIST-005). */
const val MAX_ADR_QUERY_LENGTH = 100

/** The most `tag` filters one list request names (any-of; more is a 400). */
const val MAX_TAG_FILTERS = 20

/** `text` (the full-text search) is this many CODE POINTS at least and [MAX_SEARCH_TEXT_LENGTH] at most, after trimming. */
const val MIN_SEARCH_TEXT_LENGTH = 2
const val MAX_SEARCH_TEXT_LENGTH = 100

/**
 * The ADR list's filters as ONE document: the query parameters of `GET /adrs` and the stored filters of a lens (V16) are this same
 * shape with the same names, so a lens applies to the list by copying its keys into the URL and the SPA never maps a second
 * vocabulary. Everything is optional ("not filtered on"); a document is VALIDATED and put in canonical form by [normalized], the one
 * rule set the route and the lens service share. Dates are `YYYY-MM-DD` (UTC days).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AdrFilterDocument(
    // An absent filter is an ABSENT key on the wire (never a `null`), so the stored text and the response stay the minimal document.
    @EncodeDefault(EncodeDefault.Mode.NEVER) val status: List<AdrStatus>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val q: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val text: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val participantId: UInt? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val participantRole: AdrRole? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val tag: List<String>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val createdFrom: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val createdTo: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val updatedFrom: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val updatedTo: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val expiresBefore: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val hasOpenIssues: Boolean? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val hasPredecessor: Boolean? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val hasSuccessor: Boolean? = null,
)

/** The list filters, validated (all optional): what [adrListPredicate] reads. */
data class AdrListFilter(
    val statuses: List<AdrStatus> = emptyList(),
    val q: String? = null,
    /** The validated full-text input (never logged); null = no search. */
    val text: String? = null,
    val participantId: UInt? = null,
    val participantRole: AdrRole? = null,
    /** Any-of: the ADRs carrying at least one of these tags (normalised names; a retired tag still matches the ADRs that kept it). */
    val tags: List<String> = emptyList(),
    /** Inclusive UTC days on `created_at` / `updated_at`. */
    val createdFrom: LocalDate? = null,
    val createdTo: LocalDate? = null,
    val updatedFrom: LocalDate? = null,
    val updatedTo: LocalDate? = null,
    /** Strictly before this day: the ADRs whose expiry date (the last day in force) is earlier. */
    val expiresBefore: LocalDate? = null,
    val hasOpenIssues: Boolean? = null,
    val hasPredecessor: Boolean? = null,
    val hasSuccessor: Boolean? = null,
)

/**
 * The validated `text`: trimmed, a control character is a `400` ([sanitizeSingleLine]), 2 to 100 code points, and at least one letter or
 * digit (`!!!` has no lexeme and could match nothing). Blank means no search. The `400` details never carry the term: a search term
 * reaches no log line, no audit line and no problem body beyond the requester's own.
 */
fun validatedSearchText(raw: String?): String? {
    val text = sanitizeSingleLine(raw ?: return null, "text").takeIf { it.isNotEmpty() } ?: return null
    val length = text.codePointCount(0, text.length)
    if (length < MIN_SEARCH_TEXT_LENGTH || length > MAX_SEARCH_TEXT_LENGTH) {
        throw BadRequestException("text must be $MIN_SEARCH_TEXT_LENGTH to $MAX_SEARCH_TEXT_LENGTH characters after trimming")
    }
    if (text.codePoints().noneMatch { Character.isLetterOrDigit(it) }) throw BadRequestException("text must contain a letter or a digit")
    return text
}

private val DAY = Regex("""\d{4}-\d{2}-\d{2}""")

private fun parseDay(raw: String?, field: String): LocalDate? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (!DAY.matches(value)) throw BadRequestException("$field must be a date (YYYY-MM-DD)")
    return try {
        LocalDate.parse(value)
    } catch (_: java.time.DateTimeException) {
        throw BadRequestException("$field must be a real calendar date (YYYY-MM-DD)")
    }
}

private fun requireOrdered(from: LocalDate?, to: LocalDate?, field: String) {
    if (from != null && to != null && from.isAfter(to)) throw BadRequestException("${field}From must not be after ${field}To")
}

/** The parsed form of [this] (a document in canonical form); the predicate and the SPA's URL both read the same values. */
private fun AdrFilterDocument.parsed(): AdrListFilter = AdrListFilter(
    statuses = status.orEmpty(),
    q = q,
    text = text,
    participantId = participantId,
    participantRole = participantRole,
    tags = tag.orEmpty(),
    createdFrom = parseDay(createdFrom, "createdFrom"),
    createdTo = parseDay(createdTo, "createdTo"),
    updatedFrom = parseDay(updatedFrom, "updatedFrom"),
    updatedTo = parseDay(updatedTo, "updatedTo"),
    expiresBefore = parseDay(expiresBefore, "expiresBefore"),
    hasOpenIssues = hasOpenIssues,
    hasPredecessor = hasPredecessor,
    hasSuccessor = hasSuccessor,
)

/**
 * The document validated and in canonical form - the ONE rule set of the list route and of a lens: strings trimmed (blank = absent),
 * `q` at most [MAX_ADR_QUERY_LENGTH] code points (and, like every single-line field, free of control and hidden-formatting characters),
 * `text` per [validatedSearchText], the statuses and tags duplicate-free (at most one per status, at most [MAX_TAG_FILTERS] tags, each
 * normalised and at most [MAX_TAG_NAME_LENGTH] characters), `participantRole` only with `participantId`, dates real `YYYY-MM-DD` days with
 * from <= to. Throws a `400` (never naming the search term). Validated structurally only, never against the registries: a lens holding
 * a since-retired tag simply matches nothing until edited.
 */
fun AdrFilterDocument.normalized(): AdrFilterDocument {
    val q = q?.let { sanitizeSingleLine(it, "q") }?.takeIf { it.isNotEmpty() }
    if (q != null && q.codePointCount(0, q.length) > MAX_ADR_QUERY_LENGTH) {
        throw BadRequestException("q must be at most $MAX_ADR_QUERY_LENGTH characters")
    }
    if (participantRole != null && participantId == null) throw BadRequestException("participantRole needs participantId")
    val statuses = status.orEmpty().distinct()
    val tags = requireTagBound(
        tag.orEmpty().map(::normalizedTagName).filter(String::isNotEmpty).onEach(::requireValidTagName).distinct(),
    )
    val parsed = parsed()
    requireOrdered(parsed.createdFrom, parsed.createdTo, "created")
    requireOrdered(parsed.updatedFrom, parsed.updatedTo, "updated")
    return AdrFilterDocument(
        status = statuses.takeIf { it.isNotEmpty() },
        q = q,
        text = validatedSearchText(text),
        participantId = participantId,
        participantRole = participantRole,
        tag = tags.takeIf { it.isNotEmpty() },
        createdFrom = parsed.createdFrom?.toString(),
        createdTo = parsed.createdTo?.toString(),
        updatedFrom = parsed.updatedFrom?.toString(),
        updatedTo = parsed.updatedTo?.toString(),
        expiresBefore = parsed.expiresBefore?.toString(),
        hasOpenIssues = hasOpenIssues,
        hasPredecessor = hasPredecessor,
        hasSuccessor = hasSuccessor,
    )
}

private fun requireTagBound(tags: List<String>): List<String> {
    if (tags.size > MAX_TAG_FILTERS) throw BadRequestException("tag takes at most $MAX_TAG_FILTERS values")
    return tags
}

/** The filter of a NORMALIZED document ([normalized]). */
fun AdrFilterDocument.toListFilter(): AdrListFilter = parsed()

/** The first millisecond of [day] (UTC) as epoch millis. */
internal fun LocalDate.startMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
