import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { adrFixture, adrPageFixture } from "../test/adrFixtures";
import { noContent, problem, stubFetch } from "../test/fetchStub";
import { jsonResponse } from "../test/http";
import { renderWithProviders, screen, waitFor } from "../test/render";
import AdrSuccessorPanel from "./AdrSuccessorPanel";

const OLD = { id: 3, key: "ADR-0003", name: "Use MySQL", status: "ACTIVE" as const };
const CANDIDATES = adrPageFixture([
  {
    id: 3, number: 3, key: "ADR-0003", name: "Use MySQL", status: "ACTIVE", expiresOn: null, revision: 2, createdAt: 1, updatedAt: 1, statusChangedAt: 1,
    driver: null, approver: null, tags: [], openIssueCount: 0,
  },
  {
    id: 1, number: 1, key: "ADR-0001", name: "Use PostgreSQL", status: "PROBLEM_RAISED", expiresOn: null, revision: 1, createdAt: 1, updatedAt: 1, statusChangedAt: 1,
    driver: null, approver: null, tags: [], openIssueCount: 0,
  },
]);

function serve(write: () => Response = noContent) {
  return stubFetch((r) => (r.method === "PUT" ? write() : jsonResponse(200, CANDIDATES)));
}

describe("AdrSuccessorPanel", () => {
  beforeEach(() => localStorage.setItem("hammurabi.auth.token", "fake-token"));
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("shows the ADR this one supersedes as a link with its status, and the one replacing it", () => {
    renderWithProviders(
      <AdrSuccessorPanel adr={adrFixture({ canEditSuccessor: false, supersedes: OLD, supersededBy: { id: 9, key: "ADR-0009", name: "Next", status: "IN_REVIEW" } })} />,
    );
    expect(screen.getByRole("link", { name: "ADR-0003 Use MySQL" })).toHaveAttribute("href", "/adrs/3");
    expect(screen.getByText("Set to replace this ADR")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "ADR-0009 Next" })).toBeInTheDocument();
  });

  test("a predecessor that is no longer in force warns that the link blocks activation, a live one does not", () => {
    const { unmount } = renderWithProviders(
      <AdrSuccessorPanel adr={adrFixture({ status: "IN_REVIEW", supersedes: { ...OLD, status: "WITHDRAWN" } })} />,
    );
    expect(screen.getByText("ADR-0003 is no longer in force (Withdrawn), so there is nothing left to replace. Remove the link before this ADR is activated.")).toBeInTheDocument();
    unmount();
    const live = renderWithProviders(<AdrSuccessorPanel adr={adrFixture({ status: "IN_REVIEW", supersedes: OLD })} />);
    expect(screen.queryByText(/no longer in force/)).not.toBeInTheDocument();
    live.unmount();
    renderWithProviders(<AdrSuccessorPanel adr={adrFixture({ status: "ACTIVE", supersedes: { ...OLD, status: "SUPERSEDED" } })} />);
    expect(screen.queryByText(/no longer in force/)).not.toBeInTheDocument();
  });

  test("a superseded ADR names its successor as the one that replaced it", () => {
    renderWithProviders(
      <AdrSuccessorPanel adr={adrFixture({ status: "SUPERSEDED", canEditSuccessor: false, supersededBy: { id: 9, key: "ADR-0009", name: "Next", status: "ACTIVE" } })} />,
    );
    expect(screen.getByText("Replaced by")).toBeInTheDocument();
  });

  test("whoever may not edit the link sees no picker and no button", () => {
    renderWithProviders(<AdrSuccessorPanel adr={adrFixture({ canEditSuccessor: false })} />);
    expect(screen.getByText("Replaces no ADR.")).toBeInTheDocument();
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  test("the picker searches ADRs by key or name (never offering this one), and Link sends the pick under the held revision", async () => {
    const stub = serve();
    const user = userEvent.setup();
    renderWithProviders(<AdrSuccessorPanel adr={adrFixture({ id: 1, revision: 5 })} />);
    expect(screen.getByRole("button", { name: "Link" })).toBeDisabled();
    const picker = screen.getByRole("combobox", { name: "This ADR supersedes…" });
    await user.click(picker);
    await user.type(picker, "mysql");
    await waitFor(() => expect(stub.requests.some((r) => r.url.includes("q=mysql"))).toBe(true));
    const search = stub.requests.find((r) => r.url.includes("q=mysql"))!;
    expect(search.url).toContain("status=PROBLEM_RAISED&status=SOLUTION_DRAFT&status=IN_REVIEW&status=ACTIVE");
    expect(screen.queryByRole("option", { name: "ADR-0001 Use PostgreSQL" })).not.toBeInTheDocument();
    await user.click(await screen.findByRole("option", { name: "ADR-0003 Use MySQL" }));
    await user.click(screen.getByRole("button", { name: "Link" }));
    await waitFor(() => expect(stub.find("PUT", "/api/v1/adrs/1/supersedes")).toBeDefined());
    const put = stub.find("PUT", "/api/v1/adrs/1/supersedes");
    expect(put?.headers.get("X-Expected-Revision")).toBe("5");
    expect(put?.body).toEqual({ supersedesId: 3 });
  });

  test("the picker does not search until the dropdown opens, and its search box is capped at the server's limit", async () => {
    const stub = serve();
    const user = userEvent.setup();
    renderWithProviders(<AdrSuccessorPanel adr={adrFixture({ id: 1 })} />);
    const picker = screen.getByRole("combobox", { name: "This ADR supersedes…" });
    expect(picker).toHaveAttribute("maxlength", "100");
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(stub.requests.filter((r) => r.url.startsWith("/api/v1/adrs?"))).toHaveLength(0);
    await user.click(picker);
    await waitFor(() => expect(stub.requests.filter((r) => r.url.startsWith("/api/v1/adrs?")).length).toBeGreaterThan(0));
  });

  test("an existing link can be removed", async () => {
    const stub = serve();
    const user = userEvent.setup();
    renderWithProviders(<AdrSuccessorPanel adr={adrFixture({ revision: 7, supersedes: OLD })} />);
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Remove the link" }));
    await waitFor(() => expect(stub.find("PUT", "/api/v1/adrs/1/supersedes")).toBeDefined());
    const put = stub.find("PUT", "/api/v1/adrs/1/supersedes");
    expect(put?.headers.get("X-Expected-Revision")).toBe("7");
    expect(put?.body).toEqual({ supersedesId: null });
  });

  test("a loop or a rival successor (409) and a refused ADR (400) are said in words", async () => {
    let answer = () => problem(409);
    serve(() => answer());
    const user = userEvent.setup();
    renderWithProviders(<AdrSuccessorPanel adr={adrFixture({ supersedes: OLD })} />);
    await user.click(screen.getByRole("button", { name: "Remove the link" }));
    expect(await screen.findByText(/close a loop of successors/)).toBeInTheDocument();
    answer = () => problem(400);
    await user.click(screen.getByRole("button", { name: "Remove the link" }));
    expect(await screen.findByText(/pick another ADR/)).toBeInTheDocument();
  });
});
