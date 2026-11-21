package ch.nokillswit

import ch.nokillswit.adrs.AdrOverviewResponse
import ch.nokillswit.adrs.AdrResponse
import ch.nokillswit.adrs.AdrStatus
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The decision log over HTTP (0.9.0, `adr-model.md` "Overview"): the shape, the groups, the ordering and the caller's own queue. ADRs are
 * shared suite state, so every test mints its own categories and tags (named to sort first) and finds its ADRs by id; the caps and the
 * horizon are pinned in [AdrOverviewCapsTest], which controls "today".
 */
class AdrOverviewTest {
    private companion object {
        /** Earlier than any overdue date another test forces (2000-01-01 and later): a row carrying it heads the shared "expiring soon". */
        const val OWN_OVERDUE_DAY = "1900-01-01"
    }

    private suspend fun HttpClient.overview(): AdrOverviewResponse {
        val response = get("/api/v1/adrs/overview")
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body()
    }

    private suspend fun AdrCast.raiseTagged(vararg tags: String): AdrResponse {
        val adr = raise()
        assertEquals(HttpStatusCode.NoContent, driver.client.setTags(adr.id, tags.toList()).status)
        return adr
    }

    /** Runs [block] with a fresh category (sorted before every other test's) holding [tags], and retires it afterwards. */
    private suspend fun ApplicationTestBuilder.withCategory(
        cast: AdrCast,
        tags: List<String>,
        block: suspend (name: String) -> Unit,
    ) {
        val category = cast.admin.client.createCategory(name = categoryName("000 Overview"), tags = tags)
        try {
            block(category.name)
        } finally {
            cast.admin.client.deleteCategory(category.id)
        }
    }

    @Test
    fun `any signed-in user reads the log - every status counted in lifecycle order`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val before = cast.stranger.client.overview()
        assertEquals(AdrStatus.entries, before.counts.map { it.status })
        assertEquals(before.counts.single { it.status == AdrStatus.ACTIVE }.count, before.inForce.total)
        assertEquals(before.inForce.total, before.recentlyDecided.total)
        assertEquals(before.counts.single { it.status == AdrStatus.IN_REVIEW }.count, before.awaitingDecision.total)
        assertEquals(30, before.expiringSoon.days)
        assertTrue(before.generatedAt > 0)

        cast.activate(cast.raise())
        val after = cast.stranger.client.overview()
        assertEquals(before.inForce.total + 1, after.inForce.total)
        fun List<ch.nokillswit.adrs.AdrStatusCount>.active() = single { it.status == AdrStatus.ACTIVE }.count
        assertEquals(before.counts.active() + 1, after.counts.active())
    }

    @Test
    fun `an activated ADR is listed under each of its live tags, newest first, and leads recently decided`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val first = uniqueTag("a")
        val second = uniqueTag("b")
        withCategory(cast, listOf(second, first)) { category ->
            val both = cast.raiseTagged(first, second)
            val onlySecond = cast.raiseTagged(second)
            cast.activate(both)
            cast.activate(onlySecond)

            val log = cast.stranger.client.overview()
            val group = log.inForce.categories.single { it.name == category }
            assertEquals(listOf(first, second), group.tags.map { it.name }, "alphabetical")
            val (a, b) = group.tags
            assertEquals(listOf(both.id), a.items.map { it.id })
            assertEquals(listOf(onlySecond.id, both.id), b.items.map { it.id }, "newest number first")
            assertEquals(listOf(1L, 2L), listOf(a.total, b.total))
            assertEquals(listOf(0L, 0L), listOf(a.more, b.more))
            val row = b.items.first()
            assertEquals(onlySecond.key, row.key)
            assertEquals(onlySecond.name, row.name)
            assertEquals(onlySecond.number, row.number)
            assertNull(row.expiresOn)

            assertEquals(listOf(onlySecond.id, both.id), log.recentlyDecided.items.take(2).map { it.id }, "the latest activation leads")
            assertTrue(log.recentlyDecided.items.first().statusChangedAt >= log.recentlyDecided.items[1].statusChangedAt)
        }
    }

    @Test
    fun `only ACTIVE ADRs are in force - a draft, a review and a deleted one are not`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val tag = uniqueTag()
        withCategory(cast, listOf(tag)) { category ->
            val draft = cast.raiseTagged(tag)
            val review = cast.raiseTagged(tag)
            cast.driver.client.moveAdrNow(review.id, AdrStatus.IN_REVIEW)
            val deleted = cast.raiseTagged(tag)
            cast.activate(deleted)
            val kept = cast.raiseTagged(tag)
            cast.activate(kept)
            assertEquals(HttpStatusCode.NoContent, cast.admin.client.delete("/api/v1/adrs/${deleted.id}").status)

            val log = cast.stranger.client.overview()
            assertEquals(listOf(kept.id), log.inForce.categories.single { it.name == category }.tags.single().items.map { it.id })
            val everyId = (
                log.recentlyDecided.items.map { it.id } + log.inForce.untagged.items.map { it.id } + log.expiringSoon.items.map { it.id }
                ).toSet()
            assertFalse(deleted.id in everyId)
            assertFalse(draft.id in everyId)
            assertFalse(review.id in log.recentlyDecided.items.map { it.id })
        }
    }

    @Test
    fun `an ACTIVE ADR with no live tag is untagged - a retired tag does not group it`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val bare = cast.raise()
        cast.activate(bare)
        val retired = uniqueTag()
        lateinit var withRetired: AdrResponse
        withCategory(cast, listOf(retired)) { category ->
            withRetired = cast.raiseTagged(retired)
            cast.activate(withRetired)
            val grouped = cast.stranger.client.overview()
            val group = grouped.inForce.categories.single { it.name == category }.tags.single()
            assertEquals(listOf(withRetired.id), group.items.map { it.id })
            assertFalse(withRetired.id in grouped.inForce.untagged.items.map { it.id })
        }
        // The block's end retired the category and its tag; the ADR keeps the tag, which no longer groups it.
        val log = cast.stranger.client.overview()
        assertTrue(log.inForce.categories.none { category -> category.tags.any { tag -> tag.name == retired } })
        val untagged = log.inForce.untagged.items.map { it.id }
        assertTrue(bare.id in untagged)
        assertTrue(withRetired.id in untagged)
        assertTrue(log.inForce.untagged.total >= 2)
    }

    @Test
    fun `an ADR in review waits for its Approver - mine is the caller's, the oldest wait leads`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val older = cast.raise()
        val newer = cast.raise()
        listOf(older, newer).forEach { cast.driver.client.moveAdrNow(it.id, AdrStatus.IN_REVIEW) }
        // The API stamps the wall clock; ancient, distinct instants put these two at the head of the shared queue, oldest first.
        forceAdrStatusChangedAt(older.id, 1_000L + older.id.toLong())
        forceAdrStatusChangedAt(newer.id, 2_000L + newer.id.toLong())

        val approversView = cast.approver.client.overview()
        val queue = approversView.awaitingDecision.items
        assertEquals(listOf(older.id, newer.id), queue.take(2).map { it.id }, "the longest wait first")
        assertTrue(queue.take(2).all { it.mine })
        assertEquals(2L, approversView.awaitingDecision.mineTotal)
        val row = queue.first()
        assertEquals(older.key, row.key)
        assertEquals(1_000L + older.id.toLong(), row.statusChangedAt)
        assertEquals(cast.approver.id, row.approver?.userId)
        assertEquals("Apr", row.approver?.name)

        val strangersView = cast.stranger.client.overview()
        assertEquals(listOf(older.id, newer.id), strangersView.awaitingDecision.items.take(2).map { it.id })
        assertTrue(strangersView.awaitingDecision.items.take(2).none { it.mine })
        assertEquals(0L, strangersView.awaitingDecision.mineTotal)
        assertEquals(0L, cast.driver.client.overview().awaitingDecision.mineTotal, "the Driver does not decide")
        val adminsView = cast.admin.client.overview().awaitingDecision
        assertEquals(listOf(older.id, newer.id), adminsView.items.take(2).map { it.id })
        assertTrue(adminsView.items.take(2).none { it.mine }, "an ADMIN who is not the Approver is not the one it waits for")
        assertEquals(0L, adminsView.mineTotal)

        // Once decided it leaves the queue and the caller's count.
        cast.approver.client.moveAdrNow(older.id, AdrStatus.ACTIVE)
        val later = cast.approver.client.overview()
        assertEquals(1L, later.awaitingDecision.mineTotal)
        assertFalse(older.id in later.awaitingDecision.items.map { it.id })
        forceAdrStatus(newer.id, AdrStatus.CANCELLED) // leave no ancient review behind for the cap test's queue
    }

    @Test
    fun `an overdue date leads expiring soon and a far one stays out`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val overdue = cast.raise()
        cast.activate(overdue)
        // The section is a capped, shared head: a date earlier than any other test's (they use 2000 and later) puts OUR row first, so the
        // assertions below read rows this test owns and not whatever else is overdue right now.
        forceAdrExpiry(overdue.id, OWN_OVERDUE_DAY)
        val far = cast.raise()
        cast.activate(far)
        cast.driver.client.getAdr(far.id).let {
            assertEquals(
                HttpStatusCode.NoContent,
                cast.approver.client.saveAdr(far.id, it.revision, it.asUpdate().copy(expiresOn = "2999-01-01")).status,
            )
        }

        val log = cast.stranger.client.overview()
        val listed = log.expiringSoon.items.map { it.id }
        assertTrue(overdue.id in listed)
        assertFalse(far.id in listed)
        assertEquals(OWN_OVERDUE_DAY, log.expiringSoon.items.single { it.id == overdue.id }.expiresOn)
        assertEquals(overdue.id, log.expiringSoon.items.first().id, "the earliest date leads")
        assertTrue(log.expiringSoon.items.zipWithNext().all { (a, b) -> a.expiresOn.orEmpty() <= b.expiresOn.orEmpty() }, "soonest first")
        // The list has no lower bound: leave no overdue ACTIVE ADR behind for the cap tests' horizon.
        forceAdrStatus(overdue.id, AdrStatus.EXPIRED)
    }

    @Test
    fun `dropping one tag from a category that stays live leaves its ADR untagged`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val keep = uniqueTag("k")
        val drop = uniqueTag("d")
        val category = cast.admin.client.createCategory(name = categoryName("000 Overview"), tags = listOf(keep, drop))
        try {
            val adr = cast.raiseTagged(drop)
            cast.activate(adr)
            val grouped = cast.stranger.client.overview().inForce
            assertEquals(listOf(adr.id), grouped.categories.single { it.name == category.name }.tags.single().items.map { it.id })
            assertFalse(adr.id in grouped.untagged.items.map { it.id })

            assertTrue(cast.admin.client.saveCategory(category.id, listOf(keep), category.name).status.isSuccess())

            val after = cast.stranger.client.overview().inForce
            assertTrue(after.categories.none { it.name == category.name }, "the category is live, but no ACTIVE ADR has a live tag of it")
            assertTrue(adr.id in after.untagged.items.map { it.id }, "the retired tag no longer groups the ADR")
        } finally {
            cast.admin.client.deleteCategory(category.id)
        }
    }

    @Test
    fun `a send back and a resubmission re-stamp the review, the activation re-stamps again`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        cast.driver.client.moveAdrNow(adr.id, AdrStatus.IN_REVIEW)
        val firstReview = cast.driver.client.getAdr(adr.id).statusChangedAt
        delay(5)
        cast.approver.client.moveAdrNow(adr.id, AdrStatus.SOLUTION_DRAFT)
        delay(5)
        cast.driver.client.moveAdrNow(adr.id, AdrStatus.IN_REVIEW)
        val resubmitted = cast.driver.client.getAdr(adr.id).statusChangedAt
        assertTrue(resubmitted > firstReview, "the wait restarts at the resubmission: $firstReview then $resubmitted")
        delay(5)
        cast.approver.client.moveAdrNow(adr.id, AdrStatus.ACTIVE)
        val activated = cast.driver.client.getAdr(adr.id).statusChangedAt
        assertTrue(activated > resubmitted)
        val row = cast.stranger.client.overview().recentlyDecided.items.single { it.id == adr.id }
        assertEquals(activated, row.statusChangedAt, "decided when it was finally activated, not when it was first submitted")
    }

    @Test
    fun `a superseded predecessor leaves recently decided and its successor enters it`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val predecessor = cast.raise()
        cast.activate(predecessor)
        assertTrue(predecessor.id in cast.stranger.client.overview().recentlyDecided.items.map { it.id })
        val successor = cast.raise()
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.setSupersedes(successor.id, predecessor.id).status)
        cast.approver.client.acceptSuccessorOk(predecessor.id, successor.id)
        cast.activate(successor)
        assertEquals(AdrStatus.SUPERSEDED, cast.driver.client.getAdr(predecessor.id).status)

        val log = cast.stranger.client.overview()
        val recent = log.recentlyDecided.items.map { it.id }
        assertTrue(successor.id in recent)
        assertFalse(predecessor.id in recent)
        assertFalse(predecessor.id in log.inForce.untagged.items.map { it.id })
        assertTrue(successor.id in log.inForce.untagged.items.map { it.id })
    }

    @Test
    fun `the log is for signed-in users only`() = testApplication {
        usePostgresTestcontainer()
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get("/api/v1/adrs/overview").status)
    }
}
