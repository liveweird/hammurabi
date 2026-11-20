package ch.nokillswit

import ch.nokillswit.infra.validation.requireNoDuplicates
import ch.nokillswit.infra.validation.sanitizeMultiLine
import ch.nokillswit.infra.validation.sanitizeSingleLine
import io.ktor.server.plugins.BadRequestException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `sanitizeMultiLine`, the hidden-character rule of `sanitizeSingleLine` and `requireNoDuplicates` (`infra/validation/Text.kt`). */
class SanitizeMultiLineTest {
    private val hidden =
        listOf('\u202A', '\u202B', '\u202C', '\u202D', '\u202E', '\u2066', '\u2067', '\u2068', '\u2069', '\u2028', '\u2029')

    @Test
    fun `line breaks are normalised to LF and tabs survive`() {
        assertEquals("a\n\tb\nc\nd", sanitizeMultiLine("a\r\n\tb\rc\nd", "Body"))
        assertEquals("a\n\nb", sanitizeMultiLine("a\r\n\r\nb", "Body"))
    }

    @Test
    fun `leading blank lines and trailing whitespace go, but the first line's indentation stays`() {
        assertEquals("    code()\n    more()", sanitizeMultiLine("\n  \r\n\t\n    code()\n    more()  \n\n \t", "Body"))
        assertEquals("    indented", sanitizeMultiLine("    indented", "Body"))
        assertEquals("a", sanitizeMultiLine("a   ", "Body"))
        assertEquals("", sanitizeMultiLine(" \n\t \r\n ", "Body"))
        assertEquals("", sanitizeMultiLine("", "Body"))
    }

    @Test
    fun `any other control character is a 400 naming the field`() {
        for (control in listOf("\u0000", "\u0007", "\u001b", "\u007f", "\u0085")) {
            val failure = assertFailsWith<BadRequestException>("U+${control.single().code.toString(16)}") {
                sanitizeMultiLine("a${control}b", "Body")
            }
            assertTrue("Body must not contain control characters" in failure.message.orEmpty())
        }
    }

    @Test
    fun `direction controls and line separators are a 400, in a body and in a single line`() {
        for (c in hidden) {
            assertFailsWith<BadRequestException>("body U+${c.code.toString(16)}") { sanitizeMultiLine("a${c}b", "Body") }
            assertFailsWith<BadRequestException>("line U+${c.code.toString(16)}") { sanitizeSingleLine("a${c}b", "Title") }
        }
    }

    @Test
    fun `the zero-width joiner of an emoji sequence is fine`() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDCBB"
        assertEquals("$family\nx", sanitizeMultiLine("$family\nx", "Body"))
        assertEquals(family, sanitizeSingleLine(" $family ", "Title"))
    }

    @Test
    fun `requireNoDuplicates rejects a repeat with the callers message and honours a fold`() {
        requireNoDuplicates(listOf("a", "b"), "dup")
        requireNoDuplicates(emptyList(), "dup")
        requireNoDuplicates(listOf("a", "A"), "dup")
        assertEquals("dup", assertFailsWith<BadRequestException> { requireNoDuplicates(listOf("a", "a"), "dup") }.message)
        assertFailsWith<BadRequestException> { requireNoDuplicates(listOf("a", "A"), "dup", fold = String::lowercase) }
    }
}
