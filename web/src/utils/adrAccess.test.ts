import { describe, expect, test } from "vitest";
import { adrFixture } from "../test/adrFixtures";
import { missingForReview } from "./adrAccess";

const PEOPLE = [
  { userId: 5, name: "Dana Driver", role: "DRIVER" as const },
  { userId: 6, name: "Alex Approver", role: "APPROVER" as const },
  { userId: 7, name: "Cora Contributor", role: "CONTRIBUTOR" as const },
  { userId: 8, name: "Ida Informed", role: "INFORMED" as const },
];

describe("missingForReview", () => {
  test("lists what entering review still needs, in reading order", () => {
    expect(missingForReview(adrFixture())).toEqual(["context", "decision", "consequences", "approver"]);
  });

  test("whitespace does not count as written; an Approver satisfies the last one", () => {
    expect(missingForReview(adrFixture({ context: "  \n", decision: "D", consequences: "C", participants: PEOPLE }))).toEqual(["context"]);
  });

  test("nothing is missing from a complete ADR", () => {
    expect(missingForReview(adrFixture({ context: "X", decision: "D", consequences: "C", participants: PEOPLE }))).toEqual([]);
  });
});
