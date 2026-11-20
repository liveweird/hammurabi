import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { jsonResponse } from "../test/http";
import { createAdr, deleteAdr, getAdr, listAdrEvents, listAdrs, replaceAdrParticipants, transitionAdr, updateAdr } from "./adrs";

type FetchMock = ReturnType<typeof vi.fn>;

describe("ADR API wrappers", () => {
  let mockFetch: FetchMock;

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
  const header = (name: string) => new Headers(lastCall()[1].headers).get(name);

  test("listAdrs repeats the status key and sends participant and role", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
    await listAdrs({ page: 2, pageSize: 40, sort: "-number", status: ["ACTIVE", "IN_REVIEW"], q: "adr-1", participant: 7, role: "DRIVER" });
    expect(lastCall()[0]).toBe("/api/v1/adrs?page=2&pageSize=40&sort=-number&status=ACTIVE&status=IN_REVIEW&q=adr-1&participant=7&role=DRIVER");
  });

  test("listAdrs omits absent filters", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
    await listAdrs({ page: 1, pageSize: 20, status: [] });
    expect(lastCall()[0]).toBe("/api/v1/adrs?page=1&pageSize=20");
  });

  test("getAdr, createAdr and deleteAdr hit the collection and member routes", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(200, { id: 4 })));
    await getAdr(4);
    expect(lastCall()[0]).toBe("/api/v1/adrs/4");
    await createAdr({ problem: "P" });
    expect(lastCall()[0]).toBe("/api/v1/adrs");
    expect(lastCall()[1]).toMatchObject({ method: "POST", body: JSON.stringify({ problem: "P" }) });
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await deleteAdr(4);
    expect(lastCall()[1].method).toBe("DELETE");
  });

  test("the document PUT sends the revision header and the whole body", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await updateAdr(4, 9, { name: "N", problem: "P", expiresOn: null });
    expect(lastCall()[0]).toBe("/api/v1/adrs/4");
    expect(lastCall()[1].method).toBe("PUT");
    expect(header("X-Expected-Revision")).toBe("9");
    expect(JSON.parse(lastCall()[1].body as string)).toEqual({ name: "N", problem: "P", expiresOn: null });
  });

  test("a transition sends the revision and the target and answers the ADR", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { id: 4, status: "IN_REVIEW" }));
    const adr = await transitionAdr(4, 2, "IN_REVIEW");
    expect(adr.status).toBe("IN_REVIEW");
    expect(lastCall()[0]).toBe("/api/v1/adrs/4/transitions");
    expect(header("X-Expected-Revision")).toBe("2");
    expect(JSON.parse(lastCall()[1].body as string)).toEqual({ to: "IN_REVIEW" });
  });

  test("the participants PUT is revision-guarded like the document PUT", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await replaceAdrParticipants(4, 6, [{ userId: 5, role: "DRIVER" }]);
    expect(lastCall()[0]).toBe("/api/v1/adrs/4/participants");
    expect(lastCall()[1].method).toBe("PUT");
    expect(header("X-Expected-Revision")).toBe("6");
    expect(JSON.parse(lastCall()[1].body as string)).toEqual({ participants: [{ userId: 5, role: "DRIVER" }] });
  });

  test("listAdrEvents pages the history", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [], page: 2, pageSize: 10, total: 0 }));
    await listAdrEvents(4, { page: 2, pageSize: 10 });
    expect(lastCall()[0]).toBe("/api/v1/adrs/4/events?page=2&pageSize=10");
  });
});
