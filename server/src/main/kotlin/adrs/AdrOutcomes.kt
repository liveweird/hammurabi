package ch.nokillswit.adrs

/** What a create made: the row id (the URL) and the allocated number. */
data class CreatedAdr(val id: UInt, val number: Int)

/** What a document PUT changed (null from [AdrService.update] = nothing did): the audit line reads it. */
data class AdrChange(val id: UInt, val number: Int, val revision: Long, val changed: List<String>)

/** The ADR a transition superseded: its id, number and new revision (the audit line reads it). */
data class AdrSuperseded(val id: UInt, val number: Int, val revision: Long)

/** What a transition did; [superseded] is the predecessor an activation moved to `SUPERSEDED` in the same transaction, if any. */
data class AdrTransitionOutcome(
    val id: UInt,
    val number: Int,
    val revision: Long,
    val from: AdrStatus,
    val to: AdrStatus,
    val superseded: AdrSuperseded? = null,
)

/** What a participants PUT did (null from [AdrService.replaceParticipants] = nothing). */
data class AdrParticipantsChange(val id: UInt, val number: Int, val revision: Long, val diff: ParticipantDiff)

/** What a tags PUT did (null from [AdrService.replaceTags] = nothing). */
data class AdrTagsChange(val id: UInt, val number: Int, val revision: Long, val diff: TagDiff)

/** What a successor-link PUT did (null from [AdrService.setSupersedes] = nothing). */
data class AdrSupersedesChange(val id: UInt, val number: Int, val revision: Long, val change: SuccessorChange)

/** What a detach did: the predecessor (route id) and the successor it was freed of, with the successor's new revision. */
data class AdrDetachChange(val id: UInt, val number: Int, val successor: AdrRef, val successorNumber: Int, val successorRevision: Long)
