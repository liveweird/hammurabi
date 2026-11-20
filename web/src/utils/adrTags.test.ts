import { describe, expect, test } from "vitest";
import { hasRetiredTag, liveTagNames, tagOptionGroups } from "./adrTags";

describe("tag helpers", () => {
  test("the picker's options are one group per category with tags, in the registry's order", () => {
    expect(
      tagOptionGroups([
        { id: 1, name: "Domain", description: null, revision: 1, tags: ["data", "security"] },
        { id: 2, name: "Empty", description: null, revision: 1, tags: [] },
        { id: 3, name: "Scope", description: "x", revision: 1, tags: ["team"] },
      ]),
    ).toEqual([
      { group: "Domain", items: [{ value: "data", label: "data" }, { value: "security", label: "security" }] },
      { group: "Scope", items: [{ value: "team", label: "team" }] },
    ]);
    expect(tagOptionGroups([])).toEqual([]);
  });

  test("the editor starts from the live tags and knows when a retired one is present", () => {
    const tags = [{ name: "data", retired: false }, { name: "gone", retired: true }];
    expect(liveTagNames(tags)).toEqual(["data"]);
    expect(hasRetiredTag(tags)).toBe(true);
    expect(hasRetiredTag(tags.slice(0, 1))).toBe(false);
    expect(hasRetiredTag([])).toBe(false);
  });
});
