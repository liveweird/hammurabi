package ch.nokillswit.adrs

import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The Markdown export of an ADR (0.8.0, `adr-model.md` "Export"): the adr-tools layout (`# 42. Name`, `Date:`, `## Status`, `## Context`,
 * `## Decision`, `## Consequences`) plus Hammurabi's extras (`## Alternatives considered`, `## Roles (DACI)`, `## Tags`). PURE: strings in,
 * strings out, no database and no clock (the export date is passed in), so the golden tests pin every byte. English only: the file is a
 * hand-off artefact, not a screen, and adr-tools' own headings are English.
 *
 * The body fields are Markdown already and are included verbatim (line endings normalised to `\n`, no escaping of any kind: the output is
 * a `.md` file, never rendered by us). Everything else the document says (names, tags, dates) is written plain; only a link's text is
 * escaped, because a `]` in an ADR name would end it. The footer comment carries the export DATE only: no user, no address.
 */

/** A tag as the export shows it: `category: name`, struck by the word "retired" once the registry dropped it. */
data class ExportTag(val category: String, val name: String, val retired: Boolean)

/** Everything one exported ADR needs, already resolved (the service reads it, [renderAdrMarkdown] only formats it). */
data class AdrExportDocument(
    val number: Int,
    val name: String,
    val status: AdrStatus,
    val expiresOn: String?,
    val problem: String,
    val context: String,
    val decision: String,
    val alternatives: String,
    val consequences: String,
    /** The decision date as epoch millis: the first activation, else the creation. */
    val dateMillis: Long,
    /** The last change, epoch millis: the zip entry's timestamp. */
    val updatedAt: Long,
    val participants: List<AdrParticipant>,
    val tags: List<ExportTag>,
    val supersedes: AdrRef?,
    val supersededBy: AdrRef?,
)

/** One line of the bulk export's `index.md`. */
data class AdrIndexEntry(val number: Int, val name: String, val status: AdrStatus)

/** The longest slug in a file name, in characters (the number and the extension come on top). */
const val MAX_EXPORT_SLUG_LENGTH = 60

/** The text of the export for the "nothing recorded yet" case of a required section (a draft exports as a draft). */
private const val NOT_RECORDED = "_Not recorded yet._"

private val NOT_SLUG = Regex("[^a-z0-9]+")
private val COMBINING_MARKS = Regex("\\p{M}+")

/** Letters that do not decompose under NFD but have an everyday ASCII spelling (Polish `ł` above all). */
private val ASCII_LETTERS = mapOf(
    'ł' to "l", 'Ł' to "L", 'ø' to "o", 'Ø' to "O", 'đ' to "d", 'Đ' to "D", 'ð' to "d", 'Ð' to "D",
    'ß' to "ss", 'ẞ' to "SS", 'æ' to "ae", 'Æ' to "AE", 'œ' to "oe", 'Œ' to "OE", 'þ' to "th", 'Þ' to "TH", 'ı' to "i",
)

/**
 * An ASCII-only slug of [name]: accents folded (`Żółw` is `zolw`; NFKD, so fullwidth letters and ligatures fold too), lower case,
 * every run of anything outside `[a-z0-9]` one `-`, trimmed of
 * dashes and cut at [MAX_EXPORT_SLUG_LENGTH]. A name with no Latin letter or digit at all gives the empty string (the file name then
 * has no slug). The result is safe in a header, a path and a Markdown link without any quoting.
 */
fun adrSlug(name: String): String {
    val folded = Normalizer.normalize(name, Normalizer.Form.NFKD).replace(COMBINING_MARKS, "")
        .map { ASCII_LETTERS[it] ?: it.toString() }.joinToString("")
    return folded.lowercase().replace(NOT_SLUG, "-").trim('-').take(MAX_EXPORT_SLUG_LENGTH).trim('-')
}

/** `ADR-0042-use-postgresql.md`: the file an ADR is exported as (and the target of the links between exported files). */
fun adrExportFileName(number: Int, name: String): String = fileNameOf(adrKey(number), name)

private fun fileNameOf(key: String, name: String): String {
    val slug = adrSlug(name)
    return if (slug.isEmpty()) "$key.md" else "$key-$slug.md"
}

private fun statusWords(status: AdrStatus): String = when (status) {
    AdrStatus.PROBLEM_RAISED -> "Problem raised"
    AdrStatus.SOLUTION_DRAFT -> "Solution draft"
    AdrStatus.IN_REVIEW -> "In review"
    AdrStatus.ACTIVE -> "Active"
    AdrStatus.CANCELLED -> "Cancelled"
    AdrStatus.WITHDRAWN -> "Withdrawn"
    AdrStatus.EXPIRED -> "Expired"
    AdrStatus.SUPERSEDED -> "Superseded"
}

/** A body field as written: line endings normalised, the blank lines around it dropped, a whitespace-only field is empty. */
private fun body(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n').let { if (it.isBlank()) "" else it.trim('\n') }

private fun day(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()

/** `[ADR-0007: Use MySQL](ADR-0007-use-mysql.md)`: the text escaped so a bracket in a name cannot end the link early. */
private fun link(key: String, name: String): String {
    val label = "$key: $name".replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
    return "[$label](${fileNameOf(key, name)})"
}

private fun link(ref: AdrRef): String = link(ref.key, ref.name)

private fun StringBuilder.section(heading: String, text: String) {
    append("\n## ").append(heading).append("\n\n").append(text).append('\n')
}

private fun statusLines(doc: AdrExportDocument): String = buildList {
    add(statusWords(doc.status))
    doc.expiresOn?.let { add("Expires on $it (the last day the decision is in force)") }
    doc.supersedes?.let { add("Supersedes ${link(it)}") }
    // A successor that has not taken effect yet is only planned: the status line must not claim a supersession that has not happened.
    doc.supersededBy?.let { add((if (doc.status == AdrStatus.SUPERSEDED) "Superseded by " else "To be superseded by ") + link(it)) }
}.joinToString("\n\n")

private fun contextText(doc: AdrExportDocument): String {
    val problem = body(doc.problem)
    val context = body(doc.context)
    return if (context.isEmpty()) "### Problem\n\n$problem" else "### Problem\n\n$problem\n\n### Background\n\n$context"
}

private fun rolesText(participants: List<AdrParticipant>): String {
    val labels = listOf(
        AdrRole.DRIVER to "Driver", AdrRole.APPROVER to "Approver", AdrRole.CONTRIBUTOR to "Contributors", AdrRole.INFORMED to "Informed",
    )
    return labels.mapNotNull { (role, label) ->
        val names = participants.filter { it.role == role }.map { it.name }
        if (names.isEmpty()) null else "- **$label:** ${names.joinToString(", ")}"
    }.joinToString("\n")
}

private fun tagsText(tags: List<ExportTag>): String = tags.sortedWith(compareBy({ it.category }, { it.name }))
    .joinToString("\n") { "- ${it.category}: ${it.name}" + if (it.retired) " (retired)" else "" }

private fun footer(exportedOn: LocalDate) = "\n<!-- Exported from Hammurabi on $exportedOn -->\n"

/**
 * One ADR as an adr-tools Markdown document. `Date:` is the day the decision was first made active, else the day the ADR was created
 * (UTC). The sections a draft has not filled in (`## Decision`, `## Consequences`) stay as headings with a placeholder, so every export has
 * the same shape; `## Alternatives considered`, `## Roles (DACI)` and `## Tags` are left out when empty.
 */
fun renderAdrMarkdown(doc: AdrExportDocument, exportedOn: LocalDate): String = buildString {
    append("# ").append(doc.number).append(". ").append(doc.name).append("\n\n")
    append("Date: ").append(day(doc.dateMillis)).append('\n')
    section("Status", statusLines(doc))
    section("Context", contextText(doc))
    section("Decision", body(doc.decision).ifEmpty { NOT_RECORDED })
    section("Consequences", body(doc.consequences).ifEmpty { NOT_RECORDED })
    body(doc.alternatives).takeIf { it.isNotEmpty() }?.let { section("Alternatives considered", it) }
    rolesText(doc.participants).takeIf { it.isNotEmpty() }?.let { section("Roles (DACI)", it) }
    tagsText(doc.tags).takeIf { it.isNotEmpty() }?.let { section("Tags", it) }
    append(footer(exportedOn))
}

/** The bulk export's `index.md` (the adr-tools `toc` shape): one bullet per exported ADR in number order, linking its file. */
fun renderAdrIndex(entries: List<AdrIndexEntry>, exportedOn: LocalDate): String = buildString {
    append("# Architecture decision records\n\n")
    if (entries.isEmpty()) append("_No ADRs matched._\n")
    entries.sortedBy { it.number }.forEach {
        append("- ").append(link(adrKey(it.number), it.name)).append(" (").append(statusWords(it.status)).append(")\n")
    }
    append(footer(exportedOn))
}
