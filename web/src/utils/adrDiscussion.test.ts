import { describe, expect, test } from "vitest";
import { DISCUSSION_KIND_COLOR, DISCUSSION_PAGE_SIZE, DISCUSSION_STATUS_COLOR, DISCUSSION_VARIANT_KINDS, lastDiscussionPage } from "./adrDiscussion";

describe("the discussion colour maps", () => {
  test("the kinds follow the app's vocabulary: an escalation is red, an issue orange, a question grape, a comment gray", () => {
    expect(DISCUSSION_KIND_COLOR).toEqual({ COMMENT: "gray", QUESTION: "grape", ISSUE: "orange", ESCALATION: "red" });
  });

  test("a settled entry is teal and an open one neutral", () => {
    expect(DISCUSSION_STATUS_COLOR).toEqual({ OPEN: "gray", ANSWERED: "teal", RESOLVED: "teal" });
  });

  test("never stock blue, indigo or violet, which sit beside the lapis brand", () => {
    for (const color of [...Object.values(DISCUSSION_KIND_COLOR), ...Object.values(DISCUSSION_STATUS_COLOR)]) {
      expect(["blue", "indigo", "violet"]).not.toContain(color);
    }
  });
});

describe("the sections", () => {
  test("the two variants split the four kinds between them", () => {
    expect(DISCUSSION_VARIANT_KINDS.discussion).toEqual(["COMMENT", "QUESTION"]);
    expect(DISCUSSION_VARIANT_KINDS.issues).toEqual(["ISSUE", "ESCALATION"]);
  });

  test("the last page of a thread: an empty one is page 1, a full page does not open another", () => {
    expect(lastDiscussionPage(0)).toBe(1);
    expect(lastDiscussionPage(DISCUSSION_PAGE_SIZE)).toBe(1);
    expect(lastDiscussionPage(DISCUSSION_PAGE_SIZE + 1)).toBe(2);
    expect(lastDiscussionPage(45, 10)).toBe(5);
  });
});
