package ch.nokillswit.adrs

/**
 * The notifications' seam into the ADR writes (0.6.0, `adr-model.md` "Notifications"): every history event an ADR write records is offered
 * here, INSIDE the same transaction and right after the event row, so a notice commits or rolls back with the change that caused it and a
 * rolled-back write announces nothing. `notifications/NotificationService` implements it (the composition root passes it to
 * [AdrEventService]); this package names no `notifications` type, so `adrs/` stays below it.
 *
 * Nothing here opens a transaction: the implementation uses the caller's. [byUserId] is the acting user, null for the system (the expiry
 * sweep); [at] is the write's own instant. An event no notice follows is simply ignored. The events of ONE write on ONE ADR arrive
 * together ([AdrEventService.recordAll]), so the notices of the write are made by a single statement however many participants it moves.
 */
fun interface AdrNotices {
    suspend fun onEvents(adrId: UInt, byUserId: UInt?, events: List<AdrEventDescriptor>, at: Long)

    /**
     * Takes, as the FIRST lock of a write that can make notices (before any ADR row and before the numbering or link advisory lock), the
     * lock the notices of that write will need: the budget account they are charged to, which is [byUserId] or, for the system (null),
     * the Approver of [adrId] (null while the ADR does not exist yet). Idempotent inside one
     * transaction, and FINAL: the notices of the transaction are charged to exactly the account (and UTC day) it chose, whatever
     * changes before they are made. The default does nothing.
     */
    suspend fun reserve(byUserId: UInt?, adrId: UInt?) {}

    companion object {
        /** No notices: the default of a test that builds the history service by hand. */
        val NONE: AdrNotices = AdrNotices { _, _, _, _ -> }
    }
}
