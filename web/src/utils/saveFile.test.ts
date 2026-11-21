import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { saveFile } from "./saveFile";

describe("saveFile", () => {
  const originalCreate = URL.createObjectURL;
  const originalRevoke = URL.revokeObjectURL;
  let created: Blob[];
  let revoked: string[];
  let clicked: { download: string; href: string }[];

  beforeEach(() => {
    created = [];
    revoked = [];
    clicked = [];
    URL.createObjectURL = vi.fn((blob: Blob) => {
      created.push(blob);
      return "blob:test-1";
    });
    URL.revokeObjectURL = vi.fn((url: string) => {
      revoked.push(url);
    });
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) {
      clicked.push({ download: this.download, href: this.href });
    });
  });
  afterEach(() => {
    vi.restoreAllMocks();
    URL.createObjectURL = originalCreate;
    URL.revokeObjectURL = originalRevoke;
  });

  test("clicks a throwaway download link for the blob, then releases the object URL and the link", async () => {
    const blob = new Blob(["# 1. x"], { type: "text/markdown" });
    saveFile({ blob, fileName: "ADR-0001-x.md" });
    expect(created).toEqual([blob]);
    expect(clicked).toEqual([{ download: "ADR-0001-x.md", href: "blob:test-1" }]);
    expect(document.querySelector("a[download]")).toBeNull();
    await new Promise((resolve) => setTimeout(resolve, 5));
    expect(revoked).toEqual(["blob:test-1"]);
  });
});
