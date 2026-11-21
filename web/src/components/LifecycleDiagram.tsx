import { Stack, Text } from "@mantine/core";
import ScrollRegion from "./ScrollRegion";

// The shared rendering primitive behind a "how it works" state diagram (ported from Lettuce, where three features had
// hand-copied SVGs that only differed in geometry and labels). It owns ALL the SVG mechanics - the arrowhead markers, the edges with
// optional two-line captions, the node boxes, the "you are here" highlight, the emphasised moves and terminal-node dimming - so a caller
// supplies DATA (nodes, edges, geometry) and two strings (alt text, the hint under the diagram). Hammurabi's additions to Lettuce's: an
// edge is a straight line OR any SVG path (`d`: the curved and elbowed routes of a graph with fan-ins and a skip arc), an edge can be
// emphasised, a node can carry a Mantine colour tint, and the SVG keeps a minimum width inside a sideways-scrolling box so a phone sees
// a legible diagram, not a thumbnail. Colours are Mantine CSS variables (light and dark follow the scheme); no charting dependency.

export type LifecycleNode = {
  /** Stable key AND the value compared against `currentId` for the "you are here" highlight. */
  id: string;
  x: number;
  y: number;
  /** Pre-resolved, already-translated label. */
  label: string;
  /** Terminal states dim their label when they are not the current node. */
  terminal?: boolean;
  /** A Mantine colour name (`teal`): the node's light tint. Omitted = the neutral surface. */
  color?: string;
};

type EdgeGeometry = { d: string } | { x1: number; y1: number; x2: number; y2: number };

export type LifecycleEdge = EdgeGeometry & {
  /** Stable React key and the `data-edge` hook of the drawn edge. */
  id: string;
  /** Pre-resolved, already-translated caption (first line, the action) and its second line (the actor). Omit both for an unlabeled arrow. */
  label?: string;
  sublabel?: string;
  /** Where the caption's FIRST line sits (its baseline); the second line is one line below. */
  labelX?: number;
  labelY?: number;
  labelAnchor?: "start" | "middle" | "end";
  /** A move to stress (the caller can make it now): the accent colour and a heavier stroke, drawn above the quiet edges. */
  emphasised?: boolean;
};

const QUIET = "var(--mantine-color-dimmed)";
// The brand hue where it must be perceived against the canvas (themeVariables.ts: light primary / dark 3-shade, >= 3:1 both schemes).
const ACCENT = "var(--hammurabi-accent-ink)";
const CAPTION_LINE = 13;

const pathOf = (e: LifecycleEdge): string => ("d" in e ? e.d : `M${e.x1},${e.y1} L${e.x2},${e.y2}`);

function Arrowhead({ id, color }: { id: string; color: string }) {
  return (
    <marker id={id} viewBox="0 0 10 10" refX="9" refY="5" markerWidth="9" markerHeight="9" markerUnits="userSpaceOnUse" orient="auto">
      <path d="M0,0 L10,5 L0,10 z" fill={color} />
    </marker>
  );
}

function EdgeView({ edge, markerId }: { edge: LifecycleEdge; markerId: string }) {
  const on = edge.emphasised === true;
  const color = on ? ACCENT : QUIET;
  return (
    <g data-edge={edge.id} data-emphasised={on ? "true" : undefined}>
      <path d={pathOf(edge)} fill="none" stroke={color} strokeWidth={on ? 2.5 : 1.5} markerEnd={`url(#${markerId}${on ? "-on" : ""})`} />
      {edge.label !== undefined && (
        <text
          x={edge.labelX}
          y={edge.labelY}
          textAnchor={edge.labelAnchor ?? "middle"}
          fontSize={12.5}
          fontWeight={on ? 700 : 500}
          fill={on ? ACCENT : "var(--mantine-color-text)"}
          // A halo in the surface colour keeps a caption legible where it sits close to a line.
          stroke="var(--mantine-color-body)"
          strokeWidth={3}
          paintOrder="stroke"
        >
          <tspan x={edge.labelX}>{edge.label}</tspan>
          {edge.sublabel !== undefined && (
            <tspan x={edge.labelX} dy={CAPTION_LINE} fontSize={11} fontWeight={on ? 600 : 400} fill={on ? ACCENT : QUIET}>
              {edge.sublabel}
            </tspan>
          )}
        </text>
      )}
    </g>
  );
}

function NodeView({ node, width, height, current }: { node: LifecycleNode; width: number; height: number; current: boolean }) {
  return (
    <g data-node={node.id} data-current={current ? "true" : undefined}>
      <rect
        x={node.x}
        y={node.y}
        width={width}
        height={height}
        rx={8}
        fill={node.color === undefined ? "var(--mantine-color-body)" : `var(--mantine-color-${node.color}-light)`}
        stroke={current ? ACCENT : "var(--hammurabi-border-strong)"}
        strokeWidth={current ? 3 : 1}
      />
      <text
        x={node.x + width / 2}
        y={node.y + height / 2}
        textAnchor="middle"
        dominantBaseline="central"
        fontSize={14}
        fontWeight={current ? 700 : 500}
        fill={node.terminal && !current ? QUIET : "var(--mantine-color-text)"}
      >
        {node.label}
      </text>
    </g>
  );
}

export default function LifecycleDiagram({
  viewBox,
  width,
  minWidth,
  regionLabel,
  nodeWidth,
  nodeHeight,
  nodes,
  edges,
  currentId,
  markerId,
  altText,
  hint,
}: {
  viewBox: string;
  /** The SVG's `maxWidth` (the viewBox's own width, by convention). */
  width: number;
  /** The narrowest the SVG is drawn: below it the box scrolls sideways instead of shrinking the type further. */
  minWidth: number;
  /** The accessible name of the scrolling region (a scroller that can overflow is a focusable, labelled region). */
  regionLabel: string;
  nodeWidth: number;
  nodeHeight: number;
  nodes: LifecycleNode[];
  edges: LifecycleEdge[];
  currentId?: string;
  /** The arrowhead `<marker>` id (and `<id>-on` for the emphasised one): caller-supplied so two diagrams on a page never share a marker. */
  markerId: string;
  altText: string;
  hint: string;
}) {
  // The emphasised edges are drawn last: where two lines meet the stressed one stays on top.
  const ordered = [...edges].sort((a, b) => Number(a.emphasised === true) - Number(b.emphasised === true));
  return (
    <Stack gap="xs">
      <ScrollRegion label={regionLabel} minWidth={minWidth}>
        <svg
          role="img"
          aria-label={altText}
          viewBox={viewBox}
          style={{ display: "block", width: "100%", maxWidth: width, height: "auto", margin: "0 auto" }}
        >
          <defs>
            <Arrowhead id={markerId} color={QUIET} />
            <Arrowhead id={`${markerId}-on`} color={ACCENT} />
          </defs>
          {ordered.map((e) => (
            <EdgeView key={e.id} edge={e} markerId={markerId} />
          ))}
          {nodes.map((n) => (
            <NodeView key={n.id} node={n} width={nodeWidth} height={nodeHeight} current={n.id === currentId} />
          ))}
        </svg>
      </ScrollRegion>
      <Text size="sm" c="dimmed" ta="center">
        {hint}
      </Text>
    </Stack>
  );
}
