import { describe, expect, test } from "vitest";
import { ADR_STATUSES, isTerminal } from "./adrStatus";
import { ADR_LIFECYCLE_MOVES, moveId, movesFrom } from "./adrLifecycle";

describe("adrLifecycle", () => {
  test("the moves are the server's map (AdrLifecycle.kt ALLOWED_TRANSITIONS) edge for edge", () => {
    const byFrom = Object.fromEntries(ADR_STATUSES.map((status) => [status, movesFrom(status).map((m) => m.to)]));
    expect(byFrom).toEqual({
      PROBLEM_RAISED: ["SOLUTION_DRAFT", "IN_REVIEW", "CANCELLED"],
      SOLUTION_DRAFT: ["IN_REVIEW", "CANCELLED"],
      IN_REVIEW: ["SOLUTION_DRAFT", "ACTIVE", "CANCELLED", "WITHDRAWN"],
      ACTIVE: ["WITHDRAWN", "EXPIRED", "SUPERSEDED"],
      CANCELLED: [],
      WITHDRAWN: [],
      EXPIRED: [],
      SUPERSEDED: [],
    });
  });

  test("no move leaves a terminal status and only the system moves into EXPIRED and SUPERSEDED", () => {
    expect(ADR_LIFECYCLE_MOVES.filter((m) => isTerminal(m.from))).toEqual([]);
    const automatic = ADR_LIFECYCLE_MOVES.filter((m) => m.actor === "AUTOMATIC").map((m) => m.to);
    expect(automatic).toEqual(["EXPIRED", "SUPERSEDED"]);
  });

  test("a move id is unique per edge", () => {
    const ids = ADR_LIFECYCLE_MOVES.map(moveId);
    expect(new Set(ids).size).toBe(ids.length);
    expect(moveId({ from: "IN_REVIEW", to: "ACTIVE" })).toBe("IN_REVIEW>ACTIVE");
  });
});
