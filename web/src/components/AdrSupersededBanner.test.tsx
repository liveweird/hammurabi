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
    renderWithProviders(<AdrSupersededBanner adr={adrFixture({ status: "ACTIVE", supersededBy: PENDING, supersededByAccepted: true })} />);
    expect(screen.getByText("A successor is on its way")).toBeInTheDocument();
    expect(screen.getByText(/this ADR is superseded\./)).toBeInTheDocument();
    expect(screen.getByText("In review")).toBeInTheDocument();
  });

  test("a pending successor shows on a pre-active ADR too", () => {
    for (const status of ["PROBLEM_RAISED", "SOLUTION_DRAFT", "IN_REVIEW"] as const) {
      const { unmount } = renderWithProviders(<AdrSupersededBanner adr={adrFixture({ status, supersededBy: PENDING, supersededByAccepted: true })} />);
      expect(screen.getByText("A successor is on its way")).toBeInTheDocument();
      unmount();
    }
  });

  test("an unaccepted successor is only proposed; whoever may accept it is offered Accept, after a confirmation", async () => {
    const stub = stubFetch((r) => (r.method === "POST" ? noContent() : jsonResponse(200, { items: [] })));
    const user = userEvent.setup();
    renderWithProviders(<AdrSupersededBanner adr={adrFixture({ id: 4, status: "ACTIVE", supersededBy: PENDING, canAcceptSuccessor: true })} />);
    expect(screen.getByText("A successor is proposed")).toBeInTheDocument();
    expect(screen.queryByText("A successor is on its way")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Accept" }));
    const dialog = await screen.findByRole("dialog", { name: "Accept ADR-0009?" });
    await user.click(within(dialog).getByRole("button", { name: "Accept" }));
    await waitFor(() => expect(stub.find("POST", "/api/v1/adrs/4/successor/accept")?.body).toEqual({ successorId: 9 }));
  });

  test("nobody who may not accept is offered Accept, and an accepted successor has nothing left to accept", () => {
    const { unmount } = renderWithProviders(<AdrSupersededBanner adr={adrFixture({ status: "ACTIVE", supersededBy: PENDING })} />);
    expect(screen.getByText("A successor is proposed")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Accept" })).not.toBeInTheDocument();
    unmount();
    renderWithProviders(<AdrSupersededBanner adr={adrFixture({ status: "ACTIVE", supersededBy: PENDING, supersededByAccepted: true })} />);
    expect(screen.queryByRole("button", { name: "Accept" })).not.toBeInTheDocument();
  });

  test("a refused accept is said in words", async () => {
    stubFetch((r) => (r.method === "POST" ? problem(404) : jsonResponse(200, { items: [] })));
    const user = userEvent.setup();
    renderWithProviders(<AdrSupersededBanner adr={adrFixture({ status: "ACTIVE", supersededBy: PENDING, canAcceptSuccessor: true })} />);
    await user.click(screen.getByRole("button", { name: "Accept" }));
    await user.click(within(await screen.findByRole("dialog", { name: "Accept ADR-0009?" })).getByRole("button", { name: "Accept" }));
    expect(await screen.findByText(/not pending any more/)).toBeInTheDocument();
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
    expect(stub.find("DELETE", "/api/v1/adrs/4/successor?successorId=9")).toBeUndefined();
    await user.click(within(dialog).getByRole("button", { name: "Detach" }));
    await waitFor(() => expect(stub.find("DELETE", "/api/v1/adrs/4/successor?successorId=9")).toBeDefined());
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  test("Detach keeps the successor captured by the confirmation when the ADR rerenders", async () => {
    const stub = stubFetch((r) => (r.method === "DELETE" ? noContent() : jsonResponse(200, { items: [] })));
    const user = userEvent.setup();
    const adr = adrFixture({ id: 4, status: "ACTIVE", supersededBy: PENDING, canDetachSuccessor: true });
    const view = renderWithProviders(<AdrSupersededBanner adr={adr} />);
    await user.click(screen.getByRole("button", { name: "Detach" }));
    const dialog = await screen.findByRole("dialog", { name: "Detach ADR-0009?" });

    const replacement = { id: 10, key: "ADR-0010", name: "Use PostgreSQL", status: "IN_REVIEW" as const };
    view.rerender(<AdrSupersededBanner adr={{ ...adr, supersededBy: replacement }} />);
    expect(within(dialog).getByRole("heading", { name: "Detach ADR-0009?" })).toBeInTheDocument();
    expect(within(dialog).queryByText(/ADR-0010/)).not.toBeInTheDocument();

    await user.click(within(dialog).getByRole("button", { name: "Detach" }));
    await waitFor(() => expect(stub.find("DELETE", "/api/v1/adrs/4/successor?successorId=9")).toBeDefined());
    expect(stub.find("DELETE", "/api/v1/adrs/4/successor?successorId=10")).toBeUndefined();
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
