package ch.nokillswit

import ch.nokillswit.infra.db.FTS_HIT_START
import ch.nokillswit.infra.db.FTS_HIT_STOP
import ch.nokillswit.infra.db.FtsSegment
import ch.nokillswit.infra.db.splitHeadline
import kotlin.test.Test
import kotlin.test.assertEquals

/** The pure half of the full-text search kernel (`infra/db/Fts.kt`): a headline written with the control markers becomes typed segments. */
class FtsTest {
    private val start = FTS_HIT_START
    private val stop = FTS_HIT_STOP

    @Test
    fun `a headline splits into plain and hit segments with the markers gone`() {
        assertEquals(
            listOf(FtsSegment("We chose ", false), FtsSegment("Postgres", true), FtsSegment(" for storage", false)),
            splitHeadline("We chose ${start}Postgres$stop for storage"),
        )
    }

    @Test
    fun `whitespace collapses across runs, the ends are trimmed and empty runs vanish`() {
        assertEquals(
            listOf(FtsSegment("a b ", false), FtsSegment("c d", true), FtsSegment(" e", false)),
            splitHeadline("  a \n\n b ${start}c\t d$stop \n e  \n"),
        )
        assertEquals(emptyList(), splitHeadline(" \n $start$stop \t"))
    }

    @Test
    fun `neighbouring runs of the same kind merge`() {
        assertEquals(listOf(FtsSegment("ab", true)), splitHeadline("${start}a$stop${start}b$stop"))
    }

    @Test
    fun `unbalanced markers are tolerated and markup is passed through as plain text`() {
        assertEquals(listOf(FtsSegment("a", false), FtsSegment("b", true)), splitHeadline("a${stop}${start}b"))
        assertEquals(listOf(FtsSegment("<img src=x onerror=alert(1)>", true)), splitHeadline("$start<img src=x onerror=alert(1)>"))
    }
}
