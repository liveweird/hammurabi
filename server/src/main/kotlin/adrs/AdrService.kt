package ch.nokillswit.adrs

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.ForbiddenException
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.infra.db.SoftDeletable
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.lockActiveForWrite
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.db.scalarChange
import ch.nokillswit.infra.revision.requireRevision
import ch.nokillswit.users.UserService
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.insertReturning
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import java.time.Clock
import java.time.LocalDate

val AdrServiceKey = AttributeKey<AdrService>("AdrService")

/**
 * The advisory lock every ADR creation takes first (`pg_advisory_xact_lock`, the single-`bigint` form): the ASCII bytes of "ADRNUMBR".
 * It serialises `MAX("number") + 1`, which a row lock cannot (a create has no row to lock yet, and `MAX` over a table is no
 * `FOR UPDATE`). Transaction-scoped: released at commit or rollback, so a failed create never leaves the counter stuck, and one global
 * counter means one lock - the writers of the adrs table queue only for the length of an INSERT plus its participants and event.
 * `uq_adrs_number` (V10) is the backstop if this is ever bypassed. `persistence.md` "ADRs (V10)".
 */
internal const val ADR_NUMBER_LOCK_KEY = 0x4144524E554D4252L

/**
 * The ADR aggregate's writes and reads (V10). Every write is ONE transaction that locks the ADR row (`FOR NO KEY UPDATE`, so the
 * participants' FK checks never queue behind it, persistence.md "Row locks vs. FK locks"), re-reads the caller's role under that lock and
 * re-runs the guards the route ran before the body (a role changed in between), then judges state (the freeze, the lifecycle map), the
 * revision and the requirements in that order, writes, and records its events - so history commits with the change. The route's order is:
 * the caller's standing (404), the coarse guard (403), the `X-Expected-Revision` header (400), the body (400); the service adds, under the
 * lock: the per-target or per-field verdict (403), the typed state conflicts (409), the stale revision (409) and the requirements (400).
 */
class AdrService(
    internal val database: R2dbcDatabase,
    private val participants: AdrParticipantService,
    internal val events: AdrEventService,
    private val discussion: AdrDiscussionService,
    private val tags: AdrTagService,
    internal val successors: AdrSuccessorService,
    private val clock: Clock = Clock.systemUTC(),
) {
    object Adrs : UIntIdTable("adrs"), SoftDeletable {
        /** The global gapless counter, unique over every row by `uq_adrs_number` (V10); allocated by [AdrService.create]. */
        val number = integer("number")

        /** Unique among ACTIVE rows, case- and accent-insensitively, by `uq_adrs_name_active` (V10): not an Exposed index. */
        val name = varchar("name", length = MAX_ADR_NAME_LENGTH)
        val problem = text("problem")
        val context = text("context")
        val decision = text("decision")
        val alternatives = text("alternatives")
        val consequences = text("consequences")
        val status = varchar("status", length = 20)
        val expiresOn = varchar("expires_on", length = 10).nullable()
        val revision = long("revision").default(1L)
        val createdBy = reference("created_by", UserService.Users)
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
        val statusChangedAt = long("status_changed_at")

        /** The ADR this one replaces (V14); set while pre-active, takes effect on activation. A self-reference. */
        val supersedesId = optReference("supersedes_id", this)

        /**
         * The predecessor the successor's Approver-side acceptance was given FOR (V19): the link is accepted when this equals
         * [supersedesId] (`ck_adrs_supersedes_accepted`). Written only by `AdrLinkWrites.kt`, which nulls it with every link change.
         */
        val supersedesAcceptedId = optReference("supersedes_accepted_id", this)
        override val markedAsDeleted = bool("marked_as_deleted").default(false)
    }

    private fun today(): LocalDate = LocalDate.now(clock)

    private fun ResultRow.toRecord() = AdrRecord(
        id = this[Adrs.id].value,
        number = this[Adrs.number],
        name = this[Adrs.name],
        problem = this[Adrs.problem],
        context = this[Adrs.context],
        decision = this[Adrs.decision],
        alternatives = this[Adrs.alternatives],
        consequences = this[Adrs.consequences],
        status = AdrStatus.valueOf(this[Adrs.status]),
        expiresOn = this[Adrs.expiresOn],
        revision = this[Adrs.revision],
        createdBy = this[Adrs.createdBy].value,
        createdAt = this[Adrs.createdAt],
        updatedAt = this[Adrs.updatedAt],
        statusChangedAt = this[Adrs.statusChangedAt],
        supersedesId = this[Adrs.supersedesId]?.value,
        supersedesAcceptedId = this[Adrs.supersedesAcceptedId]?.value,
    )

    private suspend fun activeRow(id: UInt): ResultRow? =
        Adrs.selectAll().where { (Adrs.id eq id) and Adrs.active() }.toList().singleOrNull()

    /** The live ADR [id] as a record, or null when it is missing or soft-deleted (a plain read; the caller holds any lock). */
    internal suspend fun activeRecord(id: UInt): AdrRecord? = activeRow(id)?.toRecord()

    /**
     * The row under the write lock plus the caller's fresh standing; a missing or deleted ADR is a 404. With [alsoLock] (the predecessor an
     * activation supersedes) BOTH rows are locked first, in id order, so two activations touching the same pair cannot deadlock.
     */
    internal suspend fun lockedActive(id: UInt, caller: CallerPrincipal, alsoLock: UInt? = null): Pair<AdrRecord, AdrStanding> {
        if (alsoLock != null) {
            Adrs.select(Adrs.id).where { Adrs.id inList listOf(id, alsoLock) }.orderBy(Adrs.id)
                .forUpdate(ForUpdateOption.PostgreSQL.ForNoKeyUpdate).toList()
        }
        val row = Adrs.selectAll().where { (Adrs.id eq id) and Adrs.active() }
            .forUpdate(ForUpdateOption.PostgreSQL.ForNoKeyUpdate).toList().singleOrNull()
            ?: throw NotFoundException("ADR not found")
        return row.toRecord() to AdrStanding(caller, participants.roleOf(id, caller.userId))
    }

    /** The caller's standing on the ADR, or null when there is no such active ADR (the route's 404, before any guard). */
    suspend fun standing(id: UInt, caller: CallerPrincipal): AdrStanding? = suspendTransaction(database) {
        activeRow(id)?.let { AdrStanding(caller, participants.roleOf(id, caller.userId)) }
    }

    suspend fun exists(id: UInt): Boolean = suspendTransaction(database) { activeRow(id) != null }

    /**
     * Whether the ADR has ever been `IN_REVIEW` (judged in the caller's transaction): a draft sent back from review is guarded like
     * the review itself. No query for the statuses that answer by themselves (`PROBLEM_RAISED` cannot have been, `IN_REVIEW` and
     * `ACTIVE` have been); a `SOLUTION_DRAFT` or a terminal ADR reads its `TRANSITIONED` events.
     */
    private suspend fun everInReview(id: UInt, status: AdrStatus): Boolean = when (status) {
        AdrStatus.PROBLEM_RAISED -> false
        AdrStatus.IN_REVIEW, AdrStatus.ACTIVE -> true
        else -> events.hasEnteredReview(id)
    }

    /** The ADR as [caller] sees it, or null when missing/deleted. */
    suspend fun read(id: UInt, caller: CallerPrincipal): AdrResponse? = suspendTransaction(database) {
        val record = activeRow(id)?.toRecord() ?: return@suspendTransaction null
        val successor = successors.successorOf(id)
        val view = AdrView(
            record, participants.forAdr(id), tags.forAdrs(listOf(id))[id].orEmpty(),
            successors.refOf(record.supersedesId), successor?.ref, successor?.accepted == true, everInReview(id, record.status),
        )
        val counts = discussion.countsOf(listOf(id))[id] ?: DiscussionCounts()
        view.toResponse(AdrStanding(caller, view.participants.firstOrNull { it.userId == caller.userId }?.role), counts)
    }

    /** `MAX("number") + 1` over EVERY row (a soft-deleted ADR keeps its number), under [ADR_NUMBER_LOCK_KEY], held until the commit. */
    private suspend fun R2dbcTransaction.nextNumber(): Int {
        exec("SELECT pg_advisory_xact_lock($ADR_NUMBER_LOCK_KEY)", explicitStatementType = StatementType.SELECT) { true }?.toList()
        val highest = Adrs.number.max()
        return (Adrs.select(highest).toList().single()[highest] ?: 0) + 1
    }

    private fun requireNotPast(expiresOn: String) {
        if (parseExpiresOn(expiresOn).isBefore(today())) throw BadRequestException("expiresOn must not be in the past")
    }

    /**
     * Creates the ADR in `PROBLEM_RAISED` with the caller as its Driver, the initial participants, the number and the `CREATED` event in
     * ONE transaction. The users and the dates are checked before the counter lock is taken, so the lock covers the inserts only. A
     * name already in use by an active ADR (a defaulted one too) is the central 23505 to 409.
     */
    suspend fun create(caller: CallerPrincipal, raw: AdrCreateRequest): CreatedAdr {
        // Sanitised AND validated service-side (both idempotent after the route): a direct caller can never hand a control character on.
        val request = sanitizedAdrCreate(raw)
        validateAdrCreate(request)
        if (request.participants.any { it.userId == caller.userId }) throw BadRequestException("The creator is already the Driver")
        val name = request.name ?: defaultAdrName(request.problem)
        request.expiresOn?.let(::requireNotPast)
        return suspendTransaction(database) {
            val names = participants.requireActiveUsers(request.participants.map { it.userId })
            // The mail budget row first, before the numbering lock: the wait for the caller's other writes never holds the global lock.
            if (request.participants.isNotEmpty()) events.reserveNotices(caller.userId, null)
            val number = nextNumber()
            val stamp = nowMillis()
            // `insertReturning(id)`, not `insert`: a plain insert answers `RETURNING *`, which would send the generated `search` vector
            // (V15) back. The generated column is never in this table object, so only the id returns.
            val id = Adrs.insertReturning(listOf(Adrs.id)) {
                it[Adrs.number] = number
                it[Adrs.name] = name
                it[problem] = request.problem
                it[context] = request.context
                it[decision] = request.decision
                it[alternatives] = request.alternatives
                it[consequences] = request.consequences
                it[status] = AdrStatus.PROBLEM_RAISED.name
                it[expiresOn] = request.expiresOn
                it[createdBy] = caller.userId
                it[createdAt] = stamp
                it[updatedAt] = stamp
                it[statusChangedAt] = stamp
            }.single()[Adrs.id].value
            events.record(id, caller.userId, adrCreatedEvent(number, name), stamp)
            participants.insertForCreation(id, caller.userId, request.participants, names, stamp)
            CreatedAdr(id, number)
        }
    }

    /**
     * The document PUT under `X-Expected-Revision`. Under the lock, in order: the coarse guard again (403), a terminal ADR (409
     * `adr-frozen`), the stale revision (409 - before any per-field judgement, so a stale save never reads as a false 403 or 400), then per
     * field what changed: content in a status that froze it (409 `adr-frozen`) or by someone who may not edit it (403), the expiry date by
     * someone who may not (403; once `ACTIVE` only the Approver or ADMIN) or into the past (400). Nothing changed answers null and
     * writes nothing (no revision bump, no event).
     * Otherwise one `UPDATED` event lists the changed fields.
     */
    suspend fun update(id: UInt, caller: CallerPrincipal, expectedRevision: Long, raw: AdrUpdateRequest): AdrChange? {
        val request = sanitizedAdrUpdate(raw)
        validateAdrUpdate(request)
        return suspendTransaction(database) {
            events.reserveNotices(caller.userId, id) // the budget row first, then the ADR row (security.md "Notices")
            val (before, standing) = lockedActive(id, caller)
            requireMayEdit(standing)
            before.status.requireWritable()
            requireRevision(expectedRevision, before.revision)
            val content = contentChanges(before, request)
            val expiry = scalarChange("expiresOn", before.expiresOn, request.expiresOn)
            if (content.isNotEmpty()) {
                before.status.requireContentEditable()
                if (!canEditContent(standing, before.status)) {
                    throw ForbiddenException("Your role on this ADR does not allow editing its content")
                }
            }
            if (expiry != null) {
                if (!canEditExpiry(standing, before.status)) {
                    throw ForbiddenException(
                        if (before.status == AdrStatus.ACTIVE) {
                            "Only the Approver or an administrator may change the expiry date of a decision in force"
                        } else {
                            "Your role on this ADR does not allow changing its expiry date"
                        },
                    )
                }
                request.expiresOn?.let(::requireNotPast)
            }
            val changes = content + listOfNotNull(expiry)
            if (changes.isEmpty()) return@suspendTransaction null
            val stamp = nowMillis()
            val revision = before.revision + 1
            Adrs.update({ Adrs.id eq id }) {
                it[name] = request.name
                it[problem] = request.problem
                it[context] = request.context
                it[decision] = request.decision
                it[alternatives] = request.alternatives
                it[consequences] = request.consequences
                it[expiresOn] = request.expiresOn
                it[Adrs.revision] = revision
                it[updatedAt] = stamp
            }
            events.record(id, caller.userId, adrUpdatedEvent(changes), stamp)
            AdrChange(id, before.number, revision, changes.map { it.field })
        }
    }

    /**
     * A lifecycle move under `X-Expected-Revision`. Under the lock, in order: the coarse guard (403), the per-target verdict (403: the
     * Driver moves forward and cancels, the Approver activates or withdraws, either sends back, ADMIN any), the lifecycle map (409
     * `illegal-transition`, including a target only the system moves into), the stale revision (409), a successor over a decision in
     * force that was not accepted (409 `successor-not-accepted`), the optional [reason] (400: control characters, over 1000 code points)
     * and the target's requirements (400 naming the missing fields); then the status, its stamp and the revision are written and one
     * `TRANSITIONED` event recorded (carrying the reason); an ending that replaces nothing also tells the pending successor.
     */
    suspend fun transition(
        id: UInt,
        caller: CallerPrincipal,
        expectedRevision: Long,
        to: AdrStatus,
        reason: String? = null,
    ): AdrTransitionOutcome =
        suspendTransaction(database) {
            // An activation also moves the ADR it replaces, so both rows are locked together (the peek is a plain read: the revision
            // check below catches a link changed in between, since every link change bumps the revision).
            events.reserveNotices(caller.userId, id)
            val peeked = if (to == AdrStatus.ACTIVE) successors.predecessorIdOf(id) else null
            if (to.endedUnreplaced) { // an ending tells the pending successor: the link lock, then both rows in id order
                successors.lockLinkWrites(this)
                successors.lockWithPendingSuccessor(id)
            }
            val (before, standing) = lockedActive(id, caller, alsoLock = peeked)
            requireMayTransition(standing)
            requireMayRequest(standing, before.status, to)
            before.status.requireTransitionTo(to)
            requireRevision(expectedRevision, before.revision)
            val predecessor = if (to == AdrStatus.ACTIVE) successors.refOf(before.supersedesId) else null
            // The acceptance is read from the row locked above (never a peek), right after the revision, before the 400s.
            requireSuccessorAccepted(before, predecessor)
            val note = sanitizedTransitionReason(reason)
            requireTransitionRequirements(to, before, participants.hasApprover(id), today(), predecessor)
            val stamp = nowMillis()
            val revision = bumpRevision(id, before.revision, stamp, moveTo = to)
            events.record(id, caller.userId, adrTransitionedEvent(before.status, to, note), stamp)
            if (to.endedUnreplaced) successors.recordPredecessorEnded(before.refAs(to), caller.userId, stamp)
            val superseded = predecessor?.let { supersede(it, AdrRef(id, adrKey(before.number), before.name, to), caller.userId, stamp) }
            AdrTransitionOutcome(id, before.number, revision, before.status, to, superseded, note != null)
        }

    /** Moves the ACTIVE [predecessor] (locked by the caller) to `SUPERSEDED` with its event; a [successor] activation's other half. */
    private suspend fun supersede(predecessor: AdrRef, successor: AdrRef, byUserId: UInt, stamp: Long): AdrSuperseded {
        val row = Adrs.selectAll().where { Adrs.id eq predecessor.id }.toList().single()
        val revision = bumpRevision(predecessor.id, row[Adrs.revision], stamp, moveTo = AdrStatus.SUPERSEDED)
        events.record(predecessor.id, byUserId, adrSupersededEvent(successor), stamp)
        events.record(successor.id, byUserId, adrSuccessorActivatedEvent(predecessor), stamp)
        return AdrSuperseded(predecessor.id, row[Adrs.number], revision)
    }

    /** One UPDATE of the row: the revision after [current], `updated_at` [stamp] and, for a move, the new status; returns the revision. */
    internal suspend fun bumpRevision(id: UInt, current: Long, stamp: Long, moveTo: AdrStatus? = null): Long {
        Adrs.update({ Adrs.id eq id }) {
            it[revision] = current + 1
            it[updatedAt] = stamp
            if (moveTo != null) {
                it[status] = moveTo.name
                it[statusChangedAt] = stamp
            }
        }
        return current + 1
    }

    /**
     * Replaces the participants (a diff, [AdrParticipantService.replace]) under `X-Expected-Revision` and the lock: the guard again
     * (403), no self-approval (403), a terminal ADR (409 `adr-frozen`), the stale revision (409), then who holds the Approver seat
     * against the locked set ([requireMayChangeApproverSeat], 403), the users (400). A real change bumps the revision and `updated_at`,
     * so a stale reader's next write conflicts instead of silently dropping people; a no-op bumps nothing. Null = the set did not
     * change.
     */
    suspend fun replaceParticipants(
        id: UInt,
        caller: CallerPrincipal,
        expectedRevision: Long,
        request: AdrParticipantsRequest,
    ): AdrParticipantsChange? {
        validateParticipantSet(request.participants)
        return suspendTransaction(database) {
            events.reserveNotices(caller.userId, id)
            val (before, standing) = lockedActive(id, caller)
            requireMayManage(standing)
            requireNotSelfApproval(standing, request.participants)
            before.status.requireWritable()
            requireRevision(expectedRevision, before.revision)
            requireMayChangeApproverSeat(
                standing, before.status, everInReview(id, before.status), participants.forAdr(id), request.participants,
            )
            val stamp = nowMillis()
            val diff = participants.replace(id, caller.userId, request.participants, stamp)
            if (diff.isEmpty) return@suspendTransaction null
            AdrParticipantsChange(id, before.number, bumpRevision(id, before.revision, stamp), diff)
        }
    }

    /**
     * Replaces the tags (a diff, [AdrTagService.replace]) under `X-Expected-Revision` and the lock: the guard again (403), a terminal ADR
     * (409 `adr-frozen`), the stale revision (409), then the names (400: unknown or retired). Tags are NOT part of the content freeze, so
     * an `ACTIVE` ADR takes them. A real change bumps the revision; a no-op bumps nothing (null).
     */
    suspend fun replaceTags(id: UInt, caller: CallerPrincipal, expectedRevision: Long, raw: AdrTagsRequest): AdrTagsChange? {
        val request = normalizedAdrTags(raw)
        validateAdrTags(request)
        return suspendTransaction(database) {
            val (before, standing) = lockedActive(id, caller)
            requireMayOrganise(standing)
            before.status.requireWritable()
            requireRevision(expectedRevision, before.revision)
            val stamp = nowMillis()
            val diff = tags.replace(id, caller.userId, request.tags, stamp)
            if (diff.isEmpty) return@suspendTransaction null
            AdrTagsChange(id, before.number, bumpRevision(id, before.revision, stamp), diff)
        }
    }

    /** Soft delete (ADMIN, the route guards): the ADR stops reading as active, frees its name, keeps its number. `0` = missing (404). */
    suspend fun delete(id: UInt): Int = suspendTransaction(database) {
        if (!Adrs.lockActiveForWrite(id)) return@suspendTransaction 0
        Adrs.update({ (Adrs.id eq id) and Adrs.active() }) {
            it[markedAsDeleted] = true
            it[updatedAt] = nowMillis()
        }
    }
}
