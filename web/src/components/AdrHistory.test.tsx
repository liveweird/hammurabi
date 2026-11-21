import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { jsonResponse } from "../test/http";
import { eventFixture } from "../test/adrFixtures";
import { stubFetch } from "../test/fetchStub";
import { renderWithProviders, screen, waitFor } from "../test/render";
import AdrHistory from "./AdrHistory";

describe("AdrHistory", () => {
  beforeEach(() => localStorage.setItem("hammurabi.auth.token", "fake-token"));
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("renders every event type as localized text, newest first as the server ordered them", async () => {
    const events = [
      eventFixture({ id: 4, type: "TRANSITIONED", params: { from: "PROBLEM_RAISED", to: "SOLUTION_DRAFT" } }),
      eventFixture({ id: 3, type: "PARTICIPANT_ADDED", params: { userId: "7", name: "Cora", role: "CONTRIBUTOR" } }),
      eventFixture({ id: 2, type: "UPDATED", params: { changed: "context" } }),
      eventFixture({ id: 1, type: "CREATED", params: { key: "ADR-0001", name: "Use X" } }),
    ];
    const stub = stubFetch(() => jsonResponse(200, { items: events, page: 1, pageSize: 10, total: 4 }));
    renderWithProviders(<AdrHistory adrId={9} />);
    expect(await screen.findByText("Status changed from Problem raised to Solution draft")).toBeInTheDocument();
    expect(screen.getByText("Cora added as Contributor")).toBeInTheDocument();
    expect(screen.getByText("Context changed")).toBeInTheDocument();
    expect(screen.getByText("ADR raised")).toBeInTheDocument();
    expect(screen.getAllByText(/^Dana Driver · /)).toHaveLength(4);
    expect(stub.find("GET", "/api/v1/adrs/9/events")?.url).toBe("/api/v1/adrs/9/events?page=1&pageSize=10");
    // The server's order is kept: the first rendered sentence is the first event.
    const titles = screen.getAllByText(/Status changed|added as|Edited|ADR raised/).map((el) => el.textContent);
    expect(titles[0]).toMatch(/^Status changed/);
  });

  test("a system event is attributed to the system", async () => {
    stubFetch(() =>
      jsonResponse(200, { items: [eventFixture({ type: "EXPIRED", actorId: null, actorName: null, params: { expiresOn: "2026-12-31" } })], page: 1, pageSize: 10, total: 1 }),
    );
    renderWithProviders(<AdrHistory adrId={9} />);
    expect(await screen.findByText("Expired: the expiry date 2026-12-31 has passed")).toBeInTheDocument();
    expect(screen.getByText(/^System · /)).toBeInTheDocument();
  });

  test("an ADR with no events says so", async () => {
    stubFetch(() => jsonResponse(200, { items: [], page: 1, pageSize: 10, total: 0 }));
    renderWithProviders(<AdrHistory adrId={9} />);
    expect(await screen.findByText("No history yet.")).toBeInTheDocument();
  });

  test("pages through the history ten at a time and keeps the pager when a page fails", async () => {
    const first = Array.from({ length: 10 }, (_, i) => eventFixture({ id: 20 - i, type: "UPDATED", params: { changed: "context" } }));
    const stub = stubFetch((request) => {
      if (request.url.includes("page=2")) return new Response(null, { status: 500 });
      return jsonResponse(200, { items: first, page: 1, pageSize: 10, total: 12 });
    });
    const user = userEvent.setup();
    renderWithProviders(<AdrHistory adrId={9} />);
    await screen.findAllByText("Context changed");
    await user.click(screen.getByRole("button", { name: "Next page" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Load failed (500)");
    expect(screen.getByRole("button", { name: "Previous page" })).toBeInTheDocument();
    expect(stub.find("GET", "/api/v1/adrs/9/events?page=2")).toBeDefined();
    await user.click(screen.getByRole("button", { name: "Retry" }));
    await waitFor(() => expect(stub.count("GET", "/api/v1/adrs/9/events?page=2")).toBeGreaterThan(1));
  });
});
