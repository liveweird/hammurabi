/**
 * `YYYY-MM-DD` ISO-date helpers shared by the ADR filters and lenses (validation), the decision log
 * (day arithmetic) and `formatDate` (a day in a zone). The plain conversions are UTC; the `…InZone` ones take the
 * given IANA zone. Neither reads the browser's local time zone, so results are the same in every
 * test/CI locale (never `toLocaleString()`).
 */

/** One fixed 24-hour day in millis — UTC calendar arithmetic only (zone days go through the `…InZone` helpers). */
export const DAY_MS = 86_400_000;

const ISO_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

/** A real calendar date, not just the `YYYY-MM-DD` shape (rejects e.g. `2024-02-30`). */
export function isValidIsoDate(value: string): boolean {
  if (!ISO_DATE_PATTERN.test(value)) return false;
  const [year, month, day] = value.split("-").map(Number);
  // `Date.UTC` maps the years 0-99 to 1900-1999; `setUTCFullYear` takes them literally, like the server's `LocalDate` (0001-01-01 is a date).
  const parsed = new Date(0);
  parsed.setUTCFullYear(year, month - 1, day);
  return parsed.getUTCFullYear() === year && parsed.getUTCMonth() === month - 1 && parsed.getUTCDate() === day;
}

/** A `YYYY-MM-DD` string to its UTC midnight epoch millis. Caller validates the shape first. */
function isoDateToEpochMillis(value: string): number {
  const [year, month, day] = value.split("-").map(Number);
  return Date.UTC(year, month - 1, day);
}

/** Epoch millis to `YYYY-MM-DD`, sliced from the UTC ISO string. */
export function epochMillisToIsoDate(epochMillis: number): string {
  return new Date(epochMillis).toISOString().slice(0, 10);
}

/** The `YYYY-MM-DD` date `days` calendar days after `iso` (negative = before), UTC. Caller validates the shape first. */
export function addDays(iso: string, days: number): string {
  return epochMillisToIsoDate(isoDateToEpochMillis(iso) + days * DAY_MS);
}

/**
 * Epoch millis to the `YYYY-MM-DD` calendar date it falls on in an IANA `timeZone` (the caller names the zone; the
 * default of `formatDate` is UTC). `en-CA` because its date
 * format IS the ISO one; the parts are read individually rather than trusting that formatting.
 */
export function epochMillisToIsoDateInZone(epochMillis: number, timeZone: string): string {
  const parts = new Intl.DateTimeFormat("en-CA", { timeZone, year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(
    new Date(epochMillis),
  );
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((p) => p.type === type)?.value ?? "";
  return `${part("year")}-${part("month")}-${part("day")}`;
}
