import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { adrFixture } from "../test/adrFixtures";
import { problem, stubFetch } from "../test/fetchStub";
import { jsonResponse } from "../test/http";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import AdrStatusControl from "./AdrStatusControl";

const COMPLETE = { context: "C", decision: "D", consequences: "Q" };
const WITH_APPROVER = [
  { userId: 5, name: "Dana Driver", role: "DRIVER" as const },
  { userId: 6, name: "Alex Approver", role: "APPROVER" as const },
];

describe("AdrStatusControl", () => {
  beforeEach(() => localStorage.setItem("hammurabi.auth.token", "fake-token"));
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("shows the badge and one button per allowed move, and nothing the server did not allow", () => {
    stubFetch(() => null);
    renderWithProviders(<AdrStatusControl adr={adrFixture({ allowedTransitions: ["SOLUTION_DRAFT", "IN_REVIEW", "CANCELLED"] })} />);
    expect(screen.getByText("Problem raised")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Move to draft" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Send to review" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Cancel ADR" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Activate" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Withdraw" })).not.toBeInTheDocument();
  });

  test("a non-participant sees no buttons and is told so; a closed ADR says it is read-only", () => {
    const { unmount } = renderWithProviders(<AdrStatusControl adr={adrFixture({ allowedTransitions: [] })} />);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    expect(screen.getByText("No status change is available to you.")).toBeInTheDocument();
    unmount();
    renderWithProviders(<AdrStatusControl adr={adrFixture({ status: "CANCELLED", allowedTransitions: [] })} />);
    expect(screen.getByText("This ADR is closed: it is read-only.")).toBeInTheDocument();
  });

  test("lists what entering review still needs, only when a review move is offered", () => {
    const { unmount } = renderWithProviders(<AdrStatusControl adr={adrFixture()} />);
    expect(screen.getByText("Still needed to continue: context, decision, consequences, an Approver.")).toBeInTheDocument();
    unmount();
    renderWithProviders(<AdrStatusControl adr={adrFixture({ allowedTransitions: ["CANCELLED"] })} />);
    expect(screen.queryByText(/Still needed/)).not.toBeInTheDocument();
  });

  test("a forward move acts at once, sending the held revision", async () => {
    const stub = stubFetch((r) =>
      r.method === "POST" ? jsonResponse(200, adrFixture({ status: "SOLUTION_DRAFT" })) : jsonResponse(200, { items: [] }),
    );
    const user = userEvent.setup();
    renderWithProviders(<AdrStatusControl adr={adrFixture({ revision: 11 })} />);
    await user.click(screen.getByRole("button", { name: "Move to draft" }));
    await waitFor(() => expect(stub.find("POST", "/api/v1/adrs/1/transitions")).toBeDefined());
    const post = stub.find("POST", "/api/v1/adrs/1/transitions");
    expect(post?.body).toEqual({ to: "SOLUTION_DRAFT" });
    expect(post?.headers.get("X-Expected-Revision")).toBe("11");
  });

  test("cancelling asks first; Cancel in the dialog sends nothing, confirming moves the ADR", async () => {
    const stub = stubFetch((r) => (r.method === "POST" ? jsonResponse(200, adrFixture({ status: "CANCELLED" })) : jsonResponse(200, { items: [] })));
    const user = userEvent.setup();
    renderWithProviders(<AdrStatusControl adr={adrFixture({ ...COMPLETE, participants: WITH_APPROVER })} />);
    await user.click(screen.getByRole("button", { name: "Cancel ADR" }));
    const dialog = await screen.findByRole("dialog", { name: "Cancel this ADR?" });
    expect(within(dialog).getByText(/closed for good/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(stub.find("POST", "/api/v1/adrs/1/transitions")).toBeUndefined();

    await user.click(screen.getByRole("button", { name: "Cancel ADR" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Cancel ADR" }));
    await waitFor(() => expect(stub.find("POST", "/api/v1/adrs/1/transitions")?.body).toEqual({ to: "CANCELLED" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  test("withdrawing has its own confirmation", async () => {
    stubFetch(() => null);
    const user = userEvent.setup();
    renderWithProviders(<AdrStatusControl adr={adrFixture({ status: "IN_REVIEW", allowedTransitions: ["ACTIVE", "CANCELLED", "WITHDRAWN"] })} />);
    await user.click(screen.getByRole("button", { name: "Withdraw" }));
    expect(await screen.findByRole("dialog", { name: "Withdraw this ADR?" })).toBeInTheDocument();
  });

  test("a stale revision shows the reload notice; the dialog closes and the notice stays", async () => {
    stubFetch((r) => (r.method === "POST" ? problem(409, "urn:hammurabi:problem:revision-conflict") : jsonResponse(200, { items: [] })));
    const user = userEvent.setup();
    renderWithProviders(<AdrStatusControl adr={adrFixture()} />);
    await user.click(screen.getByRole("button", { name: "Cancel ADR" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Cancel ADR" }));
    expect(await screen.findByText(/Someone else changed this ADR/)).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await user.click(screen.getByRole("button", { name: "Reload the ADR" }));
    await waitFor(() => expect(screen.queryByText(/Someone else changed/)).not.toBeInTheDocument());
  });

  test("an illegal move has its own message, and an unmet requirement says what to check", async () => {
    let answer = problem(409, "urn:hammurabi:problem:illegal-transition");
    stubFetch((r) => (r.method === "POST" ? answer : jsonResponse(200, { items: [] })));
    const user = userEvent.setup();
    renderWithProviders(<AdrStatusControl adr={adrFixture()} />);
    await user.click(screen.getByRole("button", { name: "Send to review" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("cannot move there from its current status");
    answer = problem(400);
    await user.click(screen.getByRole("button", { name: "Reload the ADR" }));
    await user.click(screen.getByRole("button", { name: "Send to review" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Not ready for that move");
  });
});
