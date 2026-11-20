import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { adrFixture } from "../test/adrFixtures";
import { EMPTY_ADR_FORM, adrFormValidation, adrFormValues, toCreateBody, toUpdateBody } from "./adrForm";
import { EMPTY_PARTICIPANTS } from "./adrParticipants";

const t = i18n.t.bind(i18n);

describe("ADR form", () => {
  test("the form values are the document's editable fields", () => {
    expect(adrFormValues(adrFixture({ name: "N", problem: "P", context: "C" }))).toEqual({
      name: "N",
      problem: "P",
      context: "C",
      decision: "",
      alternatives: "",
      consequences: "",
    });
  });

  test("a create may leave the name blank; an edit needs one of 1 to 120 characters", () => {
    const create = adrFormValidation(t, { nameRequired: false });
    const edit = adrFormValidation(t, { nameRequired: true });
    expect(create.name("")).toBeNull();
    expect(create.name("x".repeat(120))).toBeNull();
    expect(create.name("x".repeat(121))).toBe("The name must be 1–120 characters");
    expect(edit.name("   ")).toBe("The name must be 1–120 characters");
    expect(edit.name("Use X")).toBeNull();
  });

  test("the problem is required and every Markdown field is capped at 20 000", () => {
    const rules = adrFormValidation(t, { nameRequired: true });
    expect(rules.problem("  \n")).toBe("Describe the problem or question");
    expect(rules.problem("x".repeat(20_001))).toBe("At most 20000 characters");
    expect(rules.problem("ok")).toBeNull();
    expect(rules.context("x".repeat(20_001))).toBe("At most 20000 characters");
    expect(rules.consequences("")).toBeNull();
  });

  test("the create body leaves a blank name out and carries the extra participants", () => {
    const body = toCreateBody({ ...EMPTY_ADR_FORM, problem: "P", name: "  " }, { ...EMPTY_PARTICIPANTS, approver: { value: "6", label: "A" } });
    expect(body).toEqual({ problem: "P", context: "", decision: "", alternatives: "", consequences: "", participants: [{ userId: 6, role: "APPROVER" }] });
    expect(toCreateBody({ ...EMPTY_ADR_FORM, problem: "P", name: " Use X " }, EMPTY_PARTICIPANTS).name).toBe("Use X");
  });

  test("the update body is the whole document with the expiry date to keep or set", () => {
    const values = { ...adrFormValues(adrFixture()), name: " Renamed " };
    expect(toUpdateBody(values, "2027-01-31")).toMatchObject({ name: "Renamed", problem: "We need a database.", expiresOn: "2027-01-31" });
    expect(toUpdateBody(values, null).expiresOn).toBeNull();
  });
});
