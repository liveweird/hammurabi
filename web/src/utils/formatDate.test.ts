import { describe, expect, test } from "vitest";
import { browserTimeZone, formatDate, formatDateTime } from "./formatDate";
import { usePinnedBrowserTimeZone } from "../test/timeZone";

describe("formatDate", () => {
  test("slices the UTC ISO date", () => {
    expect(formatDate(Date.UTC(2026, 8, 29, 23, 59))).toBe("2026-09-29");
  });

  test("reads the calendar day in the given zone", () => {
    // 22:30Z on the 29th is already the 30th in Warsaw (CEST, +2) — and still the 29th in UTC.
    const at = Date.UTC(2026, 8, 29, 22, 30);
    expect(formatDate(at, "—", "Europe/Warsaw")).toBe("2026-09-30");
    expect(formatDate(at)).toBe("2026-09-29");
    // Winter time (+1): 23:30Z crosses midnight, 22:30Z does not.
    expect(formatDate(Date.UTC(2026, 0, 10, 23, 30), "—", "Europe/Warsaw")).toBe("2026-01-11");
    expect(formatDate(Date.UTC(2026, 0, 10, 22, 30), "—", "Europe/Warsaw")).toBe("2026-01-10");
  });

  test("null and undefined render the fallback", () => {
    expect(formatDate(null)).toBe("—");
    expect(formatDate(undefined, "open")).toBe("open");
  });
});

describe("formatDateTime", () => {
  test("reads the 24-hour wall clock in the given zone", () => {
    const at = Date.UTC(2026, 8, 29, 22, 5);
    expect(formatDateTime(at)).toBe("2026-09-29 22:05");
    expect(formatDateTime(at, "Europe/Warsaw")).toBe("2026-09-30 00:05");
    expect(formatDateTime(Date.UTC(2026, 0, 10, 23, 30), "Europe/Warsaw")).toBe("2026-01-11 00:30");
  });
});

describe("browserTimeZone", () => {
  test("is an IANA zone the date formatter accepts", () => {
    const zone = browserTimeZone();
    expect(zone).not.toBe("");
    expect(formatDateTime(Date.UTC(2026, 8, 29, 22, 5), zone)).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/);
  });

  describe("under a non-UTC browser zone", () => {
    usePinnedBrowserTimeZone("Pacific/Auckland");

    test("timestamps move into the zone while UTC stays the default", () => {
      // 22:05Z on the 29th is 11:05 on the 30th in Auckland (NZDT, +13).
      const at = Date.UTC(2026, 8, 29, 22, 5);
      expect(browserTimeZone()).toBe("Pacific/Auckland");
      expect(formatDateTime(at, browserTimeZone())).toBe("2026-09-30 11:05");
      expect(formatDate(at, "—", browserTimeZone())).toBe("2026-09-30");
      expect(formatDateTime(at)).toBe("2026-09-29 22:05");
    });
  });
});
