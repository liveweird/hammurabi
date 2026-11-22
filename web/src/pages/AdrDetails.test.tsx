import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation, useNavigate } from "react-router-dom";
import { QueryClient } from "@tanstack/react-query";
import type { AdrResponse } from "../api/adrs";
import { adrFixture, discussionPageFixture, entryFixture, eventFixture } from "../test/adrFixtures";
import { noContent, problem, stubFetch } from "../test/fetchStub";
import { jsonResponse } from "../test/http";
import { headingOutline } from "../test/headings";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import AdrDetails from "./AdrDetails";

// The visual editor (Lexical) is not what these tests are about: a plain textarea stands in (MarkdownRichEditor.test.tsx runs the real one).
vi.mock("../components/MarkdownRichEditor", () => import("../test/markdownRichEditorStub"));

const saveFile = vi.fn();
vi.mock("../utils/saveFile", () => ({ saveFile: (download: unknown) => saveFile(download) }));
const originalScrollIntoView = HTMLElement.prototype.scrollIntoView;

const PEOPLE = [
  { userId: 5, name: "Dana Driver", role: "DRIVER" as const },
  { userId: 6, name: "Alex Approver", role: "APPROVER" as const },
  { userId: 7, name: "Cora Contributor", role: "CONTRIBUTOR" as const },
];

function Probe() {
  const { pathname } = useLocation();
  return <p>at {pathname}</p>;
}

function ValidityNavigation() {
  const navigate = useNavigate();
  return (
    <>
      <button onClick={() => navigate("/adrs/1#validity")}>Open validity</button>
      <button onClick={() => navigate("/adrs/1#issues")}>Open issues</button>
      <button onClick={() => navigate("/adrs/1")}>Open ADR</button>
      <button onClick={() => navigate(-1)}>Back</button>
    </>
  );
}

function renderDetails(route = "/adrs/1") {
  return renderWithProviders(
    <Routes>
      <Route path="/adrs" element={<Probe />} />
      <Route path="/adrs/:id" element={<AdrDetails />} />
    </Routes>,
    { route },
  );
}

/** A server holding one ADR the tests mutate through the `state` box; the history serves one event. */
function serve(state: { adr: AdrResponse }, extra: (method: string, url: string) => Response | null = () => null) {
  return stubFetch((r) => {
    const custom = extra(r.method, r.url);
    if (custom) return custom;
    if (r.method === "GET" && r.url === "/api/v1/adrs/1") return jsonResponse(200, state.adr);
    if (r.method === "GET" && r.url.startsWith("/api/v1/adrs/1/events")) {
      return jsonResponse(200, { items: [eventFixture({ params: { key: "ADR-0001", name: "Use PostgreSQL" } })], page: 1, pageSize: 10, total: 1 });
    }
    return null;
  });
}

describe("AdrDetails page", () => {
  beforeEach(() => {
    localStorage.setItem("hammurabi.auth.token", "fake-token");
    localStorage.setItem("hammurabi.auth.userId", "5");
  });
  afterEach(() => {
    saveFile.mockReset();
    if (originalScrollIntoView) HTMLElement.prototype.scrollIntoView = originalScrollIntoView;
    else Reflect.deleteProperty(HTMLElement.prototype, "scrollIntoView");
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("the main pane and side panel share one heading outline", async () => {
    serve({ adr: adrFixture({ participants: PEOPLE, context: "Because." }) });
    renderDetails();
    expect(await screen.findByRole("heading", { level: 2, name: "Use PostgreSQL" })).toBeInTheDocument();
    expect(screen.getByText("ADR-0001", { selector: "p" })).toBeInTheDocument();
    await screen.findByText("ADR raised");
    expect(headingOutline()).toEqual([
      [2, "Use PostgreSQL"],
      [3, "Problem / question"],
      [3, "Context"],
      [3, "Decision"],
      [3, "Alternatives considered"],
      [3, "Consequences / side effects"],
      [3, "Status"],
      [3, "Validity"],
      [3, "DACI"],
      [3, "Tags"],
      [3, "Successor"],
      [3, "Details"],
      [3, "History"],
    ]);
    expect(screen.getByText("We need a database.")).toBeInTheDocument();
    expect(screen.getByText("Because.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to the ADRs" })).toHaveAttribute("href", "/adrs");
  });

  test("a validity deep link focuses and scrolls its section after the ADR loads, once for that navigation", async () => {
    const scrollIntoView = vi.fn();
    HTMLElement.prototype.scrollIntoView = scrollIntoView;
    const state = { adr: adrFixture({ participants: PEOPLE }) };
    const stub = serve(state);
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderWithProviders(
      <Routes>
        <Route path="/adrs/:id" element={<AdrDetails />} />
      </Routes>,
      { route: "/adrs/1#validity", queryClient },
    );
    await screen.findByRole("heading", { level: 2, name: "Use PostgreSQL" });
    const target = screen.getByRole("region", { name: "Validity" });
    await waitFor(() => expect(target).toHaveFocus());
    expect(scrollIntoView).toHaveBeenCalledTimes(1);
    const readsBeforeRefresh = stub.count("GET", "/api/v1/adrs/1");
    state.adr = { ...state.adr, revision: state.adr.revision + 1 };
    await queryClient.invalidateQueries({ queryKey: ["adrs", "detail", 1] });
    await waitFor(() => expect(stub.count("GET", "/api/v1/adrs/1")).toBeGreaterThan(readsBeforeRefresh));
    expect(scrollIntoView).toHaveBeenCalledTimes(1);
  });

  test("same-ADR navigation to the validity hash focuses and scrolls the section", async () => {
    const scrollIntoView = vi.fn();
    HTMLElement.prototype.scrollIntoView = scrollIntoView;
    serve({ adr: adrFixture({ participants: PEOPLE }) });
    const user = userEvent.setup();
    renderWithProviders(
      <>
        <ValidityNavigation />
        <Routes>
          <Route path="/adrs/:id" element={<AdrDetails />} />
        </Routes>
      </>,
      { route: "/adrs/1" },
    );
    await screen.findByRole("heading", { level: 2, name: "Use PostgreSQL" });
    const target = screen.getByRole("region", { name: "Validity" });
    expect(target).not.toHaveFocus();
    await user.click(screen.getByRole("button", { name: "Open validity" }));
    await waitFor(() => expect(target).toHaveFocus());
    expect(scrollIntoView).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole("button", { name: "Open ADR" }));
    await user.click(screen.getByRole("button", { name: "Back" }));
    await waitFor(() => expect(target).toHaveFocus());
    expect(scrollIntoView).toHaveBeenCalledTimes(2);
  });

  test.each([
    ["discussion", "Discussion", "Comments and questions"],
    ["issues", "Issues", "Issues and escalations"],
  ])("a %s deep link opens the tab and focuses its heading", async (hash, tab, heading) => {
    HTMLElement.prototype.scrollIntoView = vi.fn();
    serve({ adr: adrFixture({ participants: PEOPLE }) }, (method, url) =>
      method === "GET" && url.startsWith("/api/v1/adrs/1/discussion?")
        ? jsonResponse(200, discussionPageFixture())
        : null);
    renderDetails(`/adrs/1#${hash}`);
    const target = await screen.findByRole("heading", { name: heading });
    await waitFor(() => expect(target).toHaveFocus());
    expect(screen.getByRole("tab", { name: tab })).toHaveAttribute("aria-selected", "true");
  });

  test("a new discussion hash supersedes a manual tab selection on the same ADR", async () => {
    HTMLElement.prototype.scrollIntoView = vi.fn();
    serve({ adr: adrFixture({ participants: PEOPLE }) }, (method, url) =>
      method === "GET" && url.startsWith("/api/v1/adrs/1/discussion?")
        ? jsonResponse(200, discussionPageFixture())
        : null);
    const user = userEvent.setup();
    renderWithProviders(<><ValidityNavigation /><Routes><Route path="/adrs/:id" element={<AdrDetails />} /></Routes></>, { route: "/adrs/1" });
    await user.click(await screen.findByRole("tab", { name: "Lifecycle" }));
    await user.click(screen.getByRole("button", { name: "Open issues" }));
    await waitFor(() => expect(screen.getByRole("heading", { name: "Issues and escalations" })).toHaveFocus());
  });

  test("Export Markdown downloads this ADR's file - for a reader with no role on it too - under the server's name", async () => {
    // A stranger: no edit verdicts, no role - exporting is open to every signed-in user.
    const stub = serve(
      { adr: adrFixture({ participants: PEOPLE, allowedTransitions: [], canEditContent: false, canEditExpiry: false, canEditTags: false, canEditSuccessor: false }) },
      (method, url) =>
        method === "GET" && url === "/api/v1/adrs/1/export"
          ? new Response("# 1. Use PostgreSQL\n", { headers: { "Content-Disposition": 'attachment; filename="ADR-0001-use-postgresql.md"' } })
          : null,
    );
    renderDetails();
    await screen.findByRole("heading", { level: 2, name: "Use PostgreSQL" });
    await userEvent.click(screen.getByRole("button", { name: "Export Markdown" }));
    await waitFor(() => expect(saveFile).toHaveBeenCalledTimes(1));
    expect(stub.count("GET", "/api/v1/adrs/1/export")).toBe(1);
    const saved = saveFile.mock.calls[0]?.[0] as { blob: Blob; fileName: string };
    expect(saved.fileName).toBe("ADR-0001-use-postgresql.md");
    expect(await saved.blob.text()).toBe("# 1. Use PostgreSQL\n");
  });

  test("a failed export is said inline and saves nothing", async () => {
    serve({ adr: adrFixture({ participants: PEOPLE }) }, (method, url) => (method === "GET" && url === "/api/v1/adrs/1/export" ? problem(404) : null));
    renderDetails();
    await screen.findByRole("heading", { level: 2, name: "Use PostgreSQL" });
    await userEvent.click(screen.getByRole("button", { name: "Export Markdown" }));
    expect(await screen.findByText("This ADR no longer exists.")).toBeInTheDocument();
    expect(saveFile).not.toHaveBeenCalled();
  });

  test("a superseded ADR carries the banner naming its successor, and the side panel shows both ends of the link", async () => {
    serve({
      adr: adrFixture({
        status: "SUPERSEDED",
        allowedTransitions: [],
        canEditContent: false,
        canEditExpiry: false,
        canEditTags: false,
        canEditSuccessor: false,
        supersedes: { id: 3, key: "ADR-0003", name: "Use MySQL", status: "SUPERSEDED" },
        supersededBy: { id: 9, key: "ADR-0009", name: "Use CockroachDB", status: "ACTIVE" },
      }),
    });
    renderDetails();
    await screen.findByRole("heading", { level: 2, name: "Use PostgreSQL" });
    const banner = screen.getByText("This ADR is superseded").closest("[role=status]") as HTMLElement;
    expect(within(banner).getByRole("link", { name: "ADR-0009 Use CockroachDB" })).toHaveAttribute("href", "/adrs/9");
    const successor = screen.getByRole("region", { name: "Successor" });
    expect(within(successor).getByRole("link", { name: "ADR-0003 Use MySQL" })).toBeInTheDocument();
    expect(within(successor).getByText("Replaced by")).toBeInTheDocument();
  });

  test("the main pane is four tabs; the discussion opens on demand and shows the counts the ADR carries", async () => {
    const stub = serve(
      { adr: adrFixture({ participants: PEOPLE, status: "ACTIVE", allowedTransitions: [], canComment: false, canRaiseIssue: true, openIssueCount: 2, unansweredQuestionCount: 1 }) },
      (method, url) => (method === "GET" && url.startsWith("/api/v1/adrs/1/discussion") ? jsonResponse(200, discussionPageFixture([entryFixture({ kind: "ISSUE", body: "It leaks" })])) : null),
    );
    const user = userEvent.setup();
    renderDetails();
    await screen.findByRole("heading", { level: 2, name: "Use PostgreSQL" });
    expect(screen.getByRole("tab", { name: "Document", selected: true })).toBeInTheDocument();
    expect(stub.count("GET", "/api/v1/adrs/1/discussion")).toBe(0);
    expect(screen.getByRole("tab", { name: "Discussion unanswered: 1" })).toBeInTheDocument();
    await user.click(screen.getByRole("tab", { name: "Issues open: 2" }));
    expect(await screen.findByText("It leaks")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 3, name: "Issues and escalations" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { level: 3, name: "Problem / question" })).not.toBeInTheDocument();
  });

  test("the Lifecycle tab shows the state diagram with where this ADR is and the moves the caller may make now", async () => {
    serve({ adr: adrFixture({ participants: PEOPLE, status: "SOLUTION_DRAFT", allowedTransitions: ["IN_REVIEW", "CANCELLED"] }) });
    const user = userEvent.setup();
    renderDetails();
    await screen.findByRole("heading", { level: 2, name: "Use PostgreSQL" });
    expect(screen.queryByRole("img", { name: /Diagram of the ADR lifecycle/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole("tab", { name: "Lifecycle" }));
    expect(screen.getByRole("img", { name: /This ADR is: Solution draft\./ })).toBeInTheDocument();
    expect(document.querySelector('g[data-node="SOLUTION_DRAFT"]')).toHaveAttribute("data-current", "true");
    expect(Array.from(document.querySelectorAll("g[data-emphasised]")).map((g) => g.getAttribute("data-edge")).sort()).toEqual([
      "SOLUTION_DRAFT>CANCELLED",
      "SOLUTION_DRAFT>IN_REVIEW",
    ]);
    expect(screen.getByRole("region", { name: "Moves from Solution draft" })).toBeInTheDocument();
  });

  test("a tab switch keeps an unsaved edit, and Edit from another tab brings the document forward", async () => {
    serve({ adr: adrFixture({ participants: PEOPLE }) }, (method, url) =>
      method === "GET" && url.startsWith("/api/v1/adrs/1/discussion") ? jsonResponse(200, discussionPageFixture([])) : null,
    );
    const user = userEvent.setup();
    renderDetails();
    await user.click(await screen.findByRole("button", { name: "Edit" }));
    await user.type(await screen.findByRole("textbox", { name: /^Context/ }), "Unsaved context");
    await user.click(screen.getByRole("tab", { name: "Discussion" }));
    expect(await screen.findByRole("textbox", { name: /Your comment or question/ })).toBeInTheDocument();
    // The editor is hidden, not gone, and Edit is still offered: clicking it brings the document tab forward.
    await user.click(screen.getByRole("tab", { name: "Document" }));
    expect(await screen.findByRole("textbox", { name: /^Context/ })).toHaveValue("Unsaved context");
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Discard changes" }));
    await user.click(screen.getByRole("tab", { name: "Issues" }));
    await user.click(screen.getByRole("button", { name: "Edit" }));
    expect(screen.getByRole("tab", { name: "Document", selected: true })).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 3, name: "Edit the ADR" })).toBeInTheDocument();
  });

  test("a non-participant reads everything but is offered no Edit, no moves and no participant editing", async () => {
    serve({
      adr: adrFixture({ participants: PEOPLE, allowedTransitions: [], canEditContent: false, canEditExpiry: false, canManageParticipants: false }),
    });
    localStorage.setItem("hammurabi.auth.userId", "99");
    renderDetails();
    await screen.findByRole("heading", { level: 2, name: "Use PostgreSQL" });
    expect(screen.queryByRole("button", { name: "Edit" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Send to review" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Edit participants" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Delete" })).not.toBeInTheDocument();
    expect(screen.getByText("No status change is available to you.")).toBeInTheDocument();
  });

  test("a Contributor edits the content but is not offered the moves", async () => {
    localStorage.setItem("hammurabi.auth.userId", "7");
    serve({ adr: adrFixture({ participants: PEOPLE, allowedTransitions: [], canManageParticipants: false }) });
    renderDetails();
    expect(await screen.findByRole("button", { name: "Edit" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Move to draft" })).not.toBeInTheDocument();
  });

  test("an Approver may move it and set the date but not edit the content", async () => {
    localStorage.setItem("hammurabi.auth.userId", "6");
    serve({
      adr: adrFixture({ participants: PEOPLE, status: "IN_REVIEW", allowedTransitions: ["ACTIVE", "WITHDRAWN"], canEditContent: false }),
    });
    renderDetails();
    expect(await screen.findByRole("button", { name: "Activate" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Edit" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Edit the expiry date" })).toBeInTheDocument();
  });

  test("editing swaps the sections for the form; saving sends the held revision and the page follows the refreshed ADR", async () => {
    const state = { adr: adrFixture({ participants: PEOPLE, revision: 3 }) };
    const stub = serve(state, (method) => {
      if (method !== "PUT") return null;
      state.adr = adrFixture({ participants: PEOPLE, revision: 4, context: "Fresh context" });
      return noContent();
    });
    const user = userEvent.setup();
    renderDetails();
    await user.click(await screen.findByRole("button", { name: "Edit" }));
    expect(screen.getByRole("heading", { level: 3, name: "Edit the ADR" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Edit" })).not.toBeInTheDocument();
    await user.type(await screen.findByRole("textbox", { name: /^Context/ }), "Fresh context");
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText("Fresh context")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { level: 3, name: "Edit the ADR" })).not.toBeInTheDocument();
    expect(stub.find("PUT", "/api/v1/adrs/1")?.headers.get("X-Expected-Revision")).toBe("3");
  });

  test("Cancel leaves the form untouched and the editor closed", async () => {
    serve({ adr: adrFixture({ participants: PEOPLE }) });
    const user = userEvent.setup();
    renderDetails();
    await user.click(await screen.findByRole("button", { name: "Edit" }));
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.getByRole("button", { name: "Edit" })).toBeInTheDocument();
  });

  test("Cancel asks before discarding a changed ADR document", async () => {
    serve({ adr: adrFixture({ participants: PEOPLE }) });
    const user = userEvent.setup();
    renderDetails();
    await user.click(await screen.findByRole("button", { name: "Edit" }));
    const context = await screen.findByRole("textbox", { name: /^Context/ });
    await user.type(context, "Unsaved context");
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Keep editing" }));
    expect(context).toHaveValue("Unsaved context");
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Discard changes" }));
    expect(screen.getByRole("button", { name: "Edit" })).toBeInTheDocument();
  });

  test("if the content freezes under an open editor the page falls back to the sections", async () => {
    const state = { adr: adrFixture({ participants: PEOPLE }) };
    serve(state, (method, url) => {
      if (method === "POST" && url.endsWith("/transitions")) {
        state.adr = adrFixture({ participants: PEOPLE, status: "ACTIVE", revision: 4, allowedTransitions: [], canEditContent: false });
        return jsonResponse(200, state.adr);
      }
      return null;
    });
    const user = userEvent.setup();
    renderDetails();
    await user.click(await screen.findByRole("button", { name: "Edit" }));
    await user.click(screen.getByRole("button", { name: "Send to review" }));
    await waitFor(() => expect(screen.queryByRole("heading", { level: 3, name: "Edit the ADR" })).not.toBeInTheDocument());
    expect(screen.getByRole("heading", { level: 3, name: "Problem / question" })).toBeInTheDocument();
  });

  test("a freeze keeps changed text available to copy and prevents saving it", async () => {
    const state = { adr: adrFixture({ participants: PEOPLE }) };
    serve(state, (method, url) => {
      if (method !== "POST" || !url.endsWith("/transitions")) return null;
      state.adr = { ...state.adr, status: "ACTIVE", revision: 4, allowedTransitions: [], canEditContent: false };
      return jsonResponse(200, state.adr);
    });
    const user = userEvent.setup();
    renderDetails();
    await user.click(await screen.findByRole("button", { name: "Edit" }));
    const context = await screen.findByRole("textbox", { name: /^Context/ });
    await user.type(context, "Unsaved context");
    await user.click(screen.getByRole("button", { name: "Send to review" }));
    await screen.findByText("This ADR can no longer be edited. You can copy or discard your draft.");
    expect(context).toHaveValue("Unsaved context");
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  test("only an ADMIN deletes: confirm, leave for the list and drop the ADR from the caches", async () => {
    localStorage.setItem("hammurabi.auth.roles", JSON.stringify(["ADMIN"]));
    const stub = serve({ adr: adrFixture({ participants: PEOPLE }) }, (method) => (method === "DELETE" ? noContent() : null));
    const user = userEvent.setup();
    renderDetails();
    await user.click(await screen.findByRole("button", { name: "Delete" }));
    const dialog = await screen.findByRole("dialog", { name: "Delete ADR?" });
    expect(within(dialog).getByText('ADR-0001 "Use PostgreSQL" will be deleted. Its number is never reused.')).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Delete" }));
    expect(await screen.findByText("at /adrs")).toBeInTheDocument();
    expect(stub.find("DELETE", "/api/v1/adrs/1")).toBeDefined();
    // The deleted ADR's detail is never re-fetched on the way out.
    expect(stub.requests.filter((r) => r.method === "GET" && r.url === "/api/v1/adrs/1")).toHaveLength(1);
  });

  test("a failed delete is explained in the dialog", async () => {
    localStorage.setItem("hammurabi.auth.roles", JSON.stringify(["ADMIN"]));
    serve({ adr: adrFixture() }, (method) => (method === "DELETE" ? problem(404) : null));
    const user = userEvent.setup();
    renderDetails();
    await user.click(await screen.findByRole("button", { name: "Delete" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Delete" }));
    expect(await within(screen.getByRole("dialog")).findByRole("alert")).toHaveTextContent("This ADR no longer exists.");
  });

  test("a delete refused by the adr-write bucket says to wait", async () => {
    localStorage.setItem("hammurabi.auth.roles", JSON.stringify(["ADMIN"]));
    serve({ adr: adrFixture() }, (method) => (method === "DELETE" ? problem(429) : null));
    const user = userEvent.setup();
    renderDetails();
    await user.click(await screen.findByRole("button", { name: "Delete" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Delete" }));
    expect(await within(screen.getByRole("dialog")).findByRole("alert")).toHaveTextContent("Too many requests. Wait a minute and try again.");
  });

  test("a missing ADR, a malformed id and a failed load are each a back-to-list state", async () => {
    stubFetch(() => null);
    const { unmount } = renderDetails();
    expect(await screen.findByText("This ADR does not exist (or was deleted).")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to the ADRs" })).toHaveAttribute("href", "/adrs");
    unmount();
    renderDetails("/adrs/abc");
    expect(await screen.findByText("This ADR does not exist (or was deleted).")).toBeInTheDocument();
    unmount();
    vi.unstubAllGlobals();
    stubFetch(() => new Response(null, { status: 500 }));
    renderDetails();
    expect(await screen.findByText("Load failed (500)")).toBeInTheDocument();
  });
});
