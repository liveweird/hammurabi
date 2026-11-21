package ch.nokillswit.notifications

import ch.nokillswit.adrs.AdrRole
import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.adrs.words
import ch.nokillswit.infra.mail.LocalizedText
import java.text.Normalizer
import java.util.Locale

/** Entries one digest lists; the rest are a count ("and 4 more"). */
internal const val DIGEST_MAX_ENTRIES = 10

/**
 * One line of a digest: WHO did it (null = the system), WHAT happened and to which ADR. Nothing else about a notice may reach an email:
 * never an ADR name or any ADR or discussion text (`security.md` "Outbound email"), so the type has no field to carry one. The key
 * (`ADR-0042`, derived from the number) is the only name of an ADR. [adrId] only builds the link.
 */
internal data class DigestEntry(
    val type: NotificationType,
    val actorName: String?,
    val adrId: UInt,
    val adrKey: String,
    val otherAdrKey: String?,
    val detail: String?,
)

/** A composed email: a subject and a plain-text body. */
internal data class DigestEmail(val subject: String, val body: String)

private val GREETING = LocalizedText(en = "Hi", pl = "Cze\u015b\u0107")
private val INTRO = LocalizedText(en = "In Hammurabi, while you were away:", pl = "W Hammurabim, gdy Ci\u0119 nie by\u0142o:")
private val MORE = LocalizedText(en = "...and %d more", pl = "...i jeszcze %d")
private val PREFERENCES = LocalizedText(en = "Change which emails you get:", pl = "Zmie\u0144, jakie wiadomo\u015bci dostajesz:")
private val SUBJECT_PREFIX = LocalizedText(en = "Hammurabi: ", pl = "Hammurabi: ")
private val SYSTEM = LocalizedText(en = "Hammurabi", pl = "Hammurabi")

private val ROLES: Map<AdrRole, LocalizedText> = mapOf(
    AdrRole.DRIVER to LocalizedText("Driver", "Prowadz\u0105cy/a"),
    AdrRole.APPROVER to LocalizedText("Approver", "Zatwierdzaj\u0105cy/a"),
    AdrRole.CONTRIBUTOR to LocalizedText("Contributor", "Wsp\u00f3\u0142tw\u00f3rca/czyni"),
    AdrRole.INFORMED to LocalizedText("Informed", "Informowany/a"),
)

private val KINDS: Map<String, LocalizedText> = mapOf(
    "COMMENT" to LocalizedText("comment", "komentarz"),
    "QUESTION" to LocalizedText("question", "pytanie"),
    "ISSUE" to LocalizedText("issue", "zg\u0142oszenie"),
    "ESCALATION" to LocalizedText("escalation", "eskalacja"),
)

/** A stored detail the build does not know (a later release's role, status or kind) is written as it is, in the entry's own words. */
private fun LocalizedText?.orRaw(raw: String?, language: String): String = this?.of(language) ?: raw.orEmpty()

private fun roleWord(detail: String?, language: String): String =
    AdrRole.entries.firstOrNull { it.name == detail }?.let { ROLES.getValue(it) }.orRaw(detail, language)

private fun statusWord(detail: String?, language: String): String =
    AdrStatus.entries.firstOrNull { it.name == detail }?.words().orRaw(detail, language)

private fun kindWord(detail: String?, language: String): String = KINDS[detail].orRaw(detail, language)

private val ROLE_ASSIGNED = LocalizedText("%s made you %s of %s", "%s przypisuje Ci rol\u0119 \u201e%s\u201d w %s")
private val ROLE_REMOVED = LocalizedText("%s removed you as %s of %s", "%s odbiera Ci rol\u0119 \u201e%s\u201d w %s")
private val STATUS_CHANGED = LocalizedText("%s moved %s to \"%s\"", "%s zmienia status %s na \u201e%s\u201d")
private val SENT_BACK = LocalizedText("%s sent %s back to draft", "%s odsy\u0142a %s do szkicu")
private val POSTED = LocalizedText("%s added a %s on %s", "%s dodaje wpis typu \u201e%s\u201d w %s")
private val ANSWERED = LocalizedText("%s answered your question on %s", "%s odpowiada na Twoje pytanie w %s")
private val RESOLVED = LocalizedText("%s resolved your %s on %s", "%s rozwi\u0105zuje Tw\u00f3j wpis typu \u201e%s\u201d w %s")
private val EXPIRED = LocalizedText("%s has expired (its last day in force was %s)",
    "%s wygas\u0142 (ostatni dzie\u0144 obowi\u0105zywania: %s)")
private val SUPERSEDED = LocalizedText("%s was superseded by %s", "%s zosta\u0142 zast\u0105piony przez %s")
private val LINKED = LocalizedText("%s proposed %s to replace %s", "%s proponuje %s jako nast\u0119pc\u0119 %s")
private val UNLINKED = LocalizedText("%s removed the link: %s will not replace %s", "%s usuwa powi\u0105zanie: %s nie zast\u0105pi %s")
private val EXPIRY_CHANGED = LocalizedText(
    "%s changed the expiry date of %s to %s (the last day in force)",
    "%s zmienia dat\u0119 wyga\u015bni\u0119cia %s na %s (ostatni dzie\u0144 obowi\u0105zywania)",
)
private val EXPIRY_REMOVED = LocalizedText(
    "%s removed the expiry date of %s",
    "%s usuwa dat\u0119 wyga\u015bni\u0119cia %s",
)
private val ACCEPTED = LocalizedText(
    "%s accepted %s to replace %s",
    "%s akceptuje %s jako nast\u0119pc\u0119 %s",
)
private val PREDECESSOR_ENDED = LocalizedText(
    "%s, which %s was set to replace, is now \"%s\"",
    "%s, kt\u00f3ry mia\u0142 zosta\u0107 zast\u0105piony przez %s, ma teraz status \u201e%s\u201d",
)
private val REMINDER = LocalizedText("%s expires on %s (the last day in force)", "%s wygasa %s (ostatni dzie\u0144 obowi\u0105zywania)")

/** `String.format` in the root locale: a digit is always an ASCII digit, whatever the server's default locale. */
private fun fmt(pattern: String, vararg args: Any): String = String.format(Locale.ROOT, pattern, *args)

/**
 * The sentence of one entry, in [language]. Every name that reaches it (the actor's, the ADR keys) passes [mailSafe]; a role, status or
 * kind is a word from the tables above (or, unknown, its stored name).
 */
private fun entryText(entry: DigestEntry, language: String): String {
    val actor = mailSafe(entry.actorName ?: SYSTEM.of(language))
    val adr = mailSafe(entry.adrKey)
    val other = mailSafe(entry.otherAdrKey.orEmpty())
    val detail = entry.detail
    return when (entry.type) {
        NotificationType.ROLE_ASSIGNED -> fmt(ROLE_ASSIGNED.of(language), actor, roleWord(detail, language), adr)
        NotificationType.ROLE_REMOVED -> fmt(ROLE_REMOVED.of(language), actor, roleWord(detail, language), adr)
        NotificationType.STATUS_CHANGED ->
            if (detail == SENT_BACK_DETAIL) fmt(SENT_BACK.of(language), actor, adr)
            else fmt(STATUS_CHANGED.of(language), actor, adr, statusWord(detail, language))
        NotificationType.DISCUSSION_POSTED -> fmt(POSTED.of(language), actor, kindWord(detail, language), adr)
        NotificationType.DISCUSSION_SETTLED ->
            if (detail == "QUESTION") fmt(ANSWERED.of(language), actor, adr) else fmt(RESOLVED.of(language), actor, kindWord(detail,
                language), adr)
        NotificationType.ADR_EXPIRED -> fmt(EXPIRED.of(language), adr, mailSafe(detail.orEmpty()))
        NotificationType.ADR_SUPERSEDED -> fmt(SUPERSEDED.of(language), adr, other)
        NotificationType.SUCCESSOR_LINKED -> fmt(LINKED.of(language), actor, other, adr)
        NotificationType.SUCCESSOR_UNLINKED -> fmt(UNLINKED.of(language), actor, other, adr)
        NotificationType.SUCCESSOR_ACCEPTED -> fmt(ACCEPTED.of(language), actor, adr, other)
        NotificationType.PREDECESSOR_ENDED -> fmt(PREDECESSOR_ENDED.of(language), other, adr, statusWord(detail, language))
        NotificationType.EXPIRY_CHANGED ->
            if (detail.isNullOrEmpty()) fmt(EXPIRY_REMOVED.of(language), actor, adr)
            else fmt(EXPIRY_CHANGED.of(language), actor, adr, mailSafe(detail))
        NotificationType.EXPIRY_REMINDER -> fmt(REMINDER.of(language), adr, mailSafe(detail.orEmpty()))
    }
}

private val COUNT_ONE = LocalizedText(en = "%d unseen notification", pl = "%d nieprzeczytane powiadomienie")
private val COUNT_FEW = LocalizedText(en = "%d unseen notifications", pl = "%d nieprzeczytane powiadomienia")
private val COUNT_MANY = LocalizedText(en = "%d unseen notifications", pl = "%d nieprzeczytanych powiadomie\u0144")

/**
 * The subject's count phrase, with the plural forms of each language: the plural class is chosen by the Polish rule (1, 2 to 4 except
 * 12 to 14, the rest) and a language that does not tell "few" from "many" writes the same text for both (English).
 */
private fun countPhrase(count: Int, language: String): String {
    val text = when {
        count == 1 -> COUNT_ONE
        count % 10 in 2..4 && count % 100 !in 12..14 -> COUNT_FEW
        else -> COUNT_MANY
    }
    return fmt(text.of(language), count)
}

/**
 * A name made safe for a plain-text body: control characters and line or paragraph separators become a space and bidirectional controls
 * go, so a name can never start a new line (a forged "entry" or a header-looking line) or reorder the text around it. Names are already
 * single-line (`sanitizeSingleLine`); this is the second line of defence, in the one place that writes them into mail.
 */
internal fun plainName(text: String): String = buildString {
    for (c in text) {
        when {
            c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069' || c == '\u200E' || c == '\u200F' || c == '\u061C' -> Unit
            Character.isISOControl(c) || c == '\u2028' || c == '\u2029' -> append(' ')
            else -> append(c)
        }
    }
}.trim()

/** U+2024 ONE DOT LEADER: reads as a full stop, is not one (no mail client links across it). */
private const val DOT_LOOKALIKE = '․'

/** U+2215 DIVISION SLASH: reads as a slash, is not one. */
private const val SLASH_LOOKALIKE = '∕'

/** U+2216 SET MINUS: reads as a backslash, is not one (Outlook links a UNC path, and `file:` followed by backslashes). */
private const val BACKSLASH_LOOKALIKE = '∖'

/** U+FF20 FULLWIDTH COMMERCIAL AT: reads as an at sign, is not an address separator. */
private const val AT_LOOKALIKE = '＠'

/** The full stops a domain name is written with: ASCII, the ideographic and the fullwidth ones (IDNA treats all of them as a dot). */
private fun isDot(c: Int): Boolean = c == '.'.code || c == 0x3002 || c == 0xFF0E || c == 0xFF61

/**
 * Text chosen by someone else (a person's name) made safe for Hammurabi's own mail, where a mail client turns a bare address
 * or domain into a link: the result can contain no address and no link. Deterministic:
 *
 * - every format character (Unicode category `Cf`: zero-width spaces and joiners, the soft hyphen, bidirectional controls) is DROPPED,
 *   then the text is NFC-normalised, then [plainName] runs. A soft hyphen or zero-width character beside a dot cannot hide it from the
 *   rule below (a linkifier and IDNA ignore them and still resolve the dot);
 * - `@` becomes U+FF20 (fullwidth at), so no `user@host` survives;
 * - the two slashes of `://` become U+2215 (division slash), so no scheme survives;
 * - EVERY backslash becomes U+2216 (set minus), so no UNC path and no backslash scheme survives;
 * - EVERY full stop (ASCII, ideographic U+3002, fullwidth U+FF0E, halfwidth ideographic U+FF61) that is not followed by a plain space or
 *   the end of the text becomes U+2024 (one dot leader), whatever stands on either side: the rule is "a dot that continues into anything
 *   is not a dot", never "a dot between letters" (a combining mark beside the dot defeated that), so no bare domain
 *   (`evil.example.com`, an IDN) survives. A full stop that ends a sentence is left alone; "J.K. Rowling" reads "J․K. Rowling" (a
 *   no-break space was made a plain space by [plainName], so "evil.&nbsp;com" reads "evil. com", which links to nothing).
 *
 * The text stays readable; what it can no longer do is be clicked.
 */
internal fun mailSafe(text: String): String {
    val plain = plainName(Normalizer.normalize(dropFormatCharacters(text), Normalizer.Form.NFC))
    val out = StringBuilder(plain.length)
    var i = 0
    while (i < plain.length) {
        val c = plain.codePointAt(i)
        val next = i + Character.charCount(c)
        if (c == '/'.code && plain.startsWith("//", i) && out.endsWith(":")) {
            out.append(SLASH_LOOKALIKE).append(SLASH_LOOKALIKE)
            i += 2
            continue
        }
        val continuesPastDot = isDot(c) && next < plain.length && plain[next] != ' '
        when {
            c == '@'.code -> out.append(AT_LOOKALIKE)
            c == '\\'.code -> out.append(BACKSLASH_LOOKALIKE)
            continuesPastDot -> out.append(DOT_LOOKALIKE)
            else -> out.appendCodePoint(c)
        }
        i = next
    }
    return out.toString()
}

/** [text] without its `Cf` (format) characters. */
private fun dropFormatCharacters(text: String): String = buildString(text.length) {
    text.codePoints().forEach { if (Character.getType(it) != Character.FORMAT.toInt()) appendCodePoint(it) }
}

/**
 * The digest of [entries] (at least one, oldest first) for [name], in [language] (English for anything unknown). Pure: names and keys in,
 * text out. The subject carries a count and no name. The body lists at most [DIGEST_MAX_ENTRIES] entries, then "and N more"; a link follows
 * each entry, and the preferences link ends the mail, only when [appUrl] (the configured `mail.appUrl` origin, never a request's `Host`) is
 * set. [unlisted] is the person's further due notices that were claimed in the same digest but are not given as entries (a flooded
 * backlog): they count in the subject and in "and N more".
 */
internal fun notificationEmail(
    name: String,
    entries: List<DigestEntry>,
    appUrl: String?,
    language: String,
    unlisted: Int = 0,
): DigestEmail {
    require(entries.isNotEmpty()) { "a digest lists at least one notice" }
    val body = buildString {
        appendLine("${GREETING.of(language)} ${mailSafe(name)},")
        appendLine()
        appendLine(INTRO.of(language))
        appendLine()
        for (entry in entries.take(DIGEST_MAX_ENTRIES)) {
            appendLine("- ${entryText(entry, language)}")
            if (appUrl != null) appendLine("  $appUrl/adrs/${entry.adrId}")
        }
        val more = maxOf(entries.size - DIGEST_MAX_ENTRIES, 0) + unlisted
        if (more > 0) appendLine(fmt(MORE.of(language), more))
        if (appUrl != null) {
            appendLine()
            appendLine("${PREFERENCES.of(language)} $appUrl/notification-preferences")
        }
    }
    return DigestEmail(SUBJECT_PREFIX.of(language) + countPhrase(entries.size + unlisted, language), body)
}
