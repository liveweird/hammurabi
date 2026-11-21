import type { AdrStatus } from "../api/adrs";

/**
 * Who takes a move (`adr-model.md` "DACI roles and verdicts"; an ADMIN may take any move a person can, which the diagram says once in its
 * legend instead of on every arrow). The server judges every move: this map only DESCRIBES the machine for the lifecycle diagram, the
 * buttons and the writes still follow `allowedTransitions`.
 */
export type LifecycleActor = "DRIVER" | "APPROVER" | "APPROVER_OR_DRIVER" | "AUTOMATIC";

export type AdrLifecycleMove = { from: AdrStatus; to: AdrStatus; actor: LifecycleActor };

/**
 * Every edge of the server's map (`AdrLifecycle.kt`'s `ALLOWED_TRANSITIONS`), with who takes it. **The server pins this table**:
 * `AdrLifecycleMirrorTest` parses the literal below (one `{ from, to, actor }` object per line, keep that shape) and fails when an edge
 * differs from the map or an actor differs from what `allowedTransitions` lets a Driver and an Approver do (AUTOMATIC = the system's
 * statuses). Change the server's rule and this table in the same commit.
 */
export const ADR_LIFECYCLE_MOVES: readonly AdrLifecycleMove[] = [
  { from: "PROBLEM_RAISED", to: "SOLUTION_DRAFT", actor: "DRIVER" },
  { from: "PROBLEM_RAISED", to: "IN_REVIEW", actor: "DRIVER" },
  { from: "PROBLEM_RAISED", to: "CANCELLED", actor: "DRIVER" },
  { from: "SOLUTION_DRAFT", to: "IN_REVIEW", actor: "DRIVER" },
  { from: "SOLUTION_DRAFT", to: "CANCELLED", actor: "DRIVER" },
  { from: "IN_REVIEW", to: "SOLUTION_DRAFT", actor: "APPROVER_OR_DRIVER" },
  { from: "IN_REVIEW", to: "ACTIVE", actor: "APPROVER" },
  { from: "IN_REVIEW", to: "CANCELLED", actor: "DRIVER" },
  { from: "IN_REVIEW", to: "WITHDRAWN", actor: "APPROVER" },
  { from: "ACTIVE", to: "WITHDRAWN", actor: "APPROVER" },
  { from: "ACTIVE", to: "EXPIRED", actor: "AUTOMATIC" },
  { from: "ACTIVE", to: "SUPERSEDED", actor: "AUTOMATIC" },
];

/** The moves out of [status], in the machine's order (none for a terminal status). */
export function movesFrom(status: AdrStatus): AdrLifecycleMove[] {
  return ADR_LIFECYCLE_MOVES.filter((move) => move.from === status);
}

/** The stable key of a move: an edge id in the diagram and a list key. */
export const moveId = (move: Pick<AdrLifecycleMove, "from" | "to">): string => `${move.from}>${move.to}`;
