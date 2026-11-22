import { describe, expect, test } from "vitest";
import { ADR_ROLES, ADR_STATUS_COLOR, ADR_STATUSES, CONFIRMED_MOVES, ENDING_MOVES, isAdrRole, isAdrStatus, hasForbiddenReasonCharacter, isPreActive, isTerminal, moveOf, MY_WORK_REASON_COLOR } from "./adrStatus";

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

describe("the My work reason colour map", () => {
  test("covers every reason the server reports, exactly once", () => {
    expect(Object.keys(MY_WORK_REASON_COLOR).sort()).toEqual(["ESCALATION", "EXPIRING", "RETURNED", "REVIEW", "STALLED_DRAFT", "STALLED_REVIEW"]);
  });

  test("follows the colour vocabulary: blocking red, warnings orange, nudges gray, waiting on you grape", () => {
    expect(MY_WORK_REASON_COLOR).toEqual({
      ESCALATION: "red",
      EXPIRING: "orange",
      RETURNED: "orange",
      STALLED_DRAFT: "gray",
      STALLED_REVIEW: "gray",
      REVIEW: "grape",
    });
    for (const color of Object.values(MY_WORK_REASON_COLOR)) expect(["blue", "indigo", "violet"]).not.toContain(color);
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

describe("hasForbiddenReasonCharacter", () => {
  test("refuses what the server's multi-line sanitiser refuses: controls, the separators and the bidi controls", () => {
    for (const code of [0x00, 0x07, 0x0b, 0x0c, 0x1b, 0x7f, 0x85, 0x9f, 0x2028, 0x2029, 0x202a, 0x202e, 0x2066, 0x2069]) {
      expect(hasForbiddenReasonCharacter(`ok ${String.fromCodePoint(code)} ok`), code.toString(16)).toBe(true);
    }
  });

  test("lets text, line breaks, tabs, carriage returns, emoji and joiners through", () => {
    expect(hasForbiddenReasonCharacter("Needs the cost\r\nof B\n\tindented, zażółć gęślą \u{1F600} \u200D \u2065 \u202f")).toBe(false);
    expect(hasForbiddenReasonCharacter("")).toBe(false);
  });
});

describe("moveOf", () => {
  test("tells the send back and the withdrawal of a decision in force from the plain moves to the same targets", () => {
    expect(moveOf("IN_REVIEW", "SOLUTION_DRAFT")).toBe("SEND_BACK");
    expect(moveOf("PROBLEM_RAISED", "SOLUTION_DRAFT")).toBe("SOLUTION_DRAFT");
    expect(moveOf("ACTIVE", "WITHDRAWN")).toBe("WITHDRAW_ACTIVE");
    expect(moveOf("IN_REVIEW", "WITHDRAWN")).toBe("WITHDRAWN");
    expect(moveOf("SOLUTION_DRAFT", "IN_REVIEW")).toBe("IN_REVIEW");
  });

  test("the endings and the send back confirm; only the endings are red", () => {
    expect([...CONFIRMED_MOVES].sort()).toEqual(["CANCELLED", "SEND_BACK", "WITHDRAWN", "WITHDRAW_ACTIVE"]);
    expect([...ENDING_MOVES].sort()).toEqual(["CANCELLED", "WITHDRAWN", "WITHDRAW_ACTIVE"]);
  });
});
