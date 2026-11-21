package ch.nokillswit.adrs

import ch.nokillswit.audit.audit
import ch.nokillswit.infra.db.SearchBudget
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.budgetedTransaction
import ch.nokillswit.infra.db.ftsQueryText
import ch.nokillswit.infra.db.ftsRank
import ch.nokillswit.infra.db.ftsVector
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Semaphore
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val AdrListServiceKey = AttributeKey<AdrListService>("AdrListService")

/** The page ceiling of a search: a row carries two headlines, and `ts_headline` is the expensive call (the shared maximum is 100). */
const val MAX_SEARCH_PAGE_SIZE = 20

/**
 * `GET /adrs`: the count and the page on ONE predicate in ONE transaction (`list-endpoints.md`), with the rows' participants, counts
 * and tags read in the same transaction. Without `text` it is the plain list. WITH `text` (0.5.0, `adr-model.md` "Search") it is the
 * full-text search: ONE read transaction under ONE deadline of [timeoutSeconds] ([SearchBudget]: each statement's `queryTimeout`, the only
 * way a statement timeout may be set, is the time LEFT): a statement past it, or a deadline spent between two statements, is the typed
 * `429` `search-timeout`, audited `search.timeout` with ids only, and the throwable is never logged (a database failure's message can
 * echo the term). At most [maxConcurrent] searches run in this process at once: the next one is refused AT ONCE with the typed `429`
 * `search-busy` (a `tryAcquire`, never a wait, before any pool connection is asked for), audited `search.busy`. The term itself reaches no
 * log, no audit line and no problem detail.
 */
class AdrListService(
    private val database: R2dbcDatabase,
    private val participants: AdrParticipantService,
    private val discussion: AdrDiscussionService,
    private val tags: AdrTagService,
    private val timeoutSeconds: Int,
    maxConcurrent: Int,
    private val clock: () -> Long = System::nanoTime,
) {
    private val permits = Semaphore(maxConcurrent)
    private val adrs = AdrService.Adrs

    suspend fun list(filter: AdrListFilter, paging: PageRequest, callerUserId: UInt): AdrListResult =
        searching(filter.text, callerUserId, AdrListResult(emptyList(), 0)) { queryText, budget -> page(filter, queryText, paging, budget) }

    /**
     * The bulk export's plan (0.8.0): which ADRs match [filter], in number order, as the headline columns only - the same predicate, the
     * same `text` guard (deadline, concurrency cap, the typed `429`s) as the list. A match over [cap] answers its total and NO rows (the
     * route refuses it with a `400` before a byte is streamed); at most [cap] rows are ever read.
     */
    suspend fun exportPlan(filter: AdrListFilter, cap: Int, callerUserId: UInt): AdrExportPlan =
        searching(filter.text, callerUserId, AdrExportPlan(0, emptyList())) { queryText, budget -> plan(filter, queryText, cap, budget) }

    /** Runs [block] plainly without `text`, else under the search's permit and deadline (a term with no lexeme: [noMatch]). */
    private suspend fun <T> searching(
        text: String?,
        callerUserId: UInt,
        noMatch: T,
        block: suspend R2dbcTransaction.(queryText: String?, budget: SearchBudget?) -> T,
    ): T {
        text ?: return suspendTransaction(database) { block(null, null) }
        if (!permits.tryAcquire()) {
            audit("search.busy", "byUserId" to callerUserId.toLong())
            throw searchBusy()
        }
        try {
            return budgetedTransaction(database, timeoutSeconds, clock, onTimeout = { timedOut(callerUserId) }) { budget ->
                budget.arm(this)
                val queryText = ftsQueryText(text) ?: return@budgetedTransaction noMatch
                block(queryText, budget)
            }
        } finally {
            permits.release()
        }
    }

    /** The audited `429` `search-timeout` (ids only, never the term); the cause is deliberately neither logged nor attached. */
    private fun timedOut(callerUserId: UInt): Nothing {
        audit("search.timeout", "byUserId" to callerUserId.toLong())
        throw searchTimeout()
    }

    private suspend fun R2dbcTransaction.page(
        filter: AdrListFilter,
        queryText: String?,
        paging: PageRequest,
        budget: SearchBudget? = null,
    ): AdrListResult {
        val predicate = adrListPredicate(filter, queryText) and adrs.active()
        val columns: Map<String, Expression<*>> =
            if (queryText == null) ADR_SORT_COLUMNS else ADR_SORT_COLUMNS + (SORT_RELEVANCE to adrs.ftsVector().ftsRank(queryText))
        budget?.arm(this)
        val total = adrs.selectAll().where { predicate }.count()
        budget?.arm(this)
        val rows = adrs.selectAll().where { predicate }.applyPaging(paging, columns).toList()
        val ids = rows.map { it[adrs.id].value }
        budget?.arm(this)
        val leads = participants.leadsOf(ids)
        val counts = discussion.countsOf(ids)
        val tagsOf = tags.forAdrs(ids)
        budget?.arm(this)
        val matches = if (queryText == null || ids.isEmpty()) emptyMap() else adrMatches(ids, queryText)
        return AdrListResult(
            items = rows.map { row ->
                val id = row[adrs.id].value
                val lead = leads[id].orEmpty()
                row.toItem(
                    lead.firstOrNull { it.role == AdrRole.DRIVER },
                    lead.firstOrNull { it.role == AdrRole.APPROVER },
                    tagsOf[id].orEmpty(),
                    counts[id]?.openIssues ?: 0,
                    matches[id],
                )
            },
            total = total,
        )
    }

    private suspend fun R2dbcTransaction.plan(filter: AdrListFilter, queryText: String?, cap: Int, budget: SearchBudget?): AdrExportPlan {
        val predicate = adrListPredicate(filter, queryText) and adrs.active()
        budget?.arm(this)
        val total = adrs.selectAll().where { predicate }.count()
        if (total > cap) return AdrExportPlan(total, emptyList())
        budget?.arm(this)
        val rows = adrs.select(adrs.id, adrs.number, adrs.name).where { predicate }.orderBy(adrs.number).limit(cap).toList()
        return AdrExportPlan(total, rows.map { AdrExportRow(it[adrs.id].value, it[adrs.number], it[adrs.name]) })
    }

    private fun ResultRow.toItem(
        driver: AdrParticipant?,
        approver: AdrParticipant?,
        tags: List<AdrTag>,
        openIssues: Int,
        match: AdrSearchMatch?,
    ) =
        AdrListItem(
            id = this[adrs.id].value,
            number = this[adrs.number],
            key = adrKey(this[adrs.number]),
            name = this[adrs.name],
            status = AdrStatus.valueOf(this[adrs.status]),
            expiresOn = this[adrs.expiresOn],
            revision = this[adrs.revision],
            createdAt = this[adrs.createdAt],
            updatedAt = this[adrs.updatedAt],
            statusChangedAt = this[adrs.statusChangedAt],
            driver = driver,
            approver = approver,
            tags = tags,
            openIssueCount = openIssues,
            match = match,
        )
}
