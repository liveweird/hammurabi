import { describe, expect, test } from "vitest";
import { ADR_ROLES, ADR_STATUS_COLOR, ADR_STATUSES, isAdrRole, isAdrStatus, isPreActive, isTerminal } from "./adrStatus";

describe("the ADR status colour map", () => {
  test("covers every status exactly once, in lifecycle order", () => {
    expect(ADR_STATUSES).toEqual(["PROBLEM_RAISED", "SOLUTION_DRAFT", "IN_REVIEW", "ACTIVE", "CANCELLED", "WITHDRAWN", "EXPIRED", "SUPERSEDED"]);
    expect(Object.keys(ADR_STATUS_COLOR).sort()).toEqual([...ADR_STATUSES].sort());
  });

  test("follows the app's colour vocabulary: gray, grape, cyan, teal, orange, red — never blue, indigo or violet", () => {
    expect(ADR_STATUS_COLOR).toEqual({
      PROBLEM_RAISED: "gray",
      SOLUTION_DRAFT: "grape",
      IN_REVIEW: "cyan",
      ACTIVE: "teal",
      CANCELLED: "gray",
      WITHDRAWN: "orange",
      EXPIRED: "red",
      SUPERSEDED: "gray",
    });
    for (const color of Object.values(ADR_STATUS_COLOR)) expect(["blue", "indigo", "violet"]).not.toContain(color);
  });
});

describe("status predicates", () => {
  test("content is open only before the decision is in force", () => {
    expect(ADR_STATUSES.filter(isPreActive)).toEqual(["PROBLEM_RAISED", "SOLUTION_DRAFT", "IN_REVIEW"]);
  });

  test("the four endings are terminal", () => {
    expect(ADR_STATUSES.filter(isTerminal)).toEqual(["CANCELLED", "WITHDRAWN", "EXPIRED", "SUPERSEDED"]);
  });

  test("the string guards accept the enum values only", () => {
    expect(isAdrStatus("ACTIVE")).toBe(true);
    expect(isAdrStatus("active")).toBe(false);
    expect(isAdrStatus(null)).toBe(false);
    expect(ADR_ROLES.every(isAdrRole)).toBe(true);
    expect(isAdrRole("OWNER")).toBe(false);
    expect(isAdrRole(undefined)).toBe(false);
  });
});
