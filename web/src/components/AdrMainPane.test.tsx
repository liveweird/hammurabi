import { useState } from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import type { AdrResponse } from "../api/adrs";
import { adrFixture, discussionPageFixture, entryFixture } from "../test/adrFixtures";
import { headingOutline } from "../test/headings";
import { stubFetch } from "../test/fetchStub";
import { jsonResponse } from "../test/http";
import { renderWithProviders, screen } from "../test/render";
import AdrMainPane, { type AdrTab } from "./AdrMainPane";

/** The page's side of the contract: it owns the active tab. */
function Harness({ adr, initial = "document" }: { adr: AdrResponse; initial?: AdrTab }) {
  const [tab, setTab] = useState<AdrTab>(initial);
  return (
    <AdrMainPane adr={adr} tab={tab} onTabChange={setTab}>
      <label>
        Draft
        <input aria-label="document draft" />
      </label>
    </AdrMainPane>
  );
}

describe("AdrMainPane", () => {
  beforeEach(() => localStorage.setItem("hammurabi.auth.token", "fake-token"));
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("opens on the document and fetches no discussion until a tab asks for it", () => {
    const stub = stubFetch(() => jsonResponse(200, discussionPageFixture([])));
    renderWithProviders(<Harness adr={adrFixture()} />);
    expect(screen.getByRole("textbox", { name: "document draft" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Document", selected: true })).toBeInTheDocument();
    expect(stub.requests).toHaveLength(0);
  });

  test("the tabs carry the unanswered questions and the open issues, and open their sections", async () => {
    stubFetch((r) =>
      jsonResponse(200, discussionPageFixture(r.url.includes("kind=ISSUE") ? [entryFixture({ id: 21, kind: "ISSUE", body: "It leaks" })] : [entryFixture()])),
    );
    const user = userEvent.setup();
    renderWithProviders(<Harness adr={adrFixture({ status: "ACTIVE", unansweredQuestionCount: 2, openIssueCount: 3, canComment: false, canRaiseIssue: true })} />);
    await user.click(screen.getByRole("tab", { name: "Discussion unanswered: 2" }));
    expect(await screen.findByText("Why not MySQL?")).toBeInTheDocument();
    // Only the open tab's headings are in the outline: the others are hidden, not gone.
    expect(headingOutline()).toEqual([[3, "Comments and questions"]]);
    await user.click(screen.getByRole("tab", { name: "Issues open: 3" }));
    expect(await screen.findByText("It leaks")).toBeInTheDocument();
    expect(headingOutline()).toEqual([[3, "Issues and escalations"]]);
  });

  test("a tab switch costs nothing: the document draft and the composer draft are still there when you come back", async () => {
    const stub = stubFetch(() => jsonResponse(200, discussionPageFixture([])));
    const user = userEvent.setup();
    renderWithProviders(<Harness adr={adrFixture()} />);
    await user.type(screen.getByRole("textbox", { name: "document draft" }), "half-written context");
    await user.click(screen.getByRole("tab", { name: "Discussion" }));
    await user.type(await screen.findByRole("textbox", { name: /Your comment or question/ }), "a question I have not sent");
    await user.click(screen.getByRole("tab", { name: "Document" }));
    expect(screen.getByRole("textbox", { name: "document draft" })).toHaveValue("half-written context");
    await user.click(screen.getByRole("tab", { name: "Discussion" }));
    expect(screen.getByRole("textbox", { name: /Your comment or question/ })).toHaveValue("a question I have not sent");
    // Coming back does not refetch the section either: it was never unmounted.
    expect(stub.count("GET", "/api/v1/adrs/1/discussion")).toBe(1);
  });

  test("with nothing unanswered or open the tabs are bare", () => {
    stubFetch(() => null);
    renderWithProviders(<Harness adr={adrFixture()} />);
    expect(screen.getByRole("tab", { name: "Discussion" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Issues" })).toBeInTheDocument();
  });
});
