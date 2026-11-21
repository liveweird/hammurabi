import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { useLocation } from "react-router-dom";
import { act, screen, waitFor, within } from "@testing-library/react";
import { QueryClient } from "@tanstack/react-query";
import NotificationsButton from "./NotificationsButton";
import type { NotificationItem } from "../api/notifications";
import { clearSession } from "../api/session";
import i18n from "../i18n";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import { usePinnedBrowserTimeZone } from "../test/timeZone";
import { formatDateTime } from "../utils/formatDate";
import { bindSessionQueryCache } from "../utils/sessionQueryCache";

const AT = 1_795_000_000_000;

const notice = (over: Partial<NotificationItem> = {}): NotificationItem => ({
  id: 77,
  type: "STATUS_CHANGED",
  seen: false,
  createdAt: AT,
  actorId: 7,
  actorName: "Ada",
  adrId: 42,
  adrKey: "ADR-0042",
  adrName: "Use Postgres",
  otherAdrId: null,
  otherAdrKey: null,
  detail: "ACTIVE",
  ...over,
});
const seenNotice = notice({ id: 76, seen: true, type: "ROLE_ASSIGNED", detail: "DRIVER", actorName: "Bob", adrId: 5, adrKey: "ADR-0005", adrName: "Runbook" });
const page = (items: NotificationItem[], total = items.length, pageNo = 1) => ({ items, page: pageNo, pageSize: 20, total });

function Where() {
  const { pathname, search } = useLocation();
  return <output aria-label="where">{pathname + search}</output>;
}

let unseenPage = page([], 1);
let listPage = page([notice(), seenNotice]);
let listStatus = 200;
let markAllStatus = 204;
let seenStatus = 204;
let unseenStatus = 200;
/** Overrides the list's answer for a page (the default serves `listPage`, and a made-up second page for any page past 1). */
let serveListPage: ((pageNo: number) => unknown) | null = null;
const calls: string[] = [];

const noContent = (status: number) => (status === 204 ? new Response(null, { status }) : jsonResponse(status, { title: "Boom", status }));

function api(url: string, init?: RequestInit): Promise<Response> {
  const method = init?.method ?? "GET";
  calls.push(`${method} ${url}`);
  if (method === "POST" && url === "/api/v1/notifications/seen-all") return Promise.resolve(noContent(markAllStatus));
  if (method === "POST" && /^\/api\/v1\/notifications\/\d+\/seen$/.test(url)) return Promise.resolve(noContent(seenStatus));
  if (url.startsWith("/api/v1/notifications?") && url.includes("seen=false")) return Promise.resolve(unseenStatus === 200 ? jsonResponse(200, unseenPage) : noContent(unseenStatus));
  if (url.startsWith("/api/v1/notifications?")) {
    if (listStatus !== 200) return Promise.resolve(noContent(listStatus));
    const pageNo = Number(new URL(url, "http://x").searchParams.get("page"));
    if (serveListPage) return Promise.resolve(jsonResponse(200, serveListPage(pageNo)));
    return Promise.resolve(jsonResponse(200, pageNo > 1 ? { ...listPage, page: pageNo, items: [notice({ id: 10, adrName: "Second page" })] } : listPage));
  }
  return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
}

function renderBell(route = "/teams", queryClient?: QueryClient) {
  return renderWithProviders(
    <>
      <NotificationsButton />
      <Where />
    </>,
    { route, queryClient },
  );
}

const bell = () => screen.findByRole("button", { name: /^Notifications/ });
const unseenReads = () => calls.filter((call) => call.includes("seen=false")).length;

describe("NotificationsButton", () => {
  usePinnedBrowserTimeZone("America/Los_Angeles");

  beforeEach(() => {
    unseenPage = page([], 1);
    listPage = page([notice(), seenNotice]);
    listStatus = 200;
    markAllStatus = 204;
    seenStatus = 204;
    unseenStatus = 200;
    serveListPage = null;
    calls.length = 0;
    localStorage.setItem("hammurabi.auth.token", "fake-token");
    vi.stubGlobal("fetch", vi.fn(api));
  });
  afterEach(async () => {
    vi.unstubAllGlobals();
    localStorage.clear();
    await i18n.changeLanguage("en");
  });

  test("the button is labelled with the unseen total, which is also the badge", async () => {
    unseenPage = page([], 3);
    renderBell();
    expect(await screen.findByRole("button", { name: "Notifications (3 unseen)" })).toBeInTheDocument();
    expect(screen.getByText("3")).toBeInTheDocument();
    // It asks for the unseen count only: one row of the unseen ones.
    expect(calls).toContain("GET /api/v1/notifications?page=1&pageSize=1&seen=false");
  });

  test("the unread cues are the brand accent, never the blocking red", async () => {
    unseenPage = page([], 3);
    listPage = page([notice()], 1);
    const user = userEvent.setup();
    const { baseElement } = renderBell();
    await user.click(await bell());
    await screen.findByRole("link", { name: /Ada changed the status of ADR-0042/ });
    const dot = baseElement.querySelector("[data-unseen-dot]");
    expect(dot?.getAttribute("style")).toContain("hammurabi");
    const count = baseElement.querySelector(".mantine-Indicator-root");
    expect(count?.getAttribute("style")).toContain("hammurabi");
    const inlineStyles = Array.from(baseElement.querySelectorAll("[style]"), (el) => el.getAttribute("style")).join(" ");
    expect(inlineStyles).not.toMatch(/--mantine-color-red|\bred\b/);
  });

  test("at the server's cap the badge reads 999+ and the label says so", async () => {
    unseenPage = page([], 1000);
    renderBell();
    expect(await screen.findByRole("button", { name: "Notifications (999+ unseen)" })).toBeInTheDocument();
    expect(screen.getByText("999+")).toBeInTheDocument();
  });

  test("999 is shown as it is", async () => {
    unseenPage = page([], 999);
    renderBell();
    expect(await screen.findByText("999")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Notifications (999 unseen)" })).toBeInTheDocument();
  });

  test("with nothing unseen there is no badge, and the label says 0", async () => {
    unseenPage = page([], 0);
    renderBell();
    expect(await screen.findByRole("button", { name: "Notifications (0 unseen)" })).toBeInTheDocument();
    expect(screen.queryByText("0")).not.toBeInTheDocument();
  });

  test("the unseen read waits for a signed-in session", async () => {
    localStorage.clear();
    renderBell();
    expect(await screen.findByRole("button", { name: /^Notifications/ })).toBeInTheDocument();
    expect(calls).toEqual([]);
  });

  test("the badge text is hidden from assistive technology, and the bell says whether its drawer is open", async () => {
    unseenPage = page([], 3);
    const user = userEvent.setup();
    renderBell();
    const button = await screen.findByRole("button", { name: "Notifications (3 unseen)" });
    expect(screen.getByText("3").closest("[aria-hidden='true']")).not.toBeNull();
    expect(button).toHaveAttribute("aria-expanded", "false");
    await user.click(button);
    await screen.findByRole("dialog", { name: "Notifications" });
    expect(button).toHaveAttribute("aria-expanded", "true");
  });

  test("the drawer lists who, what, the ADR and the time, as text", async () => {
    listPage = page([notice({ actorName: "<b>Ada</b>", adrName: "**Use Postgres**" }), seenNotice]);
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    const drawer = await screen.findByRole("dialog", { name: "Notifications" });
    const list = await within(drawer).findByRole("list", { name: "Your notifications" });
    const status = within(list).getByRole("link", { name: /<b>Ada<\/b> changed the status of ADR-0042 to Active/ });
    expect(status).toHaveTextContent(`ADR-0042 · **Use Postgres** · ${formatDateTime(AT, "America/Los_Angeles")}`);
    expect(status).toHaveAttribute("href", "/adrs/42");
    expect(within(status).getByText("Unseen")).toBeInTheDocument();
    // The unseen row carries the dot; the seen one has neither the dot nor the word.
    expect(status.querySelectorAll("[data-unseen-dot]")).toHaveLength(1);
    const role = within(list).getByRole("link", { name: /Bob gave you a role on ADR-0005: Driver/ });
    expect(role).toHaveAttribute("href", "/adrs/5");
    expect(role.querySelector("[data-unseen-dot]")).toBeNull();
    expect(within(role).queryByText("Unseen")).not.toBeInTheDocument();
    expect(role).toHaveTextContent("ADR-0005 · Runbook ·");
    expect(drawer.querySelector("b")).toBeNull();
  });

  test("every notice type reads as a sentence, a system notice names the system", async () => {
    listPage = page([
      notice({ id: 1, type: "ROLE_REMOVED", detail: "CONTRIBUTOR" }),
      notice({ id: 2, type: "DISCUSSION_POSTED", detail: "QUESTION" }),
      notice({ id: 3, type: "DISCUSSION_SETTLED", detail: "QUESTION" }),
      notice({ id: 4, type: "DISCUSSION_SETTLED", detail: "ISSUE" }),
      notice({ id: 5, type: "ADR_EXPIRED", detail: "2026-11-30", actorId: null, actorName: null }),
      notice({ id: 6, type: "ADR_SUPERSEDED", detail: null, otherAdrId: 43, otherAdrKey: "ADR-0043" }),
      notice({ id: 7, type: "SUCCESSOR_LINKED", detail: null, otherAdrId: 43, otherAdrKey: "ADR-0043" }),
      notice({ id: 8, type: "SUCCESSOR_UNLINKED", detail: null, otherAdrId: 43, otherAdrKey: "ADR-0043" }),
      notice({ id: 9, type: "EXPIRY_REMINDER", detail: "2026-12-01", actorId: null, actorName: null }),
      notice({ id: 10, type: "ROLE_ASSIGNED", detail: "APPROVER", actorId: null, actorName: null }),
    ]);
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    const drawer = await screen.findByRole("dialog", { name: "Notifications" });
    for (const sentence of [
      "Ada took your role on ADR-0042 away: Contributor",
      "Ada posted on ADR-0042: Question",
      "Ada marked your question on ADR-0042 as answered",
      "Ada resolved your entry on ADR-0042: Issue",
      "ADR-0042 expired: its expiry date 2026-11-30 has passed",
      "ADR-0042 was superseded by ADR-0043",
      "Ada proposed ADR-0043 to replace ADR-0042",
      "Ada detached ADR-0043: it no longer replaces ADR-0042",
      "ADR-0042 expires on 2026-12-01",
      "The system gave you a role on ADR-0042: Approver",
    ]) {
      expect(await within(drawer).findByText(sentence)).toBeInTheDocument();
    }
  });

  test("a notice of a type this build does not know shows its raw name and still links to its ADR", async () => {
    listPage = page([{ ...notice(), type: "FROM_THE_FUTURE" as NotificationItem["type"] }]);
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    expect(await screen.findByRole("link", { name: /FROM_THE_FUTURE/ })).toHaveAttribute("href", "/adrs/42");
  });

  test("a click marks the notice seen, opens the ADR, closes the drawer and re-reads", async () => {
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    await user.click(await screen.findByRole("link", { name: /Ada changed the status of ADR-0042/ }));
    expect(screen.getByLabelText("where")).toHaveTextContent(/^\/adrs\/42$/);
    await waitFor(() => expect(calls).toContain("POST /api/v1/notifications/77/seen"));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await waitFor(() => expect(unseenReads()).toBeGreaterThan(1));
  });

  test("a seen notice is not marked again", async () => {
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    await user.click(await screen.findByRole("link", { name: /Bob gave you a role/ }));
    expect(screen.getByLabelText("where")).toHaveTextContent(/^\/adrs\/5$/);
    expect(calls.some((call) => call.startsWith("POST"))).toBe(false);
  });

  test("a Ctrl/Cmd-click marks the notice seen but keeps the drawer and the page", async () => {
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    const link = await screen.findByRole("link", { name: /Ada changed the status of ADR-0042/ });
    await user.keyboard("{Control>}");
    await user.click(link);
    await user.keyboard("{/Control}");
    await waitFor(() => expect(calls).toContain("POST /api/v1/notifications/77/seen"));
    expect(screen.getByRole("dialog", { name: "Notifications" })).toBeInTheDocument();
    expect(screen.getByLabelText("where")).toHaveTextContent(/^\/teams$/);
  });

  test.each([404, 500])("a failed seen write (%i) still re-reads the badge and the list", async (status) => {
    seenStatus = status;
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    await user.click(await screen.findByRole("link", { name: /Ada changed the status of ADR-0042/ }));
    await waitFor(() => expect(calls).toContain("POST /api/v1/notifications/77/seen"));
    await waitFor(() => expect(unseenReads()).toBeGreaterThan(1));
  });

  test("a notice that leads where the person already is gives the focus back to the bell", async () => {
    const user = userEvent.setup();
    renderBell("/adrs/42");
    const button = await bell();
    await user.click(button);
    await user.click(await screen.findByRole("link", { name: /Ada changed the status of ADR-0042/ }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await waitFor(() => expect(button).toHaveFocus());
  });

  test("a notice that leads elsewhere does not pull the focus back to the bell", async () => {
    const user = userEvent.setup();
    renderBell();
    const button = await bell();
    await user.click(button);
    await user.click(await screen.findByRole("link", { name: /Ada changed the status of ADR-0042/ }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(button).not.toHaveFocus();
  });

  test("Mark all as seen sends the one write and re-reads", async () => {
    unseenPage = page([], 2);
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    await user.click(await screen.findByRole("button", { name: "Mark all as seen" }));
    await waitFor(() => expect(calls).toContain("POST /api/v1/notifications/seen-all"));
    await waitFor(() => expect(unseenReads()).toBeGreaterThan(1));
  });

  test("a failed Mark all as seen says so, inline", async () => {
    markAllStatus = 500;
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    await user.click(await screen.findByRole("button", { name: "Mark all as seen" }));
    expect(await screen.findByText("Could not mark the notifications as seen.")).toBeInTheDocument();
  });

  test("with nothing unseen there is no Mark all button", async () => {
    unseenPage = page([], 0);
    listPage = page([seenNotice]);
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    await screen.findByRole("link", { name: /Bob gave you a role/ });
    expect(screen.queryByRole("button", { name: "Mark all as seen" })).not.toBeInTheDocument();
  });

  test("Mark all as seen shows while the list holds an unseen row, even if the badge read failed", async () => {
    unseenStatus = 500;
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    await screen.findByRole("link", { name: /Ada changed the status/ });
    expect(await screen.findByRole("button", { name: "Mark all as seen" })).toBeInTheDocument();
  });

  test("an empty list says so", async () => {
    unseenPage = page([], 0);
    listPage = page([]);
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    expect(await screen.findByText("You have no notifications.")).toBeInTheDocument();
  });

  test("a failed read says so, with the status words, and no raw error", async () => {
    listStatus = 500;
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Could not load the notifications.");
    expect(alert).not.toHaveTextContent("Boom");
  });

  test("past 20 notices the drawer pages, 20 at a time", async () => {
    listPage = page([notice()], 45);
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    await screen.findByRole("link", { name: /Ada changed the status of ADR-0042/ });
    expect(calls).toContain("GET /api/v1/notifications?page=1&pageSize=20");
    await user.click(screen.getByRole("button", { name: "3" }));
    expect(await screen.findByRole("link", { name: /Second page/ })).toBeInTheDocument();
    expect(calls).toContain("GET /api/v1/notifications?page=3&pageSize=20");
  });

  test("the pager's icon-only previous and next buttons have names", async () => {
    listPage = page([notice()], 45);
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    await screen.findByRole("link", { name: /Ada changed the status of ADR-0042/ });
    expect(screen.getByRole("button", { name: "Previous page" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Next page" }));
    expect(await screen.findByRole("link", { name: /Second page/ })).toBeInTheDocument();
    expect(calls).toContain("GET /api/v1/notifications?page=2&pageSize=20");
  });

  test("a small list has no pager", async () => {
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    await screen.findByRole("link", { name: /Ada changed the status/ });
    expect(screen.queryByRole("button", { name: "2" })).not.toBeInTheDocument();
  });

  test("a page that a re-read empties steps back to the last page that exists, not just one", async () => {
    let total = 100;
    serveListPage = (pageNo) => page(total === 100 || pageNo < 3 ? [notice({ id: pageNo, adrName: `Row ${pageNo}` })] : [], total, pageNo);
    unseenPage = page([], 2);
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    await screen.findByRole("link", { name: /Row 1/ });
    await user.click(screen.getByRole("button", { name: "5" }));
    await screen.findByRole("link", { name: /Row 5/ });
    // Three pages' worth of notices go; the page the person is on is now past the end (25 notices = 2 pages).
    total = 25;
    await user.click(screen.getByRole("button", { name: "Mark all as seen" }));
    expect(await screen.findByRole("link", { name: /Row 2/ })).toBeInTheDocument();
    expect(calls.some((call) => call.includes("page=4&"))).toBe(false);
    expect(screen.getByRole("button", { name: "2", current: "page" })).toBeInTheDocument();
  });

  test("the drawer's close button has a name, and closing returns focus to the bell", async () => {
    const user = userEvent.setup();
    renderBell();
    const button = await bell();
    await user.click(button);
    const drawer = await screen.findByRole("dialog", { name: "Notifications" });
    await user.click(within(drawer).getByRole("button", { name: "Close" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await waitFor(() => expect(button).toHaveFocus());
  });

  test("the drawer is reachable from the keyboard", async () => {
    const user = userEvent.setup();
    renderBell();
    (await bell()).focus();
    await user.keyboard("{Enter}");
    expect(await screen.findByRole("dialog", { name: "Notifications" })).toBeInTheDocument();
  });

  test("signing out while mounted clears the badge, closes the drawer and reads nothing more", async () => {
    unseenPage = page([], 3);
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const unbind = bindSessionQueryCache(queryClient);
    const user = userEvent.setup();
    renderBell("/teams", queryClient);
    await user.click(await screen.findByRole("button", { name: "Notifications (3 unseen)" }));
    await screen.findByRole("link", { name: /Ada changed the status/ });
    act(() => clearSession());
    expect(await screen.findByRole("button", { name: "Notifications (0 unseen)" })).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(screen.queryByText("3")).not.toBeInTheDocument();
    const reads = calls.length;
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(calls).toHaveLength(reads);
    unbind();
  });

  test.each([
    [1, "Powiadomienia (1 nieprzeczytane)"],
    [2, "Powiadomienia (2 nieprzeczytane)"],
    [5, "Powiadomienia (5 nieprzeczytanych)"],
    [1000, "Powiadomienia (ponad 999 nieprzeczytanych)"],
  ])("in Polish %i unseen reads with the right plural form", async (count, name) => {
    await i18n.changeLanguage("pl");
    unseenPage = page([], count);
    renderBell();
    expect(await screen.findByRole("button", { name })).toBeInTheDocument();
  });
});
