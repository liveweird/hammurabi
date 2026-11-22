import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import type { AdrMyWork } from "../api/adrs";
import { problem, stubFetch } from "../test/fetchStub";
import { headingOutline } from "../test/headings";
import { jsonResponse } from "../test/http";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import MyWork from "./MyWork";

const GENERATED_AT = Date.UTC(2026, 10, 22, 12);

function adr(id: number, role: AdrMyWork["adrs"]["items"][number]["role"], status: AdrMyWork["adrs"]["items"][number]["status"]) {
  return {
    id,
    key: `ADR-${String(id).padStart(4, "0")}`,
    name: `Decision ${id}`,
    role,
    status,
    lastActivityAt: GENERATED_AT - 2 * 60 * 60 * 1000,
    expiresOn: null,
  };
}

const WORK: AdrMyWork = {
  generatedAt: GENERATED_AT,
  actions: {
    hasMore: false,
    items: [
      { adr: { ...adr(1, "APPROVER", "IN_REVIEW"), name: "Set the audit retention policy" }, reason: "REVIEW", since: GENERATED_AT - 8 * 86_400_000, approverName: "Alex" },
      { adr: { ...adr(2, "APPROVER", "ACTIVE"), name: "Retire the legacy webhook", expiresOn: "2026-11-25" }, reason: "EXPIRING", since: GENERATED_AT, approverName: "Alex" },
      { adr: { ...adr(3, "DRIVER", "SOLUTION_DRAFT"), name: "Document API ownership" }, reason: "RETURNED", since: GENERATED_AT - 3 * 86_400_000, approverName: "Marta" },
      { adr: { ...adr(4, "DRIVER", "SOLUTION_DRAFT"), name: "Standardize health checks" }, reason: "STALLED_DRAFT", since: GENERATED_AT - 18 * 86_400_000, approverName: null },
      { adr: { ...adr(5, "DRIVER", "ACTIVE"), name: "Handle data residency" }, reason: "ESCALATION", since: GENERATED_AT - 86_400_000, approverName: "Marta" },
    ],
  },
  watching: {
    hasMore: true,
    items: [
      { adr: { ...adr(6, "INFORMED", "ACTIVE"), name: "Move previews to shared runners", expiresOn: "2026-12-01" }, reason: "EXPIRING", since: GENERATED_AT, approverName: "Ania" },
    ],
  },
  activity: {
    hasMore: false,
    items: [
      {
        adr: { ...adr(7, "CONTRIBUTOR", "SOLUTION_DRAFT"), name: "Define cache invalidation rules" },
        event: { id: 17, actorId: 9, actorName: "Tomasz", actorDeleted: false, type: "QUESTION_ASKED", params: { entryId: "4", kind: "QUESTION" }, createdAt: GENERATED_AT - 26 * 60 * 60 * 1000 },
      },
      {
        adr: { ...adr(8, "APPROVER", "ACTIVE"), name: "Retain customer audit logs" },
        event: { id: 18, actorId: 10, actorName: "Marta", actorDeleted: false, type: "UPDATED", params: { changed: "expiresOn", "expiresOn.from": "2026-12-01", "expiresOn.to": "2027-01-31" }, createdAt: GENERATED_AT - 2 * 60 * 60 * 1000 },
      },
    ],
  },
  adrs: {
    hasMore: true,
    items: [adr(1, "APPROVER", "IN_REVIEW"), adr(3, "DRIVER", "SOLUTION_DRAFT"), adr(7, "CONTRIBUTOR", "SOLUTION_DRAFT"), adr(6, "INFORMED", "ACTIVE")],
  },
};

function serve(work: AdrMyWork = WORK) {
  return stubFetch(({ url }) => url.startsWith("/api/v1/adrs/my-work") ? jsonResponse(200, work) : null);
}

describe("MyWork", () => {
  beforeEach(() => localStorage.setItem("hammurabi.auth.token", "fake-token"));
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("shows personal actions, activity and current ADRs in a useful heading outline", async () => {
    const stub = serve();
    renderWithProviders(<MyWork />, { route: "/my-work" });
    await screen.findByRole("heading", { name: "Needs your action" });
    expect(stub.count("GET", "/api/v1/adrs/my-work")).toBe(1);
    expect(headingOutline()).toEqual([
      [2, "My work"],
      [3, "Needs your action"],
      [4, "Worth watching"],
      [3, "Recent activity"],
      [3, "My ADRs"],
    ]);
    expect(screen.getByRole("link", { name: /Set the audit retention policy/ })).toHaveAttribute("href", "/adrs/1");
    expect(screen.getByRole("link", { name: /Retire the legacy webhook/ })).toHaveAttribute("href", "/adrs/2#validity");
    expect(screen.getByRole("link", { name: /Handle data residency/ })).toHaveAttribute("href", "/adrs/5#issues");
    expect(screen.getByText("Awaiting your decision")).toBeInTheDocument();
    expect(screen.getByText("Expires in 3 days")).toBeInTheDocument();
    expect(screen.getByText("No progress for 18 days")).toBeInTheDocument();
    expect(screen.getByText("Expires in 9 days · Ania can extend it")).toBeInTheDocument();
    expect(screen.getByText("Showing the 12 highest-priority items.")).toBeInTheDocument();
    expect(screen.getAllByText("Last activity 2 hours ago")).toHaveLength(4);
    expect(screen.getByRole("link", { name: "Raise an ADR" })).toHaveAttribute("href", "/adrs/new");
  });

  test("activity links to the affected area and describes the actor, event and server-relative time", async () => {
    serve();
    renderWithProviders(<MyWork />);
    const activity = (await screen.findByRole("heading", { name: "Recent activity" })).closest("section")!;
    expect(within(activity).getByRole("link", { name: /Tomasz.*Question asked.*Define cache invalidation rules/ })).toHaveAttribute("href", "/adrs/7#discussion");
    expect(within(activity).getByText(/ADR-0007.*1 day ago/)).toBeInTheDocument();
    expect(within(activity).getByRole("link", { name: /Marta.*Edited/ })).toHaveAttribute("href", "/adrs/8#validity");
    expect(within(activity).getByText(/ADR-0008.*2 hours ago/)).toBeInTheDocument();
    expect(within(activity).getByText("Expiry date: 2026-12-01 → 2027-01-31")).toBeInTheDocument();
  });

  test("attention and activity use the server snapshot for boundary wording and deep links", async () => {
    const baseAdr = adr(20, "APPROVER", "IN_REVIEW");
    serve({
      ...WORK,
      actions: {
        hasMore: false,
        items: [
          { adr: baseAdr, reason: "STALLED_REVIEW", since: GENERATED_AT - 7 * 86_400_000, approverName: "Alex" },
          { adr: { ...baseAdr, id: 21, expiresOn: "2026-11-22" }, reason: "EXPIRING", since: GENERATED_AT, approverName: "Alex" },
          { adr: { ...baseAdr, id: 22, expiresOn: "2026-11-20" }, reason: "EXPIRING", since: GENERATED_AT, approverName: "Alex" },
        ],
      },
      activity: {
        hasMore: true,
        items: [
          { adr: baseAdr, event: { id: 30, actorId: 4, actorName: "Ola", actorDeleted: false, type: "COMMENTED", params: {}, createdAt: GENERATED_AT - 30 * 60_000 } },
          { adr: adr(21, "APPROVER", "EXPIRED"), event: { id: 31, actorId: null, actorName: null, actorDeleted: false, type: "EXPIRED", params: { expiresOn: "2026-11-21" }, createdAt: GENERATED_AT } },
        ],
      },
    });
    renderWithProviders(<MyWork />);
    expect(await screen.findByText("Waiting for a decision for 7 days")).toBeInTheDocument();
    expect(screen.getByText("Expires today")).toBeInTheDocument();
    expect(screen.getByText("Expiry is 2 days overdue")).toBeInTheDocument();
    expect(screen.getByText(/ADR-0020.*30 minutes ago/)).toBeInTheDocument();
    expect(screen.getByText(/ADR-0021.*Just now/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /System.*Expired/ })).toHaveAttribute("href", "/adrs/21#validity");
    expect(screen.getByText("Showing the 12 latest events.")).toBeInTheDocument();
  });

  test("the URL-backed role filter requests and links the selected role", async () => {
    const user = userEvent.setup();
    const stub = serve({ ...WORK, actions: { items: [], hasMore: false }, watching: { items: [], hasMore: false }, activity: { items: [], hasMore: false }, adrs: { items: [], hasMore: false } });
    renderWithProviders(<MyWork />, { route: "/my-work?participantRole=DRIVER" });
    expect(await screen.findByRole("combobox", { name: "My role" })).toHaveValue("Driver");
    await screen.findByText("Nothing needs your action.");
    expect(stub.find("GET", "/api/v1/adrs/my-work")?.url).toBe("/api/v1/adrs/my-work?participantRole=DRIVER");
    expect(screen.getByRole("link", { name: "View all my ADRs" })).toHaveAttribute("href", "/adrs?mine=1&participantRole=DRIVER");
    await user.click(screen.getByRole("combobox", { name: "My role" }));
    await user.click(screen.getByRole("option", { name: "Contributor" }));
    await waitFor(() => expect(stub.find("GET", "/api/v1/adrs/my-work?participantRole=CONTRIBUTOR")).toBeDefined());
  });

  test("empty sections explain that there is currently nothing to do or review", async () => {
    serve({ ...WORK, actions: { items: [], hasMore: false }, watching: { items: [], hasMore: false }, activity: { items: [], hasMore: false }, adrs: { items: [], hasMore: false } });
    renderWithProviders(<MyWork />);
    expect(await screen.findByText("Nothing needs your action.")).toBeInTheDocument();
    expect(screen.getByText("Nothing needs your attention right now.")).toBeInTheDocument();
    expect(screen.getByText("No recent activity in your ADRs.")).toBeInTheDocument();
    expect(screen.getByText("You have no open ADRs in this role.")).toBeInTheDocument();
  });

  test("a failed load shows one inline error and can be retried without leaving the workspace", async () => {
    const user = userEvent.setup();
    let attempts = 0;
    stubFetch(() => ++attempts === 1 ? problem(500) : jsonResponse(200, WORK));
    renderWithProviders(<MyWork />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Your work could not be loaded.");
    expect(screen.queryByRole("heading", { name: "Needs your action" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByRole("heading", { name: "Needs your action" })).toBeInTheDocument();
  });
});
