import { describe, expect, test } from "vitest";
import { NO_ADR_FILTERS, parseAdrListParams } from "./adrListParams";
import { filtersFromParams, hasFilters, paramsFromFilters, sameFilters } from "./lensFilters";

const parse = (query: string) => parseAdrListParams(new URLSearchParams(query));

describe("lens filters", () => {
  test("the URL filters become a lens document that names only what is set", () => {
    expect(filtersFromParams(parse(""), 5)).toEqual({});
    expect(
      filtersFromParams(
        parse("q=%20db%20&text=queue&status=ACTIVE&tag=data&mine=1&participantRole=DRIVER&createdFrom=2026-01-01&updatedTo=2026-04-01&expiresBefore=2027-01-01&hasOpenIssues=false&hasPredecessor=true"),
        5,
      ),
    ).toEqual({
      q: "db",
      text: "queue",
      status: ["ACTIVE"],
      tag: ["data"],
      participantId: 5,
      participantRole: "DRIVER",
      createdFrom: "2026-01-01",
      updatedTo: "2026-04-01",
      expiresBefore: "2027-01-01",
      hasOpenIssues: false,
      hasPredecessor: true,
    });
  });

  test("mine without a known user, and a role without a participant, are left out", () => {
    expect(filtersFromParams(parse("mine=1&participantRole=DRIVER"), null)).toEqual({});
    expect(filtersFromParams(parse("participantId=9&participantRole=INFORMED"), 5)).toEqual({ participantId: 9, participantRole: "INFORMED" });
  });

  test("a lens replaces EVERY filter: what it lacks is reset", () => {
    expect(paramsFromFilters({}, 5)).toEqual(NO_ADR_FILTERS);
    expect(paramsFromFilters({ status: ["ACTIVE", "ACTIVE", "NOPE" as never], hasPredecessor: false, createdFrom: "2026-13-45", text: "a" }, 5)).toEqual({
      ...NO_ADR_FILTERS,
      status: ["ACTIVE"],
      hasPredecessor: false,
    });
  });

  test("the lens's participant is 'mine' when it is the signed-in user, someone else's id otherwise", () => {
    expect(paramsFromFilters({ participantId: 5, participantRole: "APPROVER" }, 5)).toMatchObject({ mine: true, participantId: null, participantRole: "APPROVER" });
    expect(paramsFromFilters({ participantId: 9, participantRole: "APPROVER" }, 5)).toMatchObject({ mine: false, participantId: 9, participantRole: "APPROVER" });
    expect(paramsFromFilters({ participantRole: "APPROVER" }, 5)).toMatchObject({ participantRole: null });
  });

  test("a lens round-trips through the URL filters", () => {
    const filters = { q: "db", text: "queue", status: ["IN_REVIEW" as const], tag: ["data"], participantId: 5, participantRole: "DRIVER" as const, expiresBefore: "2027-01-01", hasOpenIssues: true };
    expect(filtersFromParams(paramsFromFilters(filters, 5), 5)).toEqual(filters);
  });

  test("equality ignores the order of statuses and tags, blanks, and a role without a participant", () => {
    expect(sameFilters({ status: ["ACTIVE", "IN_REVIEW"], tag: ["b", "a"] }, { status: ["IN_REVIEW", "ACTIVE"], tag: ["a", "b"] })).toBe(true);
    expect(sameFilters({ q: "  db " }, { q: "db", tag: [], status: [] })).toBe(true);
    expect(sameFilters({ participantRole: "DRIVER" }, {})).toBe(true);
    expect(sameFilters({ hasPredecessor: false }, {})).toBe(false);
    expect(sameFilters({ text: "queue" }, { text: "queues" })).toBe(false);
    expect(sameFilters({ participantId: 5, participantRole: "DRIVER" }, { participantId: 5 })).toBe(false);
  });

  test("an empty document filters on nothing", () => {
    expect(hasFilters({})).toBe(false);
    expect(hasFilters({ q: " ", tag: [] })).toBe(false);
    expect(hasFilters({ hasOpenIssues: false })).toBe(true);
  });
});
