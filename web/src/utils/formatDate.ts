import { epochMillisToIsoDateInZone } from "./isoDate";

/**
 * Epoch millis to the app's one date rendering, `YYYY-MM-DD` — never `toLocaleString()`
 * (deterministic across test/CI locales). The calendar day is read in `timeZone` (UTC by
 * default; the ADR pages pass `browserTimeZone()`, so 22:30Z shows as the next day in Warsaw). `null`/`undefined`
 * (an ADR with no decision date yet) renders as the fallback.
 */
export function formatDate(epochMillis: number | null | undefined, fallback = "—", timeZone = "UTC"): string {
  if (epochMillis == null) return fallback;
  return epochMillisToIsoDateInZone(epochMillis, timeZone);
}

/**
 * Epoch millis as `YYYY-MM-DD HH:mm` read in `timeZone` (the print header's generation stamp). `sv-SE` because its
 * date-time format IS that one; the parts are read individually rather than trusting the formatting.
 */
export function formatDateTime(epochMillis: number, timeZone = "UTC"): string {
  const parts = new Intl.DateTimeFormat("sv-SE", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).formatToParts(new Date(epochMillis));
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((p) => p.type === type)?.value ?? "";
  return `${part("year")}-${part("month")}-${part("day")} ${part("hour")}:${part("minute")}`;
}

/**
 * The browser's own IANA time zone. A timestamp on an ADR page (updated, history, discussion, notices) is read where its reader is:
 * callers pass this as the `timeZone` of [formatDate]/[formatDateTime]. Date-only values (`expiresOn`, the `YYYY-MM-DD` filters) are
 * calendar days and are never shifted; the telemetry panel keeps UTC (the server's own sampling clock).
 */
export function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone;
}
