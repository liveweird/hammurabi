package ch.nokillswit.infra.validation

import io.ktor.server.plugins.BadRequestException

/**
 * Canonical single-line identity fields (names and the like): trimmed, and control characters
 * are a clean 400 instead of stored garbage. Ported from Lettuce. Hidden direction and separator characters are refused too
 * ([isHiddenFormatting]): they can make a title or a name read differently from what it is.
 */
fun sanitizeSingleLine(value: String, field: String): String {
    val trimmed = value.trim()
    if (trimmed.any { it.isISOControl() || isHiddenFormatting(it) }) {
        throw BadRequestException("$field must not contain control characters")
    }
    return trimmed
}

/**
 * The invisible characters that reorder or re-flow visible text (the "Trojan Source" family): the bidirectional embeddings, overrides and
 * isolates U+202A..U+202E and U+2066..U+2069, and the line and paragraph separators U+2028/U+2029. Deliberately NOT every format
 * character: U+200D (the zero-width joiner) glues emoji sequences, and U+200B/U+200C/U+FE0F have legitimate uses.
 */
fun isHiddenFormatting(c: Char): Boolean = c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069' || c == '\u2028' || c == '\u2029'

/**
 * Canonical multi-line free text (a message body and the like), the [sanitizeSingleLine] sibling for text that wraps. It normalises
 * CRLF and a lone CR to LF, then rejects every control character except `\n` and `\t`, and the hidden characters of
 * [isHiddenFormatting], as a clean 400 instead of stored garbage. The result has its leading blank lines and all trailing whitespace
 * dropped, but never the first content line's own indentation: an indented code block must survive. Blank in, empty string out.
 * Ported from Toadie.
 */
fun sanitizeMultiLine(value: String, field: String): String {
    val text = value.replace("\r\n", "\n").replace('\r', '\n')
    if (text.any { (it.isISOControl() && it != '\n' && it != '\t') || isHiddenFormatting(it) }) {
        throw BadRequestException("$field must not contain control characters")
    }
    val firstContent = text.indexOfFirst { !it.isWhitespace() }
    if (firstContent < 0) return ""
    return text.substring(text.lastIndexOf('\n', firstContent) + 1).trimEnd()
}

/**
 * Rejects a collection carrying a duplicate, optionally folded before comparison (e.g. a case-insensitive check): the one
 * `size != toSet().size` check the registry validators share. [message] is the caller's exact rejection text. Ported from Toadie.
 */
fun requireNoDuplicates(values: Collection<String>, message: String, fold: (String) -> String = { it }) {
    val folded = values.map(fold)
    if (folded.size != folded.toSet().size) {
        throw BadRequestException(message)
    }
}

/** The optional description every registry carries: trimmed, control characters a 400, blank = absent. */
fun sanitizedDescription(raw: String?): String? = raw?.let { sanitizeSingleLine(it, "Description") }?.takeIf { it.isNotBlank() }

/** The name/description length rule every registry enforces — one wording, so the SPA's messages never drift per area. */
fun requireNameAndDescription(name: String, description: String?, maxName: Int, maxDescription: Int) {
    if (name.isBlank() || name.length > maxName) throw BadRequestException("Name must be 1-$maxName characters")
    if (description != null && description.length > maxDescription) {
        throw BadRequestException("Description must be at most $maxDescription characters")
    }
}

/**
 * The ONE canonical form of an email address (trimmed, lower-cased): user logins and every other email-keyed lookup.
 * Applied at EVERY entry point so one
 * mailbox is one account (`ADMIN@x` cannot create a second account beside `admin@x`, and a padded/case-variant login matches). A pure fold
 * that never throws: login must stay a uniform 401 on garbage.
 */
fun canonicalEmail(raw: String): String = raw.trim().lowercase()
