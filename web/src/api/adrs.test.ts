import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { jsonResponse } from "../test/http";
import { createAdr, deleteAdr, detachAdrSuccessor, exportAdr, exportAdrs, getAdr, listAdrEvents, listAdrs, replaceAdrParticipants, replaceAdrTags, setAdrSupersedes, transitionAdr, updateAdr } from "./adrs";

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

  test("exportAdr downloads the one ADR's Markdown under the server's file name", async () => {
    mockFetch.mockResolvedValue(new Response("# 7. x\n", { headers: { "Content-Disposition": 'attachment; filename="ADR-0007-x.md"' } }));
    const download = await exportAdr(7);
    expect(lastCall()[0]).toBe("/api/v1/adrs/7/export");
    expect(download.fileName).toBe("ADR-0007-x.md");
  });

  test("exportAdrs sends the list's filters under the list's names, and no paging or sort", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(new Response("zip")));
    const download = await exportAdrs({ status: ["ACTIVE", "IN_REVIEW"], q: "pg", text: "queue", tag: ["data"], hasOpenIssues: false, participant: 5, role: "DRIVER" });
    expect(lastCall()[0]).toBe("/api/v1/adrs/export?status=ACTIVE&status=IN_REVIEW&q=pg&participant=5&role=DRIVER&tag=data&text=queue&hasOpenIssues=false");
    expect(download.fileName).toBe("adr-export.zip");
    await exportAdrs({});
    expect(lastCall()[0]).toBe("/api/v1/adrs/export?");
  });

  test("listAdrs repeats the tag key", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
    await listAdrs({ page: 1, pageSize: 20, tag: ["data", "high availability"] });
    expect(lastCall()[0]).toBe("/api/v1/adrs?page=1&pageSize=20&tag=data&tag=high+availability");
  });

  test("listAdrs sends the full-text term, the date ranges and the three yes/no filters (false included)", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
    await listAdrs({
      page: 1,
      pageSize: 20,
      text: "message queue",
      createdFrom: "2026-01-01",
      createdTo: "2026-02-01",
      updatedFrom: "2026-03-01",
      updatedTo: "2026-04-01",
      expiresBefore: "2027-01-01",
      hasOpenIssues: true,
      supersedes: false,
      supersededBy: false,
    });
    expect(lastCall()[0]).toBe(
      "/api/v1/adrs?page=1&pageSize=20&text=message+queue&createdFrom=2026-01-01&createdTo=2026-02-01&updatedFrom=2026-03-01&updatedTo=2026-04-01&expiresBefore=2027-01-01&hasOpenIssues=true&supersedes=false&supersededBy=false",
    );
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

  test("the tags PUT and the supersedes PUT are revision-guarded and send their whole body", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await replaceAdrTags(4, 8, ["data", "team"]);
    expect(lastCall()[0]).toBe("/api/v1/adrs/4/tags");
    expect(lastCall()[1].method).toBe("PUT");
    expect(header("X-Expected-Revision")).toBe("8");
    expect(JSON.parse(lastCall()[1].body as string)).toEqual({ tags: ["data", "team"] });
    await setAdrSupersedes(4, 9, 2);
    expect(lastCall()[0]).toBe("/api/v1/adrs/4/supersedes");
    expect(header("X-Expected-Revision")).toBe("9");
    expect(JSON.parse(lastCall()[1].body as string)).toEqual({ supersedesId: 2 });
    await setAdrSupersedes(4, 10, null);
    expect(JSON.parse(lastCall()[1].body as string)).toEqual({ supersedesId: null });
  });

  test("detaching the pending successor is a header-less DELETE on the predecessor's successor", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await detachAdrSuccessor(4);
    expect(lastCall()[0]).toBe("/api/v1/adrs/4/successor");
    expect(lastCall()[1].method).toBe("DELETE");
    expect(header("X-Expected-Revision")).toBeNull();
  });

  test("listAdrEvents pages the history", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [], page: 2, pageSize: 10, total: 0 }));
    await listAdrEvents(4, { page: 2, pageSize: 10 });
    expect(lastCall()[0]).toBe("/api/v1/adrs/4/events?page=2&pageSize=10");
  });
});
