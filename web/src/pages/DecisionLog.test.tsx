import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import type { AdrOverview } from "../api/adrs";
import { overviewFixture } from "../test/adrFixtures";
import { problem, stubFetch } from "../test/fetchStub";
import { headingOutline } from "../test/headings";
import { jsonResponse } from "../test/http";
import { renderWithProviders, screen, within } from "../test/render";
import DecisionLog from "./DecisionLog";

const serve = (log: AdrOverview) => stubFetch(() => jsonResponse(200, log));

const EMPTY: AdrOverview = overviewFixture({
  counts: overviewFixture().counts.map((c) => ({ ...c, count: 0 })),
  inForce: { total: 0, categories: [], hiddenTags: 0, untagged: { total: 0, items: [], more: 0 } },
  recentlyDecided: { total: 0, items: [], more: 0 },
  expiringSoon: { days: 30, total: 0, items: [], more: 0 },
  awaitingDecision: { total: 0, items: [], more: 0, mineTotal: 0 },
});

describe("DecisionLog", () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["Date"] });
    // The browser clock is deliberately NOT the server's day: "today" must come from the response's generatedAt (2026-11-21).
    vi.setSystemTime(new Date("2030-01-01T12:00:00Z"));
    localStorage.setItem("hammurabi.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("reads the log once and lays it out as sections in a proper heading outline", async () => {
    const stub = serve(overviewFixture());
    renderWithProviders(<DecisionLog />);
    await screen.findByRole("heading", { name: "In force by tag" });
    expect(stub.count("GET", "/api/v1/adrs/overview")).toBe(1);
    expect(headingOutline()).toEqual([
      [2, "Decision log"],
      [3, "ADRs by status"],
      [3, "Awaiting decision"],
      [3, "Expiring soon"],
      [3, "Recently decided"],
      [3, "In force by tag"],
      [4, "Domain"],
      [5, "data"],
      [4, "Untagged"],
    ]);
  });

  test("the status strip shows every status with its count and links to the list in that status", async () => {
    serve(overviewFixture());
    renderWithProviders(<DecisionLog />);
    const strip = (await screen.findByRole("heading", { name: "ADRs by status" })).closest("section")!;
    const links = within(strip).getAllByRole("link");
    expect(links).toHaveLength(8);
    expect(within(strip).getByRole("link", { name: "Active 2" })).toHaveAttribute("href", "/adrs?status=ACTIVE");
    expect(within(strip).getByRole("link", { name: "In review 1" })).toHaveAttribute("href", "/adrs?status=IN_REVIEW");
    expect(within(strip).getByRole("link", { name: "Superseded 0" })).toBeInTheDocument();
  });

  test("an ADR in review that waits for the caller is badged, counted and linked to their review queue", async () => {
    serve(overviewFixture());
    renderWithProviders(<DecisionLog />);
    const card = (await screen.findByRole("heading", { name: "Awaiting decision" })).closest("section")!;
    const row = within(card).getByRole("link", { name: /ADR-0005\s*Adopt Kafka/ });
    expect(row).toHaveAttribute("href", "/adrs/5");
    const item = row.closest("li")!;
    expect(within(item).getByText("Needs your decision")).toBeInTheDocument();
    expect(within(item).getByText("in review since 2026-11-01")).toBeInTheDocument();
    expect(within(item).getByText("Approver: Alex Approver")).toBeInTheDocument();
    expect(within(card).getByRole("link", { name: "1 ADR is waiting for your decision" })).toHaveAttribute(
      "href",
      "/adrs?status=IN_REVIEW&mine=1&role=APPROVER",
    );
  });

  test("somebody else's review is plain, and an empty seat says so", async () => {
    const base = overviewFixture();
    const mine = base.awaitingDecision.items[0];
    serve(
      overviewFixture({
        awaitingDecision: { total: 2, items: [{ ...mine, mine: false, approver: null }], more: 1, mineTotal: 0 },
      }),
    );
    renderWithProviders(<DecisionLog />);
    const card = (await screen.findByRole("heading", { name: "Awaiting decision" })).closest("section")!;
    expect(within(card).queryByText("Needs your decision")).not.toBeInTheDocument();
    expect(within(card).queryByRole("link", { name: /waiting for your decision/ })).not.toBeInTheDocument();
    expect(within(card).getByText("no Approver")).toBeInTheDocument();
    expect(within(card).getByRole("link", { name: /^\+1 more\s*: All ADRs in review$/ })).toHaveAttribute("href", "/adrs?status=IN_REVIEW");
    expect(within(card).getByText("+1 more")).toBeInTheDocument();
  });

  test("expiring soon shows the last day, marks an overdue one and links the rest to the list bounded by the horizon", async () => {
    const base = overviewFixture();
    const key = base.expiringSoon.items[0];
    serve(
      overviewFixture({
        expiringSoon: { days: 30, total: 5, items: [{ ...key, id: 13, number: 13, key: "ADR-0013", name: "Renew the cert", expiresOn: "2026-11-20" }, key], more: 3 },
      }),
    );
    renderWithProviders(<DecisionLog />);
    const card = (await screen.findByRole("heading", { name: "Expiring soon" })).closest("section")!;
    expect(within(card).getByText("ADRs in force whose last day is within 30 days, the soonest first.")).toBeInTheDocument();
    const overdue = within(card).getByRole("link", { name: /Renew the cert/ }).closest("li")!;
    expect(within(overdue).getByText("Overdue")).toBeInTheDocument();
    expect(within(overdue).getByText("last day 2026-11-20")).toBeInTheDocument();
    const soon = within(card).getByRole("link", { name: /Rotate the keys/ }).closest("li")!;
    expect(within(soon).queryByText("Overdue")).not.toBeInTheDocument();
    expect(within(soon).getByText("last day 2026-11-25")).toBeInTheDocument();
    // 2026-11-21 + 30 days + 1: the list's expiresBefore is strict.
    expect(within(card).getByRole("link", { name: /^\+3 more\s*: All expiring ADRs$/ })).toHaveAttribute("href", "/adrs?status=ACTIVE&expiresBefore=2026-12-22");
  });

  test("recently decided dates each ADR by its activation and leads to the active ADRs", async () => {
    serve(overviewFixture());
    renderWithProviders(<DecisionLog />);
    const card = (await screen.findByRole("heading", { name: "Recently decided" })).closest("section")!;
    const row = within(card).getByRole("link", { name: /ADR-0007\s*Use PostgreSQL/ }).closest("li")!;
    expect(within(row).getByText("decided 2026-11-03")).toBeInTheDocument();
    expect(within(card).getByRole("link", { name: /^\+1 more\s*: All active ADRs$/ })).toHaveAttribute("href", "/adrs?status=ACTIVE");
  });

  test("in force groups by category and tag, each tag linking its remaining ADRs, then the untagged", async () => {
    serve(overviewFixture());
    renderWithProviders(<DecisionLog />);
    const card = (await screen.findByRole("heading", { name: "In force by tag" })).closest("section")!;
    expect(within(card).getByText("2 decisions in force.")).toBeInTheDocument();
    const tagList = within(card).getByRole("list", { name: "Domain: data" });
    expect(within(tagList).getByRole("link", { name: /Use PostgreSQL/ })).toHaveAttribute("href", "/adrs/7");
    expect(within(card).getByRole("link", { name: /^\+2 more\s*: ADRs tagged data$/ })).toHaveAttribute("href", "/adrs?status=ACTIVE&tag=data");
    const untagged = within(card).getByRole("list", { name: "Untagged" });
    expect(within(untagged).getByRole("link", { name: /Pick a queue/ })).toHaveAttribute("href", "/adrs/9");
    // No untagged overflow: only the closing link to all the active ADRs.
    expect(within(card).getAllByRole("link", { name: "All active ADRs" })).toHaveLength(1);
  });

  test("tags beyond the server's cap are counted in a sentence, and an untagged overflow links the active list", async () => {
    const base = overviewFixture();
    serve(
      overviewFixture({
        inForce: { ...base.inForce, hiddenTags: 3, untagged: { total: 25, items: base.inForce.untagged.items, more: 24 } },
      }),
    );
    renderWithProviders(<DecisionLog />);
    const card = (await screen.findByRole("heading", { name: "In force by tag" })).closest("section")!;
    expect(within(card).getByText("3 more tags have active ADRs but are not shown.")).toBeInTheDocument();
    // The untagged group has no list of its own: its link says where it really goes, and replaces the closing link.
    const all = within(card).getByRole("link", { name: "All active ADRs" });
    expect(all).toHaveAttribute("href", "/adrs?status=ACTIVE");
    expect(within(card).getAllByRole("link", { name: "All active ADRs" })).toHaveLength(1);
  });

  test("an empty log says so in every section and shows no list", async () => {
    serve(EMPTY);
    renderWithProviders(<DecisionLog />);
    expect(await screen.findByText("Nothing is waiting for a decision.")).toBeInTheDocument();
    expect(screen.getByText("No decision in force expires soon.")).toBeInTheDocument();
    expect(screen.getAllByText("No decision is in force yet.")).toHaveLength(2);
    expect(screen.getByText("0 decisions in force.")).toBeInTheDocument();
    expect(screen.queryAllByRole("list").filter((list) => list.tagName === "UL" && list.getAttribute("aria-label"))).toHaveLength(0);
    expect(headingOutline()).toEqual([
      [2, "Decision log"],
      [3, "ADRs by status"],
      [3, "Awaiting decision"],
      [3, "Expiring soon"],
      [3, "Recently decided"],
      [3, "In force by tag"],
    ]);
  });

  test("every \"+N more\" link's accessible name contains its visible text (WCAG 2.5.3)", async () => {
    serve(overviewFixture());
    renderWithProviders(<DecisionLog />);
    await screen.findByRole("heading", { name: "In force by tag" });
    const more = screen.getAllByRole("link").filter((link) => (link.textContent ?? "").startsWith("+"));
    expect(more.length).toBeGreaterThanOrEqual(2);
    for (const link of more) {
      expect(link).not.toHaveAttribute("aria-label");
      const visible = (link.textContent ?? "").split(":")[0];
      expect(screen.getAllByRole("link", { name: new RegExp(`^${visible.replace("+", "\\+")}`) })).toContain(link);
    }
  });

  test("a failed load is an inline alert, not a half page", async () => {
    stubFetch(() => problem(500));
    renderWithProviders(<DecisionLog />);
    expect(await screen.findByRole("alert")).toHaveTextContent("The decision log could not be loaded.");
    expect(screen.queryByRole("heading", { name: "In force by tag" })).not.toBeInTheDocument();
  });
});
