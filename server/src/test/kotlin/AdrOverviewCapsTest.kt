package ch.nokillswit

import ch.nokillswit.adrs.AdrEventService
import ch.nokillswit.adrs.AdrOverviewService
import ch.nokillswit.adrs.AdrParticipantService
import ch.nokillswit.adrs.AdrResponse
import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.adrs.OVERVIEW_EXPIRING
import ch.nokillswit.adrs.OVERVIEW_PER_TAG
import ch.nokillswit.adrs.OVERVIEW_RECENT
import ch.nokillswit.adrs.OVERVIEW_TAG_GROUPS
import io.ktor.client.request.delete
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The decision log's bounds (0.9.0, `list-endpoints.md`): the expiry horizon and every cap, driven through the service with an injected
 * "today" so each test works in a year no other test's data touches (the shared database holds every other test's ADRs; the expiry
 * list has no lower bound, so the horizon-bound tests sit in 1990 and 1991, before any date another test forces). A cap is only
 * proven by an excess, so these mint 21 or 120 rows through the API and force the states the API would take a lifecycle to reach.
 */
class AdrOverviewCapsTest {
    /** An instant long before any real status change, after [AdrOverviewTest]'s own: the head of the shared queue, behind that test's. */
    private val ancient = 10_000_000L

    /** The shared instant of the tie test's queue: behind [AdrOverviewTest]'s own (1000 + id), ahead of [ancient]. */
    private val tieInstant = 5_000_000L

    /** The overview for [caller] as it reads on [today] (an injected clock) with a horizon of [days] and a tag-group cap of [cap]. */
    private suspend fun overviewOn(
        today: LocalDate,
        caller: UInt,
        days: Int = 30,
        cap: Int = OVERVIEW_TAG_GROUPS,
    ) = AdrOverviewService(
        sharedDatabaseForTests(),
        AdrParticipantService(AdrEventService(sharedDatabaseForTests())),
        days,
        cap,
        Clock.fixed(today.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
    ).overview(caller)

    private suspend fun AdrCast.activeAt(expiresOn: String?): AdrResponse {
        val adr = driver.client.createAdr()
        forceAdrStatus(adr.id, AdrStatus.ACTIVE)
        forceAdrExpiry(adr.id, expiresOn)
        return adr
    }

    @Test
    fun `expiring soon holds the ACTIVE ADRs up to the horizon, overdue ones first`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val today = LocalDate.of(1990, 6, 15)
        val overdue = cast.activeAt("1990-06-14")
        val onTheDay = cast.activeAt("1990-06-15")
        val lastDay = cast.activeAt("1990-07-15")
        val beyond = cast.activeAt("1990-07-16")
        val undated = cast.activeAt(null)
        val inReview = cast.driver.client.createAdr().also {
            forceAdrStatus(it.id, AdrStatus.IN_REVIEW)
            forceAdrExpiry(it.id, "1990-06-20")
        }
        val deleted = cast.activeAt("1990-06-20")
        assertEquals(HttpStatusCode.NoContent, cast.admin.client.delete("/api/v1/adrs/${deleted.id}").status)
        val mine = setOf(overdue, onTheDay, lastDay, beyond, undated, inReview, deleted).map { it.id }.toSet()
        try {
            val section = overviewOn(today, cast.stranger.id).expiringSoon
            assertEquals(30, section.days)
            assertEquals(
                listOf(overdue.id, onTheDay.id, lastDay.id),
                section.items.map { it.id }.filter { it in mine },
                "the 30th day is in, the 31st is out; a draft-ish, undated or deleted ADR never is",
            )
            assertEquals(listOf("1990-06-14", "1990-06-15", "1990-07-15"), section.items.map { it.expiresOn })

            // A shorter horizon (days = 1) keeps the overdue one and the day itself, from the same data.
            val tomorrow = overviewOn(today, cast.stranger.id, days = 1).expiringSoon
            assertEquals(listOf(overdue.id, onTheDay.id), tomorrow.items.map { it.id }.filter { it in mine })
        } finally {
            mine.forEach { forceAdrStatus(it, AdrStatus.EXPIRED) } // the list has no lower bound: leave nothing overdue behind
        }
    }

    @Test
    fun `expiring soon is capped and counts what it left out`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val ids = (1..OVERVIEW_EXPIRING + 1).map { cast.activeAt("1991-03-${(9 + it).toString().padStart(2, '0')}").id }
        try {
            val section = overviewOn(LocalDate.of(1991, 3, 9), cast.stranger.id).expiringSoon
            assertEquals(OVERVIEW_EXPIRING, section.items.size)
            assertTrue(section.total >= ids.size)
            assertEquals(section.total - OVERVIEW_EXPIRING, section.more)
            val listed = section.items.map { it.id }.filter { it in ids }
            assertEquals(ids.take(OVERVIEW_EXPIRING), listed, "soonest first; the latest of ours is cut")
        } finally {
            ids.forEach { forceAdrStatus(it, AdrStatus.EXPIRED) } // the list has no lower bound: leave nothing overdue behind
        }
    }

    @Test
    fun `a tag lists its newest 20 and counts the rest`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val tag = uniqueTag()
        val category = cast.admin.client.createCategory(name = categoryName("000 Overview"), tags = listOf(tag))
        try {
            val adrs = (1..OVERVIEW_PER_TAG + 1).map {
                val adr = cast.driver.client.createAdr()
                assertEquals(HttpStatusCode.NoContent, cast.driver.client.setTags(adr.id, listOf(tag)).status)
                forceAdrStatus(adr.id, AdrStatus.ACTIVE)
                adr
            }
            val group = overviewOn(LocalDate.of(2003, 1, 1), cast.stranger.id)
                .inForce.categories.single { it.name == category.name }.tags.single()
            assertEquals(OVERVIEW_PER_TAG, group.items.size)
            assertEquals(adrs.size.toLong(), group.total)
            assertEquals(1L, group.more)
            assertEquals(adrs.drop(1).map { it.id }.reversed(), group.items.map { it.id }, "newest first; the oldest is the one cut")
        } finally {
            cast.admin.client.deleteCategory(category.id)
        }
    }

    @Test
    fun `at most 100 tag groups are shown and the exact rest is counted`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val firstTags = (1..60).map { uniqueTag("g$it") }
        val secondTags = (1..60).map { uniqueTag("h$it") }
        val first = cast.admin.client.createCategory(name = categoryName("000 Overview"), tags = firstTags)
        val second = cast.admin.client.createCategory(name = categoryName("000 Overview"), tags = secondTags)
        try {
            // One ADR carries at most 20 tags: six ADRs cover the 120.
            (firstTags + secondTags).chunked(20).forEach { chunk ->
                val adr = cast.driver.client.createAdr()
                assertEquals(HttpStatusCode.NoContent, cast.driver.client.setTags(adr.id, chunk).status)
                forceAdrStatus(adr.id, AdrStatus.ACTIVE)
            }
            // Every group of the shared database, counted with a cap nothing reaches: the real cap must hide exactly the excess.
            val all = overviewOn(LocalDate.of(2004, 1, 1), cast.stranger.id, cap = 100_000).inForce
            val groups = all.categories.sumOf { it.tags.size }
            assertEquals(0, all.hiddenTags)
            assertTrue(groups >= 120)
            val inForce = overviewOn(LocalDate.of(2004, 1, 1), cast.stranger.id).inForce
            assertEquals(OVERVIEW_TAG_GROUPS, inForce.categories.sumOf { it.tags.size })
            assertEquals(groups - OVERVIEW_TAG_GROUPS, inForce.hiddenTags)
            assertEquals(setOf(first.id, second.id), inForce.categories.take(2).map { it.id }.toSet(), "the 000 categories sort first")
        } finally {
            cast.admin.client.deleteCategory(first.id)
            cast.admin.client.deleteCategory(second.id)
        }
    }

    @Test
    fun `an injected group cap hides exactly the tags beyond it`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val tags = (1..3).map { uniqueTag("c$it") }
        val category = cast.admin.client.createCategory(name = categoryName("000 Overview"), tags = tags)
        try {
            val adr = cast.driver.client.createAdr()
            assertEquals(HttpStatusCode.NoContent, cast.driver.client.setTags(adr.id, tags).status)
            forceAdrStatus(adr.id, AdrStatus.ACTIVE)
            val day = LocalDate.of(2007, 1, 1)
            val groups = overviewOn(day, cast.stranger.id, cap = 100_000).inForce.categories.sumOf { it.tags.size }
            assertTrue(groups >= 3)
            for (hidden in 1..2) {
                val inForce = overviewOn(day, cast.stranger.id, cap = groups - hidden).inForce
                assertEquals(hidden, inForce.hiddenTags)
                assertEquals(groups - hidden, inForce.categories.sumOf { it.tags.size })
            }
        } finally {
            cast.admin.client.deleteCategory(category.id)
        }
    }

    @Test
    fun `recently decided and the untagged hold their caps and say what is left`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        repeat(OVERVIEW_PER_TAG + 1) {
            val adr = cast.driver.client.createAdr()
            forceAdrStatus(adr.id, AdrStatus.ACTIVE)
        }
        val log = overviewOn(LocalDate.of(2005, 1, 1), cast.stranger.id)
        assertEquals(OVERVIEW_RECENT, log.recentlyDecided.items.size)
        assertEquals(log.inForce.total - OVERVIEW_RECENT, log.recentlyDecided.more)
        assertEquals(OVERVIEW_PER_TAG, log.inForce.untagged.items.size)
        assertEquals(log.inForce.untagged.total - OVERVIEW_PER_TAG, log.inForce.untagged.more)
        assertTrue(log.inForce.untagged.total >= OVERVIEW_PER_TAG + 1)
        val recent = log.recentlyDecided.items.map { it.statusChangedAt }
        assertEquals(recent.sortedDescending(), recent)
    }

    @Test
    fun `the review queue holds 20 and the caller's own count covers the ones beyond`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adrs = (1..21).map {
            val adr = cast.raise()
            forceAdrStatus(adr.id, AdrStatus.IN_REVIEW)
            forceAdrStatusChangedAt(adr.id, ancient + it)
            adr
        }
        try {
            val approvers = overviewOn(LocalDate.of(2006, 1, 1), cast.approver.id).awaitingDecision
            assertEquals(20, approvers.items.size)
            assertEquals(approvers.total - 20, approvers.more)
            assertEquals(21L, approvers.mineTotal, "the 21st (cut from the list) still counts as theirs")
            assertEquals(adrs.take(20).map { it.id }, approvers.items.filter { it.id in adrs.map { a -> a.id } }.map { it.id })
        } finally {
            adrs.forEach { forceAdrStatus(it.id, AdrStatus.CANCELLED) }
        }
    }

    @Test
    fun `equal instants and dates keep a stable order - ids decide the queue and recent, numbers the expiry`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val queued = (1..3).map {
            cast.raise().also { adr ->
                forceAdrStatus(adr.id, AdrStatus.IN_REVIEW)
                forceAdrStatusChangedAt(adr.id, tieInstant)
            }
        }
        val decided = (1..3).map {
            cast.driver.client.createAdr().also { adr ->
                forceAdrStatus(adr.id, AdrStatus.ACTIVE)
                forceAdrStatusChangedAt(adr.id, Long.MAX_VALUE / 2)
            }
        }
        val dated = (1..3).map { cast.activeAt("1992-05-05") }
        try {
            val log = overviewOn(LocalDate.of(1992, 5, 1), cast.stranger.id)
            val queuedIds = queued.map { it.id }.toSet()
            assertEquals(queued.map { it.id }, log.awaitingDecision.items.map { it.id }.filter { it in queuedIds }, "oldest id first")
            assertEquals(decided.map { it.id }.reversed(), log.recentlyDecided.items.map { it.id }.take(3), "newest id first")
            val datedIds = dated.map { it.id }.toSet()
            assertEquals(dated.map { it.id }, log.expiringSoon.items.map { it.id }.filter { it in datedIds }, "lowest number first")
        } finally {
            (queued + decided).forEach { forceAdrStatus(it.id, AdrStatus.CANCELLED) }
            dated.forEach { forceAdrStatus(it.id, AdrStatus.EXPIRED) }
        }
    }

    @Test
    fun `a deleted Approver reads as no Approver on the review queue`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        forceAdrStatus(adr.id, AdrStatus.IN_REVIEW)
        forceAdrStatusChangedAt(adr.id, tieInstant + 1)
        try {
            val before = overviewOn(LocalDate.of(2008, 1, 1), cast.stranger.id).awaitingDecision.items.single { it.id == adr.id }
            assertEquals(cast.approver.id, before.approver?.userId)
            TestUsers.softDelete(cast.approver.id)
            val after = overviewOn(LocalDate.of(2008, 1, 1), cast.stranger.id).awaitingDecision.items.single { it.id == adr.id }
            assertNull(after.approver, "the seat row is still there; the person is not")
            assertEquals(false, after.mine)
        } finally {
            forceAdrStatus(adr.id, AdrStatus.CANCELLED)
        }
    }
}
