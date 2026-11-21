package ch.nokillswit

import ch.nokillswit.infra.db.BudgetSpent
import ch.nokillswit.infra.db.SearchBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The one deadline of a search request (`infra/db/SearchBudget.kt`): the arithmetic over a fake clock (HTTP: `AdrSearchTest`). */
class SearchBudgetTest {
    private class FakeClock(var nanos: Long = 0L) : () -> Long {
        override fun invoke(): Long = nanos

        fun seconds(value: Double) {
            nanos = (value * 1_000_000_000L).toLong()
        }
    }

    @Test
    fun `the time left is the budget less the time spent, in whole seconds rounded up`() {
        val clock = FakeClock()
        val budget = SearchBudget(5, clock)
        assertEquals(5, budget.remainingSeconds())
        clock.seconds(0.001)
        assertEquals(5, budget.remainingSeconds(), "a started second still counts as a second")
        clock.seconds(1.0)
        assertEquals(4, budget.remainingSeconds())
        clock.seconds(3.2)
        assertEquals(2, budget.remainingSeconds(), "1.8 s left rounds up to 2")
        clock.seconds(4.999)
        assertEquals(1, budget.remainingSeconds(), "never less than a second while any time is left")
    }

    @Test
    fun `a spent deadline refuses the next statement`() {
        val clock = FakeClock()
        val budget = SearchBudget(2, clock)
        clock.seconds(2.0)
        assertFailsWith<BudgetSpent> { budget.remainingSeconds() }
        clock.seconds(30.0)
        assertFailsWith<BudgetSpent> { budget.remainingSeconds() }
    }
}
