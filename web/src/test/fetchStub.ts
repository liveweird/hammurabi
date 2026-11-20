import { vi } from "vitest";
import { jsonResponse } from "./http";

export type StubRequest = { method: string; url: string; body: unknown; headers: Headers };

/**
 * Stubs `fetch` with a handler per request: return a fresh `Response` (a body can be read once) or `null` for a 404. Returns the mock and
 * a finder over the recorded calls — `find("PUT", "/api/v1/adrs/1")` is the first request with that method whose URL starts with that path.
 */
export function stubFetch(handler: (request: StubRequest) => Response | null) {
  const requests: StubRequest[] = [];
  const mock = vi.fn((url: string, init?: RequestInit) => {
    const request: StubRequest = {
      method: init?.method ?? "GET",
      url,
      body: typeof init?.body === "string" ? JSON.parse(init.body) : undefined,
      headers: new Headers(init?.headers),
    };
    requests.push(request);
    return Promise.resolve(handler(request) ?? jsonResponse(404, { title: "Not found", status: 404 }));
  });
  vi.stubGlobal("fetch", mock);
  return {
    mock,
    requests,
    find: (method: string, urlPrefix: string) => requests.find((r) => r.method === method && r.url.startsWith(urlPrefix)),
    count: (method: string, urlPrefix: string) => requests.filter((r) => r.method === method && r.url.startsWith(urlPrefix)).length,
  };
}

export const noContent = () => new Response(null, { status: 204 });
export const problem = (status: number, type?: string) => jsonResponse(status, { type: type ?? "about:blank", title: "x", status, detail: "d", instance: "/x" });
