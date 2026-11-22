import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router-dom";
import { QueryClient } from "@tanstack/react-query";
import { adrFixture, eventFixture } from "../test/adrFixtures";
import { stubFetch } from "../test/fetchStub";
import { jsonResponse } from "../test/http";
import { renderWithProviders, screen, waitFor } from "../test/render";
import AdrDetails from "./AdrDetails";

// The visual editor (Lexical) is not what these tests are about: a plain textarea stands in (MarkdownRichEditor.test.tsx runs the real one).
vi.mock("../components/MarkdownRichEditor", () => import("../test/markdownRichEditorStub"));

const PEOPLE = [{ userId: 5, name: "Dana Driver", role: "DRIVER" as const }];

describe("AdrDetails edit mode follows the server's editing verdict", () => {
  beforeEach(() => {
    localStorage.setItem("hammurabi.auth.token", "fake-token");
    localStorage.setItem("hammurabi.auth.userId", "5");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("an editor closed by a freeze stays closed when editing becomes possible again", async () => {
    let adr = adrFixture({ participants: PEOPLE });
    stubFetch((r) => {
      if (r.method === "POST" && r.url.endsWith("/transitions")) {
        adr = adrFixture({ participants: PEOPLE, status: "ACTIVE", revision: 4, allowedTransitions: [], canEditContent: false });
        return jsonResponse(200, adr);
      }
      if (r.method === "GET" && r.url === "/api/v1/adrs/1") return jsonResponse(200, adr);
      if (r.method === "GET" && r.url.startsWith("/api/v1/adrs/1/events")) return jsonResponse(200, { items: [eventFixture()], page: 1, pageSize: 10, total: 1 });
      return null;
    });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const user = userEvent.setup();
    renderWithProviders(<Routes><Route path="/adrs/:id" element={<AdrDetails />} /></Routes>, { route: "/adrs/1", queryClient });
    await user.click(await screen.findByRole("button", { name: "Edit" }));
    await user.click(screen.getByRole("button", { name: "Send to review" }));
    await waitFor(() => expect(screen.queryByRole("heading", { level: 3, name: "Edit the ADR" })).not.toBeInTheDocument());
    expect(screen.queryByRole("button", { name: "Edit" })).not.toBeInTheDocument();

    // Editing opens up again (a reopened ADR, a role change): the page offers Edit, it does not resurrect the editor nobody asked for.
    adr = adrFixture({ participants: PEOPLE, status: "SOLUTION_DRAFT", revision: 5 });
    await queryClient.invalidateQueries({ queryKey: ["adrs"] });
    expect(await screen.findByRole("button", { name: "Edit" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { level: 3, name: "Edit the ADR" })).not.toBeInTheDocument();
  });
});
