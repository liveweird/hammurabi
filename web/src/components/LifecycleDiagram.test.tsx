import { describe, expect, test } from "vitest";
import { renderWithProviders, screen } from "../test/render";
import LifecycleDiagram, { type LifecycleEdge, type LifecycleNode } from "./LifecycleDiagram";

const NODES: LifecycleNode[] = [
  { id: "DRAFT", x: 20, y: 40, label: "Draft", color: "grape" },
  { id: "ACTIVE", x: 275, y: 40, label: "Active" },
  { id: "ARCHIVED", x: 530, y: 40, label: "Archived", terminal: true },
];

const EDGES: LifecycleEdge[] = [
  { id: "activate", x1: 190, y1: 67, x2: 275, y2: 67, label: "Activate", sublabel: "Approver", labelX: 232, labelY: 55 },
  { id: "archive", d: "M445,67 C480,20 500,20 530,67", emphasised: true, label: "Archive", labelX: 490, labelY: 30, labelAnchor: "end" },
  { id: "plain", x1: 275, y1: 100, x2: 190, y2: 100 },
];

function renderDiagram(currentId?: string) {
  return renderWithProviders(
    <LifecycleDiagram
      viewBox="0 0 720 150"
      width={720}
      minWidth={480}
      regionLabel="Test diagram region"
      nodeWidth={170}
      nodeHeight={60}
      nodes={NODES}
      edges={EDGES}
      currentId={currentId}
      markerId="test-lc-arrow"
      altText="Diagram of the test lifecycle"
      hint="This is a hint."
    />,
  );
}

const edgeGroup = (id: string) => document.querySelector(`g[data-edge="${id}"]`);
const nodeGroup = (id: string) => document.querySelector(`g[data-node="${id}"]`);

describe("LifecycleDiagram", () => {
  test("renders nodes, both lines of a caption, the alt text, the hint and a labelled scroll region", () => {
    renderDiagram();
    expect(screen.getByRole("img", { name: "Diagram of the test lifecycle" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Test diagram region" })).toBeInTheDocument();
    for (const label of ["Draft", "Active", "Archived", "Activate", "Approver", "Archive"]) {
      expect(screen.getByText(label)).toBeInTheDocument();
    }
    expect(screen.getByText("This is a hint.")).toBeInTheDocument();
  });

  test("a straight edge becomes a line path, a path edge keeps its own d, and both end in an arrowhead", () => {
    renderDiagram();
    const line = edgeGroup("activate")?.querySelector("path");
    expect(line).toHaveAttribute("d", "M190,67 L275,67");
    expect(line).toHaveAttribute("marker-end", "url(#test-lc-arrow)");
    expect(edgeGroup("archive")?.querySelector("path")).toHaveAttribute("d", "M445,67 C480,20 500,20 530,67");
    expect(document.querySelector("marker#test-lc-arrow")).not.toBeNull();
    expect(document.querySelector("marker#test-lc-arrow-on")).not.toBeNull();
  });

  test("a caption honours its anchor (middle by default) and an unlabeled edge draws no text", () => {
    renderDiagram();
    expect(edgeGroup("activate")?.querySelector("text")).toHaveAttribute("text-anchor", "middle");
    expect(edgeGroup("archive")?.querySelector("text")).toHaveAttribute("text-anchor", "end");
    expect(edgeGroup("plain")?.querySelector("text")).toBeNull();
  });

  test("an emphasised edge is marked, heavier, in the accent, with the accent arrowhead, and is drawn after the quiet ones", () => {
    renderDiagram();
    const on = edgeGroup("archive");
    expect(on).toHaveAttribute("data-emphasised", "true");
    expect(on?.querySelector("path")).toHaveAttribute("stroke-width", "2.5");
    expect(on?.querySelector("path")).toHaveAttribute("stroke", "var(--hammurabi-accent-ink)");
    expect(on?.querySelector("path")).toHaveAttribute("marker-end", "url(#test-lc-arrow-on)");
    const quiet = edgeGroup("activate");
    expect(quiet).not.toHaveAttribute("data-emphasised");
    expect(quiet?.querySelector("path")).toHaveAttribute("stroke-width", "1.5");
    const order = Array.from(document.querySelectorAll("g[data-edge]")).map((g) => g.getAttribute("data-edge"));
    expect(order).toEqual(["activate", "plain", "archive"]);
  });

  test("highlights the current node and dims a non-current terminal node", () => {
    renderDiagram("ACTIVE");
    expect(nodeGroup("ACTIVE")).toHaveAttribute("data-current", "true");
    expect(nodeGroup("ACTIVE")?.querySelector("rect")).toHaveAttribute("stroke-width", "3");
    expect(screen.getByText("Active")).toHaveAttribute("font-weight", "700");
    expect(nodeGroup("DRAFT")).not.toHaveAttribute("data-current");
    expect(screen.getByText("Archived")).toHaveAttribute("fill", "var(--mantine-color-dimmed)");
  });

  test("with no current id nothing is highlighted", () => {
    renderDiagram();
    expect(document.querySelector("g[data-current]")).toBeNull();
  });

  test("a current terminal node is not dimmed", () => {
    renderDiagram("ARCHIVED");
    expect(screen.getByText("Archived")).toHaveAttribute("fill", "var(--mantine-color-text)");
  });

  test("a node's colour tints its box; an uncoloured node keeps the surface", () => {
    renderDiagram();
    expect(nodeGroup("DRAFT")?.querySelector("rect")).toHaveAttribute("fill", "var(--mantine-color-grape-light)");
    expect(nodeGroup("ACTIVE")?.querySelector("rect")).toHaveAttribute("fill", "var(--mantine-color-body)");
  });
});
