import { useId } from "react";
import { useTranslation } from "react-i18next";
import type { TFunction } from "i18next";
import { Badge, List, Stack, Text, Title } from "@mantine/core";
import type { AdrStatus } from "../api/adrs";
import { ADR_STATUS_COLOR, ADR_STATUSES, isTerminal, moveOf } from "../utils/adrStatus";
import { moveId, movesFrom, ADR_LIFECYCLE_MOVES, type AdrLifecycleMove, type LifecycleActor } from "../utils/adrLifecycle";
import LifecycleDiagram, { type LifecycleEdge, type LifecycleNode } from "./LifecycleDiagram";

// The ADR workflow as a picture (Lettuce's LifecycleDiagram shape: geometry here as data, the drawing in the shared primitive, every word
// from i18n). The graph runs top to bottom because the detail page's main pane is a portrait column: the draft pipeline down the middle,
// the endings to the right (CANCELLED and WITHDRAWN, each fed by an orthogonal "bus" from the nodes that can reach it), the two automatic
// endings below ACTIVE, and the two backward routes - the skip arc PROBLEM_RAISED -> IN_REVIEW and the send back - on the free left side.
// The captions sit beside the arrows in the gaps between rows, never on a row's own line.

const NODE_W = 132;
const NODE_H = 44;
const HALF_H = NODE_H / 2;
const CX = 256; // the pipeline's centre line
const COL_L = CX - NODE_W / 2; // 190
const COL_R = CX + NODE_W / 2; // 322
const END_X = 448; // the endings' column (left edge)
const END_CX = END_X + NODE_W / 2; // 514

// The row centres: the pipeline down the middle, the automatic endings below.
const ROW = { PROBLEM_RAISED: 40, SOLUTION_DRAFT: 150, IN_REVIEW: 260, ACTIVE: 370, AUTO: 490 } as const;

const NODE_POS: Record<AdrStatus, { x: number; cy: number }> = {
  PROBLEM_RAISED: { x: COL_L, cy: ROW.PROBLEM_RAISED },
  SOLUTION_DRAFT: { x: COL_L, cy: ROW.SOLUTION_DRAFT },
  IN_REVIEW: { x: COL_L, cy: ROW.IN_REVIEW },
  ACTIVE: { x: COL_L, cy: ROW.ACTIVE },
  CANCELLED: { x: END_X, cy: ROW.SOLUTION_DRAFT },
  WITHDRAWN: { x: END_X, cy: ROW.ACTIVE },
  EXPIRED: { x: 116, cy: ROW.AUTO },
  SUPERSEDED: { x: 264, cy: ROW.AUTO },
};

type Geometry = { d: string } & Pick<LifecycleEdge, "labelX" | "labelY" | "labelAnchor">;

const down = (x: number, from: number, to: number) => `M${x},${from + HALF_H} V${to - HALF_H}`;

// One route per move of `ADR_LIFECYCLE_MOVES`, keyed by `moveId`. A caption's Y is the baseline of its first line (the actor line follows).
const GEOMETRY: Record<string, Geometry> = {
  // The pipeline.
  "PROBLEM_RAISED>SOLUTION_DRAFT": { d: down(CX, ROW.PROBLEM_RAISED, ROW.SOLUTION_DRAFT), labelX: CX + 10, labelY: 92, labelAnchor: "start" },
  "SOLUTION_DRAFT>IN_REVIEW": { d: down(CX - 18, ROW.SOLUTION_DRAFT, ROW.IN_REVIEW), labelX: CX - 28, labelY: 202, labelAnchor: "end" },
  "IN_REVIEW>SOLUTION_DRAFT": { d: `M${CX + 18},${ROW.IN_REVIEW - HALF_H} V${ROW.SOLUTION_DRAFT + HALF_H}`, labelX: CX + 28, labelY: 202, labelAnchor: "start" },
  "IN_REVIEW>ACTIVE": { d: down(CX, ROW.IN_REVIEW, ROW.ACTIVE), labelX: CX + 10, labelY: 312, labelAnchor: "start" },
  // The skip arc, on the free left side, around the draft.
  "PROBLEM_RAISED>IN_REVIEW": { d: `M${COL_L},${ROW.PROBLEM_RAISED} C0,${ROW.PROBLEM_RAISED} 0,${ROW.IN_REVIEW} ${COL_L},${ROW.IN_REVIEW}`, labelX: 119, labelY: 146, labelAnchor: "middle" },
  // The cancel bus: PROBLEM_RAISED and IN_REVIEW elbow into CANCELLED, SOLUTION_DRAFT is level with it.
  "PROBLEM_RAISED>CANCELLED": { d: `M${COL_R},${ROW.PROBLEM_RAISED} H${END_CX - 8} Q${END_CX},${ROW.PROBLEM_RAISED} ${END_CX},${ROW.PROBLEM_RAISED + 8} V${ROW.SOLUTION_DRAFT - HALF_H}`, labelX: END_CX + 8, labelY: 80, labelAnchor: "start" },
  "SOLUTION_DRAFT>CANCELLED": { d: `M${COL_R},${ROW.SOLUTION_DRAFT} H${END_X}`, labelX: (COL_R + END_X) / 2, labelY: 126, labelAnchor: "middle" },
  "IN_REVIEW>CANCELLED": { d: `M${COL_R},${ROW.IN_REVIEW - 8} H${END_CX - 8} Q${END_CX},${ROW.IN_REVIEW - 8} ${END_CX},${ROW.IN_REVIEW - 16} V${ROW.SOLUTION_DRAFT + HALF_H}`, labelX: END_CX + 8, labelY: 202, labelAnchor: "start" },
  // The withdraw bus: IN_REVIEW elbows into WITHDRAWN, ACTIVE is level with it.
  "IN_REVIEW>WITHDRAWN": { d: `M${COL_R},${ROW.IN_REVIEW + 8} H${END_CX - 8} Q${END_CX},${ROW.IN_REVIEW + 8} ${END_CX},${ROW.IN_REVIEW + 16} V${ROW.ACTIVE - HALF_H}`, labelX: END_CX + 8, labelY: 312, labelAnchor: "start" },
  "ACTIVE>WITHDRAWN": { d: `M${COL_R},${ROW.ACTIVE} H${END_X}`, labelX: (COL_R + END_X) / 2, labelY: 346, labelAnchor: "middle" },
  // The two automatic endings, straight down out of ACTIVE.
  "ACTIVE>EXPIRED": { d: down(211, ROW.ACTIVE, ROW.AUTO), labelX: 201, labelY: 426, labelAnchor: "end" },
  "ACTIVE>SUPERSEDED": { d: down(301, ROW.ACTIVE, ROW.AUTO), labelX: 311, labelY: 426, labelAnchor: "start" },
};

const ACTOR_KEY = {
  DRIVER: "adrs.role.DRIVER",
  APPROVER: "adrs.role.APPROVER",
  APPROVER_OR_DRIVER: "adrs.lifecycle.actor.APPROVER_OR_DRIVER",
  AUTOMATIC: "adrs.lifecycle.actor.AUTOMATIC",
} as const satisfies Record<LifecycleActor, string>;

const actorLabel = (actor: LifecycleActor, t: TFunction): string => t(ACTOR_KEY[actor]);
/** The caption's action: the SAME word as the status button for that move (`adrs.transition.*`). */
const actionLabel = (move: AdrLifecycleMove, t: TFunction): string => t(`adrs.transition.${moveOf(move.from, move.to)}`);

/** One line of the text alternative: what the move is called, where it leads, who takes it and whether the caller may now. */
function MoveItem({ move, canNow, expiresOn }: { move: AdrLifecycleMove; canNow: boolean; expiresOn: string | null }) {
  const { t } = useTranslation();
  const who =
    move.to === "EXPIRED" && expiresOn !== null
      ? t("adrs.lifecycle.auto.EXPIRED_ON", { date: expiresOn })
      : move.actor === "AUTOMATIC"
        ? t(`adrs.lifecycle.auto.${move.to as "EXPIRED" | "SUPERSEDED"}`)
        : actorLabel(move.actor, t);
  return (
    <List.Item>
      <Text span fw={600}>
        {actionLabel(move, t)}
      </Text>{" "}
      <Text span>{t("adrs.lifecycle.leadsTo", { to: t(`adrs.status.${move.to}`), who })}</Text>
      {canNow && (
        <Badge ml="xs" size="sm" color="hammurabi" variant="light">
          {t("adrs.lifecycle.canNow")}
        </Badge>
      )}
    </List.Item>
  );
}

/**
 * The ADR workflow: every status and every move, who takes it, where THIS ADR is (the heavy outline) and which moves the caller may make
 * right now (`allowedTransitions`, the server's verdict for this caller, drawn in the accent). Below the picture, the same facts for the
 * current status as text (the picture is one image to assistive technology, and a phone scrolls it sideways).
 */
export default function AdrLifecycleDiagram({
  currentStatus,
  allowedTransitions,
  expiresOn,
}: {
  currentStatus: AdrStatus;
  allowedTransitions: readonly AdrStatus[];
  expiresOn: string | null;
}) {
  const { t } = useTranslation();
  const headingId = useId();
  const statusName = t(`adrs.status.${currentStatus}`);
  const allowed = new Set(allowedTransitions);

  const nodes: LifecycleNode[] = ADR_STATUSES.map((status) => ({
    id: status,
    x: NODE_POS[status].x,
    y: NODE_POS[status].cy - HALF_H,
    label: t(`adrs.status.${status}`),
    terminal: isTerminal(status),
    color: ADR_STATUS_COLOR[status],
  }));
  const edges: LifecycleEdge[] = ADR_LIFECYCLE_MOVES.map((move) => {
    const id = moveId(move);
    return {
      id,
      ...GEOMETRY[id],
      label: actionLabel(move, t),
      sublabel: actorLabel(move.actor, t),
      emphasised: move.from === currentStatus && allowed.has(move.to),
    };
  });
  const moves = movesFrom(currentStatus);

  return (
    <Stack gap="md">
      <LifecycleDiagram
        viewBox="30 0 600 530"
        width={600}
        minWidth={500}
        regionLabel={t("adrs.lifecycle.regionAria")}
        nodeWidth={NODE_W}
        nodeHeight={NODE_H}
        nodes={nodes}
        edges={edges}
        currentId={currentStatus}
        markerId="adr-lc-arrow"
        altText={t("adrs.lifecycle.alt", { status: statusName })}
        hint={t("adrs.lifecycle.hint")}
      />
      <Stack gap="xs" component="section" aria-labelledby={headingId}>
        <Title order={4} size="h5" id={headingId}>
          {t("adrs.lifecycle.movesTitle", { status: statusName })}
        </Title>
        {moves.length === 0 ? (
          <Text size="sm">{t("adrs.lifecycle.noMoves", { status: statusName })}</Text>
        ) : (
          <>
            <List size="sm" spacing="xs">
              {moves.map((move) => (
                <MoveItem key={moveId(move)} move={move} canNow={allowed.has(move.to)} expiresOn={expiresOn} />
              ))}
            </List>
            {allowed.size === 0 && (
              <Text size="sm" c="dimmed">
                {t("adrs.lifecycle.noneForYou")}
              </Text>
            )}
          </>
        )}
      </Stack>
    </Stack>
  );
}
