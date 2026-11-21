import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { adrFixture } from "../test/adrFixtures";
import { noContent, problem, stubFetch } from "../test/fetchStub";
import { jsonResponse } from "../test/http";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import AdrSupersededBanner from "./AdrSupersededBanner";

const NEXT = { id: 9, key: "ADR-0009", name: "Use CockroachDB", status: "ACTIVE" as const };
const PENDING = { ...NEXT, status: "IN_REVIEW" as const };

describe("AdrSupersededBanner", () => {
  beforeEach(() => localStorage.setItem("hammurabi.auth.token", "fake-token"));
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("a superseded ADR says by which one, with a link to it", () => {
    renderWithProviders(<AdrSupersededBanner adr={adrFixture({ status: "SUPERSEDED", supersededBy: NEXT })} />);
    expect(screen.getByText("This ADR is superseded")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "ADR-0009 Use CockroachDB" })).toHaveAttribute("href", "/adrs/9");
    expect(screen.getByText("Active")).toBeInTheDocument();
  });

  test("a superseded ADR whose replacement was withdrawn since still names it, with a Withdrawn badge", () => {
    renderWithProviders(<AdrSupersededBanner adr={adrFixture({ status: "SUPERSEDED", supersededBy: { ...NEXT, status: "WITHDRAWN" } })} />);
    expect(screen.getByText("This ADR is superseded")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "ADR-0009 Use CockroachDB" })).toBeInTheDocument();
    expect(screen.getByText("Withdrawn")).toBeInTheDocument();
  });

  test("a pending successor is a quieter note about what activating it will do", () => {
    renderWithProviders(<AdrSupersededBanner adr={adrFixture({ status: "ACTIVE", supersededBy: PENDING })} />);
    expect(screen.getByText("A successor is on its way")).toBeInTheDocument();
    expect(screen.getByText(/this ADR is superseded\./)).toBeInTheDocument();
    expect(screen.getByText("In review")).toBeInTheDocument();
  });

  test("a pending successor shows on a pre-active ADR too", () => {
    for (const status of ["PROBLEM_RAISED", "SOLUTION_DRAFT", "IN_REVIEW"] as const) {
      const { unmount } = renderWithProviders(<AdrSupersededBanner adr={adrFixture({ status, supersededBy: PENDING })} />);
      expect(screen.getByText("A successor is on its way")).toBeInTheDocument();
      unmount();
    }
  });

  test("a terminal ADR that was not superseded shows nothing, whatever link it still carries", () => {
    for (const status of ["CANCELLED", "WITHDRAWN", "EXPIRED"] as const) {
      const { container, unmount } = renderWithProviders(<AdrSupersededBanner adr={adrFixture({ status, supersededBy: PENDING })} />);
      expect(container.querySelector(".mantine-Alert-root")).toBeNull();
      expect(screen.queryByText("A successor is on its way")).not.toBeInTheDocument();
      unmount();
    }
  });

  test("no successor, no banner", () => {
    const { container } = renderWithProviders(<AdrSupersededBanner adr={adrFixture()} />);
    expect(container.querySelector(".mantine-Alert-root")).toBeNull();
  });

  test("without the server's verdict there is no Detach action", () => {
    renderWithProviders(<AdrSupersededBanner adr={adrFixture({ status: "ACTIVE", supersededBy: PENDING, canDetachSuccessor: false })} />);
    expect(screen.queryByRole("button", { name: "Detach" })).not.toBeInTheDocument();
  });

  test("Detach asks first, then DELETEs the successor link and refreshes", async () => {
    const stub = stubFetch((r) => (r.method === "DELETE" ? noContent() : jsonResponse(200, { items: [] })));
    const user = userEvent.setup();
    renderWithProviders(<AdrSupersededBanner adr={adrFixture({ id: 4, status: "ACTIVE", supersededBy: PENDING, canDetachSuccessor: true })} />);
    await user.click(screen.getByRole("button", { name: "Detach" }));
    const dialog = await screen.findByRole("dialog", { name: "Detach ADR-0009?" });
    expect(stub.find("DELETE", "/api/v1/adrs/4/successor")).toBeUndefined();
    await user.click(within(dialog).getByRole("button", { name: "Detach" }));
    await waitFor(() => expect(stub.find("DELETE", "/api/v1/adrs/4/successor")).toBeDefined());
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  test("Keep it closes the confirmation without a request", async () => {
    const stub = stubFetch(() => noContent());
    const user = userEvent.setup();
    renderWithProviders(<AdrSupersededBanner adr={adrFixture({ status: "ACTIVE", supersededBy: PENDING, canDetachSuccessor: true })} />);
    await user.click(screen.getByRole("button", { name: "Detach" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Keep it" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(stub.requests).toHaveLength(0);
  });

  test("a successor that is already gone (404) and one that activated first (409) are said in words", async () => {
    let answer = () => problem(404);
    stubFetch(() => answer());
    const user = userEvent.setup();
    renderWithProviders(<AdrSupersededBanner adr={adrFixture({ status: "ACTIVE", supersededBy: PENDING, canDetachSuccessor: true })} />);
    await user.click(screen.getByRole("button", { name: "Detach" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Detach" }));
    expect(await screen.findByText(/no pending successor any more/)).toBeInTheDocument();
    answer = () => problem(409, "urn:hammurabi:problem:adr-frozen");
    await user.click(screen.getByRole("button", { name: "Detach" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Detach" }));
    expect(await screen.findByRole("button", { name: "Reload the ADR" })).toBeInTheDocument();
  });
});
