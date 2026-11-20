import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { eventFixture } from "../test/adrFixtures";
import { adrEventActor, adrEventLines, describeAdrEvent } from "./adrEvents";

const t = i18n.t.bind(i18n);

describe("describeAdrEvent", () => {
  test.each([
    [{ type: "CREATED" as const, params: { key: "ADR-0001", name: "N" } }, "ADR raised"],
    [{ type: "UPDATED" as const, params: { changed: "context" } }, "Edited"],
    [{ type: "TRANSITIONED" as const, params: { from: "PROBLEM_RAISED", to: "SOLUTION_DRAFT" } }, "Status changed from Problem raised to Solution draft"],
    [{ type: "PARTICIPANT_ADDED" as const, params: { userId: "7", name: "Cora", role: "CONTRIBUTOR" } }, "Cora added as Contributor"],
    [{ type: "PARTICIPANT_REMOVED" as const, params: { userId: "7", name: "Cora", role: "INFORMED" } }, "Cora removed (Informed)"],
    [{ type: "PARTICIPANT_CHANGED" as const, params: { userId: "7", name: "Cora", from: "INFORMED", to: "APPROVER" } }, "Cora moved from Informed to Approver"],
    [{ type: "EXPIRED" as const, params: { expiresOn: "2026-12-31" } }, "Expired: the expiry date 2026-12-31 has passed"],
    [{ type: "SUPERSEDED" as const, params: { successorId: "9", key: "ADR-0009", name: "Next" } }, "Superseded by ADR-0009"],
    [{ type: "SUPERSEDED" as const, params: {} }, "Superseded by —"],
    [{ type: "TAGS_CHANGED" as const, params: { added: "data" } }, "Tags changed"],
    [{ type: "SUCCESSOR_LINKED" as const, params: { predecessorId: "3", key: "ADR-0003", name: "Old" } }, "Set to replace ADR-0003"],
    [{ type: "SUCCESSOR_UNLINKED" as const, params: { predecessorId: "3", key: "ADR-0003", name: "Old" } }, "No longer replaces ADR-0003"],
    [{ type: "SUCCESSOR_UNLINKED" as const, params: { successorId: "9", key: "ADR-0009", name: "Next" } }, "Pending successor ADR-0009 detached"],
    [{ type: "SUCCESSOR_ACTIVATED" as const, params: { predecessorId: "3", key: "ADR-0003", name: "Old" } }, "Activated and superseded ADR-0003"],
    [{ type: "SUCCESSOR_ACTIVATED" as const, params: {} }, "Activated and superseded —"],
    [{ type: "COMMENTED" as const, params: { entryId: "3", kind: "COMMENT" } }, "Comment added"],
    [{ type: "QUESTION_ASKED" as const, params: { entryId: "3", kind: "QUESTION" } }, "Question asked"],
    [{ type: "QUESTION_ANSWERED" as const, params: { entryId: "3", kind: "QUESTION" } }, "Question answered"],
    [{ type: "QUESTION_REOPENED" as const, params: { entryId: "3", kind: "QUESTION" } }, "Question reopened"],
    [{ type: "ISSUE_RAISED" as const, params: { entryId: "4", kind: "ISSUE" } }, "Issue raised"],
    [{ type: "ESCALATED" as const, params: { entryId: "4", kind: "ESCALATION" } }, "Escalation raised"],
    [{ type: "ISSUE_RESOLVED" as const, params: { entryId: "4", kind: "ISSUE" } }, "Issue resolved"],
    [{ type: "ISSUE_REOPENED" as const, params: { entryId: "4", kind: "ISSUE" } }, "Issue reopened"],
    [{ type: "ISSUE_RESOLVED" as const, params: { entryId: "5", kind: "ESCALATION" } }, "Escalation resolved"],
    [{ type: "ISSUE_REOPENED" as const, params: { entryId: "5", kind: "ESCALATION" } }, "Escalation reopened"],
    [{ type: "ISSUE_RESOLVED" as const, params: {} }, "Issue resolved"],
  ])("%j reads as its sentence", (event, expected) => {
    expect(describeAdrEvent(eventFixture(event), t)).toBe(expected);
  });

  test("a value the SPA does not know shows as recorded, a missing one as a dash", () => {
    expect(describeAdrEvent(eventFixture({ type: "TRANSITIONED", params: { from: "ARCHIVED" } }), t)).toBe("Status changed from ARCHIVED to —");
    expect(describeAdrEvent(eventFixture({ type: "PARTICIPANT_ADDED", params: { role: "OWNER" } }), t)).toBe("— added as OWNER");
    expect(describeAdrEvent(eventFixture({ type: "PARTICIPANT_ADDED", params: {} }), t)).toBe("— added as —");
    expect(describeAdrEvent(eventFixture({ type: "PARTICIPANT_REMOVED", params: {} }), t)).toBe("— removed (—)");
    expect(describeAdrEvent(eventFixture({ type: "PARTICIPANT_CHANGED", params: {} }), t)).toBe("— moved from — to —");
    expect(describeAdrEvent(eventFixture({ type: "EXPIRED", params: {} }), t)).toBe("Expired: the expiry date — has passed");
  });
});

describe("adrEventLines", () => {
  test("a created event names the key and the name", () => {
    expect(adrEventLines(eventFixture({ type: "CREATED", params: { key: "ADR-0007", name: "Use X" } }), t)).toEqual(['ADR-0007 "Use X"']);
    expect(adrEventLines(eventFixture({ type: "CREATED", params: {} }), t)).toEqual(['— "—"']);
  });

  test("an edit lists each changed field: Markdown by name, the name and the date with their sides", () => {
    const lines = adrEventLines(
      eventFixture({
        type: "UPDATED",
        params: { changed: "name,problem,decision,expiresOn", "name.from": "Old", "name.to": "New", "expiresOn.from": "2026-12-01", "expiresOn.to": "2027-01-01" },
      }),
      t,
    );
    expect(lines).toEqual(['Name: "Old" → "New"', "Problem / question changed", "Decision changed", "Expiry date: 2026-12-01 → 2027-01-01"]);
  });

  test("the expiry date can be set or removed", () => {
    expect(adrEventLines(eventFixture({ type: "UPDATED", params: { changed: "expiresOn", "expiresOn.to": "2027-01-01" } }), t)).toEqual(["Expiry date set to 2027-01-01"]);
    expect(adrEventLines(eventFixture({ type: "UPDATED", params: { changed: "expiresOn", "expiresOn.from": "2027-01-01" } }), t)).toEqual(["Expiry date removed (was 2027-01-01)"]);
    expect(adrEventLines(eventFixture({ type: "UPDATED", params: { changed: "expiresOn" } }), t)).toEqual(["Expiry date removed (was —)"]);
  });

  test("a name without sides and a field from a newer server degrade gracefully", () => {
    expect(adrEventLines(eventFixture({ type: "UPDATED", params: { changed: "name,tags" } }), t)).toEqual(['Name: "—" → "—"', "tags changed"]);
    expect(adrEventLines(eventFixture({ type: "UPDATED", params: {} }), t)).toEqual([]);
  });

  test("a tag change lists what was added and removed, a link event names the other ADR", () => {
    expect(adrEventLines(eventFixture({ type: "TAGS_CHANGED", params: { added: "data,team", removed: "old tag" } }), t)).toEqual(["Added: data, team", "Removed: old tag"]);
    expect(adrEventLines(eventFixture({ type: "TAGS_CHANGED", params: { removed: "x" } }), t)).toEqual(["Removed: x"]);
    expect(adrEventLines(eventFixture({ type: "TAGS_CHANGED", params: {} }), t)).toEqual([]);
    for (const type of ["SUPERSEDED", "SUCCESSOR_LINKED", "SUCCESSOR_UNLINKED", "SUCCESSOR_ACTIVATED"] as const) {
      expect(adrEventLines(eventFixture({ type, params: { key: "ADR-0003", name: "Old" } }), t)).toEqual(['"Old"']);
    }
    expect(adrEventLines(eventFixture({ type: "SUCCESSOR_LINKED", params: {} }), t)).toEqual(['"—"']);
  });

  test("other events have no detail lines", () => {
    expect(adrEventLines(eventFixture({ type: "TRANSITIONED", params: { from: "ACTIVE", to: "EXPIRED" } }), t)).toEqual([]);
  });
});

describe("adrEventActor", () => {
  test("the recorded name, with a note for a deleted account, or the system", () => {
    expect(adrEventActor(eventFixture(), t)).toBe("Dana Driver");
    expect(adrEventActor(eventFixture({ userDeleted: true }), t)).toBe("Dana Driver (deleted account)");
    expect(adrEventActor(eventFixture({ userId: null, userName: null, type: "EXPIRED" }), t)).toBe("System");
  });
});
