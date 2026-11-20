package ch.nokillswit.adrs

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.revision.requireRevision
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

/**
 * The two successor-link writes of an ADR (V14), beside [AdrService] because they share its row-lock and revision machinery. Both take
 * the link advisory lock FIRST and the ADR row locks after, never the other way (`persistence.md` "Successor links (V14)").
 */

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
        successors.lockLinkWrites(this)
        val (before, standing) = lockedActive(id, caller)
        requireMayOrganise(standing)
        before.status.requireWritable()
        requireRevision(expectedRevision, before.revision)
        before.status.requireLinkable()
        val stamp = nowMillis()
        val change = successors.replace(before, request.supersedesId, caller.userId, stamp) ?: return@suspendTransaction null
        AdrService.Adrs.update({ AdrService.Adrs.id eq id }) { it[supersedesId] = request.supersedesId }
        AdrSupersedesChange(id, before.number, bumpRevision(id, before.revision, stamp), change)
    }

/**
 * Detaches the PENDING successor of the ADR [id] (`DELETE .../successor`, the PREDECESSOR's owner): clears the successor's
 * `supersedes_id`, bumps the SUCCESSOR's revision (so its open editors and a racing activation conflict) and records
 * `SUCCESSOR_UNLINKED` on both ADRs. Link lock first, then both rows together in id order (the same pair order an activation uses). Under
 * the locks: the guard again (403), a terminal predecessor (409 `adr-frozen`; a successor that already activated superseded it), then
 * no pending successor (404). The predecessor's own revision does not move: its document is untouched.
 */
suspend fun AdrService.detachSuccessor(id: UInt, caller: CallerPrincipal): AdrDetachChange =
    suspendTransaction(database) {
        successors.lockLinkWrites(this)
        val (predecessor, standing) = lockedActive(id, caller, alsoLock = successors.pendingSuccessorIdOf(id))
        requireMayOrganise(standing)
        predecessor.status.requireWritable()
        val successor = successors.pendingSuccessorIdOf(id)?.let { activeRecord(it) }
            ?: throw NotFoundException("This ADR has no pending successor")
        val stamp = nowMillis()
        AdrService.Adrs.update({ AdrService.Adrs.id eq successor.id }) { it[supersedesId] = null }
        val revision = bumpRevision(successor.id, successor.revision, stamp)
        val predecessorRef = AdrRef(id, adrKey(predecessor.number), predecessor.name, predecessor.status)
        val successorRef = AdrRef(successor.id, adrKey(successor.number), successor.name, successor.status)
        successors.recordDetached(predecessorRef, successorRef, caller.userId, stamp)
        AdrDetachChange(id, predecessor.number, successorRef, successor.number, revision)
    }
