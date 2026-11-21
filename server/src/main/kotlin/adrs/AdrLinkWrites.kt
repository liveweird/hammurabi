package ch.nokillswit.adrs

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.ConflictException
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.revision.requireRevision
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

/**
 * The successor-link writes of an ADR (V14, V19), beside [AdrService] because they share its row-lock and revision machinery. All take
 * the link advisory lock FIRST and the ADR row locks after, never the other way (`persistence.md` "Successor links (V14)").
 */

/**
 * The ONE writer of `supersedes_id`: sets or clears the successor's predecessor AND always clears `supersedes_accepted_id` in the same
 * UPDATE, because an acceptance belongs to one link (a re-point, an unlink and a detach each void it; `adr-model.md` "Successors"). A
 * source pin (`AdrSuccessorAcceptTest`) keeps every other file from assigning either column.
 */
private suspend fun writeLink(successorId: UInt, predecessorId: UInt?) {
    AdrService.Adrs.update({ AdrService.Adrs.id eq successorId }) {
        it[supersedesId] = predecessorId
        it[supersedesAcceptedId] = null
    }
}

/**
 * The marker's one writer beside [writeLink]: accepts the link of [successorId] to the predecessor it already names (the two columns
 * then agree, `ck_adrs_supersedes_accepted`).
 */
private suspend fun writeAcceptance(successorId: UInt) {
    AdrService.Adrs.update({ AdrService.Adrs.id eq successorId }) { it[supersedesAcceptedId] = supersedesId }
}

/**
 * Sets or removes the ADR this one replaces (`PUT .../supersedes`, [AdrSuccessorService.replace]) under `X-Expected-Revision`. The
 * link lock first (cycles span rows), then the row lock; under it: the guard again (403), a terminal ADR (409 `adr-frozen`), the
 * stale revision (409), an `ACTIVE` ADR (409 `adr-frozen`: the link is set before the decision is in force), then the predecessor
 * (400 / 409). Unchanged = null.
 */
suspend fun AdrService.setSupersedes(
    id: UInt,
    caller: CallerPrincipal,
    expectedRevision: Long,
    request: AdrSupersedesRequest,
): AdrSupersedesChange? =
    suspendTransaction(database) {
        events.reserveNotices(caller.userId, id) // the budget row before the link advisory lock
        successors.lockLinkWrites(this)
        val (before, standing) = lockedActive(id, caller)
        requireMayOrganise(standing)
        before.status.requireWritable()
        requireRevision(expectedRevision, before.revision)
        before.status.requireLinkable()
        val stamp = nowMillis()
        val change = successors.replace(before, request.supersedesId, caller.userId, stamp) ?: return@suspendTransaction null
        writeLink(id, request.supersedesId)
        AdrSupersedesChange(id, before.number, bumpRevision(id, before.revision, stamp), change)
    }

/**
 * Detaches the PENDING successor of the ADR [id] (`DELETE .../successor`, the PREDECESSOR's owner): clears the successor's
 * `supersedes_id`, bumps the SUCCESSOR's revision (so its open editors and a racing activation conflict) and records
 * `SUCCESSOR_UNLINKED` on both ADRs. Link lock first, then both rows together in id order (the same pair order an activation uses). Under
 * the locks: the guard again (403), a terminal predecessor (409 `adr-frozen`; a successor that already activated superseded it), then
 * no pending successor (404), including when optional [successorId] names a link that has since been replaced. The predecessor's own
 * revision does not move: its document is untouched. Omitting [successorId] preserves the original blind-detach contract for API clients.
 */
suspend fun AdrService.detachSuccessor(id: UInt, caller: CallerPrincipal, successorId: UInt? = null): AdrDetachChange =
    suspendTransaction(database) {
        events.reserveNotices(caller.userId, id) // the budget row before the link advisory lock
        successors.lockLinkWrites(this)
        val (predecessor, standing) = lockedActive(id, caller, alsoLock = successors.pendingSuccessorIdOf(id))
        requireMayOrganise(standing)
        predecessor.status.requireWritable()
        val successor = successors.pendingSuccessorIdOf(id)
            ?.takeIf { successorId == null || it == successorId }
            ?.let { activeRecord(it) }
            ?: throw NotFoundException(
                if (successorId == null) "This ADR has no pending successor" else "This ADR has no pending successor with that id",
            )
        val stamp = nowMillis()
        writeLink(successor.id, null)
        val revision = bumpRevision(successor.id, successor.revision, stamp)
        val predecessorRef = AdrRef(id, adrKey(predecessor.number), predecessor.name, predecessor.status)
        val successorRef = AdrRef(successor.id, adrKey(successor.number), successor.name, successor.status)
        successors.recordDetached(predecessorRef, successorRef, caller.userId, stamp)
        AdrDetachChange(id, predecessor.number, successorRef, successor.number, revision)
    }

/**
 * The PREDECESSOR's Approver (or ADMIN) accepts its pending successor (`POST .../successor/accept`, 1.0.0): the consent the successor's
 * activation needs while this decision is `ACTIVE`. Judged on the PREDECESSOR [id]; the body names the successor ([successorId]) so a
 * link that was re-pointed since the caller looked is `404`, never a blind acceptance of another ADR. Link lock first, then both rows
 * together in id order (the activation's pair order), so it serialises with a racing detach, re-link and activation. Under the locks:
 * the guard again (403), a terminal predecessor (409 `adr-frozen`), a predecessor not yet in force (409: nothing to accept before it
 * is), the pending successor re-read and equal to [successorId] (404), then already accepted = null (nothing written). Otherwise the
 * marker is written and `SUCCESSOR_ACCEPTED` recorded on both ADRs; neither revision moves (the document and its editors are
 * untouched), and the stale-looking successor is protected instead by the activation reading the marker from its own locked row.
 */
suspend fun AdrService.acceptSuccessor(id: UInt, caller: CallerPrincipal, successorId: UInt): AdrAcceptChange? =
    suspendTransaction(database) {
        events.reserveNotices(caller.userId, id) // the budget row before the link advisory lock
        successors.lockLinkWrites(this)
        val (predecessor, standing) = lockedActive(id, caller, alsoLock = successors.pendingSuccessorIdOf(id))
        requireMayAcceptSuccessor(standing)
        predecessor.status.requireWritable()
        if (predecessor.status != AdrStatus.ACTIVE) {
            throw ConflictException("A ${predecessor.status} ADR is not in force yet: its successor is accepted once it is ACTIVE")
        }
        val successor = successors.pendingSuccessorIdOf(id)?.takeIf { it == successorId }?.let { activeRecord(it) }
            ?: throw NotFoundException("This ADR has no pending successor with that id")
        if (successor.supersedesAccepted) return@suspendTransaction null
        writeAcceptance(successor.id)
        val predecessorRef = AdrRef(id, adrKey(predecessor.number), predecessor.name, predecessor.status)
        val successorRef = AdrRef(successor.id, adrKey(successor.number), successor.name, successor.status)
        successors.recordAccepted(predecessorRef, successorRef, caller.userId, nowMillis())
        AdrAcceptChange(id, predecessor.number, successorRef, successor.number)
    }
