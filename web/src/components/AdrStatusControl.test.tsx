import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { adrFixture } from "../test/adrFixtures";
import { problem, stubFetch } from "../test/fetchStub";
import { jsonResponse } from "../test/http";
import { fireEvent, renderWithProviders, screen, waitFor, within } from "../test/render";
import { showSuccessToast } from "../utils/toast";
import AdrStatusControl from "./AdrStatusControl";

vi.mock("../utils/toast", () => ({ showSuccessToast: vi.fn() }));

const COMPLETE = { context: "C", decision: "D", consequences: "Q" };
const WITH_APPROVER = [
  { userId: 5, name: "Dana Driver", role: "DRIVER" as const },
  { userId: 6, name: "Alex Approver", role: "APPROVER" as const },
];

describe("AdrStatusControl", () => {
  beforeEach(() => localStorage.setItem("hammurabi.auth.token", "fake-token"));
  afterEach(() => {
    vi.mocked(showSuccessToast).mockClear();
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

  test("an ADR in review offers the Approver a send back that is not the filled button, and the Driver one beside Cancel", () => {
    const { unmount } = renderWithProviders(
      <AdrStatusControl adr={adrFixture({ status: "IN_REVIEW", ...COMPLETE, participants: WITH_APPROVER, allowedTransitions: ["SOLUTION_DRAFT", "ACTIVE", "WITHDRAWN"] })} />,
    );
    expect(screen.getByRole("button", { name: "Send back to draft" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Move to draft" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Activate" })).toHaveAttribute("data-variant", "filled");
    expect(screen.getByRole("button", { name: "Send back to draft" })).not.toHaveAttribute("data-variant", "filled");
    unmount();
    renderWithProviders(<AdrStatusControl adr={adrFixture({ status: "IN_REVIEW", allowedTransitions: ["SOLUTION_DRAFT", "CANCELLED"] })} />);
    expect(screen.getByRole("button", { name: "Send back to draft" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Cancel ADR" })).toBeInTheDocument();
  });

  test("sending back asks first, takes an optional reason and sends it with the held revision", async () => {
    const stub = stubFetch((r) => (r.method === "POST" ? jsonResponse(200, adrFixture({ status: "SOLUTION_DRAFT" })) : jsonResponse(200, { items: [] })));
    const user = userEvent.setup();
    renderWithProviders(<AdrStatusControl adr={adrFixture({ status: "IN_REVIEW", revision: 7, allowedTransitions: ["SOLUTION_DRAFT", "ACTIVE", "WITHDRAWN"] })} />);
    await user.click(screen.getByRole("button", { name: "Send back to draft" }));
    const dialog = await screen.findByRole("dialog", { name: "Send this ADR back to draft?" });
    expect(within(dialog).getByText(/content, people and tags are kept/)).toBeInTheDocument();
    expect(stub.find("POST", "/api/v1/adrs/1/transitions")).toBeUndefined();
    await user.type(within(dialog).getByLabelText("Reason (optional)"), "  Needs the cost of option B ");
    await user.click(within(dialog).getByRole("button", { name: "Send back to draft" }));
    await waitFor(() => expect(stub.find("POST", "/api/v1/adrs/1/transitions")?.body).toEqual({ to: "SOLUTION_DRAFT", reason: "Needs the cost of option B" }));
    expect(stub.find("POST", "/api/v1/adrs/1/transitions")?.headers.get("X-Expected-Revision")).toBe("7");
    await waitFor(() => expect(showSuccessToast).toHaveBeenCalledWith("ADR sent back to draft"));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  test("an empty reason sends no reason field, and the dialog starts empty each time", async () => {
    const stub = stubFetch((r) => (r.method === "POST" ? problem(403) : jsonResponse(200, { items: [] })));
    const user = userEvent.setup();
    renderWithProviders(<AdrStatusControl adr={adrFixture({ status: "IN_REVIEW", allowedTransitions: ["SOLUTION_DRAFT"] })} />);
    await user.click(screen.getByRole("button", { name: "Send back to draft" }));
    await user.type(within(await screen.findByRole("dialog")).getByLabelText("Reason (optional)"), "draft text");
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await user.click(screen.getByRole("button", { name: "Send back to draft" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByLabelText("Reason (optional)")).toHaveValue("");
    await user.click(within(dialog).getByRole("button", { name: "Send back to draft" }));
    await waitFor(() => expect(stub.find("POST", "/api/v1/adrs/1/transitions")?.body).toEqual({ to: "SOLUTION_DRAFT" }));
    // A refused move (403) reports in the section in fixed words, and the dialog is gone.
    expect(await screen.findByRole("alert")).toHaveTextContent("Your role on this ADR does not allow this.");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  test("activating over a decision in force that has not accepted the link says it is waiting", () => {
    const OLD = { id: 3, key: "ADR-0003", name: "Use MySQL", status: "ACTIVE" as const };
    const { unmount } = renderWithProviders(<AdrStatusControl adr={adrFixture({ status: "IN_REVIEW", allowedTransitions: ["ACTIVE"], supersedes: OLD })} />);
    expect(screen.getByText(/Waiting for ADR-0003's Approver to accept this ADR as its successor/)).toBeInTheDocument();
    unmount();
    renderWithProviders(<AdrStatusControl adr={adrFixture({ status: "IN_REVIEW", allowedTransitions: ["ACTIVE"], supersedes: OLD, supersedesAccepted: true })} />);
    expect(screen.queryByText(/Waiting for/)).not.toBeInTheDocument();
  });

  test("an ACTIVE decision is withdrawn in red after a confirmation with an optional reason", async () => {
    const stub = stubFetch((r) => (r.method === "POST" ? jsonResponse(200, adrFixture({ status: "WITHDRAWN" })) : jsonResponse(200, { items: [] })));
    const user = userEvent.setup();
    renderWithProviders(<AdrStatusControl adr={adrFixture({ status: "ACTIVE", allowedTransitions: ["WITHDRAWN"] })} />);
    expect(screen.queryByRole("button", { name: "Withdraw" })).not.toBeInTheDocument();
    const button = screen.getByRole("button", { name: "Withdraw decision" });
    expect(button).toHaveAttribute("data-variant", "light");
    await user.click(button);
    const dialog = await screen.findByRole("dialog", { name: "Withdraw this decision?" });
    expect(within(dialog).getByText(/stops being in force/)).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText("Reason (optional)"), "Replaced by the platform standard");
    await user.click(within(dialog).getByRole("button", { name: "Withdraw decision" }));
    await waitFor(() => expect(stub.find("POST", "/api/v1/adrs/1/transitions")?.body).toEqual({ to: "WITHDRAWN", reason: "Replaced by the platform standard" }));
    await waitFor(() => expect(showSuccessToast).toHaveBeenCalledWith("ADR withdrawn"));
  });

  test("a reason over 1,000 characters blocks the confirm button", async () => {
    stubFetch(() => null);
    const user = userEvent.setup();
    renderWithProviders(<AdrStatusControl adr={adrFixture({ status: "ACTIVE", allowedTransitions: ["WITHDRAWN"] })} />);
    await user.click(screen.getByRole("button", { name: "Withdraw decision" }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText("Reason (optional)"), { target: { value: "x".repeat(1001) } });
    expect(within(dialog).getByText("At most 1000 characters")).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Withdraw decision" })).toBeDisabled();
    fireEvent.change(within(dialog).getByLabelText("Reason (optional)"), { target: { value: "\u{1F600}".repeat(1000) } });
    expect(within(dialog).getByRole("button", { name: "Withdraw decision" })).toBeEnabled();
  });

  test("a reason with a hidden direction or control character is caught in the dialog before anything is sent", async () => {
    const stub = stubFetch(() => null);
    const user = userEvent.setup();
    renderWithProviders(<AdrStatusControl adr={adrFixture({ status: "ACTIVE", allowedTransitions: ["WITHDRAWN"] })} />);
    await user.click(screen.getByRole("button", { name: "Withdraw decision" }));
    const dialog = await screen.findByRole("dialog");
    for (const bad of ["a\u202eb", "a\u2028b", "a\u2029b", "a\u0007b", "a\u0085b", "a\u2066b"]) {
      fireEvent.change(within(dialog).getByLabelText("Reason (optional)"), { target: { value: bad } });
      expect(within(dialog).getByText("The reason must not contain control characters or hidden direction characters")).toBeInTheDocument();
      expect(within(dialog).getByRole("button", { name: "Withdraw decision" })).toBeDisabled();
    }
    fireEvent.change(within(dialog).getByLabelText("Reason (optional)"), { target: { value: "line one\n\tline two" } });
    expect(within(dialog).queryByText(/hidden direction characters/)).not.toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Withdraw decision" })).toBeEnabled();
    expect(stub.find("POST", "/api/v1/adrs/1/transitions")).toBeUndefined();
  });

  test("a 400 to a confirmed move keeps the dialog open with the reason in it and says the reason was refused", async () => {
    let answer = problem(400);
    const stub = stubFetch((r) => (r.method === "POST" ? answer : jsonResponse(200, { items: [] })));
    const user = userEvent.setup();
    renderWithProviders(<AdrStatusControl adr={adrFixture({ status: "ACTIVE", allowedTransitions: ["WITHDRAWN"] })} />);
    await user.click(screen.getByRole("button", { name: "Withdraw decision" }));
    const dialog = await screen.findByRole("dialog");
    await user.type(within(dialog).getByLabelText("Reason (optional)"), "Replaced by the platform standard");
    await user.click(within(dialog).getByRole("button", { name: "Withdraw decision" }));
    expect(await within(dialog).findByText(/The reason was refused/)).toBeInTheDocument();
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(within(dialog).getByLabelText("Reason (optional)")).toHaveValue("Replaced by the platform standard");
    // Not the "context, decision ..." notice of the forward moves.
    expect(screen.queryByText(/Not ready for that move/)).not.toBeInTheDocument();
    // Editing the reason clears the refusal; the next try goes through and closes the dialog.
    answer = jsonResponse(200, adrFixture({ status: "WITHDRAWN" }));
    await user.type(within(dialog).getByLabelText("Reason (optional)"), "!");
    expect(within(dialog).queryByText(/The reason was refused/)).not.toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Withdraw decision" }));
    await waitFor(() => expect(stub.count("POST", "/api/v1/adrs/1/transitions")).toBe(2));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  test("cancelling the dialog after a refused reason leaves no stale notice behind", async () => {
    stubFetch((r) => (r.method === "POST" ? problem(400) : jsonResponse(200, { items: [] })));
    const user = userEvent.setup();
    renderWithProviders(<AdrStatusControl adr={adrFixture({ status: "ACTIVE", allowedTransitions: ["WITHDRAWN"] })} />);
    await user.click(screen.getByRole("button", { name: "Withdraw decision" }));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Withdraw decision" }));
    expect(await within(dialog).findByText(/The reason was refused/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
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
