package ch.nokillswit.infra.db

// The shared field-diff vocabulary of the per-record history logs (Toadie's `EventChanges.kt`, trimmed to what the ADR log uses):
// a FieldChange per changed field and the params encoding that carries them. Each resource owns how ITS record is walked into
// changes; this file owns what a change looks like and how it is encoded.

/**
 * One changed field of a record. A short scalar carries [from]/[to] (an absent side means unset); a field whose value is withheld -
 * free text such as the Markdown fields, or a value over [MAX_CHANGE_VALUE_LENGTH] - carries none and is reported by NAME only.
 */
data class FieldChange(val field: String, val from: String? = null, val to: String? = null)

/**
 * The value ceiling per side. Beyond it a field degrades to name-only rather than being truncated into a misleading diff - and
 * event rows stay small.
 */
internal const val MAX_CHANGE_VALUE_LENGTH = 200

/**
 * The params encoding (Lettuce's impact-log idiom): `changed` comma-joins the changed field names in record order, and each short
 * field's values follow under `<field>.from` / `<field>.to`. A name-only field contributes to `changed` alone. Field names cannot
 * contain a comma, so the join stays unambiguous.
 */
internal fun encodeChanges(changes: List<FieldChange>): Map<String, String> {
    if (changes.isEmpty()) return emptyMap()
    return buildMap {
        put("changed", changes.joinToString(",") { it.field })
        changes.forEach { change ->
            change.from?.let { put("${change.field}.from", it) }
            change.to?.let { put("${change.field}.to", it) }
        }
    }
}

/** The change of a short scalar, or null when [from] equals [to]; a side over [MAX_CHANGE_VALUE_LENGTH] makes it name-only. */
internal fun scalarChange(field: String, from: String?, to: String?): FieldChange? {
    if (from == to) return null
    val tooLong = (from?.length ?: 0) > MAX_CHANGE_VALUE_LENGTH || (to?.length ?: 0) > MAX_CHANGE_VALUE_LENGTH
    return if (tooLong) FieldChange(field) else FieldChange(field, from = from, to = to)
}

/** The change of a free-text field, or null when [from] equals [to]: the bare FACT that it changed, never the text. */
internal fun nameOnlyChange(field: String, from: String?, to: String?): FieldChange? =
    if (from == to) null else FieldChange(field)
