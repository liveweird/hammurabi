package ch.nokillswit.adrs

import ch.nokillswit.infra.mail.LocalizedText

/**
 * The words for a status in running text, English and Polish: the ONE source for the Markdown export (English, it carries no
 * language) and the notification emails (the recipient's language). The `when` is exhaustive, so a new [AdrStatus] cannot ship
 * without its words. The SPA keeps its own copy in its locale files.
 */
internal fun AdrStatus.words(): LocalizedText = when (this) {
    AdrStatus.PROBLEM_RAISED -> LocalizedText("Problem raised", "Zgłoszony problem")
    AdrStatus.SOLUTION_DRAFT -> LocalizedText("Solution draft", "Szkic rozwiązania")
    AdrStatus.IN_REVIEW -> LocalizedText("In review", "W przeglądzie")
    AdrStatus.ACTIVE -> LocalizedText("Active", "Obowiązujący")
    AdrStatus.CANCELLED -> LocalizedText("Cancelled", "Anulowany")
    AdrStatus.WITHDRAWN -> LocalizedText("Withdrawn", "Wycofany")
    AdrStatus.EXPIRED -> LocalizedText("Expired", "Wygasły")
    AdrStatus.SUPERSEDED -> LocalizedText("Superseded", "Zastąpiony")
}
