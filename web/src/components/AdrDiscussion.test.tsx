import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { adrFixture, discussionPageFixture, entryFixture } from "../test/adrFixtures";
import { problem, stubFetch } from "../test/fetchStub";
import { jsonResponse } from "../test/http";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import AdrDiscussion from "./AdrDiscussion";

// The visual editor (Lexical) is not what these tests are about: a plain textarea stands in (MarkdownRichEditor.test.tsx runs the real one).
vi.mock("./MarkdownRichEditor", () => import("../test/markdownRichEditorStub"));

const THREAD = [
  entryFixture({ id: 11, kind: "QUESTION", body: "Why not MySQL?" }),
  entryFixture({ id: 12, kind: "COMMENT", state: undefined, body: "Fine by me", authorId: 8, authorName: "Ivan Informed", authorDeleted: false }),
];

const listUrls = (stub: ReturnType<typeof stubFetch>) => stub.requests.filter((r) => r.method === "GET").map((r) => r.url);

describe("AdrDiscussion", () => {
  beforeEach(() => localStorage.setItem("hammurabi.auth.token", "fake-token"));
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("the discussion section asks for comments and questions, oldest first, and shows them under its own h3", async () => {
    const stub = stubFetch(() => jsonResponse(200, discussionPageFixture(THREAD)));
    renderWithProviders(<AdrDiscussion adr={adrFixture()} variant="discussion" />);
    expect(await screen.findByText("Why not MySQL?")).toBeInTheDocument();
    expect(screen.getByText("Fine by me")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 3, name: "Comments and questions" })).toBeInTheDocument();
    expect(listUrls(stub)).toEqual(["/api/v1/adrs/1/discussion?page=1&pageSize=20&kind=COMMENT&kind=QUESTION"]);
    expect(screen.queryByRole("switch")).not.toBeInTheDocument();
  });

  test("an empty thread says so", async () => {
    stubFetch(() => jsonResponse(200, discussionPageFixture([])));
    renderWithProviders(<AdrDiscussion adr={adrFixture()} variant="discussion" />);
    expect(await screen.findByText("Nothing has been said yet.")).toBeInTheDocument();
  });

  test("the composer posts a comment or a question by the chosen kind, then clears", async () => {
    const stub = stubFetch((r) => {
      if (r.method === "POST") return jsonResponse(201, entryFixture());
      return jsonResponse(200, discussionPageFixture([]));
    });
    const user = userEvent.setup();
    renderWithProviders(<AdrDiscussion adr={adrFixture({ canComment: true })} variant="discussion" />);
    const field = await screen.findByRole("textbox", { name: /Your comment or question/ });
    const submit = screen.getByRole("button", { name: "Post comment" });
    expect(submit).toBeDisabled();
    await user.type(field, "Looks fine");
    await user.click(submit);
    await waitFor(() => expect(stub.find("POST", "/api/v1/adrs/1/discussion")?.body).toEqual({ kind: "COMMENT", body: "Looks fine" }));
    await waitFor(() => expect(field).toHaveValue(""));
    await user.click(screen.getByRole("radio", { name: "Question" }));
    expect(screen.getByRole("button", { name: "Ask question" })).toBeInTheDocument();
    await user.type(field, "Why?");
    await user.click(screen.getByRole("button", { name: "Ask question" }));
    await waitFor(() => expect(stub.requests.filter((r) => r.method === "POST").map((r) => r.body)).toContainEqual({ kind: "QUESTION", body: "Why?" }));
  });

  test("the composer can show the Markdown it will post, and the draft survives the switch", async () => {
    stubFetch(() => jsonResponse(200, discussionPageFixture([])));
    const user = userEvent.setup();
    renderWithProviders(<AdrDiscussion adr={adrFixture()} variant="discussion" />);
    await user.type(await screen.findByRole("textbox", { name: /Your comment or question/ }), "Some **bold** text");
    await user.click(screen.getByRole("radio", { name: "Markdown" }));
    expect(await screen.findByRole("textbox", { name: /Your comment or question/ })).toHaveValue("Some **bold** text");
    expect(screen.getByRole("radio", { name: "Markdown" })).toBeChecked();
  });

  test("a rejected post is the typed notice or a red alert, and keeps the draft", async () => {
    stubFetch((r) => (r.method === "POST" ? problem(409, "urn:hammurabi:problem:discussion-closed") : jsonResponse(200, discussionPageFixture([]))));
    const user = userEvent.setup();
    renderWithProviders(<AdrDiscussion adr={adrFixture()} variant="discussion" />);
    const field = await screen.findByRole("textbox", { name: /Your comment or question/ });
    await user.type(field, "Too late");
    await user.click(screen.getByRole("button", { name: "Post comment" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("The ADR's status no longer takes this");
    expect(field).toHaveValue("Too late");
  });

  test("when the ADR no longer takes comments there is no composer, only the reason", async () => {
    stubFetch(() => jsonResponse(200, discussionPageFixture(THREAD)));
    renderWithProviders(<AdrDiscussion adr={adrFixture({ status: "ACTIVE", canComment: false, canRaiseIssue: true })} variant="discussion" />);
    await screen.findByText("Why not MySQL?");
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(screen.getByText("Comments and questions are taken until the ADR is active. What was said stays readable.")).toBeInTheDocument();
  });

  test("the issues section asks for issues and escalations and posts either", async () => {
    const stub = stubFetch((r) => (r.method === "POST" ? jsonResponse(201, entryFixture({ kind: "ESCALATION" })) : jsonResponse(200, discussionPageFixture([]))));
    const user = userEvent.setup();
    renderWithProviders(<AdrDiscussion adr={adrFixture({ status: "ACTIVE", canComment: false, canRaiseIssue: true })} variant="issues" />);
    expect(await screen.findByText("No issues have been raised.")).toBeInTheDocument();
    expect(listUrls(stub)).toEqual(["/api/v1/adrs/1/discussion?page=1&pageSize=20&kind=ISSUE&kind=ESCALATION"]);
    expect(screen.getByRole("heading", { level: 3, name: "Issues and escalations" })).toBeInTheDocument();
    await user.click(screen.getByRole("radio", { name: "Escalation" }));
    await user.type(await screen.findByRole("textbox", { name: /Describe the issue/ }), "Production is down");
    await user.click(screen.getByRole("button", { name: "Escalate" }));
    await waitFor(() => expect(stub.find("POST", "/api/v1/adrs/1/discussion")?.body).toEqual({ kind: "ESCALATION", body: "Production is down" }));
  });

  test("before the ADR is active the issues section explains when it opens; after it ends, that it closed", async () => {
    stubFetch(() => jsonResponse(200, discussionPageFixture([])));
    const { unmount } = renderWithProviders(<AdrDiscussion adr={adrFixture({ canRaiseIssue: false })} variant="issues" />);
    expect(await screen.findByText(/can be raised once the ADR is active/)).toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    unmount();
    renderWithProviders(<AdrDiscussion adr={adrFixture({ status: "EXPIRED", canRaiseIssue: false })} variant="issues" />);
    expect(await screen.findByText(/no longer active, so no new issues can be raised/)).toBeInTheDocument();
  });

  test("the open-only switch narrows the issues to the OPEN ones and says so when none is", async () => {
    const stub = stubFetch((r) => jsonResponse(200, discussionPageFixture(r.url.includes("state=OPEN") ? [] : [entryFixture({ kind: "ISSUE", state: "RESOLVED", body: "Old issue" })])));
    const user = userEvent.setup();
    renderWithProviders(<AdrDiscussion adr={adrFixture({ status: "ACTIVE", canRaiseIssue: true })} variant="issues" />);
    expect(await screen.findByText("Old issue")).toBeInTheDocument();
    await user.click(screen.getByRole("switch", { name: "Only open issues" }));
    expect(await screen.findByText("Nothing open.")).toBeInTheDocument();
    expect(listUrls(stub)).toContain("/api/v1/adrs/1/discussion?page=1&pageSize=20&kind=ISSUE&kind=ESCALATION&state=OPEN");
  });

  test("a long thread pages, and a failed load is an alert with Retry that does not lose the thread's pager", async () => {
    const many = Array.from({ length: 20 }, (_, index) => entryFixture({ id: 100 + index, body: `Entry ${index}` }));
    const stub = stubFetch((r) => jsonResponse(200, { ...discussionPageFixture(many, 45), page: r.url.includes("page=2") ? 2 : 1 }));
    const user = userEvent.setup();
    renderWithProviders(<AdrDiscussion adr={adrFixture()} variant="discussion" />);
    await screen.findByText("Entry 0");
    await user.click(screen.getByRole("button", { name: "Next page" }));
    await waitFor(() => expect(listUrls(stub)).toContain("/api/v1/adrs/1/discussion?page=2&pageSize=20&kind=COMMENT&kind=QUESTION"));
  });

  test("when the thread shrinks below the page we are on, the page steps back to the last one", async () => {
    const many = Array.from({ length: 20 }, (_, index) => entryFixture({ id: 200 + index, body: `Entry ${index}` }));
    const stub = stubFetch((r) =>
      r.url.includes("page=2")
        ? jsonResponse(200, { ...discussionPageFixture([], 20), page: 2 })
        : jsonResponse(200, discussionPageFixture(r.url.includes("page=1") ? many : [], 21)),
    );
    const user = userEvent.setup();
    renderWithProviders(<AdrDiscussion adr={adrFixture()} variant="discussion" />);
    await screen.findByText("Entry 0");
    await user.click(screen.getByRole("button", { name: "Next page" }));
    // Page 2 came back empty with a total of 20 (an entry was deleted): the section returns to page 1 on its own.
    await waitFor(() => expect(stub.requests.filter((r) => r.url.includes("page=1")).length).toBeGreaterThan(1));
    expect(await screen.findByText("Entry 0")).toBeInTheDocument();
  });

  test("a failed load is an alert with Retry", async () => {
    let attempts = 0;
    stubFetch(() => (attempts++ === 0 ? new Response(null, { status: 500 }) : jsonResponse(200, discussionPageFixture(THREAD))));
    const user = userEvent.setup();
    renderWithProviders(<AdrDiscussion adr={adrFixture()} variant="discussion" />);
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Load failed (500)");
    await user.click(within(alert.parentElement as HTMLElement).getByRole("button", { name: "Retry" }));
    expect(await screen.findByText("Why not MySQL?")).toBeInTheDocument();
  });
});
