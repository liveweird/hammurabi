import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { problem } from "../test/fetchStub";
import { ApiError } from "./http";
import { downloadRequest, fileNameFrom } from "./download";

describe("fileNameFrom", () => {
  test("reads the quoted filename of an attachment", () => {
    expect(fileNameFrom('attachment; filename="ADR-0042-use-postgresql.md"', "x.md")).toBe("ADR-0042-use-postgresql.md");
  });

  test("falls back when the header is absent, unquoted or not a plain name", () => {
    expect(fileNameFrom(null, "x.md")).toBe("x.md");
    expect(fileNameFrom("attachment", "x.md")).toBe("x.md");
    expect(fileNameFrom("attachment; filename=plain.md", "x.md")).toBe("x.md");
    expect(fileNameFrom('attachment; filename="../../etc/passwd"', "x.md")).toBe("x.md");
    expect(fileNameFrom('attachment; filename="a b.md"', "x.md")).toBe("x.md");
    expect(fileNameFrom('attachment; filename=""', "x.md")).toBe("x.md");
  });
});

describe("downloadRequest", () => {
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

  test("sends the bearer token and returns the bytes with the server's file name", async () => {
    mockFetch.mockResolvedValue(
      new Response("# 1. Use PostgreSQL\n", { status: 200, headers: { "Content-Disposition": 'attachment; filename="ADR-0001-use-postgresql.md"' } }),
    );
    const download = await downloadRequest("/api/v1/adrs/1/export", "adr-1.md");
    const [url, init] = mockFetch.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/v1/adrs/1/export");
    expect(new Headers(init.headers).get("Authorization")).toBe("Bearer fake-token");
    expect(download.fileName).toBe("ADR-0001-use-postgresql.md");
    expect(await download.blob.text()).toBe("# 1. Use PostgreSQL\n");
  });

  test("uses the fallback name when the server names none", async () => {
    mockFetch.mockResolvedValue(new Response("zip", { status: 200 }));
    expect((await downloadRequest("/api/v1/adrs/export?", "adr-export.zip")).fileName).toBe("adr-export.zip");
  });

  test("a refusal throws an ApiError carrying the problem body", async () => {
    mockFetch.mockResolvedValue(problem(400));
    const failure = await downloadRequest("/api/v1/adrs/export?", "adr-export.zip").catch((e: unknown) => e);
    expect(failure).toBeInstanceOf(ApiError);
    expect((failure as ApiError).status).toBe(400);
    expect((failure as ApiError).detail).toBe("d");
  });
});
