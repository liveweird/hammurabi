package ch.nokillswit.lenses

import ch.nokillswit.adrs.AdrFilterDocument
import ch.nokillswit.adrs.AdrRole
import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.ConflictException
import ch.nokillswit.authz.isAdmin
import ch.nokillswit.infra.db.SoftDeletable
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.users.UserService
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

val LensServiceKey = AttributeKey<LensService>("LensService")

/**
 * The first key of the per-user lens-cap lock: `pg_advisory_xact_lock(LENS_CAP_LOCK_KEY, userId)`, the TWO-`int` form (its key space is
 * disjoint from the single-`bigint` locks, [ch.nokillswit.adrs.ADR_NUMBER_LOCK_KEY] and the like): the ASCII bytes of "LENS". A create
 * counts the caller's lenses, and a count cannot be row-locked (the new row does not exist yet), so two creates of the same user at the
 * 49th lens would both pass it. Per user, so nobody queues behind another user's save; transaction-scoped, released at commit or rollback.
 */
internal const val LENS_CAP_LOCK_KEY = 0x4C454E53

/**
 * A mutation's authorization verdict, decided inside the mutation's own transaction (Toadie's `LensMutationResult`). The split backs the
 * hybrid disclosure policy (`authorization.md`): an absent, soft-deleted or FOREIGN PRIVATE lens is uniformly [NOT_FOUND] (a private
 * lens's existence is itself the secret, an ADMIN's too), while a foreign PUBLIC lens - visible in everyone's list anyway - gets the
 * honest [FORBIDDEN] (`403`).
 */
enum class LensVerdict { OK, NOT_FOUND, FORBIDDEN }

/** What the lens list is filtered by: `mine` keeps the caller's own lenses (`true`) or only the PUBLIC ones of others (`false`). */
data class LensListFilter(val mine: Boolean? = null, val visibility: LensVisibility? = null)

private val LENS_SORT: Map<String, Expression<*>> = mapOf(
    "id" to LensService.Lenses.id,
    "name" to LensService.Lenses.name.lowerCase(),
    "updatedAt" to LensService.Lenses.updatedAt,
    "createdAt" to LensService.Lenses.createdAt,
)

/** The sortable whitelist of the lens list, derived from the column map so the two cannot disagree. */
val LENS_SORT_FIELDS: Set<String> = LENS_SORT.keys

private val STATUS_NAMES: Set<String> = AdrStatus.entries.map { it.name }.toSet()
private val ROLE_NAMES: Set<String> = AdrRole.entries.map { it.name }.toSet()

class LensService(private val database: R2dbcDatabase) {
    object Lenses : UIntIdTable("lenses"), SoftDeletable {
        // Per-owner case-folded name uniqueness is `uq_lenses_owner_name_active` (V16, active rows only: a deleted lens frees its name).
        val name = varchar("name", length = MAX_LENS_NAME_LENGTH)
        val visibility = varchar("visibility", length = 10)

        // The ADR list's filter document as JSON text, replaced whole on every save.
        val filters = text("filters")
        val createdBy = reference("created_by", UserService.Users)
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
        override val markedAsDeleted = bool("marked_as_deleted").default(false)
    }

    private val json = Json

    /** Stored filters are read LENIENTLY (a lens outlives the release that wrote it): see [decodeStoredFilters]. */
    private val storedJson = Json { ignoreUnknownKeys = true }

    // The sanctioned cross-feature read (persistence.md): the creator's display fields come from the same transaction as the lens rows.
    private fun joined() = Lenses.join(UserService.Users, JoinType.INNER, onColumn = Lenses.createdBy, otherColumn = UserService.Users.id)

    private fun ResultRow.toResponse(caller: CallerPrincipal): LensResponse {
        val mine = this[Lenses.createdBy].value == caller.userId
        val public = this[Lenses.visibility] == LensVisibility.PUBLIC.name
        return LensResponse(
            id = this[Lenses.id].value,
            name = this[Lenses.name],
            visibility = LensVisibility.valueOf(this[Lenses.visibility]),
            filters = decodeStoredFilters(this[Lenses.filters]),
            createdBy = this[Lenses.createdBy].value,
            creatorName = this[UserService.Users.name],
            creatorDeleted = this[UserService.Users.markedAsDeleted],
            createdAt = this[Lenses.createdAt],
            updatedAt = this[Lenses.updatedAt],
            canEdit = mine,
            canDelete = mine || (public && caller.isAdmin()),
        )
    }

    /**
     * The stored document as THIS release reads it: a key it no longer knows is ignored, and a `status` or `role` value that is no longer
     * in its enum is dropped (a list the drop empties, or a role that goes, stops filtering on that key), so a lens written by an older
     * release is listable instead of a `500`; it is rewritten whole (and so cleaned) on its next save. A persisted enum rename or removal
     * therefore needs a migration that rewrites `lenses.filters` (`persistence.md`).
     */
    private fun decodeStoredFilters(text: String): AdrFilterDocument {
        val known = JsonObject(
            buildMap {
                for ((key, value) in storedJson.parseToJsonElement(text).jsonObject) {
                    when (key) {
                        "status" -> {
                            val kept = value.jsonArray.filter { (it as? JsonPrimitive)?.contentOrNull in STATUS_NAMES }
                            if (kept.isNotEmpty()) put(key, JsonArray(kept))
                        }
                        "role" -> if ((value as? JsonPrimitive)?.contentOrNull in ROLE_NAMES) put(key, value)
                        else -> put(key, value)
                    }
                }
            },
        )
        return storedJson.decodeFromJsonElement(AdrFilterDocument.serializer(), known)
    }

    /** The lenses [caller] may see: their own (both visibilities) plus everyone's PUBLIC ones, paged. */
    suspend fun list(caller: CallerPrincipal, filter: LensListFilter, paging: PageRequest): Pair<List<LensResponse>, Long> =
        suspendTransaction(database) {
            val visible = (Lenses.visibility eq LensVisibility.PUBLIC.name) or (Lenses.createdBy eq caller.userId)
            var predicate: Op<Boolean> = Lenses.active() and visible
            filter.mine?.let {
                predicate = predicate and (if (it) Lenses.createdBy eq caller.userId else Lenses.createdBy neq caller.userId)
            }
            filter.visibility?.let { predicate = predicate and (Lenses.visibility eq it.name) }
            val total = joined().selectAll().where { predicate }.count()
            val rows = joined().selectAll().where { predicate }.applyPaging(paging, LENS_SORT).toList().map { it.toResponse(caller) }
            rows to total
        }

    /** The lens [id] as [caller] may see it, or null: missing, soft-deleted or a foreign PRIVATE one. */
    suspend fun read(id: UInt, caller: CallerPrincipal): LensResponse? = suspendTransaction(database) {
        joined().selectAll().where { (Lenses.id eq id) and Lenses.active() }.toList().singleOrNull()
            ?.takeIf { it[Lenses.visibility] == LensVisibility.PUBLIC.name || it[Lenses.createdBy].value == caller.userId }
            ?.toResponse(caller)
    }

    /** Saves a lens; sanitised and validated again here so a direct caller stays guarded. The 51st active lens of a user is a `409`. */
    suspend fun create(raw: LensRequest, caller: CallerPrincipal): UInt {
        val request = sanitizedLensRequest(raw)
        validateLensRequest(request)
        return suspendTransaction(database) {
            // The cap is a count, so it is serialised per user BEFORE counting (see LENS_CAP_LOCK_KEY).
            exec(
                "SELECT pg_advisory_xact_lock($LENS_CAP_LOCK_KEY, ${caller.userId.toInt()})",
                explicitStatementType = StatementType.SELECT,
            ) { true }?.toList()
            val owned = Lenses.selectAll().where { (Lenses.createdBy eq caller.userId) and Lenses.active() }.count()
            if (owned >= MAX_LENSES_PER_USER) throw ConflictException("You can keep at most $MAX_LENSES_PER_USER saved filters")
            val stamp = nowMillis()
            Lenses.insert {
                it[name] = request.name
                it[visibility] = request.visibility.name
                it[filters] = json.encodeToString(AdrFilterDocument.serializer(), request.filters)
                it[createdBy] = caller.userId
                it[createdAt] = stamp
                it[updatedAt] = stamp
            }[Lenses.id].value
        }
    }

    /**
     * Whole-lens replace (name, visibility AND filters: overwrite, rename and the visibility flip are all this one PUT). Creator-only,
     * and the verdict runs BEFORE the payload is sanitised or validated so 403/404 win over 400 (sanitising could itself throw).
     */
    suspend fun update(id: UInt, raw: LensRequest, caller: CallerPrincipal): LensVerdict = suspendTransaction(database) {
        val verdict = mutationVerdict(id, caller, adminMayDelete = false, lock = true).first
        if (verdict != LensVerdict.OK) return@suspendTransaction verdict
        val request = sanitizedLensRequest(raw)
        validateLensRequest(request)
        Lenses.update({ (Lenses.id eq id) and Lenses.active() }) {
            it[name] = request.name
            it[visibility] = request.visibility.name
            it[filters] = json.encodeToString(AdrFilterDocument.serializer(), request.filters)
            it[updatedAt] = nowMillis()
        }
        LensVerdict.OK
    }

    /** The verdict [update] would reach for [id], read WITHOUT the row lock: the route's gate before it decodes the body. */
    suspend fun verdictForUpdate(id: UInt, caller: CallerPrincipal): LensVerdict =
        suspendTransaction(database) { mutationVerdict(id, caller, adminMayDelete = false, lock = false).first }

    /** Soft delete: the creator, or an ADMIN for a PUBLIC lens. [byAdmin] says it was the ADMIN's moderation (the audit line). */
    suspend fun delete(id: UInt, caller: CallerPrincipal): Pair<LensVerdict, Boolean> = suspendTransaction(database) {
        val (verdict, creator) = mutationVerdict(id, caller, adminMayDelete = true, lock = true)
        if (verdict != LensVerdict.OK) return@suspendTransaction verdict to false
        Lenses.update({ (Lenses.id eq id) and Lenses.active() }) {
            it[markedAsDeleted] = true
            it[updatedAt] = nowMillis()
        }
        LensVerdict.OK to (creator != caller.userId)
    }

    /** One read deciding the mutation's fate (see [LensVerdict]), under the row lock when [lock], plus the lens's creator (or null). */
    private suspend fun mutationVerdict(
        id: UInt,
        caller: CallerPrincipal,
        adminMayDelete: Boolean,
        lock: Boolean,
    ): Pair<LensVerdict, UInt?> {
        val query = Lenses.selectAll().where { (Lenses.id eq id) and Lenses.active() }
        val row = (if (lock) query.forUpdate(ForUpdateOption.PostgreSQL.ForNoKeyUpdate) else query).toList().singleOrNull()
            ?: return LensVerdict.NOT_FOUND to null
        val creator = row[Lenses.createdBy].value
        val verdict = when {
            creator == caller.userId -> LensVerdict.OK
            row[Lenses.visibility] != LensVisibility.PUBLIC.name -> LensVerdict.NOT_FOUND
            adminMayDelete && caller.isAdmin() -> LensVerdict.OK
            else -> LensVerdict.FORBIDDEN
        }
        return verdict to creator
    }
}
