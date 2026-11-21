package ch.nokillswit.adrs

import ch.nokillswit.infra.db.active
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** One ADR of a bulk export's plan: enough to name its file and list it. */
data class AdrExportRow(val id: UInt, val number: Int, val name: String)

/** The ADRs a bulk export would contain, in number order; [rows] is empty when [total] is over the cap. */
data class AdrExportPlan(val total: Long, val rows: List<AdrExportRow>)

val AdrExportServiceKey = AttributeKey<AdrExportService>("AdrExportService")

/**
 * The reads behind the Markdown export (0.8.0, `adr-model.md` "Export"): the ADRs as [AdrExportDocument]s, a batch per transaction. READS
 * `adrs`, `adr_participants` (with the users' names), `adr_tags` with `tags` and `tag_categories`, and `adr_events` (the decision date);
 * writes nothing. Reading is open to every authenticated user, like the ADR itself, so there is no standing to compute. A batch is one
 * transaction (a consistent view of its ADRs); the bulk export runs one batch after another, so it never holds more than a batch.
 */
open class AdrExportService(
    private val database: R2dbcDatabase,
    private val participants: AdrParticipantService,
    private val tags: AdrTagService,
    private val successors: AdrSuccessorService,
    private val events: AdrEventService,
) {
    private val adrs = AdrService.Adrs

    /** The live ADRs among [ids] in number order; a missing or soft-deleted one is simply absent. Open only so a test can fail a batch. */
    open suspend fun documents(ids: Collection<UInt>): List<AdrExportDocument> {
        if (ids.isEmpty()) return emptyList()
        return suspendTransaction(database) {
            val rows = adrs.selectAll().where { (adrs.id inList ids.distinct()) and adrs.active() }.orderBy(adrs.number).toList()
            val found = rows.map { it[adrs.id].value }
            val people = participants.forAdrs(found)
            val labels = tags.categorizedForAdrs(found)
            val activated = events.firstActivations(found)
            val predecessors = successors.refsOf(rows.mapNotNull { it[adrs.supersedesId]?.value })
            val replacements = successors.successorsOf(found)
            rows.map { row ->
                val id = row[adrs.id].value
                AdrExportDocument(
                    number = row[adrs.number],
                    name = row[adrs.name],
                    status = AdrStatus.valueOf(row[adrs.status]),
                    expiresOn = row[adrs.expiresOn],
                    problem = row[adrs.problem],
                    context = row[adrs.context],
                    decision = row[adrs.decision],
                    alternatives = row[adrs.alternatives],
                    consequences = row[adrs.consequences],
                    dateMillis = activated[id] ?: row[adrs.createdAt],
                    updatedAt = row[adrs.updatedAt],
                    participants = people[id].orEmpty(),
                    tags = labels[id].orEmpty(),
                    supersedes = row[adrs.supersedesId]?.let { predecessors[it.value] },
                    supersededBy = replacements[id]?.ref,
                    supersededByAccepted = replacements[id]?.accepted == true,
                )
            }
        }
    }

    suspend fun document(id: UInt): AdrExportDocument? = documents(listOf(id)).singleOrNull()
}
