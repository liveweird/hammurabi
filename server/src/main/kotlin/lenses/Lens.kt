package ch.nokillswit.lenses

import ch.nokillswit.adrs.AdrFilterDocument
import ch.nokillswit.adrs.normalized
import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.infra.validation.sanitizeSingleLine
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable

/**
 * Lenses (V16, `.claude/docs/adr-model.md` "Lenses"; Toadie's `lenses/`, ported and reshaped): named, saved snapshots of the ADR list's
 * filters. A PRIVATE lens is visible only to its creator; a PUBLIC lens is visible to every authenticated user but mutable by its
 * creator only (an ADMIN may delete a PUBLIC one, nothing else: "ADMIN gets no special content access" holds here too).
 *
 * The payload is the list's OWN filter document ([AdrFilterDocument], the same names as the query parameters) and is validated by the
 * list's own rules ([normalized]) - structurally only, never against the registries: registry values drift, and a lens holding a
 * since-retired tag simply matches nothing until edited.
 */
enum class LensVisibility { PRIVATE, PUBLIC }

const val MAX_LENS_NAME_LENGTH = 100

/** The most lenses one user owns (active ones): a personal-plus-curated scale, and the bound that keeps "my lenses" one page. */
const val MAX_LENSES_PER_USER = 50

@Serializable
data class LensResponse(
    val id: UInt,
    val name: String,
    val visibility: LensVisibility,
    val filters: AdrFilterDocument,
    val createdBy: UInt,
    val creatorName: String,
    val creatorDeleted: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    /** The caller may rename it, flip its visibility and replace its filters (its creator). */
    val canEdit: Boolean,
    /** The caller may delete it (its creator, or an ADMIN for a PUBLIC one). */
    val canDelete: Boolean,
)

typealias LensPageResponse = PageResponse<LensResponse>

@Serializable
data class LensRequest(
    val name: String,
    val visibility: LensVisibility,
    val filters: AdrFilterDocument,
)

/** Trims the name (control characters are a `400`) and puts the filters in canonical form (the list's rules; no `400` names the term). */
fun sanitizedLensRequest(request: LensRequest): LensRequest = LensRequest(
    name = sanitizeSingleLine(request.name, "name"),
    visibility = request.visibility,
    filters = request.filters.normalized(),
)

/** The lens rules - enforced by the route AND re-checked by the service. */
fun validateLensRequest(request: LensRequest) {
    if (request.name.isEmpty()) throw BadRequestException("name must not be blank")
    if (request.name.length > MAX_LENS_NAME_LENGTH) throw BadRequestException("name must be at most $MAX_LENS_NAME_LENGTH characters")
}
