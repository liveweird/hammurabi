import { describe, expect, test } from "vitest";
import { adrFixture } from "../test/adrFixtures";
import { canEditContent, missingForReview } from "./adrAccess";

const PEOPLE = [
  { userId: 5, name: "Dana Driver", role: "DRIVER" as const },
  { userId: 6, name: "Alex Approver", role: "APPROVER" as const },
  { userId: 7, name: "Cora Contributor", role: "CONTRIBUTOR" as const },
  { userId: 8, name: "Ida Informed", role: "INFORMED" as const },
];

describe("canEditContent", () => {
  const adr = adrFixture({ participants: PEOPLE });

  test("the Driver and a Contributor edit the content, the Approver and the Informed do not", () => {
    expect(canEditContent(adr, 5, false)).toBe(true);
    expect(canEditContent(adr, 7, false)).toBe(true);
    expect(canEditContent(adr, 6, false)).toBe(false);
    expect(canEditContent(adr, 8, false)).toBe(false);
    expect(canEditContent(adr, null, false)).toBe(false);
  });

  test("an administrator edits without a role", () => {
    expect(canEditContent(adr, 99, true)).toBe(true);
  });

  test("the freeze and the server's canEdit verdict win over the role", () => {
    expect(canEditContent({ ...adr, status: "ACTIVE" }, 5, true)).toBe(false);
    expect(canEditContent({ ...adr, status: "CANCELLED" }, 5, true)).toBe(false);
    expect(canEditContent({ ...adr, canEdit: false }, 5, true)).toBe(false);
  });
});

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
