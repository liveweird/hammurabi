package ch.nokillswit.tags

import ch.nokillswit.infra.validation.requireNoDuplicates
import ch.nokillswit.infra.validation.sanitizeSingleLine
import ch.nokillswit.infra.validation.sanitizedDescription
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable

/**
 * The ADMIN-curated tag categories' wire shapes (V13, `.claude/docs/adr-model.md` "Tags"; Toadie's `tags/TagCategory.kt` without the
 * Backstage entity kinds): a category is a display name, an optional description and its tags, and each tag belongs to exactly ONE
 * category (a cross-category clash is the service's 409). The registry is the whitelist every ADR tag write is checked against, strictly.
 */
@Serializable
data class TagCategory(
    val id: UInt,
    val name: String,
    val description: String?,
    /** The concurrency token: send it back as `X-Expected-Revision` on the category's PUT or DELETE (starts at 1, every PUT bumps it). */
    val revision: Long,
    /** The category's live tags (normalised names), alphabetical. */
    val tags: List<String>,
)

/** The whole registry; plain `{items}` because it is a bounded, admin-curated set (`list-endpoints.md`). */
@Serializable
data class TagCategoryList(val items: List<TagCategory>)

/** Create and replace share the shape: a PUT is a full replace of the category, its tag list included. */
@Serializable
data class TagCategoryRequest(
    val name: String,
    val description: String? = null,
    val tags: List<String>,
)

const val MAX_TAG_CATEGORIES = 100
const val MAX_CATEGORY_TAGS = 100
const val MAX_CATEGORY_NAME_LENGTH = 60
const val MAX_CATEGORY_DESCRIPTION_LENGTH = 500
const val MAX_TAG_NAME_LENGTH = 40

private val WHITESPACE_RUN = Regex("\\s+")

/** A tag starts with a letter or digit and goes on with those, spaces and `. _ / + # : -` (no comma: event params join names with one). */
private val TAG_GRAMMAR = Regex("""[\p{L}\p{N}][\p{L}\p{N} ._/+#:-]*""")

/**
 * The ONE canonical form of a tag name: trimmed, control and hidden direction characters a 400 ([sanitizeSingleLine]), runs of whitespace
 * collapsed to one space, lower-cased. Applied at every entry point (the registry and the ADR tag write), so `Security` and ` security `
 * are the tag `security`.
 */
fun normalizedTagName(raw: String): String = sanitizeSingleLine(raw, "Tag").replace(WHITESPACE_RUN, " ").lowercase()

/** A normalised tag name's rule: 1-[MAX_TAG_NAME_LENGTH] characters of the [TAG_GRAMMAR]. */
fun requireValidTagName(name: String) {
    if (name.isEmpty() || name.length > MAX_TAG_NAME_LENGTH || !TAG_GRAMMAR.matches(name)) {
        throw BadRequestException("Tag '$name' must be 1-$MAX_TAG_NAME_LENGTH characters: letters, digits, and spaces . _ / + # : - inside")
    }
}

/** Trims the payload (the name via the control-character-rejecting sanitiser) and normalises every tag name. */
fun sanitizedTagCategoryRequest(request: TagCategoryRequest): TagCategoryRequest = TagCategoryRequest(
    name = sanitizeSingleLine(request.name, "Name"),
    description = sanitizedDescription(request.description),
    tags = request.tags.map(::normalizedTagName),
)

/** The registry's validation rules - enforced by the route AND re-checked by the service. */
fun validateTagCategoryRequest(request: TagCategoryRequest) {
    if (request.name.isEmpty() || request.name.length > MAX_CATEGORY_NAME_LENGTH) {
        throw BadRequestException("Name must be 1-$MAX_CATEGORY_NAME_LENGTH characters")
    }
    if ((request.description?.length ?: 0) > MAX_CATEGORY_DESCRIPTION_LENGTH) {
        throw BadRequestException("Description must be at most $MAX_CATEGORY_DESCRIPTION_LENGTH characters")
    }
    if (request.tags.isEmpty()) throw BadRequestException("A category needs at least one tag")
    if (request.tags.size > MAX_CATEGORY_TAGS) throw BadRequestException("A category holds at most $MAX_CATEGORY_TAGS tags")
    request.tags.forEach(::requireValidTagName)
    requireNoDuplicates(request.tags, "Tags must not repeat")
}
