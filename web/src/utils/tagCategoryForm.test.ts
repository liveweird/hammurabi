import { describe, expect, test } from "vitest";
import { ApiError } from "../api/http";
import i18n from "../i18n";
import {
  EMPTY_TAG_CATEGORY_FORM,
  MAX_CATEGORY_TAGS,
  MAX_TAG_NAME_LENGTH,
  normalizeTag,
  tagCategoryFormValidation,
  tagCategorySaveErrorMessage,
  toTagCategoryBody,
  toTagCategoryFormValues,
} from "./tagCategoryForm";

const t = i18n.t.bind(i18n);
const rules = tagCategoryFormValidation(t);

describe("tag category form", () => {
  test("a tag's normal form is trimmed, single-spaced and lower-case, like the server's", () => {
    expect(normalizeTag("  High   Availability ")).toBe("high availability");
  });

  test("the wire body carries normalised tags and null for a blank description", () => {
    expect(toTagCategoryBody({ name: " Domain ", description: "  ", tags: [" Data ", "Time  Series"] })).toEqual({
      name: "Domain",
      description: null,
      tags: ["data", "time series"],
    });
    expect(toTagCategoryBody({ name: "Scope", description: " how far ", tags: ["team"] }).description).toBe("how far");
  });

  test("an edit starts from the category, a missing description as empty", () => {
    expect(toTagCategoryFormValues({ id: 1, name: "Domain", description: null, revision: 1, tags: ["data"] })).toEqual({ name: "Domain", description: "", tags: ["data"] });
    expect(EMPTY_TAG_CATEGORY_FORM).toEqual({ name: "", description: "", tags: [] });
  });

  test("the name and description follow the server's lengths", () => {
    expect(rules.name("")).toMatch(/1–60/);
    expect(rules.name("x".repeat(61))).toMatch(/1–60/);
    expect(rules.name("Domain")).toBeNull();
    expect(rules.description("d".repeat(501))).toMatch(/at most 500/);
    expect(rules.description("")).toBeNull();
  });

  test("the tags need one, at most a hundred, each in the grammar and unique after normalising", () => {
    expect(rules.tags([])).toMatch(/at least one/);
    expect(rules.tags(Array.from({ length: MAX_CATEGORY_TAGS + 1 }, (_, i) => `t${i}`))).toMatch(/At most 100/);
    expect(rules.tags(["a,b"])).toMatch(/"a,b"/);
    expect(rules.tags(["-lead"])).toMatch(/"-lead"/);
    expect(rules.tags(["x".repeat(MAX_TAG_NAME_LENGTH + 1)])).toMatch(/1–40/);
    expect(rules.tags([" "])).toMatch(/must be 1–40/);
    expect(rules.tags(["Data", " data "])).toMatch(/must not repeat/);
    expect(rules.tags(["data", "high availability", "c++", "żółć"])).toBeNull();
  });

  test("a save failure has fixed words per status and the network fallback", () => {
    const err = (status: number) => new ApiError(status, { status });
    expect(tagCategorySaveErrorMessage(err(403), t)).toMatch(/Only administrators/);
    expect(tagCategorySaveErrorMessage(err(409), t)).toMatch(/already in use/);
    expect(tagCategorySaveErrorMessage(err(400), t)).toMatch(/invalid/);
    expect(tagCategorySaveErrorMessage(err(404), t)).toMatch(/no longer exists/);
    expect(tagCategorySaveErrorMessage(err(500), t)).toBe("Save failed (500)");
    expect(tagCategorySaveErrorMessage(new Error("x"), t)).toMatch(/Save failed\. Check your connection/);
  });
});
