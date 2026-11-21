import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { jsonResponse } from "../test/http";
import { createLens, deleteLens, listLenses, updateLens } from "./lenses";

describe("lens API wrappers", () => {
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

  test("listLenses pages and sends mine, including false", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 100, total: 0 })));
    await listLenses({ page: 1, pageSize: 100, mine: true });
    expect(lastCall()[0]).toBe("/api/v1/lenses?page=1&pageSize=100&mine=true");
    await listLenses({ page: 2, pageSize: 50, sort: "-updatedAt", mine: false });
    expect(lastCall()[0]).toBe("/api/v1/lenses?page=2&pageSize=50&sort=-updatedAt&mine=false");
    await listLenses({ page: 1, pageSize: 20 });
    expect(lastCall()[0]).toBe("/api/v1/lenses?page=1&pageSize=20");
  });

  test("createLens posts the whole body and answers the lens", async () => {
    mockFetch.mockResolvedValue(jsonResponse(201, { id: 9, name: "Open", visibility: "PRIVATE", filters: { status: ["ACTIVE"] } }));
    const lens = await createLens({ name: "Open", visibility: "PRIVATE", filters: { status: ["ACTIVE"] } });
    expect(lens.id).toBe(9);
    expect(lastCall()[0]).toBe("/api/v1/lenses");
    expect(lastCall()[1]).toMatchObject({ method: "POST" });
    expect(JSON.parse(lastCall()[1].body as string)).toEqual({ name: "Open", visibility: "PRIVATE", filters: { status: ["ACTIVE"] } });
  });

  test("updateLens PUTs the whole lens and deleteLens DELETEs it", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await updateLens(4, { name: "New", visibility: "PUBLIC", filters: { text: "queue" } });
    expect(lastCall()[0]).toBe("/api/v1/lenses/4");
    expect(lastCall()[1].method).toBe("PUT");
    expect(JSON.parse(lastCall()[1].body as string)).toEqual({ name: "New", visibility: "PUBLIC", filters: { text: "queue" } });
    await deleteLens(4);
    expect(lastCall()[0]).toBe("/api/v1/lenses/4");
    expect(lastCall()[1].method).toBe("DELETE");
  });

  test("a failed call throws the ApiError", async () => {
    mockFetch.mockResolvedValue(jsonResponse(409, { title: "Conflict", status: 409 }));
    await expect(createLens({ name: "Dup", visibility: "PRIVATE", filters: {} })).rejects.toMatchObject({ status: 409 });
  });
});
