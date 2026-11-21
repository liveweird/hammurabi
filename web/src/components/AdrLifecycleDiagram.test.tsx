import { describe, expect, test } from "vitest";
import type { AdrStatus } from "../api/adrs";
import { renderWithProviders, screen, within } from "../test/render";
import { ADR_STATUSES } from "../utils/adrStatus";
import { ADR_LIFECYCLE_MOVES, moveId } from "../utils/adrLifecycle";
import AdrLifecycleDiagram from "./AdrLifecycleDiagram";

const edge = (from: AdrStatus, to: AdrStatus) => document.querySelector(`g[data-edge="${moveId({ from, to })}"]`) as HTMLElement;
const node = (status: AdrStatus) => document.querySelector(`g[data-node="${status}"]`) as HTMLElement;

describe("AdrLifecycleDiagram", () => {
  test("draws all eight statuses and every move of the machine, each caption naming the action and who takes it", () => {
    renderWithProviders(<AdrLifecycleDiagram currentStatus="PROBLEM_RAISED" allowedTransitions={[]} expiresOn={null} />);
    for (const status of ADR_STATUSES) expect(node(status)).not.toBeNull();
    expect(document.querySelectorAll("g[data-edge]")).toHaveLength(ADR_LIFECYCLE_MOVES.length);
    // Every move has geometry: a path that starts with a move command.
    for (const move of ADR_LIFECYCLE_MOVES) expect(edge(move.from, move.to).querySelector("path")?.getAttribute("d")).toMatch(/^M\d/);
    expect(within(edge("IN_REVIEW", "ACTIVE")).getByText("Activate")).toBeInTheDocument();
    expect(within(edge("IN_REVIEW", "ACTIVE")).getByText("Approver")).toBeInTheDocument();
    expect(within(edge("IN_REVIEW", "SOLUTION_DRAFT")).getByText("Send back to draft")).toBeInTheDocument();
    expect(within(edge("IN_REVIEW", "SOLUTION_DRAFT")).getByText("Approver or Driver")).toBeInTheDocument();
    expect(within(edge("ACTIVE", "EXPIRED")).getByText("Automatic")).toBeInTheDocument();
    expect(within(edge("ACTIVE", "WITHDRAWN")).getByText("Withdraw decision")).toBeInTheDocument();
  });

  test("names the current status in the image's alt text and marks that node as the one you are at", () => {
    renderWithProviders(<AdrLifecycleDiagram currentStatus="IN_REVIEW" allowedTransitions={[]} expiresOn={null} />);
    expect(screen.getByRole("img", { name: /This ADR is: In review\./ })).toBeInTheDocument();
    expect(node("IN_REVIEW")).toHaveAttribute("data-current", "true");
    expect(document.querySelectorAll("g[data-current]")).toHaveLength(1);
  });

  test("emphasises exactly the moves the caller may make from where the ADR is", () => {
    renderWithProviders(<AdrLifecycleDiagram currentStatus="IN_REVIEW" allowedTransitions={["ACTIVE", "WITHDRAWN"]} expiresOn={null} />);
    const emphasised = Array.from(document.querySelectorAll("g[data-emphasised]")).map((g) => g.getAttribute("data-edge"));
    expect(emphasised.sort()).toEqual(["IN_REVIEW>ACTIVE", "IN_REVIEW>WITHDRAWN"]);
    // The same target reached from another status stays quiet.
    expect(edge("ACTIVE", "WITHDRAWN")).not.toHaveAttribute("data-emphasised");
  });

  test("dims the terminal statuses that are not this one, and not the one the ADR is in", () => {
    renderWithProviders(<AdrLifecycleDiagram currentStatus="EXPIRED" allowedTransitions={[]} expiresOn={null} />);
    expect(within(node("CANCELLED")).getByText("Cancelled")).toHaveAttribute("fill", "var(--mantine-color-dimmed)");
    expect(within(node("EXPIRED")).getByText("Expired")).toHaveAttribute("fill", "var(--mantine-color-text)");
    expect(within(node("ACTIVE")).getByText("Active")).toHaveAttribute("fill", "var(--mantine-color-text)");
  });

  test("tints each node with its status badge colour", () => {
    renderWithProviders(<AdrLifecycleDiagram currentStatus="ACTIVE" allowedTransitions={[]} expiresOn={null} />);
    expect(node("ACTIVE").querySelector("rect")).toHaveAttribute("fill", "var(--mantine-color-teal-light)");
    expect(node("IN_REVIEW").querySelector("rect")).toHaveAttribute("fill", "var(--mantine-color-cyan-light)");
    expect(node("EXPIRED").querySelector("rect")).toHaveAttribute("fill", "var(--mantine-color-red-light)");
  });

  test("the text alternative lists the moves from this status with who takes them and which are the caller's", () => {
    renderWithProviders(<AdrLifecycleDiagram currentStatus="IN_REVIEW" allowedTransitions={["ACTIVE", "CANCELLED"]} expiresOn={null} />);
    const section = screen.getByRole("region", { name: "Moves from In review" });
    const items = within(section).getAllByRole("listitem");
    expect(items.map((li) => li.textContent)).toEqual([
      "Send back to draft → Solution draft: Approver or Driver",
      "Activate → Active: ApproverYou can do this now",
      "Cancel ADR → Cancelled: DriverYou can do this now",
      "Withdraw → Withdrawn: Approver",
    ]);
    expect(within(section).queryByText("None of these moves is yours to make right now.")).toBeNull();
  });

  test("the automatic moves say when they happen, and a caller with no move is told so", () => {
    renderWithProviders(<AdrLifecycleDiagram currentStatus="ACTIVE" allowedTransitions={[]} expiresOn={null} />);
    const section = screen.getByRole("region", { name: "Moves from Active" });
    expect(within(section).getByText(/→ Expired: automatic, when the expiry date passes/)).toBeInTheDocument();
    expect(within(section).getByText(/→ Superseded: automatic, when its successor is activated/)).toBeInTheDocument();
    expect(within(section).getByText("None of these moves is yours to make right now.")).toBeInTheDocument();
  });

  test("names the current last day in force in the automatic expiry rule", () => {
    renderWithProviders(<AdrLifecycleDiagram currentStatus="ACTIVE" allowedTransitions={[]} expiresOn="2027-06-30" />);
    const section = screen.getByRole("region", { name: "Moves from Active" });
    expect(within(section).getByText(/→ Expired: automatic after 2027-06-30, the last day in force/)).toBeInTheDocument();
    expect(within(section).queryByText(/when the expiry date passes/)).toBeNull();
  });

  test("a terminal status has no moves out of it", () => {
    renderWithProviders(<AdrLifecycleDiagram currentStatus="SUPERSEDED" allowedTransitions={[]} expiresOn={null} />);
    const section = screen.getByRole("region", { name: "Moves from Superseded" });
    expect(within(section).getByText("No move leaves Superseded: this ADR is closed.")).toBeInTheDocument();
    expect(within(section).queryByRole("listitem")).toBeNull();
  });

  test("the hint says what the highlight means", () => {
    renderWithProviders(<AdrLifecycleDiagram currentStatus="PROBLEM_RAISED" allowedTransitions={["SOLUTION_DRAFT"]} expiresOn={null} />);
    expect(screen.getByText(/Highlighted arrows are the moves you can make now/)).toBeInTheDocument();
  });
});
