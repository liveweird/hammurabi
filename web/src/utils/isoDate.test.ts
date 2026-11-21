import { describe, expect, test } from "vitest";
import { addDays, epochMillisToIsoDate, epochMillisToIsoDateInZone, isValidIsoDate } from "./isoDate";

describe("isValidIsoDate", () => {
  test("rejects a malformed shape", () => {
    expect(isValidIsoDate("2024-1-1")).toBe(false);
    expect(isValidIsoDate("not-a-date")).toBe(false);
    expect(isValidIsoDate("")).toBe(false);
  });

  test("rejects an impossible calendar date (overflow normalizes to a different date)", () => {
    expect(isValidIsoDate("2024-02-30")).toBe(false);
    expect(isValidIsoDate("2024-13-01")).toBe(false);
  });

  test("accepts a real date, including a leap day", () => {
    expect(isValidIsoDate("2024-02-29")).toBe(true);
    expect(isValidIsoDate("2024-01-01")).toBe(true);
  });

  test("takes the years below 100 literally, like the server (0050-03-04 is a date, 0001-02-29 is not)", () => {
    expect(isValidIsoDate("0050-03-04")).toBe(true);
    expect(isValidIsoDate("0001-01-01")).toBe(true);
    expect(isValidIsoDate("0000-12-31")).toBe(true);
    expect(isValidIsoDate("0001-02-29")).toBe(false);
    expect(isValidIsoDate("0004-02-29")).toBe(true);
  });
});

describe("addDays / epochMillisToIsoDate", () => {
  test("addDays moves across month, year and leap-day boundaries in both directions", () => {
    expect(addDays("2024-02-28", 1)).toBe("2024-02-29");
    expect(addDays("2024-02-29", 1)).toBe("2024-03-01");
    expect(addDays("2025-12-31", 1)).toBe("2026-01-01");
    expect(addDays("2026-01-01", -1)).toBe("2025-12-31");
    expect(addDays("2026-09-30", 0)).toBe("2026-09-30");
  });

  test("epochMillisToIsoDate reads the UTC calendar day", () => {
    expect(epochMillisToIsoDate(Date.UTC(2024, 0, 15, 23, 59))).toBe("2024-01-15");
    expect(epochMillisToIsoDate(Date.UTC(2024, 0, 16))).toBe("2024-01-16");
  });
});

describe("epochMillisToIsoDateInZone", () => {
  test("follows the zone, not UTC: 23:30Z on Oct 31 is 00:30 on Nov 1 in Warsaw", () => {
    const at = Date.UTC(2026, 9, 31, 23, 30);
    expect(epochMillisToIsoDateInZone(at, "UTC")).toBe("2026-10-31");
    expect(epochMillisToIsoDateInZone(at, "Europe/Warsaw")).toBe("2026-11-01");
    // A zone behind UTC keeps the previous day.
    expect(epochMillisToIsoDateInZone(at, "America/New_York")).toBe("2026-10-31");
  });

  test("handles the DST change day", () => {
    // Warsaw leaves DST on 2026-10-25: 00:30 local (22:30Z the day before) is still the 25th.
    expect(epochMillisToIsoDateInZone(Date.UTC(2026, 9, 24, 22, 30), "Europe/Warsaw")).toBe("2026-10-25");
  });
});
