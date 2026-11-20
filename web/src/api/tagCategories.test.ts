import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { jsonResponse } from "../test/http";
import { createTagCategory, deleteTagCategory, getTagCategories, updateTagCategory } from "./tagCategories";

describe("tag registry API wrappers", () => {
  let mockFetch: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("hammurabi.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  const lastCall = () => mockFetch.mock.calls.at(-1) as [string, RequestInit];

  test("the registry is read whole and unwrapped from its items", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [{ id: 1, name: "Domain", description: null, revision: 1, tags: ["data"] }] }));
    expect(await getTagCategories()).toEqual([{ id: 1, name: "Domain", description: null, revision: 1, tags: ["data"] }]);
    expect(lastCall()[0]).toBe("/api/v1/tag-categories");
  });

  test("create, replace and delete address the collection and the member", async () => {
    mockFetch.mockResolvedValue(jsonResponse(201, { id: 5, name: "N", description: null, tags: ["a"] }));
    await createTagCategory({ name: "N", description: null, tags: ["a"] });
    expect(lastCall()[0]).toBe("/api/v1/tag-categories");
    expect(lastCall()[1]).toMatchObject({ method: "POST", body: JSON.stringify({ name: "N", description: null, tags: ["a"] }) });
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await updateTagCategory(5, 7, { name: "M", tags: ["b"] });
    expect(lastCall()[0]).toBe("/api/v1/tag-categories/5");
    expect(lastCall()[1].method).toBe("PUT");
    await deleteTagCategory(5, 8);
    expect(lastCall()[1].method).toBe("DELETE");
  });

  test("the replace and the delete carry the revision the admin loaded the category at", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await updateTagCategory(5, 7, { name: "M", tags: ["b"] });
    expect(new Headers(lastCall()[1].headers).get("X-Expected-Revision")).toBe("7");
    await deleteTagCategory(5, 8);
    expect(new Headers(lastCall()[1].headers).get("X-Expected-Revision")).toBe("8");
  });
});
