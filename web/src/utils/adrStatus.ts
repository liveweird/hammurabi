import type { MantineColor } from "@mantine/core";
import type { AdrRole, AdrStatus } from "../api/adrs";

/** The lifecycle in order (the server sorts by `status` in this order, not alphabetically). */
export const ADR_STATUSES: readonly AdrStatus[] = [
  "PROBLEM_RAISED",
  "SOLUTION_DRAFT",
  "IN_REVIEW",
  "ACTIVE",
  "CANCELLED",
  "WITHDRAWN",
  "EXPIRED",
  "SUPERSEDED",
];

/** The DACI roles in the order they are listed everywhere. */
export const ADR_ROLES: readonly AdrRole[] = ["DRIVER", "APPROVER", "CONTRIBUTOR", "INFORMED"];

/**
 * THE status colour map, used by the badge, the list and the history alike. It follows the app-wide colour vocabulary
 * (`web/CLAUDE.md`): gray = neutral state, grape = waiting, cyan = in progress, teal = success, orange = warning/waived, red = ended
 * hard — never stock blue, indigo or violet, which sit beside the lapis brand.
 */
export const ADR_STATUS_COLOR: Record<AdrStatus, MantineColor> = {
  PROBLEM_RAISED: "gray",
  SOLUTION_DRAFT: "grape",
  IN_REVIEW: "cyan",
  ACTIVE: "teal",
  CANCELLED: "gray",
  WITHDRAWN: "orange",
  EXPIRED: "red",
  SUPERSEDED: "gray",
};

/** Content (the name and the Markdown fields) is open to edits only before the decision is in force. */
const PRE_ACTIVE: ReadonlySet<AdrStatus> = new Set(["PROBLEM_RAISED", "SOLUTION_DRAFT", "IN_REVIEW"]);

export function isPreActive(status: AdrStatus): boolean {
  return PRE_ACTIVE.has(status);
}

/** Terminal statuses are read-only for everyone: the decision was cancelled, withdrawn, expired or superseded. */
export function isTerminal(status: AdrStatus): boolean {
  return !isPreActive(status) && status !== "ACTIVE";
}

/** The longest reason a status move carries, in code points (the server's bound). */
export const MAX_TRANSITION_REASON_LENGTH = 1000;

/**
 * Whether [text] holds a character the server refuses in a reason (`sanitizeMultiLine`): a C0 or C1 control other than the line break
 * and the tab (a carriage return is normalised to a line break there, so it passes), or a hidden direction character - the line and
 * paragraph separators U+2028/2029 and the bidirectional embeddings, overrides and isolates (U+202A..202E, U+2066..2069).
 */
export function hasForbiddenReasonCharacter(text: string): boolean {
  for (const char of text) {
    const code = char.codePointAt(0) ?? 0;
    const control = code < 0x20 || (code >= 0x7f && code <= 0x9f);
    const allowed = code === 0x09 || code === 0x0a || code === 0x0d;
    const hidden = code === 0x2028 || code === 0x2029 || (code >= 0x202a && code <= 0x202e) || (code >= 0x2066 && code <= 0x2069);
    if ((control && !allowed) || hidden) return true;
  }
  return false;
}

/**
 * The move a button stands for. Most are the target status; two are told apart by where the ADR is now (the labels and the confirmation
 * differ, the server's `allowedTransitions` still decides whether they are offered): a SEND BACK is the move from review back to the
 * draft, a WITHDRAW_ACTIVE the revoking of a decision that is in force.
 */
export type AdrMove = AdrStatus | "SEND_BACK" | "WITHDRAW_ACTIVE";

export function moveOf(from: AdrStatus, to: AdrStatus): AdrMove {
  if (from === "IN_REVIEW" && to === "SOLUTION_DRAFT") return "SEND_BACK";
  if (from === "ACTIVE" && to === "WITHDRAWN") return "WITHDRAW_ACTIVE";
  return to;
}

/** The moves that ask for a confirmation (and an optional reason) before acting: the endings and the send back. */
export type ConfirmedMove = "CANCELLED" | "WITHDRAWN" | "WITHDRAW_ACTIVE" | "SEND_BACK";
export const CONFIRMED_MOVES: ReadonlySet<AdrMove> = new Set<AdrMove>(["CANCELLED", "WITHDRAWN", "WITHDRAW_ACTIVE", "SEND_BACK"]);
export const isConfirmedMove = (move: AdrMove): move is ConfirmedMove => CONFIRMED_MOVES.has(move);

/** The destructive ones, drawn red: they end the ADR for good. */
export const ENDING_MOVES: ReadonlySet<AdrMove> = new Set<AdrMove>(["CANCELLED", "WITHDRAWN", "WITHDRAW_ACTIVE"]);

/** Type guards for values that arrive as plain strings (URL params, event params). */
export const isAdrStatus = (value: string | null | undefined): value is AdrStatus => (ADR_STATUSES as readonly (string | null | undefined)[]).includes(value);
export const isAdrRole = (value: string | null | undefined): value is AdrRole => (ADR_ROLES as readonly (string | null | undefined)[]).includes(value);
