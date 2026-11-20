import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { jsonResponse } from "../test/http";
import { deleteDiscussionEntry, editDiscussionEntry, listDiscussion, postDiscussionEntry, setDiscussionEntryState } from "./adrDiscussion";

type FetchMock = ReturnType<typeof vi.fn>;

describe("ADR discussion API wrappers", () => {
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

  test("listDiscussion repeats the kind key and sends the status", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
    await listDiscussion(4, { page: 2, pageSize: 10, sort: "-createdAt", kind: ["ISSUE", "ESCALATION"], status: "OPEN" });
    expect(lastCall()[0]).toBe("/api/v1/adrs/4/discussion?page=2&pageSize=10&sort=-createdAt&kind=ISSUE&kind=ESCALATION&status=OPEN");
  });

  test("listDiscussion omits absent filters", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
    await listDiscussion(4, { page: 1, pageSize: 20, kind: [] });
    expect(lastCall()[0]).toBe("/api/v1/adrs/4/discussion?page=1&pageSize=20");
  });

  test("post, edit and delete address the collection and the entry", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(200, { id: 9 })));
    await postDiscussionEntry(4, "QUESTION", "Why?");
    expect(lastCall()[0]).toBe("/api/v1/adrs/4/discussion");
    expect(lastCall()[1].method).toBe("POST");
    expect(JSON.parse(lastCall()[1].body as string)).toEqual({ kind: "QUESTION", body: "Why?" });
    await editDiscussionEntry(4, 9, "Why not?");
    expect(lastCall()[0]).toBe("/api/v1/adrs/4/discussion/9");
    expect(lastCall()[1].method).toBe("PUT");
    expect(JSON.parse(lastCall()[1].body as string)).toEqual({ body: "Why not?" });
    mockFetch.mockImplementation(() => Promise.resolve(new Response(null, { status: 204 })));
    await deleteDiscussionEntry(4, 9);
    expect(lastCall()[0]).toBe("/api/v1/adrs/4/discussion/9");
    expect(lastCall()[1].method).toBe("DELETE");
  });

  test("the state PUT sends the note only when there is one", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(200, { id: 9 })));
    await setDiscussionEntryState(4, 9, "RESOLVED", "Fixed");
    expect(lastCall()[0]).toBe("/api/v1/adrs/4/discussion/9/state");
    expect(JSON.parse(lastCall()[1].body as string)).toEqual({ status: "RESOLVED", resolution: "Fixed" });
    await setDiscussionEntryState(4, 9, "OPEN");
    expect(JSON.parse(lastCall()[1].body as string)).toEqual({ status: "OPEN" });
  });
});
