import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { entryFixture } from "../test/adrFixtures";
import { noContent, problem, stubFetch } from "../test/fetchStub";
import { jsonResponse } from "../test/http";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import { usePinnedBrowserTimeZone } from "../test/timeZone";
import DiscussionEntryCard from "./DiscussionEntryCard";

// The visual editor (Lexical) is not what these tests are about: a plain textarea stands in (MarkdownRichEditor.test.tsx runs the real one).
vi.mock("./MarkdownRichEditor", () => import("../test/markdownRichEditorStub"));

const ALL = { canEdit: true, canDelete: true, canChangeState: true };

describe("DiscussionEntryCard", () => {
  usePinnedBrowserTimeZone("America/Los_Angeles");
  beforeEach(() => localStorage.setItem("hammurabi.auth.token", "fake-token"));
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("shows the kind and state badges, the author, the date and the Markdown text - and no action the verdicts did not allow", () => {
    stubFetch(() => null);
    renderWithProviders(<DiscussionEntryCard entry={entryFixture({ body: "Why **not** MySQL?" })} />);
    const card = screen.getByRole("article", { name: "Question by Cora Contributor" });
    expect(within(card).getByText("Question")).toBeInTheDocument();
    expect(within(card).getByText("Open")).toBeInTheDocument();
    expect(within(card).getByText("Cora Contributor")).toBeInTheDocument();
    expect(within(card).getByText("2026-11-03 01:05")).toBeInTheDocument();
    expect(within(card).getByText("not", { selector: "strong" })).toBeInTheDocument();
    expect(within(card).queryByRole("button")).not.toBeInTheDocument();
  });

  test("a comment has no state badge; an edited entry says so; a deleted author keeps the name with a note", () => {
    stubFetch(() => null);
    renderWithProviders(
      <DiscussionEntryCard
        entry={entryFixture({ kind: "COMMENT", state: undefined, editedAt: Date.UTC(2026, 10, 4), authorName: "Cora", authorDeleted: true })}
      />,
    );
    expect(screen.queryByText("Open")).not.toBeInTheDocument();
    expect(screen.getByText(/· edited/)).toBeInTheDocument();
    expect(screen.getByText("Cora (deleted account)")).toBeInTheDocument();
  });

  test("a tombstone is a placeholder: no text, no actions", () => {
    stubFetch(() => null);
    renderWithProviders(<DiscussionEntryCard entry={entryFixture({ deleted: true, body: null, state: undefined, ...ALL, canDelete: false, canEdit: false, canChangeState: false })} />);
    expect(screen.getByText("This entry was deleted.")).toBeInTheDocument();
    expect(screen.queryByText("Why not MySQL?")).not.toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  test("the author edits the text in place: the field is prefilled, Save sends the new text and closes the editor", async () => {
    const stub = stubFetch((r) => (r.method === "PUT" ? jsonResponse(200, entryFixture({ body: "Why not Oracle?" })) : jsonResponse(200, { items: [] })));
    const user = userEvent.setup();
    renderWithProviders(<DiscussionEntryCard entry={entryFixture(ALL)} />);
    await user.click(screen.getByRole("button", { name: "Edit the entry by Cora Contributor" }));
    const field = await screen.findByRole("textbox", { name: /Edit the text/ });
    expect(field).toHaveValue("Why not MySQL?");
    await user.clear(field);
    await user.type(field, "Why not Oracle?");
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => {
      const request = stub.find("PUT", "/api/v1/adrs/1/discussion/11");
      expect(request?.body).toEqual({ body: "Why not Oracle?" });
      expect(request?.headers.get("X-Expected-Revision")).toBe("1");
    });
    await waitFor(() => expect(screen.queryByRole("textbox")).not.toBeInTheDocument());
  });

  test("Cancel leaves the text alone", async () => {
    const stub = stubFetch(() => null);
    const user = userEvent.setup();
    renderWithProviders(<DiscussionEntryCard entry={entryFixture(ALL)} />);
    await user.click(screen.getByRole("button", { name: "Edit the entry by Cora Contributor" }));
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.getByText("Why not MySQL?")).toBeInTheDocument();
    expect(stub.count("PUT", "/api/v1")).toBe(0);
  });

  test("a question is marked answered at once, and an answered one is reopened at once", async () => {
    const stub = stubFetch((r) => (r.method === "PUT" ? jsonResponse(200, entryFixture()) : jsonResponse(200, { items: [] })));
    const user = userEvent.setup();
    const { unmount } = renderWithProviders(<DiscussionEntryCard entry={entryFixture({ canChangeState: true })} />);
    await user.click(screen.getByRole("button", { name: "Mark answered" }));
    await waitFor(() => expect(stub.find("PUT", "/api/v1/adrs/1/discussion/11/state")?.headers.get("X-Expected-Revision")).toBe("1"));
    unmount();
    renderWithProviders(
      <DiscussionEntryCard
        entry={entryFixture({ canChangeState: true, state: "ANSWERED", stateChangedById: 5, stateChangedByName: "Dana Driver", stateChangedAt: Date.UTC(2026, 10, 5) })}
      />,
    );
    expect(screen.getByText("Answered by Dana Driver on 2026-11-04")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reopen question" }));
    await waitFor(() => expect(stub.requests.filter((r) => r.method === "PUT").map((r) => r.body)).toContainEqual({ state: "OPEN" }));
  });

  test("an issue is resolved through a dialog with an optional note; a resolved one shows who, when and the note, and reopens at once", async () => {
    const stub = stubFetch((r) => (r.method === "PUT" ? jsonResponse(200, entryFixture({ kind: "ISSUE" })) : jsonResponse(200, { items: [] })));
    const user = userEvent.setup();
    const { unmount } = renderWithProviders(<DiscussionEntryCard entry={entryFixture({ kind: "ISSUE", canChangeState: true })} />);
    await user.click(screen.getByRole("button", { name: "Resolve" }));
    const dialog = await screen.findByRole("dialog", { name: "Resolve this entry" });
    await user.type(within(dialog).getByRole("textbox", { name: /Resolution note/ }), "Fixed in 2.1");
    await user.click(within(dialog).getByRole("button", { name: "Resolve" }));
    await waitFor(() => expect(stub.find("PUT", "/api/v1/adrs/1/discussion/11/state")?.body).toEqual({ state: "RESOLVED", resolution: "Fixed in 2.1" }));
    unmount();
    renderWithProviders(
      <DiscussionEntryCard
        entry={entryFixture({
          kind: "ISSUE",
          state: "RESOLVED",
          resolution: "Fixed in 2.1",
          canChangeState: true,
          stateChangedById: 6,
          stateChangedByName: "Alex Approver",
          stateChangedAt: Date.UTC(2026, 10, 6),
        })}
      />,
    );
    expect(screen.getByText("Fixed in 2.1")).toBeInTheDocument();
    expect(screen.getByText("Resolved by Alex Approver on 2026-11-05")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reopen" }));
    await waitFor(() => expect(stub.requests.filter((r) => r.method === "PUT").map((r) => r.body)).toContainEqual({ state: "OPEN" }));
  });

  test("resolving without a note sends none; cancelling the dialog sends nothing", async () => {
    const stub = stubFetch((r) => (r.method === "PUT" ? jsonResponse(200, entryFixture({ kind: "ESCALATION" })) : jsonResponse(200, { items: [] })));
    const user = userEvent.setup();
    renderWithProviders(<DiscussionEntryCard entry={entryFixture({ kind: "ESCALATION", canChangeState: true })} />);
    await user.click(screen.getByRole("button", { name: "Resolve" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Cancel" }));
    expect(stub.count("PUT", "/api/v1")).toBe(0);
    await user.click(screen.getByRole("button", { name: "Resolve" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Resolve" }));
    await waitFor(() => expect(stub.find("PUT", "/api/v1/adrs/1/discussion/11/state")?.body).toEqual({ state: "RESOLVED" }));
  });

  test("a failed resolve keeps the dialog and the typed note open, with the error inside it", async () => {
    stubFetch((r) => (r.method === "PUT" ? problem(403) : jsonResponse(200, { items: [] })));
    const user = userEvent.setup();
    renderWithProviders(<DiscussionEntryCard entry={entryFixture({ kind: "ISSUE", canChangeState: true })} />);
    await user.click(screen.getByRole("button", { name: "Resolve" }));
    const dialog = await screen.findByRole("dialog", { name: "Resolve this entry" });
    await user.type(within(dialog).getByRole("textbox", { name: /Resolution note/ }), "A careful note");
    await user.click(within(dialog).getByRole("button", { name: "Resolve" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("Your role on this ADR does not allow this.");
    expect(within(dialog).getByRole("textbox", { name: /Resolution note/ })).toHaveValue("A careful note");
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    const discard = await screen.findByRole("dialog", { name: "Discard unsaved changes?" });
    await user.click(within(discard).getByRole("button", { name: "Discard changes" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  test("deleting asks first; confirming sends the DELETE", async () => {
    const stub = stubFetch((r) => (r.method === "DELETE" ? noContent() : jsonResponse(200, { items: [] })));
    const user = userEvent.setup();
    renderWithProviders(<DiscussionEntryCard entry={entryFixture({ canDelete: true })} />);
    await user.click(screen.getByRole("button", { name: "Delete the entry by Cora Contributor" }));
    const dialog = await screen.findByRole("dialog", { name: "Delete this entry?" });
    expect(stub.count("DELETE", "/api/v1")).toBe(0);
    await user.click(within(dialog).getByRole("button", { name: "Delete" }));
    await waitFor(() => expect(stub.find("DELETE", "/api/v1/adrs/1/discussion/11")?.headers.get("X-Expected-Revision")).toBe("1"));
  });

  test("a closed window is the orange reload notice; any other failure a red alert in fixed words", async () => {
    stubFetch(() => problem(409, "urn:hammurabi:problem:discussion-closed"));
    const user = userEvent.setup();
    const { unmount } = renderWithProviders(<DiscussionEntryCard entry={entryFixture({ canChangeState: true })} />);
    await user.click(screen.getByRole("button", { name: "Mark answered" }));
    const notice = await screen.findByRole("alert");
    expect(notice).toHaveTextContent("The ADR's status no longer takes this");
    expect(within(notice).getByRole("button", { name: "Reload the ADR" })).toBeInTheDocument();
    unmount();
    vi.unstubAllGlobals();
    stubFetch(() => problem(403));
    renderWithProviders(<DiscussionEntryCard entry={entryFixture({ canChangeState: true })} />);
    await user.click(screen.getByRole("button", { name: "Mark answered" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Your role on this ADR does not allow this.");
  });
});
