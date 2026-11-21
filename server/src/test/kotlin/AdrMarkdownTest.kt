package ch.nokillswit

import ch.nokillswit.adrs.AdrExportDocument
import ch.nokillswit.adrs.AdrIndexEntry
import ch.nokillswit.adrs.AdrParticipant
import ch.nokillswit.adrs.AdrRef
import ch.nokillswit.adrs.AdrRole
import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.adrs.ExportTag
import ch.nokillswit.adrs.MAX_EXPORT_SLUG_LENGTH
import ch.nokillswit.adrs.adrExportFileName
import ch.nokillswit.adrs.adrSlug
import ch.nokillswit.adrs.renderAdrIndex
import ch.nokillswit.adrs.renderAdrMarkdown
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pure Markdown renderer (`adrs/AdrMarkdown.kt`): golden output, an empty optional section, the slug and the links. */
class AdrMarkdownTest {
    private val exportedOn = LocalDate.of(2026, 11, 21)
    private fun millis(day: String) = LocalDate.parse(day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 3_600_000

    private fun person(id: UInt, name: String, role: AdrRole) = AdrParticipant(id, name, role)

    private val full = AdrExportDocument(
        number = 42,
        name = "Use PostgreSQL",
        status = AdrStatus.ACTIVE,
        expiresOn = "2027-06-30",
        problem = "We need a database.",
        context = "Two teams know Postgres.\n\n- fast\n- boring",
        decision = "We use PostgreSQL 17.",
        alternatives = "MySQL, rejected: weaker JSON.",
        consequences = "Backups become our job.",
        dateMillis = millis("2026-11-20"),
        updatedAt = millis("2026-11-20"),
        participants = listOf(
            person(1u, "Ada Driver", AdrRole.DRIVER),
            person(2u, "Bob Approver", AdrRole.APPROVER),
            person(3u, "Cy Contributor", AdrRole.CONTRIBUTOR),
            person(4u, "Di Contributor", AdrRole.CONTRIBUTOR),
            person(5u, "Eve Informed", AdrRole.INFORMED),
        ),
        tags = listOf(ExportTag("Scope", "team", false), ExportTag("Domain", "data", false), ExportTag("Domain", "old", true)),
        supersedes = AdrRef(7u, "ADR-0007", "Use MySQL", AdrStatus.SUPERSEDED),
        supersededBy = null,
        supersededByAccepted = false,
    )

    @Test
    fun `a full ADR renders the adr-tools layout and the Hammurabi extras - golden`() {
        val expected = """
            # 42. Use PostgreSQL

            Date: 2026-11-20

            ## Status

            Active

            Expires on 2027-06-30 (the last day the decision is in force)

            Supersedes [ADR-0007: Use MySQL](ADR-0007-use-mysql.md)

            ## Context

            ### Problem

            We need a database.

            ### Background

            Two teams know Postgres.

            - fast
            - boring

            ## Decision

            We use PostgreSQL 17.

            ## Consequences

            Backups become our job.

            ## Alternatives considered

            MySQL, rejected: weaker JSON.

            ## Roles (DACI)

            - **Driver:** Ada Driver
            - **Approver:** Bob Approver
            - **Contributors:** Cy Contributor, Di Contributor
            - **Informed:** Eve Informed

            ## Tags

            - Domain: data
            - Domain: old (retired)
            - Scope: team

            <!-- Exported from Hammurabi on 2026-11-21 -->

        """.trimIndent()
        assertEquals(expected, renderAdrMarkdown(full, exportedOn))
    }

    @Test
    fun `a draft keeps the shape - empty optional sections are left out, empty required ones say so`() {
        val draft = full.copy(
            number = 3, name = "Draft", status = AdrStatus.PROBLEM_RAISED, expiresOn = null, context = "", decision = "", consequences = "",
            alternatives = "  \n", participants = listOf(person(1u, "Ada Driver", AdrRole.DRIVER)), tags = emptyList(), supersedes = null,
        )
        val text = renderAdrMarkdown(draft, exportedOn)
        val head = "# 3. Draft\n\nDate: 2026-11-20\n\n## Status\n\nProblem raised\n\n## Context\n\n### Problem\n\nWe need a database.\n\n"
        assertTrue(text.startsWith("$head## Decision"))
        val placeholders = "## Decision\n\n_Not recorded yet._\n\n## Consequences\n\n_Not recorded yet._\n\n"
        assertTrue("$placeholders## Roles (DACI)\n\n- **Driver:** Ada Driver\n" in text)
        assertFalse("### Background" in text)
        assertFalse("Alternatives considered" in text)
        assertFalse("## Tags" in text)
        assertFalse("Expires" in text)
        assertTrue(text.endsWith("\n<!-- Exported from Hammurabi on 2026-11-21 -->\n"))
    }

    @Test
    fun `the superseded side says who replaced it, and a planned replacement says so`() {
        val replacement = AdrRef(50u, "ADR-0050", "Use [Cockroach] DB", AdrStatus.ACTIVE)
        val replaced = full.copy(status = AdrStatus.SUPERSEDED, supersedes = null, supersededBy = replacement)
        val superseded = renderAdrMarkdown(replaced, exportedOn)
        val expiry = "Expires on 2027-06-30 (the last day the decision is in force)"
        val link = "[ADR-0050: Use \\[Cockroach\\] DB](ADR-0050-use-cockroach-db.md)"
        assertTrue("## Status\n\nSuperseded\n\n$expiry\n\nSuperseded by $link\n" in superseded, superseded)
        val accepted = full.copy(supersedes = null, supersededBy = replacement, supersededByAccepted = true)
        assertTrue("\nTo be superseded by [ADR-0050" in renderAdrMarkdown(accepted, exportedOn))
    }

    @Test
    fun `a successor its Approver has not accepted is only proposed, never claimed`() {
        val replacement = AdrRef(50u, "ADR-0050", "Use CockroachDB", AdrStatus.IN_REVIEW)
        val proposed = renderAdrMarkdown(full.copy(supersedes = null, supersededBy = replacement), exportedOn)
        assertTrue("\nSuccessor proposed: [ADR-0050: Use CockroachDB](ADR-0050-use-cockroachdb.md)\n" in proposed, proposed)
        assertFalse("superseded by" in proposed.lowercase(), "a proposal is not a supersession")
        // Once the replacement took effect the unaccepted flag is moot: the history says what happened.
        val done = renderAdrMarkdown(full.copy(status = AdrStatus.SUPERSEDED, supersedes = null, supersededBy = replacement), exportedOn)
        assertTrue("\nSuperseded by [ADR-0050" in done)
    }

    @Test
    fun `brackets and backslashes in a name are escaped in a link only, other characters are written as they are`() {
        val awkward = "Use [a]\\b <c> `d` *e*"
        val ref = AdrRef(9u, "ADR-0009", awkward, AdrStatus.ACTIVE)
        val text = renderAdrMarkdown(full.copy(name = awkward, supersedes = ref), exportedOn)
        // The heading carries the name untouched; the link text escapes `\`, `[` and `]` so the link cannot end early;
        // the file name is the slug.
        assertTrue(text.startsWith("# 42. $awkward\n"), text.take(80))
        assertTrue("\nSupersedes [ADR-0009: Use \\[a\\]\\\\b <c> `d` *e*](ADR-0009-use-a-b-c-d-e.md)\n" in text, text)
        val index = renderAdrIndex(listOf(AdrIndexEntry(9, awkward, AdrStatus.ACTIVE)), exportedOn)
        assertTrue("- [ADR-0009: Use \\[a\\]\\\\b <c> `d` *e*](ADR-0009-use-a-b-c-d-e.md) (Active)\n" in index, index)
    }

    @Test
    fun `the body fields are verbatim - line endings normalised, nothing escaped`() {
        val raw = full.copy(decision = "Line one\r\nLine <b>two</b> & \"three\"\rLine `four`\n\n\n", alternatives = "<script>x</script>")
        val text = renderAdrMarkdown(raw, exportedOn)
        assertFalse('\r' in text)
        assertTrue("## Decision\n\nLine one\nLine <b>two</b> & \"three\"\nLine `four`\n\n## Consequences" in text)
        assertTrue("## Alternatives considered\n\n<script>x</script>\n" in text)
    }

    @Test
    fun `the footer carries the date only`() {
        val footer = renderAdrMarkdown(full, exportedOn).lines().filter { it.startsWith("<!--") }
        assertEquals(listOf("<!-- Exported from Hammurabi on 2026-11-21 -->"), footer)
    }

    @Test
    fun `the date is the decision day in UTC, however late`() {
        val late = full.copy(dateMillis = LocalDate.of(2026, 3, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1)
        assertTrue("\nDate: 2026-02-28\n" in renderAdrMarkdown(late, exportedOn))
    }

    @Test
    fun `slugs fold diacritics to ASCII, collapse the rest and stay bounded`() {
        assertEquals("zolw-lodz-co", adrSlug("Żółw — Łódź & Co."))
        assertEquals("cafe-creme-brulee", adrSlug("Café Crème Brûlée"))
        assertEquals("strasse-aeoe", adrSlug("Straße æœ"))
        assertEquals("grosse-strasse", adrSlug("GROẞE Straße"), "the capital sharp s is ss too")
        assertEquals("adr-42-cafe", adrSlug("ＡＤＲ－４２ Ｃａｆé"), "fullwidth letters and digits fold (NFKD)")
        assertEquals("office", adrSlug("o\uFB03ce"), "a ligature folds")
        assertEquals("a-b", adrSlug("  --A///B--  "))
        assertEquals("", adrSlug("決定 ***"))
        val long = adrSlug("word ".repeat(40))
        assertTrue(long.length <= MAX_EXPORT_SLUG_LENGTH && !long.endsWith("-"), long)
        assertTrue(adrSlug("Ünïcödé ✓ 100% «quoted»").all { it in 'a'..'z' || it in '0'..'9' || it == '-' })
    }

    @Test
    fun `file names carry the zero-padded key and skip an empty slug`() {
        assertEquals("ADR-0042-use-postgresql.md", adrExportFileName(42, "Use PostgreSQL"))
        assertEquals("ADR-0007.md", adrExportFileName(7, "決定"))
        assertEquals("ADR-12345-x.md", adrExportFileName(12345, "x"))
    }

    @Test
    fun `the index lists the ADRs in number order with links to their files - golden`() {
        val index = renderAdrIndex(
            listOf(
                AdrIndexEntry(12, "Żółw stack", AdrStatus.IN_REVIEW),
                AdrIndexEntry(3, "Use Kotlin", AdrStatus.ACTIVE),
            ),
            exportedOn,
        )
        val expected = """
            # Architecture decision records

            - [ADR-0003: Use Kotlin](ADR-0003-use-kotlin.md) (Active)
            - [ADR-0012: Żółw stack](ADR-0012-zolw-stack.md) (In review)

            <!-- Exported from Hammurabi on 2026-11-21 -->

        """.trimIndent()
        assertEquals(expected, index)
        assertEquals(
            "# Architecture decision records\n\n_No ADRs matched._\n\n<!-- Exported from Hammurabi on 2026-11-21 -->\n",
            renderAdrIndex(emptyList(), exportedOn),
        )
    }
}
